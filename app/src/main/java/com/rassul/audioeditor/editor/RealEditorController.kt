package com.rassul.audioeditor.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.EditRules
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.edit.UndoStack
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.loudness.Normalizer
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.peaks.PeakPyramid
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.time.TimeConv
import com.rassul.audioeditor.core.time.TimeParse
import com.rassul.audioeditor.diag.DeviceProfile
import com.rassul.audioeditor.export.ExportJobs
import com.rassul.audioeditor.media.AudioTrackInfo
import com.rassul.audioeditor.media.DecodeResult
import com.rassul.audioeditor.media.FastTrimPreview
import com.rassul.audioeditor.media.OpenedSource
import com.rassul.audioeditor.media.Player
import com.rassul.audioeditor.media.ProbeResult
import com.rassul.audioeditor.media.RemuxCopier
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/*
 * Интеграция (T20). Настоящий EditorController: PcmCache (декодированный исходник) + UndoStack (список правок,
 * FR-16) + Renderer (единый рендер для плеера и экспорта, FR-13) + Player (FR-13, FR-32) + PeakPyramid (FR-12).
 *
 * Потоки:
 *  - все публичные методы и колбэки Listener — UI-поток;
 *  - пирамида результата пересчитывается в фоне (pyrExec) с первого изменённого кадра (Renderer.firstDifference,
 *    PeakPyramid.rebuildFrom; timeline.md «Первый изменённый сэмпл p»). Двойная буферизация: UI рисует display,
 *    фон пишет только spare; после пересчёта — обмен в UI-потоке и onStateChanged. Каждая пирамида помнит, какому
 *    Renderer она соответствует, поэтому догонять отстающую копию можно с любого состояния;
 *  - перегрузка (FR-14) и число срезаемых сэмплов (FR-C7) — полный проход рендера в фоне (anaExec), отменяется
 *    при следующей правке;
 *  - нормализация (FR-22) — resolveNormalize в фоне (normExec), state.busy с процентом.
 * Источник (PcmCache) читают одновременно плеер, пирамида, UI и экспорт — PcmCache.read синхронизирован,
 * Renderer.render потокобезопасен (аудит T14: чтение под synchronized(source)).
 * Кэш удаляется при close(), но не раньше, чем закончатся экспорты, которые его читают (выход во время экспорта).
 */
class RealEditorController internal constructor(
    private val app: RealAppController,
    ctx: Context,
    private val decoded: DecodeResult,
    private val src: OpenedSource,
    private val probe: ProbeResult,
    private val track: AudioTrackInfo,
    private val fileName: String,
    private val baseName: String,
    settings: RenderSettings,
) : EditorController {
    private val appCtx = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val cache = decoded.cache
    private val fs = decoded.spec.sampleRate
    private val channels = decoded.spec.channels
    private val profile = DeviceProfile.get(appCtx)

    private var renderSettings = settings
    private val undoStack = UndoStack()
    @Volatile private var renderer: Renderer = Renderer(cache, EditList(), renderSettings)
    private val player = Player(renderer)
    @Volatile private var closed = false

    // ---------------------------------------------------------------- пирамида (двойной буфер)
    private class Slot(val pyr: PeakPyramid, @Volatile var renderer: Renderer?)
    /** То, что рисует UI (меняется только в UI-потоке). Пирамида декодера соответствует рендеру без правок. */
    private var display = Slot(decoded.peaks, renderer)
    private var spare = Slot(PeakPyramid(channels), null)
    @Volatile private var latestPeaks: Renderer? = null
    private val pyrExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "peaks").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val anaExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "overload").apply { priority = Thread.MIN_PRIORITY } }
    private val normExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "normalize") }
    private val auxExec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "fasttrim") }

    // ---------------------------------------------------------------- анализ
    @Volatile private var anaGen = 0
    @Volatile private var normGen = 0
    /** Сэмплов |x| > 1 в текущем результате (−1 — ещё не посчитано). */
    private var clipped = -1L

    // ---------------------------------------------------------------- быстрая обрезка (FR-23)
    private val fastSupported = try { RemuxCopier.fastTrimSupported(track, probe) } catch (_: Exception) { false }
    private var fastCache: Pair<Range, FastTrimPreview>? = null
    private var fastPending: Pair<Range, Future<FastTrimPreview?>>? = null
    private val fastDebounce = Runnable { prefetchFastTrim() }

    // ---------------------------------------------------------------- экспорты, читающие кэш
    private val activeExports = HashSet<Long>()
    private val jobsListener = ExportJobs.Listener { jobs -> onJobs(jobs) }

    /** Предупреждения декодирования (FR-4) — показываются постоянно. */
    private val decodeNotices = decoded.warnings

    private var st = EditorState(
        fileName = fileName, sampleRate = fs, channels = channels, length = renderer.length, selection = null,
        playing = false, looping = false, canUndo = false, canRedo = false, hasOverload = false, busy = null,
        fastTrimAvailable = false, notices = decodeNotices,
    )

    init {
        player.listener = object : Player.Listener {
            override fun onPlayStateChanged(playing: Boolean) { if (!closed && st.playing != playing) { st = st.copy(playing = playing); changed() } }
            override fun onEnded() { if (!closed) changed() }
            override fun onError(message: String) { if (!closed) { st = st.copy(playing = false); app.message(message) } }
        }
        ExportJobs.addListener(jobsListener)
        // Прогрев запасной пирамиды (копия исходника) и анализ перегрузки исходника (5.1 → стерео может дать |x| > 1).
        schedulePeaks(renderer)
        scheduleOverload(renderer)
    }

    // ================================================================= EditorController

    override val state: EditorState get() = st
    override val peaks: PeakPyramid get() = display.pyr
    override val playheadFrame: Long get() = if (closed) 0L else player.position

    override fun readSamples(from: Long, dst: FloatArray, frames: Int): Int {
        if (closed) return 0
        val r = renderer
        val len = r.length
        if (from < 0 || from >= len || frames <= 0) return 0
        return try { r.render(from, dst, minOf(frames.toLong(), len - from).toInt()) } catch (_: Exception) { 0 }
    }

    override fun play() {
        if (closed) return
        val sel = st.selection
        // FR-32: курсор вне выделения при включённом повторе → с начала выделения.
        if (st.looping && sel != null) {
            val p = player.position
            if (p < sel.start || p >= sel.end) player.seekTo(sel.start)
        }
        player.play()
        if (player.isPlaying && !st.playing) { st = st.copy(playing = true); changed() }
    }

    override fun pause() {
        if (closed) return
        player.pause()
        if (st.playing) { st = st.copy(playing = false); changed() }
    }

    override fun seekTo(frame: Long) {
        if (closed) return
        player.seekTo(frame.coerceIn(0L, renderer.length))
    }

    override fun setLooping(loop: Boolean) {
        val on = loop && st.selection != null
        player.setSelection(st.selection?.let { Range(it.start, it.end) })
        player.setRepeat(on)
        if (st.looping != on) { st = st.copy(looping = on); changed() }
    }

    override fun setSelection(sel: Selection?) {
        applySelection(sel)
        changed()
    }

    private fun applySelection(sel: Selection?) {
        val len = renderer.length
        val s = sel?.let { Selection(it.start.coerceIn(0L, len), it.end.coerceIn(0L, len)) }?.takeIf { it.end > it.start }
        val looping = st.looping && s != null
        player.setSelection(s?.let { Range(it.start, it.end) })
        player.setRepeat(looping)
        st = st.copy(selection = s, looping = looping)
        updateFastTrim()
    }

    /** FR-15: «ч:мм:сс.ммм», n = round_half_up(t·fs) (TimeConv, FR-C1); ошибки — под полями. */
    override fun setSelectionText(startText: String, endText: String): TimeFieldErrors {
        if (startText.isBlank() && endText.isBlank()) { setSelection(null); return TimeFieldErrors(null, null) }
        val len = renderer.length
        val a = TimeConv.parseWithin(startText.ifBlank { "0" }, fs, len)
        val b = TimeConv.parseWithin(endText.ifBlank { TimeConv.format(len, fs) }, fs, len)
        val ea = (a as? TimeParse.Error)?.message
        val eb = (b as? TimeParse.Error)?.message
        if (ea != null || eb != null) return TimeFieldErrors(ea, eb)
        val s = (a as TimeParse.Ok).samples
        val e = (b as TimeParse.Ok).samples
        TimeConv.rangeError(s, e, len, fs)?.let { return TimeFieldErrors(null, it) }
        setSelection(Selection(s, e))
        return TimeFieldErrors(null, null)
    }

    override fun formatTime(frame: Long): String = TimeConv.format(frame, fs)

    // ---------------------------------------------------------------- правки

    override fun keepSelection() {
        val sel = st.selection ?: run { app.message(EditRules.MSG_EMPTY); return }
        val r = Range(sel.start, sel.end)
        apply(EditOp.Keep(r), { p -> (p - r.start).coerceIn(0L, r.length) }, null)
    }

    override fun deleteSelection() {
        val sel = st.selection ?: run { app.message(EditRules.MSG_EMPTY); return }
        val r = Range(sel.start, sel.end)
        apply(EditOp.Delete(r), { p -> if (p >= r.end) p - r.length else if (p > r.start) r.start else p }, null)
    }

    override fun volume(db: Double) {
        if (!db.isFinite() || db < -40.0 || db > 20.0) { app.message("Громкость — от −40,0 до +20,0 дБ"); return }
        val r = st.selection?.let { Range(it.start, it.end) }
        apply(EditOp.Volume(r, db), null, st.selection)
    }

    override fun fade(fadeIn: Boolean, curve: FadeCurve, durationSec: Double) {
        val len = renderer.length
        val range = st.selection?.let { Range(it.start, it.end) } ?: run {
            if (!durationSec.isFinite() || durationSec <= 0.0) { app.message("Введите длительность больше нуля"); return }
            EditRules.fadeRangeWithoutSelection(durationSec * 1000.0, fs, len, fadeIn)
        }
        if (range.length == 0L) { app.message(EditRules.MSG_EMPTY); return }
        apply(EditOp.Fade(range, fadeIn, curve), null, st.selection)
    }

    /** FR-22: усиление вычисляется один раз по результату предшествующих правок (Renderer.resolveNormalize), в фоне. */
    override fun normalize(target: NormalizeTarget) {
        if (closed) return
        if (st.busy != null) { app.message("Подождите, предыдущее действие ещё выполняется."); return }
        val r = renderer
        val gen = ++normGen
        val title = if (target is NormalizeTarget.Lufs) "Измеряем громкость…" else "Ищем пик…"
        st = st.copy(busy = title); changed()
        var last = 0L
        val progress = object : Progress {
            override val isCancelled: Boolean get() = closed || gen != normGen
            override fun report(fraction: Double) {
                val now = System.nanoTime()
                if (now - last < 250_000_000L) return
                last = now
                val pct = (fraction * 100).toInt().coerceIn(0, 100)
                main.post { if (!closed && gen == normGen && st.busy != null) { st = st.copy(busy = "$title $pct %"); changed() } }
            }
        }
        normExec.execute {
            val res: Result<EditOp.Normalize> = try { Result.success(r.resolveNormalize(EditOp.Normalize(target), progress)) }
                                                catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (closed || gen != normGen) return@post
                st = st.copy(busy = null)
                val op = res.getOrNull()
                if (op == null) {
                    val e = res.exceptionOrNull()
                    if (e !is CancelledException) {
                        profile.log("Нормализация: ${e?.javaClass?.simpleName}: ${e?.message}")
                        app.message(if (e is CoreException) e.message ?: "Не удалось измерить громкость." else "Не удалось измерить громкость.")
                    }
                    changed(); return@post
                }
                if (renderer !== r) { changed(); return@post }   // список сменился во время анализа (не должно: busy)
                val unavailable = op.notice == Normalizer.MSG_TOO_SHORT || op.notice == Normalizer.MSG_TOO_QUIET || op.notice == Normalizer.MSG_SILENT
                if (unavailable) { app.message(op.notice!!); changed(); return@post }
                apply(op, null, st.selection)
                op.notice?.let { app.message(it) }
            }
        }
    }

    override fun undo() {
        if (closed || st.busy != null) return
        val l = undoStack.undo() ?: return
        setRenderer(Renderer(cache, l, renderSettings), null, st.selection)
    }

    override fun redo() {
        if (closed || st.busy != null) return
        val l = undoStack.redo() ?: return
        setRenderer(Renderer(cache, l, renderSettings), null, st.selection)
    }

    /** Добавить правку: проверка EditRules (понятный отказ), новый список в UndoStack, новый Renderer. */
    private fun apply(op: EditOp, mapPos: ((Long) -> Long)?, newSel: Selection?) {
        if (closed) return
        if (st.busy != null && op !is EditOp.Normalize) { app.message("Подождите, предыдущее действие ещё выполняется."); return }
        val cur = renderer
        EditRules.check(op, cur.length)?.let { app.message(it); return }
        val list = undoStack.current.plus(op)
        val r = try { Renderer(cache, list, renderSettings) } catch (e: Exception) {
            app.message(e.message ?: "Эту правку нельзя применить."); return
        }
        undoStack.push(list)
        val pos = mapPos?.invoke(player.position)
        setRenderer(r, pos, newSel)
    }

    private fun setRenderer(r: Renderer, newPos: Long?, sel: Selection?) {
        renderer = r
        player.setRenderer(r, newPos)
        clipped = -1L
        st = st.copy(length = r.length, canUndo = undoStack.canUndo, canRedo = undoStack.canRedo, notices = decodeNotices + r.notices)
        applySelection(sel)
        schedulePeaks(r)
        scheduleOverload(r)
        changed()
    }

    /** Новые настройки сглаживания (FR-19) — рендер пересобирается с ними. */
    internal fun applySettings(s: RenderSettings) {
        if (closed || s == renderSettings) return
        renderSettings = s
        if (undoStack.current.ops.none { it is EditOp.Keep || it is EditOp.Delete }) {
            // Стыков нет — результат не меняется; только новый Renderer для будущих правок.
            val r = Renderer(cache, undoStack.current, s)
            renderer = r; player.setRenderer(r, null)
            return
        }
        setRenderer(Renderer(cache, undoStack.current, s), null, st.selection)
    }

    // ---------------------------------------------------------------- пирамида

    private fun schedulePeaks(r: Renderer) {
        latestPeaks = r
        val slot = spare
        pyrExec.execute {
            if (closed || latestPeaks !== r) return@execute
            try {
                val from = r.firstDifference(slot.renderer)
                slot.renderer = null
                slot.pyr.rebuildFrom(from, r.asSource())
                slot.renderer = r
            } catch (e: Throwable) {
                slot.renderer = null
                if (!closed) profile.log("Пирамида: ${e.javaClass.simpleName}: ${e.message}")
                return@execute
            }
            main.post {
                // Обмен только для самого свежего рендера: иначе следующий фоновый пересчёт уже работает с этим slot.
                if (closed || latestPeaks !== r || spare !== slot) return@post
                spare = display
                display = slot
                changed()
            }
        }
    }

    // ---------------------------------------------------------------- перегрузка (FR-14) и срез (FR-C7)

    private fun scheduleOverload(r: Renderer) {
        val gen = ++anaGen
        anaExec.execute {
            if (closed || gen != anaGen) return@execute
            var count = 0L
            try {
                val ch = r.spec.channels
                val step = 65_536
                val buf = FloatArray(step * ch)
                val total = r.length
                var pos = 0L
                while (pos < total) {
                    if (closed || gen != anaGen) return@execute
                    val n = r.render(pos, buf, minOf(step.toLong(), total - pos).toInt())
                    if (n <= 0) break
                    for (i in 0 until n * ch) { val v = buf[i]; if (v > 1f || v < -1f) count++ }
                    pos += n
                }
            } catch (e: Throwable) {
                if (!closed) profile.log("Перегрузка: ${e.javaClass.simpleName}: ${e.message}")
                return@execute
            }
            main.post {
                if (closed || gen != anaGen || renderer !== r) return@post
                clipped = count
                if (st.hasOverload != (count > 0)) { st = st.copy(hasOverload = count > 0); changed() }
            }
        }
    }

    // ---------------------------------------------------------------- экспорт

    override fun export(request: ExportRequest) {
        if (closed) return
        if (st.busy != null) { app.message("Подождите, предыдущее действие ещё выполняется."); return }
        val id = ExportJobs.enqueueEditorExport(appCtx, renderer, decoded.sourceBits, request, baseName)
        activeExports += id
    }

    override fun describeExport(request: ExportRequest): List<String> {
        val r = renderer
        val plan = try { ExportPlanner.plan(r.spec, decoded.sourceBits, request) } catch (e: Exception) { return listOf(e.message ?: "Неверные параметры") }
        val out = ArrayList<String>()
        val ch = when (plan.outSpec.channels) { 1 -> "моно"; 2 -> "стерео"; else -> "${plan.outSpec.channels} кан." }
        val rate = khz(plan.outSpec.sampleRate)
        out += when (plan.format) {
            ExportFormat.M4A -> "AAC-LC, ${plan.bitrateKbps} кбит/с, $rate, $ch, .m4a"
            ExportFormat.OPUS -> "Opus, ${plan.bitrateKbps} кбит/с, $rate, $ch, .ogg"
            ExportFormat.FLAC -> "FLAC без потерь, ${plan.bits} бит, $rate, $ch"
            ExportFormat.WAV -> "WAV без сжатия, ${if (plan.bits == 32) "32 бит float" else "${plan.bits} бит"}, $rate, $ch"
        } + " · длительность ${formatTime(r.length)}"
        out += plan.notes
        if (plan.dither) out += "16 бит — с дизерингом (TPDF), чтобы тихие места не искажались."
        val lossySrc = !probe.isWav && track.mime.lowercase() !in setOf("audio/flac", "audio/raw")
        val lossyOut = plan.format == ExportFormat.M4A || plan.format == ExportFormat.OPUS
        if (lossySrc && lossyOut) out += "Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV."
        if (lossyOut && undoStack.current.ops.any { it is EditOp.Normalize }) out += "Пик нормализации измерен до сжатия: после AAC/Opus он может немного вырасти."
        val c = clipped
        when {
            c > 0 && plan.format == ExportFormat.WAV && plan.bits == 32 -> out += "Участки выше 0 дБ сохранятся без обрезки (WAV 32 бит float)."
            c > 0 -> out += "Будет обрезано примерно $c сэмпл. выше 0 дБ (подсвечены красным). Уменьшите громкость, чтобы избежать искажений."
            c < 0 -> out += "Проверка перегрузки ещё идёт…"
        }
        out += "Сохранится новым файлом ${baseName}_edit.${plan.format.ext} в Music/RAS audio editor. Исходный файл не меняется."
        return out
    }

    private fun khz(r: Int): String = if (r % 1000 == 0) "${r / 1000} кГц" else "${r / 1000},${(r % 1000).toString().padStart(3, '0').trimEnd('0')} кГц"

    // ---------------------------------------------------------------- быстрая обрезка (FR-23)

    /**
     * Диапазон исходника [a, b) для «Быстро, без потерь»: без правок — выделение; иначе список правок должен сводиться
     * к одной обрезке начала и/или конца (Keep, Delete с начала, Delete до конца). null — недоступно.
     */
    private fun trimRange(): Range? {
        val ops = undoStack.current.ops
        val total = cache.length
        if (ops.isEmpty()) return st.selection?.let { Range(it.start, it.end) }?.takeIf { it.length > 0 && !(it.start == 0L && it.end == total) }
        var a = 0L; var b = total
        for (op in ops) when (op) {
            is EditOp.Keep -> { val na = a + op.range.start; b = a + op.range.end; a = na }
            is EditOp.Delete -> {
                val len = b - a
                when {
                    op.range.start == 0L -> a += op.range.end
                    op.range.end == len -> b = a + op.range.start
                    else -> return null
                }
            }
            else -> return null
        }
        return if (b > a && !(a == 0L && b == total)) Range(a, b) else null
    }

    private fun updateFastTrim() {
        val avail = fastSupported && trimRange() != null
        if (st.fastTrimAvailable != avail) st = st.copy(fastTrimAvailable = avail)
        main.removeCallbacks(fastDebounce)
        if (avail) main.postDelayed(fastDebounce, 600)
    }

    private fun prefetchFastTrim() { val r = trimRange() ?: return; startPreview(r) }

    private fun startPreview(r: Range): Future<FastTrimPreview?> {
        fastPending?.let { (pr, f) -> if (pr == r) return f }
        val f = auxExec.submit<FastTrimPreview?> {
            if (closed) null else RemuxCopier.previewFastTrim(src, probe, track, r.start, r.end, cache.length, profile)
        }
        fastPending = r to f
        return f
    }

    private fun cachedPreview(r: Range): FastTrimPreview? {
        fastCache?.let { (cr, p) -> if (cr == r) return p }
        val (pr, f) = fastPending ?: return null
        if (pr != r || !f.isDone) return null
        val p = try { f.get() } catch (_: Exception) { null } ?: return null
        fastCache = r to p
        return p
    }

    override fun fastTrimPreview(): String? {
        if (closed || !st.fastTrimAvailable) return null
        val r = trimRange() ?: return null
        cachedPreview(r)?.let { return it.message }
        val f = startPreview(r)
        return try {
            // Обход пакетов обычно занимает доли секунды; дольше UI-поток не держим.
            val p = f.get(1500, TimeUnit.MILLISECONDS) ?: return null
            fastCache = r to p
            p.message
        } catch (e: TimeoutException) {
            "Считаем фактические границы по кадрам сжатого потока… Сохранение начнётся, как только расчёт закончится."
        } catch (e: java.util.concurrent.ExecutionException) {
            val c = e.cause
            app.message(if (c is CoreException) c.message ?: "Быстрая обрезка недоступна." else "Быстрая обрезка недоступна для этого файла.")
            fastPending = null
            null
        } catch (e: Exception) { null }
    }

    override fun fastTrim() {
        if (closed || !st.fastTrimAvailable) return
        val r = trimRange() ?: return
        cachedPreview(r)?.let { enqueueFastTrim(it); return }
        val f = startPreview(r)
        auxExec.execute {
            val p = try { f.get() } catch (e: Exception) { null }
            main.post {
                if (p == null) { app.message("Быстрая обрезка недоступна для этого файла."); return@post }
                fastCache = r to p
                enqueueFastTrim(p)
            }
        }
    }

    private fun enqueueFastTrim(p: FastTrimPreview) {
        ExportJobs.enqueueFastTrim(appCtx, src.uri, track, p, baseName)
    }

    // ---------------------------------------------------------------- жизненный цикл

    private fun onJobs(jobs: List<com.rassul.audioeditor.editor.SaveJob>) {
        if (activeExports.isEmpty()) return
        // Экспорт читает кэш только на этапе записи; проверка (VERIFYING) уже читает сохранённый файл.
        // Задачи, которых ещё нет в снимке (устаревшее уведомление), считаются идущими.
        val finished = jobs.filter { it.state != SaveJobState.RUNNING }.mapTo(HashSet()) { it.id }
        activeExports.removeAll(finished)
        if (closed && activeExports.isEmpty()) releaseCache()
    }

    internal fun onAppPause() { if (!closed) pause() }

    override fun close() {
        if (closed) return
        closed = true
        anaGen++; normGen++
        main.removeCallbacks(fastDebounce)
        try { player.release() } catch (_: Exception) {}
        pyrExec.shutdown(); anaExec.shutdown(); normExec.shutdown(); auxExec.shutdown()
        fastPending?.second?.cancel(false)
        if (activeExports.isEmpty()) releaseCache()
        else profile.log("Редактор закрыт во время экспорта: кэш удалится после записи")
    }

    private var cacheReleased = false
    private fun releaseCache() {
        if (cacheReleased) return
        cacheReleased = true
        ExportJobs.removeListener(jobsListener)
        try { cache.close() } catch (_: Exception) {}
    }

    private fun changed() { if (!closed) app.changed() }
}
