package com.rassul.audioeditor.export

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPipeline
import com.rassul.audioeditor.core.pipeline.ExportPlan
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.ExportResult
import com.rassul.audioeditor.core.pipeline.ExtractPlanner
import com.rassul.audioeditor.core.verify.VerifyReport
import com.rassul.audioeditor.diag.DeviceProfile
import com.rassul.audioeditor.diag.DeviceVerifier
import com.rassul.audioeditor.editor.AppSettings
import com.rassul.audioeditor.editor.ExtractOption
import com.rassul.audioeditor.editor.SaveJob
import com.rassul.audioeditor.editor.SaveJobState
import com.rassul.audioeditor.editor.SavedFile
import com.rassul.audioeditor.media.AudioTrackInfo
import com.rassul.audioeditor.media.CopyResult
import com.rassul.audioeditor.media.DecodePipeline
import com.rassul.audioeditor.media.Encoders
import com.rassul.audioeditor.media.FastTrimPreview
import com.rassul.audioeditor.media.IncomingSource
import com.rassul.audioeditor.media.OpenedSource
import com.rassul.audioeditor.media.OutputTarget
import com.rassul.audioeditor.media.ProbeResult
import com.rassul.audioeditor.media.RemuxCopier
import com.rassul.audioeditor.media.SourceOpener
import com.rassul.audioeditor.media.TrackProbe
import com.rassul.audioeditor.store.MediaStoreSaver
import com.rassul.audioeditor.store.SavedRepository
import java.util.concurrent.Executors

/*
 * Зона MEDIA, T18 + T27. Очередь фоновых сохранений (FR-29, FR-30, FR-33): извлечение (как есть / M4A / Opus /
 * FLAC / WAV), экспорт из редактора, быстрая обрезка. Задачи выполняются по одной на рабочем потоке, процесс
 * удерживается ExportService (foreground, mediaProcessing). Этапы: запись → «Проверка…» (DeviceVerifier) → итог.
 * Колбэки Listener — в главном потоке. Имена (FR-27): суффиксы _audio / _edit / _trim.
 */
object ExportJobs {
    fun interface Listener { fun onJobsChanged(jobs: List<SaveJob>) }

    const val MSG_NO_NOTIFICATIONS = "Уведомления выключены: сохранение идёт в фоне, но без уведомления о прогрессе."
    private const val MSG_UNVERIFIED = "Сохранено (не проверено)"

    /** Что делает задача: пишет файл в target и возвращает данные для проверки. */
    interface Work {
        /** Основа имени и суффикс (FR-27), расширение и MIME — могут зависеть от плана. */
        val baseName: String
        val suffix: String
        /** Подготовка до создания файла (открытие источника, план). Возвращает (ext, mime). */
        fun prepare(ctx: Context, progress: Progress): Pair<String, String>
        fun write(ctx: Context, target: OutputTarget, progress: Progress): Written
        /** Освободить ресурсы (всегда вызывается). */
        fun cleanup() {}
    }

    /** Итог записи — для проверки FR-33. */
    sealed class Written(val frames: Long, val sampleRate: Int) {
        class Encoded(val plan: ExportPlan, val result: ExportResult, val encoderDelay: Int?) : Written(result.frames, plan.outSpec.sampleRate)
        class Copied(val copy: CopyResult) : Written(copy.durationFrames, copy.sampleRate)
    }

    private class Job(val id: Long, val work: Work, @Volatile var snapshot: SaveJob) {
        @Volatile var cancelled = false
        @Volatile var skipVerify = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private val jobsList = ArrayList<Job>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "export").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private var nextId = 1L
    private var lastNotify = 0L
    @Volatile private var appCtx: Context? = null

    val jobs: List<SaveJob> get() = synchronized(jobsList) { jobsList.map { it.snapshot } }
    /** Есть ли незавершённые задачи (для ExportService). */
    val hasActive: Boolean get() = synchronized(jobsList) { jobsList.any { it.snapshot.state == SaveJobState.RUNNING || it.snapshot.state == SaveJobState.VERIFYING } }

    fun addListener(l: Listener) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: Listener) { synchronized(listeners) { listeners -= l } }

    /** FR-29: разрешено ли показывать уведомление (если нет — работа всё равно идёт). */
    fun notificationsPermitted(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun cancel(id: Long) { find(id)?.cancelled = true }
    fun cancelAll() = synchronized(jobsList) { jobsList.forEach { it.cancelled = true } }
    /** FR-33: «Пропустить проверку» — прекращает только проверку. */
    fun skipVerify(id: Long) { find(id)?.skipVerify = true }
    /** Убрать завершённые задачи из списка. */
    fun clearFinished() { synchronized(jobsList) { jobsList.removeAll { it.snapshot.state != SaveJobState.RUNNING && it.snapshot.state != SaveJobState.VERIFYING } }; notifyNow() }

    private fun find(id: Long) = synchronized(jobsList) { jobsList.firstOrNull { it.id == id } }

    /** Поставить задачу в очередь и поднять foreground service. Возвращает id. */
    fun enqueue(ctx: Context, work: Work): Long {
        val app = ctx.applicationContext
        appCtx = app
        val id = synchronized(jobsList) { nextId++ }
        val job = Job(id, work, SaveJob(id, "Сохранение: ${work.baseName}${work.suffix}", -1.0, SaveJobState.RUNNING, null, null, null))
        synchronized(jobsList) { jobsList += job }
        notifyNow()
        try { app.startForegroundService(Intent(app, ExportService::class.java)) } catch (e: Exception) {
            // Например, ForegroundServiceStartNotAllowedException, если приложение уже в фоне: работаем без FGS.
            DeviceProfile.get(app).log("ExportService не запущен: ${e.message}")
        }
        executor.execute { runJob(app, job) }
        return id
    }

    // ------------------------------------------------------------------ готовые задачи

    /** Извлечение по главной кнопке (FR-6a): AAC — копия, есть декодер — M4A 192 кбит/с, нет — как есть. */
    fun enqueueExtractPrimary(ctx: Context, uri: Uri, track: AudioTrackInfo, baseName: String): Long {
        val p = ExtractPlanner.plan(track.mime, track.decodable)
        val opt = if (p.action == ExtractPlanner.Action.TRANSCODE_AAC) ExtractOption.M4A else ExtractOption.AS_IS
        return enqueue(ctx, ExtractWork(uri, track.extractorIndex, baseName, opt, null))
    }

    /** «Другой формат…» (FR-6a): как есть / M4A / Opus / FLAC / WAV с параметрами FR-24. */
    fun enqueueExtract(ctx: Context, uri: Uri, track: AudioTrackInfo, baseName: String, option: ExtractOption, request: ExportRequest?): Long {
        // M4A для AAC-дорожки без явных параметров — копирование (FR-6a).
        val aac = track.mime.equals("audio/mp4a-latm", true) || track.mime.equals("audio/aac", true)
        val opt = if (option == ExtractOption.M4A && aac && request == null) ExtractOption.AS_IS else option
        return enqueue(ctx, ExtractWork(uri, track.extractorIndex, baseName, opt, request))
    }

    /** Экспорт результата правок (FR-24). Renderer — тот же, что у плеера (FR-13). */
    fun enqueueEditorExport(ctx: Context, renderer: Renderer, sourceBits: Int, request: ExportRequest, baseName: String): Long =
        enqueue(ctx, EditorExportWork(renderer, sourceBits, request, baseName))

    /** FR-23: быстрая обрезка по предпросмотру RemuxCopier.previewFastTrim. */
    fun enqueueFastTrim(ctx: Context, uri: Uri, track: AudioTrackInfo, preview: FastTrimPreview, baseName: String): Long =
        enqueue(ctx, FastTrimWork(uri, track.extractorIndex, preview, baseName))

    // ------------------------------------------------------------------ выполнение

    private fun runJob(ctx: Context, job: Job) {
        val saver = MediaStoreSaver(ctx)
        val repo = SavedRepository.get(ctx)
        val profile = DeviceProfile.get(ctx)
        var pending: MediaStoreSaver.Pending? = null
        val writeProgress = progress(job, 0.0, 1.0) { job.cancelled }
        try {
            if (job.cancelled) throw CancelledException()
            val (ext, mime) = job.work.prepare(ctx, writeProgress)
            val p = saver.create(job.work.baseName, job.work.suffix, ext, mime, repo.names())
            pending = p
            update(job) { it.copy(title = "Сохранение: ${p.displayName}", fileName = p.displayName) }
            val written = job.work.write(ctx, p, writeProgress)
            if (job.cancelled) throw CancelledException()
            val saved = saver.commit(p)
            pending = null
            val durMs = if (written.sampleRate > 0) written.frames * 1000 / written.sampleRate else 0L
            var file = SavedFile(saved.uri, saved.displayName, saved.sizeBytes, durMs, System.currentTimeMillis(), false)
            repo.add(file)
            // FR-33: «Проверка…» в том же сервисе.
            update(job) { it.copy(state = SaveJobState.VERIFYING, progress = -1.0, fileName = saved.displayName, uri = saved.uri, message = "Проверка…") }
            val vp = progress(job, 0.0, 1.0) { job.skipVerify || job.cancelled }
            val report: VerifyReport? = try {
                val v = DeviceVerifier(ctx, profile)
                when (written) {
                    is Written.Encoded -> v.verifyExport(saved.uri, written.plan, written.result,
                        written.encoderDelay?.takeIf { !usedMuxer(profile, written.plan.format) }, vp)
                    is Written.Copied -> v.verifyCopy(saved.uri, written.copy, vp)
                }
            } catch (e: CancelledException) { null }
            when {
                report == null -> update(job) { it.copy(state = SaveJobState.DONE, progress = 1.0, message = MSG_UNVERIFIED) }
                report.ok -> {
                    report.warnings.forEach { profile.log("FR-33: $it") }
                    update(job) { it.copy(state = SaveJobState.DONE, progress = 1.0, message = report.message + ((written as? Written.Copied)?.copy?.message?.let { m -> "\n$m" } ?: "")) }
                }
                else -> {
                    file = file.copy(verifyFailed = true); repo.add(file)
                    profile.log("FR-33: ${report.message}")
                    update(job) { it.copy(state = SaveJobState.VERIFY_FAILED, progress = 1.0, message = report.message) }
                }
            }
        } catch (e: CancelledException) {
            pending?.let { saver.abort(it) }
            update(job) { it.copy(state = SaveJobState.CANCELLED, message = "Отменено", uri = null) }
        } catch (e: CoreException) {
            pending?.let { saver.abort(it) }
            update(job) { it.copy(state = SaveJobState.FAILED, message = e.message, uri = null) }
        } catch (e: Throwable) {
            pending?.let { saver.abort(it) }
            profile.log("Сохранение: ${e.javaClass.simpleName}: ${e.message}")
            val msg = if ((e.message ?: "").contains("ENOSPC") || (e.message ?: "").contains("No space")) "Недостаточно места для сохранения."
                      else "Не удалось сохранить файл. Попробуйте ещё раз или выберите WAV."
            update(job) { it.copy(state = SaveJobState.FAILED, message = msg, uri = null) }
        } finally {
            try { job.work.cleanup() } catch (_: Exception) {}
            notifyNow()
        }
    }

    private fun usedMuxer(p: DeviceProfile, f: ExportFormat) = (f == ExportFormat.M4A && p.useMediaMuxerForAac) || (f == ExportFormat.OPUS && p.useMediaMuxerForOpus)

    private fun progress(job: Job, from: Double, span: Double, cancelled: () -> Boolean) = object : Progress {
        override val isCancelled: Boolean get() = cancelled()
        override fun report(fraction: Double) {
            val v = from + span * fraction.coerceIn(0.0, 1.0)
            if (Math.abs(v - job.snapshot.progress) >= 0.005 || fraction >= 1.0) update(job, throttle = true) { it.copy(progress = v) }
        }
    }

    private fun update(job: Job, throttle: Boolean = false, f: (SaveJob) -> SaveJob) {
        job.snapshot = f(job.snapshot)
        if (throttle) {
            val now = System.nanoTime()
            if (now - lastNotify < 150_000_000L) return
            lastNotify = now
        }
        notifyNow()
    }

    private fun notifyNow() {
        val snap = jobs
        main.post { val ls = synchronized(listeners) { listeners.toList() }; ls.forEach { it.onJobsChanged(snap) } }
    }

    /** Поддиапазон прогресса (декодирование 0..0,5, кодирование 0,5..1). */
    internal fun sub(p: Progress, from: Double, span: Double) = object : Progress {
        override val isCancelled: Boolean get() = p.isCancelled
        override fun report(fraction: Double) = p.report(from + span * fraction.coerceIn(0.0, 1.0))
    }
}

// ---------------------------------------------------------------------- задачи

/** Общая часть: открыть источник по URI и найти дорожку. */
private class SourceHandle(val uri: Uri, val extractorIndex: Int) {
    var src: OpenedSource? = null
    lateinit var probe: ProbeResult
    lateinit var track: AudioTrackInfo
    fun open(ctx: Context, progress: Progress) {
        val s = SourceOpener.open(ctx, uri, progress)
        src = s
        probe = TrackProbe.probe(s)
        track = probe.tracks.firstOrNull { it.extractorIndex == extractorIndex } ?: probe.tracks.first()
    }
    fun close() { src?.close(); src = null }
}

/** Кодирование Renderer → файл по плану (общая часть экспорта и перекодирования при извлечении). */
internal object EncodeHelper {
    fun write(ctx: Context, renderer: Renderer, plan: ExportPlan, target: OutputTarget, progress: Progress): ExportJobs.Written.Encoded {
        val profile = DeviceProfile.get(ctx)
        val pipe = ExportPipeline(renderer, plan)
        profile.log("Экспорт ${plan.format.title}: ${plan.outSpec.sampleRate} Гц, ${plan.outSpec.channels} кан., сид дизеринга ${pipe.ditherSeed}")
        return when (plan.format) {
            ExportFormat.WAV, ExportFormat.FLAC -> ExportJobs.Written.Encoded(plan, pipe.writeTo(target.sink, progress), null)
            ExportFormat.M4A -> {
                val enc = Encoders.aacToM4a(target, plan.outSpec, plan.bitrateKbps, profile)
                try { val r = pipe.encodeTo(enc, progress); ExportJobs.Written.Encoded(plan, r, enc.encoderDelay) }
                catch (e: Throwable) { enc.release(); throw e }
            }
            ExportFormat.OPUS -> {
                val enc = Encoders.opusToOgg(target, plan.outSpec, renderer.spec.sampleRate, plan.bitrateKbps, profile)
                try { val r = pipe.encodeTo(enc, progress); ExportJobs.Written.Encoded(plan, r, enc.encoderDelay) }
                catch (e: Throwable) { enc.release(); throw e }
            }
        }
    }

    fun format(opt: ExtractOption): ExportFormat = when (opt) {
        ExtractOption.M4A -> ExportFormat.M4A; ExtractOption.OPUS -> ExportFormat.OPUS
        ExtractOption.FLAC -> ExportFormat.FLAC; ExtractOption.WAV -> ExportFormat.WAV
        ExtractOption.AS_IS -> throw IllegalArgumentException("AS_IS")
    }
}

/** Извлечение звука (С1, FR-6, FR-6a, FR-24): как есть или декодирование в кэш → ExportPipeline. */
internal class ExtractWork(uri: Uri, extractorIndex: Int, override val baseName: String,
                           private val option: ExtractOption, private val request: ExportRequest?) : ExportJobs.Work {
    override val suffix = "audio"
    private val h = SourceHandle(uri, extractorIndex)
    private var plan: ExportPlan? = null
    private var decoded: com.rassul.audioeditor.media.DecodeResult? = null

    override fun prepare(ctx: Context, progress: Progress): Pair<String, String> {
        h.open(ctx, ExportJobs.sub(progress, 0.0, 0.02))
        if (option == ExtractOption.AS_IS) {
            val ext = RemuxCopier.asIsExt(h.track, h.probe) ?: throw CoreException("Кодек ${h.track.codecName} нельзя сохранить без перекодирования.")
            return ext to RemuxCopier.mimeForExt(ext)
        }
        if (!h.track.decodable) throw CoreException(h.track.reason!!)
        // Декодирование до создания файла: при ошибке/нехватке места в Music ничего не появится.
        val d = DecodePipeline(ctx, h.src!!, h.track, h.probe).run(ExportJobs.sub(progress, 0.02, 0.48))
        decoded = d
        d.warnings.forEach { DeviceProfile.get(ctx).log(it) }
        val req = (request ?: ExportRequest()).copy(format = EncodeHelper.format(option))
        val p = ExportPlanner.plan(d.spec, d.sourceBits, req)
        p.notes.forEach { DeviceProfile.get(ctx).log("План: $it") }
        plan = p
        return p.format.ext to (if (p.format == ExportFormat.WAV) "audio/x-wav" else p.format.mime)
    }

    override fun write(ctx: Context, target: OutputTarget, progress: Progress): ExportJobs.Written {
        val profile = DeviceProfile.get(ctx)
        if (option == ExtractOption.AS_IS) return ExportJobs.Written.Copied(RemuxCopier.copy(h.src!!, h.probe, h.track, target, profile, progress))
        val d = decoded!!
        val r = Renderer(d.cache, EditList(), RenderSettings())
        return EncodeHelper.write(ctx, r, plan!!, target, ExportJobs.sub(progress, 0.5, 0.5))
    }

    override fun cleanup() { decoded?.cache?.close(); h.close() }
}

/** Экспорт из редактора (FR-24, FR-25). */
internal class EditorExportWork(private val renderer: Renderer, private val sourceBits: Int, private val request: ExportRequest,
                                override val baseName: String) : ExportJobs.Work {
    override val suffix = "edit"
    private var plan: ExportPlan? = null
    override fun prepare(ctx: Context, progress: Progress): Pair<String, String> {
        val p = ExportPlanner.plan(renderer.spec, sourceBits, request)
        plan = p
        return p.format.ext to (if (p.format == ExportFormat.WAV) "audio/x-wav" else p.format.mime)
    }
    override fun write(ctx: Context, target: OutputTarget, progress: Progress) = EncodeHelper.write(ctx, renderer, plan!!, target, progress)
}

/** Быстрая обрезка (FR-23). */
internal class FastTrimWork(uri: Uri, extractorIndex: Int, private val preview: FastTrimPreview, override val baseName: String) : ExportJobs.Work {
    override val suffix = "trim"
    private val h = SourceHandle(uri, extractorIndex)
    override fun prepare(ctx: Context, progress: Progress): Pair<String, String> {
        h.open(ctx, progress)
        val ext = RemuxCopier.asIsExt(h.track, h.probe) ?: throw CoreException("Быстрая обрезка недоступна для этого файла.")
        return ext to RemuxCopier.mimeForExt(ext)
    }
    override fun write(ctx: Context, target: OutputTarget, progress: Progress): ExportJobs.Written =
        ExportJobs.Written.Copied(RemuxCopier.fastTrim(h.src!!, h.probe, h.track, preview, target, DeviceProfile.get(ctx), progress))
    override fun cleanup() = h.close()
}

/** FR-31: извлечение одним касанием — условия и запуск. */
object OneTapExtract {
    /** Срабатывает, только если: переключатель включён, файл пришёл через SEND/VIEW, это видео и звуковая дорожка одна. */
    fun applies(settings: AppSettings, incoming: IncomingSource, src: OpenedSource, probe: ProbeResult): Boolean =
        settings.oneTapExtract && incoming.fromShare &&
            (probe.hasVideo || src.mime?.startsWith("video/") == true || incoming.mimeHint?.startsWith("video/") == true) &&
            probe.tracks.size == 1

    /** Сохранение по правилу FR-6a + автопроверка (FR-33). */
    fun start(ctx: Context, src: OpenedSource, probe: ProbeResult): Long =
        ExportJobs.enqueueExtractPrimary(ctx, src.uri, probe.tracks[0], src.baseName)
}
