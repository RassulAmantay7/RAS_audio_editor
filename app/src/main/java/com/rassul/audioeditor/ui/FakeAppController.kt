package com.rassul.audioeditor.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.peaks.PeakPyramid
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.editor.AppController
import com.rassul.audioeditor.editor.AppSettings
import com.rassul.audioeditor.editor.EditorController
import com.rassul.audioeditor.editor.EditorState
import com.rassul.audioeditor.editor.ExtractOption
import com.rassul.audioeditor.editor.Listener
import com.rassul.audioeditor.editor.PrimaryExtract
import com.rassul.audioeditor.editor.SaveJob
import com.rassul.audioeditor.editor.SaveJobState
import com.rassul.audioeditor.editor.SavedFile
import com.rassul.audioeditor.editor.Screen
import com.rassul.audioeditor.editor.Selection
import com.rassul.audioeditor.editor.TimeFieldErrors
import com.rassul.audioeditor.editor.TrackItem
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.min

/*
 * Фейки контроллеров для разработки интерфейса без логики (T16). Переключатель — MainActivity.USE_FAKE.
 * Всё по таймерам Handler на UI-потоке; данные выдуманы. Настоящую логику подключает интеграция (T20).
 */
class FakeAppController : AppController {
    private val h = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()
    override var screen: Screen = Screen.Home
        private set
    override var jobs: List<SaveJob> = emptyList()
        private set
    override var settings: AppSettings = AppSettings()
        private set
    override var editor: EditorController? = null
        private set

    private var fakeName = "Отпуск_Турция_2026.mp4"
    private var fakeVideo = true
    private var selected = 0
    private var nextJob = 1L
    private var decodeTick: Runnable? = null
    private val saved = arrayListOf(
        SavedFile(Uri.parse("content://fake/saved/lecture_edit.m4a"), "lecture_edit.m4a", 41_300_000, 1_800_000, now() - 3_600_000, false),
        SavedFile(Uri.parse("content://fake/saved/Отпуск_audio.m4a"), "Отпуск_audio.m4a", 12_900_000, 540_000, now() - 90_000_000, false),
        SavedFile(Uri.parse("content://fake/saved/interview_trim.m4a"), "interview_trim.m4a", 3_100_000, 126_000, now() - 400_000_000, true),
        SavedFile(Uri.parse("content://fake/saved/podcast_edit.flac"), "podcast_edit.flac", 212_000_000, 2_700_000, now() - 900_000_000, false),
    )

    private fun now() = System.currentTimeMillis()
    internal fun changed() { for (l in listeners) l.onStateChanged() }
    internal fun message(t: String, a: String? = null, onA: (() -> Unit)? = null) { for (l in listeners) l.onMessage(t, a, onA) }

    override fun addListener(l: Listener) { listeners += l }
    override fun removeListener(l: Listener) { listeners -= l }

    override fun open(uri: Uri, fromShare: Boolean) {
        fakeName = uri.lastPathSegment ?: fakeName
        fakeVideo = !fakeName.endsWith(".mp3")
        selected = 0
        screen = Screen.Probing(fakeName)
        changed()
        h.postDelayed({
            if (screen !is Screen.Probing) return@postDelayed
            if (settings.oneTapExtract && fromShare && fakeVideo) {
                screen = Screen.Home; changed(); extractPrimary()
            } else { showExtract() }
        }, 900)
    }

    private fun tracks(): List<TrackItem> = if (!fakeVideo) listOf(
        TrackItem(0, "Дорожка 1 · MP3 · стерео · 44,1 кГц · 128 кбит/с", true, true, null),
    ) else listOf(
        TrackItem(0, "Дорожка 1 · рус · AAC · стерео · 48 кГц · 192 кбит/с", true, true, null),
        TrackItem(1, "Дорожка 2 · eng · AC-3 · 5.1 · 48 кГц · 384 кбит/с", false, false,
            "Этот телефон не умеет раскодировать звук Dolby Digital (AC-3). Его можно сохранить без изменений, но нельзя редактировать или перекодировать."),
    )

    private fun primaryFor(t: TrackItem): PrimaryExtract = when {
        !fakeVideo -> PrimaryExtract("Сохранить в M4A", "Перекодирование в AAC 192 кбит/с", true,
            "Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV в «Другой формат…».")
        !t.decodable -> PrimaryExtract("Сохранить как есть", "Копирование AC-3 без изменений (.ac3)", true, null)
        else -> PrimaryExtract("Сохранить в M4A", "Без потерь — копирование AAC", true, null)
    }

    private fun showExtract() {
        val t = tracks()
        screen = Screen.Extract(fakeName, fakeVideo, t, selected, primaryFor(t[selected]))
        changed()
    }

    override fun pickVideo(activity: Activity) { open(Uri.parse("content://fake/video/Отпуск_Турция_2026.mp4"), false) }
    override fun pickAudioOrVideoFile(activity: Activity) { open(Uri.parse("content://fake/file/Лекция_по_физике.mp3"), false) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean = false

    override fun selectTrack(index: Int) { selected = index; showExtract() }

    override fun extractPrimary() {
        val t = tracks()[selected]
        val p = primaryFor(t)
        val base = fakeName.substringBeforeLast('.')
        startJob("Сохранение: ${base}_audio." + if (p.title.contains("как есть")) "ac3" else "m4a")
    }

    override fun extractAs(option: ExtractOption, request: ExportRequest?) {
        val ext = when (option) {
            ExtractOption.AS_IS -> "aac"; ExtractOption.M4A -> "m4a"; ExtractOption.OPUS -> "ogg"
            ExtractOption.FLAC -> "flac"; ExtractOption.WAV -> "wav"
        }
        startJob("Сохранение: ${fakeName.substringBeforeLast('.')}_audio.$ext")
    }

    override fun openInEditor() {
        screen = Screen.Decoding(fakeName, 0.0)
        changed()
        var p = 0.0
        decodeTick?.let { h.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                p += 0.04
                if (screen !is Screen.Decoding) return
                if (p >= 1.0) {
                    editor = FakeEditorController(this@FakeAppController, fakeName)
                    screen = Screen.Editor
                } else {
                    screen = Screen.Decoding(fakeName, p)
                    h.postDelayed(this, 90)
                }
                changed()
            }
        }
        decodeTick = r
        h.postDelayed(r, 90)
    }

    override fun goHome() {
        decodeTick?.let { h.removeCallbacks(it) }
        (editor as? FakeEditorController)?.close()
        editor = null
        screen = Screen.Home
        changed()
    }

    // ---- фоновые задачи
    internal fun startJob(title: String) {
        val id = nextJob++
        val name = title.substringAfter(": ")
        setJob(SaveJob(id, title, 0.0, SaveJobState.RUNNING, name, null, null))
        val step = object : Runnable {
            var p = 0.0
            override fun run() {
                val j = jobs.firstOrNull { it.id == id } ?: return
                if (j.state != SaveJobState.RUNNING) return
                p += 0.06
                if (p >= 1.0) {
                    setJob(j.copy(progress = 1.0, state = SaveJobState.VERIFYING, message = "Проверка сохранённого файла…"))
                    h.postDelayed({ finishVerify(id, "Сохранено и проверено: $name") }, 1500)
                } else { setJob(j.copy(progress = p)); h.postDelayed(this, 140) }
            }
        }
        h.postDelayed(step, 140)
    }

    private fun finishVerify(id: Long, msg: String) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (j.state != SaveJobState.VERIFYING) return
        val uri = Uri.parse("content://fake/saved/${j.fileName}")
        saved.add(0, SavedFile(uri, j.fileName ?: "audio.m4a", 9_800_000, 300_000, now(), false))
        setJob(j.copy(state = SaveJobState.DONE, uri = uri, message = msg))
    }

    private fun setJob(j: SaveJob) {
        jobs = if (jobs.any { it.id == j.id }) jobs.map { if (it.id == j.id) j else it } else jobs + j
        changed()
    }

    override fun cancelJob(id: Long) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        setJob(j.copy(state = SaveJobState.CANCELLED, message = "Сохранение отменено. Недописанный файл удалён."))
    }

    override fun skipVerify(id: Long) {
        val j = jobs.firstOrNull { it.id == id } ?: return
        if (j.state == SaveJobState.VERIFYING) finishVerify(id, "Сохранено. Проверка пропущена.")
    }

    // ---- сохранённые
    override fun savedFiles(): List<SavedFile> = saved.toList()
    override fun share(activity: Activity, file: SavedFile) { message("Фейк: «Поделиться» ${file.name}") }
    override fun openSaved(file: SavedFile) { fakeName = file.name; fakeVideo = false; open(file.uri, false) }
    override fun delete(file: SavedFile) { saved.remove(file); message("Файл удалён: ${file.name}"); changed() }

    override fun updateSettings(s: AppSettings) { settings = s; changed() }

    override fun runSelfTest(onProgress: (String) -> Unit, onDone: (report: String) -> Unit) {
        val lines = listOf(
            "Декодер AC-3: нет", "Декодер E-AC-3: нет", "Декодер AAC: есть (c2.android.aac.decoder)",
            "Float-выход декодера: есть", "Задержка декодера MP3: 1105 сэмплов (ожидалось 1105)",
            "Задержка энкодера AAC: 2048 сэмплов", "Запись M4A своим писателем: ок", "Запись FLAC 24 бит: ок",
        )
        val sb = StringBuilder()
        lines.forEachIndexed { i, s ->
            h.postDelayed({ sb.append(s).append('\n'); onProgress(s) }, 350L * (i + 1))
        }
        h.postDelayed({ onDone("Самопроверка (фейк)\n$sb\nИтог: 7 из 8 ок, AC-3/E-AC-3 недоступны") }, 350L * (lines.size + 1))
    }
}

class FakeEditorController(private val host: FakeAppController, name: String) : EditorController {
    val wave = SyntheticWaveSource(48000, 1800, 2)
    private val h = Handler(Looper.getMainLooper())
    private val sr = 48000
    private val fakePeaks by lazy { PeakPyramid(2) }
    private var s = EditorState(
        fileName = name, sampleRate = sr, channels = 2, length = wave.lengthFrames, selection = null, playing = false,
        looping = false, canUndo = false, canRedo = false, hasOverload = false, busy = null, fastTrimAvailable = false, notices = emptyList(),
    )
    private class Snap(val segs: List<SynthSeg>, val notices: List<String>)
    private val undo = ArrayList<Snap>()
    private val redo = ArrayList<Snap>()
    private var edits = 0
    private var pos = 0L
    private var startNanos = 0L

    override val state: EditorState get() = s
    override val peaks: PeakPyramid get() = fakePeaks

    override fun readSamples(from: Long, dst: FloatArray, frames: Int): Int = wave.readSamples(from, dst, frames)

    override val playheadFrame: Long
        get() {
            if (!s.playing) return pos
            val el = (System.nanoTime() - startNanos) / 1e9
            // имитируем опрос AudioTrack раз в 20 мс (курсор должен сглаживаться в WaveformView)
            val p = pos + (Math.floor(el * 50.0) / 50.0 * sr).toLong()
            val sel = s.selection
            if (s.looping && sel != null && p >= sel.end) {
                val span = max(1L, sel.end - sel.start)
                pos = sel.start; startNanos = System.nanoTime() - (((p - sel.end) % span) * 1e9 / sr).toLong()
                return sel.start
            }
            if (p >= s.length) { pos = 0; set(s.copy(playing = false)); return 0 }
            return p
        }

    private fun set(n: EditorState) { s = n; host.changed() }

    override fun play() {
        if (s.selection != null && s.looping) { val sel = s.selection!!; if (pos < sel.start || pos >= sel.end) pos = sel.start }
        if (pos >= s.length) pos = 0
        startNanos = System.nanoTime()
        set(s.copy(playing = true))
    }

    override fun pause() { pos = playheadFrame; set(s.copy(playing = false)) }
    override fun seekTo(frame: Long) { pos = frame.coerceIn(0, s.length); startNanos = System.nanoTime(); host.changed() }
    override fun setLooping(loop: Boolean) { set(s.copy(looping = loop)) }

    override fun setSelection(sel: Selection?) {
        val c = sel?.let { Selection(it.start.coerceIn(0, s.length), it.end.coerceIn(0, s.length)) }?.takeIf { it.end > it.start }
        set(s.copy(selection = c, looping = s.looping && c != null, fastTrimAvailable = c != null && edits == 0))
    }

    override fun setSelectionText(startText: String, endText: String): TimeFieldErrors {
        if (startText.isBlank() && endText.isBlank()) { setSelection(null); return TimeFieldErrors(null, null) }
        val a = parse(startText.ifBlank { "0" })
        val b = parse(endText.ifBlank { formatTime(s.length) })
        val total = formatTime(s.length)
        var eS: String? = null
        var eE: String? = null
        if (a == null) eS = "Неверный формат. Пример: 1:23.456"
        else if (a < 0 || a >= s.length) eS = "Начало вне файла (длительность $total)"
        if (b == null) eE = "Неверный формат. Пример: 1:23.456"
        else if (b > s.length) eE = "Конец позже конца файла ($total)"
        if (eS == null && eE == null && a != null && b != null && b <= a) eE = "Конец должен быть позже начала"
        if (eS != null || eE != null) return TimeFieldErrors(eS, eE)
        setSelection(Selection(a!!, b!!))
        return TimeFieldErrors(null, null)
    }

    /** «ч:мм:сс.ммм», «м:сс», «сс,мм» → кадры, n = round(t·fs), половина вверх (FR-15, FR-C1); null — не разобрано. */
    private fun parse(t: String): Long? {
        val parts = t.trim().split(':')
        if (parts.size !in 1..3) return null
        val last = parts.last().replace(',', '.')
        val secPart = last.split('.')
        if (secPart.size > 2 || secPart[0].isEmpty() || !secPart[0].all { it.isDigit() }) return null
        val frac = if (secPart.size == 2) secPart[1] else ""
        if (frac.length > 3 || !frac.all { it.isDigit() }) return null
        var sec = secPart[0].toLong()
        val ints = parts.dropLast(1)
        if (ints.any { it.isEmpty() || !it.all { c -> c.isDigit() } }) return null
        if (ints.size == 1) sec += ints[0].toLong() * 60
        if (ints.size == 2) sec += ints[1].toLong() * 60 + ints[0].toLong() * 3600
        val ms = sec * 1000 + (frac.padEnd(3, '0')).toLong()
        return (ms * sr + 500) / 1000
    }

    override fun formatTime(frame: Long): String {
        val ms = (frame * 1000 + sr / 2) / sr
        val hh = ms / 3_600_000; val mm = (ms / 60_000) % 60; val ss = (ms / 1000) % 60; val mmm = ms % 1000
        return if (hh > 0) "%d:%02d:%02d.%03d".format(hh, mm, ss, mmm) else "%d:%02d.%03d".format(mm, ss, mmm)
    }

    private fun push() {
        undo.add(Snap(wave.segments, s.notices)); redo.clear(); edits++
    }

    private fun applySegs(segs: List<SynthSeg>, notices: List<String> = s.notices, sel: Selection? = null) {
        wave.segments = segs
        val over = segs.any { it.gain > 1.28 }
        pos = min(pos, wave.lengthFrames)
        set(s.copy(length = wave.lengthFrames, selection = sel, hasOverload = over, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty(),
            notices = notices, fastTrimAvailable = sel != null && edits == 0, looping = s.looping && sel != null))
    }

    /** Разрезать фрагменты по границам результата [a, b) и применить f к попавшим внутрь. */
    private fun mapRange(a: Long, b: Long, f: (SynthSeg) -> SynthSeg?): List<SynthSeg> {
        val out = ArrayList<SynthSeg>()
        var acc = 0L
        for (g in wave.segments) {
            val gs = acc; val ge = acc + g.len
            acc = ge
            val l = max(gs, a); val r = min(ge, b)
            if (l >= r) { if (!(ge <= a || gs >= b)) out += g else out += g; continue }
            if (l > gs) out += g.copy(len = l - gs)
            f(g.copy(srcStart = g.srcStart + (l - gs), len = r - l))?.let { out += it }
            if (r < ge) out += g.copy(srcStart = g.srcStart + (r - gs), len = ge - r)
        }
        return out
    }

    override fun keepSelection() {
        val sel = s.selection ?: return
        push(); applySegs(mapRange(sel.start, sel.end, { it }).let { all ->
            // оставить только попавшее в выделение
            val keep = ArrayList<SynthSeg>(); var acc = 0L
            for (g in all) { val ge = acc + g.len; if (acc >= sel.start && ge <= sel.end) keep += g; acc = ge }
            keep
        }); pos = 0
    }

    override fun deleteSelection() {
        val sel = s.selection ?: return
        push(); applySegs(mapRange(sel.start, sel.end) { null })
    }

    override fun volume(db: Double) {
        val g = Math.pow(10.0, db / 20.0) // FR-20: g = 10^(dB/20)
        val sel = s.selection
        push()
        applySegs(mapRange(sel?.start ?: 0, sel?.end ?: s.length) { it.copy(gain = it.gain * g) }, sel = sel)
    }

    override fun fade(fadeIn: Boolean, curve: FadeCurve, durationSec: Double) {
        push(); applySegs(wave.segments, sel = s.selection)
        host.message(if (fadeIn) "Fade in применён" else "Fade out применён")
    }

    override fun normalize(target: NormalizeTarget) {
        push()
        set(s.copy(busy = "Измеряем громкость…", canUndo = true, canRedo = false))
        h.postDelayed({
            val notes = if (target is NormalizeTarget.Lufs && target.targetLufs > -15.0)
                listOf("Громкость −14 LUFS недостижима без перегрузки. Достигнуто −15,3 LUFS (пик −1,0 dBTP).") else emptyList()
            applySegs(wave.segments, notes, s.selection)
            set(s.copy(busy = null))
        }, 1600)
    }

    private fun restore(sn: Snap) { wave.segments = sn.segs }
    override fun undo() {
        val sn = undo.removeLastOrNull() ?: return
        redo.add(Snap(wave.segments, s.notices)); edits--
        restore(sn); applySegs(sn.segs, sn.notices)
    }
    override fun redo() {
        val sn = redo.removeLastOrNull() ?: return
        undo.add(Snap(wave.segments, s.notices)); edits++
        restore(sn); applySegs(sn.segs, sn.notices)
    }

    override fun export(request: ExportRequest) {
        host.startJob("Сохранение: ${s.fileName.substringBeforeLast('.')}_edit.${request.format.ext}")
    }

    override fun describeExport(request: ExportRequest): List<String> {
        val out = ArrayList<String>()
        val secs = s.length.toDouble() / sr
        when (request.format) {
            ExportFormat.M4A -> {
                val kb = request.bitrateKbps ?: 192
                out += "AAC-LC, $kb кбит/с, ${Format.kHz(request.sampleRate ?: sr)}, стерео"
                out += "Примерный размер: " + Format.size((kb * 1000L / 8 * secs).toLong())
                out += "Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV."
            }
            ExportFormat.OPUS -> {
                val kb = request.bitrateKbps ?: 128
                out += "Opus, $kb кбит/с, 48 кГц, стерео"
                out += "Примерный размер: " + Format.size((kb * 1000L / 8 * secs).toLong())
                if (sr != 48000) out += "Частота изменится на 48 кГц: так требует Opus."
            }
            ExportFormat.FLAC -> {
                val b = request.bits ?: 16
                out += "FLAC, $b бит, ${Format.kHz(request.sampleRate ?: sr)}, стерео, без потерь"
                out += "Примерный размер: " + Format.size((sr * 2L * b / 8 * secs * 0.6).toLong())
                if (b == 16) out += "При переводе в 16 бит добавляется дизеринг (шум округления)."
            }
            ExportFormat.WAV -> {
                val b = request.bits ?: 16
                out += "WAV, ${if (b == 32) "32 бит float" else "$b бит"}, ${Format.kHz(request.sampleRate ?: sr)}, стерео, без сжатия"
                out += "Примерный размер: " + Format.size((sr * 2L * b / 8 * secs).toLong())
                if (b == 16) out += "При переводе в 16 бит добавляется дизеринг (шум округления)."
            }
        }
        if (s.hasOverload) out += "Есть участки выше 0 дБ: при сохранении в 16/24 бит или AAC они будут обрезаны (срез)."
        return out
    }

    override fun fastTrimPreview(): String? {
        val sel = s.selection ?: return null
        if (!s.fastTrimAvailable) return null
        return "Фактическое начало ${formatTime(max(0, sel.start - 912))} (на 19 мс раньше выделения), " +
            "конец ${formatTime(min(s.length, sel.end + 240))} (на 5 мс позже)."
    }

    override fun fastTrim() {
        host.startJob("Сохранение: ${s.fileName.substringBeforeLast('.')}_trim.m4a")
    }

    override fun close() { h.removeCallbacksAndMessages(null) }
}
