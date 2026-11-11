package com.rassul.audioeditor.editor

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.ExtractPlanner
import com.rassul.audioeditor.diag.DeviceProfile
import com.rassul.audioeditor.diag.SelfTest
import com.rassul.audioeditor.export.ExportJobs
import com.rassul.audioeditor.export.OneTapExtract
import com.rassul.audioeditor.media.AudioTrackInfo
import com.rassul.audioeditor.media.DecodePipeline
import com.rassul.audioeditor.media.DecodeResult
import com.rassul.audioeditor.media.IncomingSource
import com.rassul.audioeditor.media.OpenedSource
import com.rassul.audioeditor.media.ProbeResult
import com.rassul.audioeditor.media.SourceOpener
import com.rassul.audioeditor.media.TrackProbe
import com.rassul.audioeditor.store.SavedRepository
import com.rassul.audioeditor.store.Settings
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/*
 * Интеграция (T20). Настоящий AppController поверх MEDIA. Сценарии (requirements.md §2):
 *  С1/С2  open → SourceOpener.open → TrackProbe → Screen.Extract (TrackItem, PrimaryExtract по ExtractPlanner, FR-6a)
 *         → extractPrimary/extractAs → ExportJobs (FGS, автопроверка FR-33);
 *  С3     аудиофайл с одной раскодируемой дорожкой → сразу DecodePipeline (Screen.Decoding) → Screen.Editor;
 *         видео → «Открыть в редакторе» с экрана извлечения;
 *  С4     быстрая обрезка — из редактора (RealEditorController);
 *  С5     savedFiles/share/openSaved/delete через SavedRepository (кэш списка, UI-поток не ждёт диск);
 *  С6     FR-31: OneTapExtract при fromShare.
 * Все долгие операции — на фоновых потоках; колбэки Listener — в UI-потоке. Устаревшие результаты (после goHome
 * или нового open) отбрасываются по номеру сеанса [session]; их ресурсы закрываются.
 */
class RealAppController(ctx: Context) : AppController {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val settingsStore = Settings(app)
    private val repo = SavedRepository.get(app)
    private val profile = DeviceProfile.get(app)
    private val bg = Executors.newCachedThreadPool { r -> Thread(r, "app-bg") }

    override var screen: Screen = Screen.Home
        private set
    override val jobs: List<SaveJob> get() = jobsSnapshot
    private var jobsSnapshot: List<SaveJob> = ExportJobs.jobs
    override var settings: AppSettings = settingsStore.load()
        private set
    override val editor: EditorController? get() = editorImpl
    private var editorImpl: RealEditorController? = null

    // ---------------------------------------------------------------- текущий источник
    private var session = 0
    private var src: OpenedSource? = null
    private var probe: ProbeResult? = null
    private var selected = 0
    private var sourceName = ""
    private var decode: DecodePipeline? = null
    @Volatile private var openCancelled = false

    // ---------------------------------------------------------------- «Сохранённые»
    private var saved: List<SavedFile> = repo.all()
    private var savedRefreshAt = 0L
    private val finishedSeen = HashSet<Long>()
    private var selfTestRunning = false

    init {
        ExportJobs.addListener { j -> onJobs(j) }
        refreshSaved(force = true)
    }

    internal fun changed() { for (l in listeners) l.onStateChanged() }
    internal fun message(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        for (l in listeners) l.onMessage(text, action, onAction)
    }

    override fun addListener(l: Listener) { listeners += l }
    override fun removeListener(l: Listener) { listeners -= l }

    // ================================================================= открытие (FR-1, FR-2, FR-5, FR-9)

    override fun open(uri: Uri, fromShare: Boolean) {
        closeSession()
        val my = ++session
        openCancelled = false
        val incoming = IncomingSource(uri, fromShare, null)
        sourceName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "файл"
        screen = Screen.Probing(sourceName)
        changed()
        val cancel = object : Progress { override val isCancelled: Boolean get() = openCancelled || my != session }
        bg.execute {
            var s: OpenedSource? = null
            try {
                s = SourceOpener.open(app, uri, cancel)
                val p = TrackProbe.probe(s)
                val opened = s
                main.post { if (my != session) opened.close() else onProbed(incoming, opened, p) }
            } catch (e: Throwable) {
                try { s?.close() } catch (_: Exception) {}
                if (e is CancelledException) return@execute
                val msg = if (e is CoreException) e.message ?: MSG_BROKEN else {
                    profile.log("Открытие: ${e.javaClass.simpleName}: ${e.message}"); MSG_BROKEN
                }
                main.post { if (my == session) { screen = Screen.Error("Не удалось открыть файл", msg); changed() } }
            }
        }
    }

    private fun onProbed(incoming: IncomingSource, s: OpenedSource, p: ProbeResult) {
        src = s; probe = p
        selected = p.defaultTrack.coerceIn(0, p.tracks.size - 1)
        sourceName = s.displayName
        // FR-31 (С6): одно касание — без экранов выбора.
        if (OneTapExtract.applies(settings, incoming, s, p)) {
            ExportJobs.enqueueExtractPrimary(app, s.uri, p.tracks[0], s.baseName)
            val uri = s.uri
            closeSession()
            screen = Screen.Home
            changed()
            val note = if (ExportJobs.notificationsPermitted(app)) "Сохраняем звук: ${s.displayName}" else ExportJobs.MSG_NO_NOTIFICATIONS
            message(note, "Другой формат…") { open(uri, false) }
            return
        }
        val isVideo = isVideo(s, p)
        val t = p.tracks[selected]
        // С3: аудиофайл с одной раскодируемой дорожкой — сразу в редактор.
        if (!isVideo && p.tracks.size == 1 && t.decodable) { startDecode(); return }
        showExtract()
    }

    private fun isVideo(s: OpenedSource, p: ProbeResult) = p.hasVideo || s.mime?.startsWith("video/") == true

    private fun showExtract() {
        val s = src ?: return
        val p = probe ?: return
        val items = p.tracks.mapIndexed { i, t -> TrackItem(i, t.title(), t.isDefault, t.decodable, t.reason) }
        screen = Screen.Extract(s.displayName, isVideo(s, p), items, selected, primaryFor(p.tracks[selected], p))
        changed()
    }

    /** FR-6a: главная кнопка и пометка под ней. */
    private fun primaryFor(t: AudioTrackInfo, p: ProbeResult): PrimaryExtract {
        val pl = ExtractPlanner.plan(t.mime, t.decodable)
        if (pl.action == ExtractPlanner.Action.AS_IS) {
            val ext = com.rassul.audioeditor.media.RemuxCopier.asIsExt(t, p)
            return if (ext == null) PrimaryExtract(pl.buttonText, "Этот кодек нельзя сохранить без перекодирования, а декодера в телефоне нет.", false, null)
            else PrimaryExtract(pl.buttonText, "Копирование ${t.codecName} без изменений (.$ext)", true, pl.note)
        }
        return PrimaryExtract(pl.buttonText, pl.note, true, pl.hint)
    }

    override fun pickVideo(activity: Activity) {
        try { SourceOpener.launchPickVideo(activity) } catch (_: ActivityNotFoundException) {
            try { SourceOpener.launchOpenDocument(activity) } catch (_: ActivityNotFoundException) { message("Не найдено приложение для выбора файлов.") }
        }
    }

    override fun pickAudioOrVideoFile(activity: Activity) {
        try { SourceOpener.launchOpenDocument(activity) } catch (_: ActivityNotFoundException) { message("Не найдено приложение для выбора файлов.") }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != SourceOpener.REQUEST_PICK_VIDEO && requestCode != SourceOpener.REQUEST_OPEN_DOCUMENT) return false
        val inc = SourceOpener.onActivityResult(app, requestCode, resultCode, data) ?: return true
        open(inc.uri, false)
        return true
    }

    override fun selectTrack(index: Int) {
        val p = probe ?: return
        if (index !in p.tracks.indices) return
        selected = index
        showExtract()
    }

    // ================================================================= извлечение (С1, С2)

    override fun extractPrimary() {
        val s = src ?: return; val p = probe ?: return
        ExportJobs.enqueueExtractPrimary(app, s.uri, p.tracks[selected], s.baseName)
    }

    override fun extractAs(option: ExtractOption, request: ExportRequest?) {
        val s = src ?: return; val p = probe ?: return
        val t = p.tracks[selected]
        if (option != ExtractOption.AS_IS && !t.decodable) { message(t.reason ?: "Этот формат недоступен для выбранной дорожки."); return }
        ExportJobs.enqueueExtract(app, s.uri, t, s.baseName, option, request)
    }

    // ================================================================= редактор (С3)

    override fun openInEditor() {
        val p = probe ?: return
        val t = p.tracks[selected]
        if (!t.decodable) { message(t.reason ?: "Эту дорожку нельзя раскодировать на этом телефоне."); return }
        startDecode()
    }

    private fun startDecode() {
        val s = src ?: return; val p = probe ?: return
        val t = p.tracks[selected]
        val my = session
        val name = s.displayName
        screen = Screen.Decoding(name, 0.0)
        changed()
        val pipe = DecodePipeline(app, s, t, p, profile)
        decode = pipe
        pipe.start(object : DecodePipeline.Listener {
            override fun onProgress(fraction: Double) {
                if (my != session || screen !is Screen.Decoding) return
                screen = Screen.Decoding(name, fraction.coerceIn(0.0, 1.0)); changed()
            }
            override fun onDone(result: DecodeResult) {
                if (my != session) { result.cache.close(); return }
                decode = null
                val ed = try {
                    RealEditorController(this@RealAppController, app, result, s, p, t, name, s.baseName,
                        RenderSettings(settings.smoothJoints, settings.jointMs))
                } catch (e: Throwable) {
                    profile.log("Редактор: ${e.javaClass.simpleName}: ${e.message}")
                    result.cache.close()
                    screen = Screen.Error("Не удалось открыть редактор", MSG_BROKEN); changed(); return
                }
                editorImpl = ed
                screen = Screen.Editor
                changed()
                result.warnings.forEach { message(it) }
            }
            override fun onError(message: String) {
                if (my != session) return
                decode = null
                screen = Screen.Error("Не удалось подготовить звук", message); changed()
            }
            override fun onCancelled() { if (my == session) decode = null }
        })
    }

    override fun goHome() {
        closeSession()
        session++
        screen = Screen.Home
        changed()
    }

    /** Отмена декодирования/анализа, закрытие редактора и источника. */
    private fun closeSession() {
        openCancelled = true
        decode?.cancel(); decode = null
        editorImpl?.close(); editorImpl = null
        src?.close(); src = null
        probe = null
    }

    /** FR-13: уход приложения в фон — пауза воспроизведения. */
    fun onAppPause() { editorImpl?.onAppPause() }

    // ================================================================= фоновые задачи (FR-29, FR-33)

    private fun onJobs(j: List<SaveJob>) {
        jobsSnapshot = j
        var newDone = false
        for (x in j) if ((x.state == SaveJobState.DONE || x.state == SaveJobState.VERIFY_FAILED) && finishedSeen.add(x.id)) newDone = true
        if (newDone) { saved = repo.all(); savedRefreshAt = 0L }
        changed()
    }

    override fun cancelJob(id: Long) = ExportJobs.cancel(id)
    override fun skipVerify(id: Long) = ExportJobs.skipVerify(id)

    // ================================================================= «Сохранённые» (С5, FR-28)

    /** Из UI-потока: отдаёт кэш; проверка существования файлов — в фоне, при изменении — onStateChanged. */
    override fun savedFiles(): List<SavedFile> { refreshSaved(force = false); return saved }

    private fun refreshSaved(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - savedRefreshAt < 5000) return
        savedRefreshAt = now
        bg.execute {
            val l = try { repo.listExisting() } catch (_: Exception) { return@execute }
            main.post { if (l != saved) { saved = l; changed() } }
        }
    }

    override fun share(activity: Activity, file: SavedFile) {
        try { activity.startActivity(repo.shareIntent(file)) } catch (e: Exception) { message("Не удалось открыть «Поделиться».") }
    }

    override fun openSaved(file: SavedFile) = open(file.uri, false)

    override fun delete(file: SavedFile) {
        val before = saved
        saved = saved.filter { it.uri != file.uri }
        bg.execute {
            val err = try { repo.delete(file); null } catch (e: CoreException) { e.message } catch (e: Exception) { "Не удалось удалить файл." }
            main.post {
                if (err != null) { saved = before; message(err) } else message("Файл удалён: ${file.name}")
                changed()
            }
        }
    }

    // ================================================================= настройки (FR-19, FR-31)

    override fun updateSettings(s: AppSettings) {
        val fixed = s.copy(jointMs = s.jointMs.coerceIn(1.0, 50.0))
        settings = fixed
        settingsStore.save(fixed)
        editorImpl?.applySettings(RenderSettings(fixed.smoothJoints, fixed.jointMs))
        changed()
    }

    // ================================================================= самопроверка (T19)

    override fun runSelfTest(onProgress: (String) -> Unit, onDone: (report: String) -> Unit) {
        if (selfTestRunning) { main.post { onDone("Самопроверка уже идёт.") }; return }
        selfTestRunning = true
        bg.execute {
            val rep = try { SelfTest(app, profile).run { line -> main.post { onProgress(line) } } }
                      catch (e: Throwable) { "Самопроверка прервана: ${e.javaClass.simpleName}: ${e.message}" }
            main.post { selfTestRunning = false; onDone(rep) }
        }
    }

    companion object {
        const val MSG_BROKEN = "Файл повреждён или не полностью загружен."
    }
}
