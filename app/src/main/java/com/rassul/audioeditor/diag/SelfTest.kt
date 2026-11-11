package com.rassul.audioeditor.diag

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.DecoderDropCalibration
import com.rassul.audioeditor.core.pipeline.EncoderDelay
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPipeline
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.Mp4PacketSink
import com.rassul.audioeditor.core.pipeline.OggOpusPacketSink
import com.rassul.audioeditor.export.ExportJobs
import com.rassul.audioeditor.media.AacEncoderSink
import com.rassul.audioeditor.media.CapabilityProbe
import com.rassul.audioeditor.media.DecodePipeline
import com.rassul.audioeditor.media.FdSource
import com.rassul.audioeditor.media.FileChannelSink
import com.rassul.audioeditor.media.GaplessResolver
import com.rassul.audioeditor.media.MediaCodecEncoderSink
import com.rassul.audioeditor.media.MediaDecoder
import com.rassul.audioeditor.media.MuxerPacketSink
import com.rassul.audioeditor.media.OpenedSource
import com.rassul.audioeditor.media.OpusEncoderSink
import com.rassul.audioeditor.media.OutputTarget
import com.rassul.audioeditor.media.PacketSinkFactory
import com.rassul.audioeditor.media.RemuxCopier
import com.rassul.audioeditor.media.TrackProbe
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/*
 * T19. Самопроверка на телефоне (ОВ-2, ОВ-3, ОВ-4; FR-9, FR-25, FR-26, NFR-7). Результаты калибровки пишутся
 * в DeviceProfile, итог — текстовый отчёт со строками «OK» / «Проблема».
 *
 * 1. Декодеры (FR-9): AC-3/E-AC-3/DTS и др. — CapabilityProbe (MediaCodecList).
 * 2. Задержка декодера (FR-26, timeline.md «риск двойной обрезки»): эталоны assets/selftest (manifest.json: длина L
 *    и позиция импульса I в сэмплах). Сначала «сырой» проход MediaDecoder с decoderDropsOverride = 0: если системный
 *    декодер сам отбрасывает X кадров, а GaplessTrimmer режет всю задержку, импульс окажется на I − X, длина — L − X.
 *    X_imp = I − p₀ (основной), X_len = L − L₀ (контроль). X согласован по всем файлам кодека → DeviceProfile.
 *    Затем DecodePipeline с профилем: длина = L ± 0, импульс = I (± 0 без потерь, ± 2 для сжатых).
 * 3. Задержка энкодера (FR-25, plan.md R2): сигнал «тон 0,03 + импульс 0,8 в I» → MediaCodec → свой писатель
 *    (elst/pre-skip = задержка, которую дал энкодер или допущение) → декодирование тем же путём, что вход редактора.
 *    Сдвиг s = p − I. Истинная задержка D = D_исп + s (импульс запаздывает ровно на недорезанную часть).
 *    s ≠ 0 → D в DeviceProfile, повторная проверка. Длина должна быть N ± 1 (FR-25).
 *    Тот же прогон через MediaMuxer — для выбора запасного пути (DeviceProfile.useMediaMuxerFor*).
 * 4. Свой FLAC 24 бит → DeviceVerifier (длина ± 0, MD5 STREAMINFO = MD5 PCM, RFC 9639 §8.2).
 * 5. NFR-7: 30 с сигнала, время × 60 = оценка для 30 мин (линейная экстраполяция — все этапы потоковые).
 * Вызывать НЕ из UI-потока.
 */
class SelfTest(ctx: Context, private val profile: DeviceProfile = DeviceProfile.get(ctx)) {
    private val app = ctx.applicationContext
    private val dir = File(app.cacheDir, "selftest").apply { deleteRecursively(); mkdirs() }
    private val out = StringBuilder()
    private var okCount = 0
    private var problemCount = 0
    private val calibrated = ArrayList<String>()
    private lateinit var emit: (String) -> Unit

    private fun line(s: String) { out.append(s).append('\n'); emit(s) }
    private fun ok(s: String) { okCount++; line("OK — $s") }
    private fun problem(s: String) { problemCount++; line("Проблема — $s") }
    private fun info(s: String) = line("   $s")
    private fun section(s: String) { out.append('\n'); line("== $s") }

    fun run(onLine: (String) -> Unit): String {
        emit = onLine
        line("Самопроверка RAS audio editor")
        line("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (ExportJobs.hasActive) line("Внимание: идёт сохранение файла — замеры скорости будут завышены.")
        try {
            guarded("Кодеки") { codecs() }
            guarded("Эталонные файлы (задержка декодеров)") { decoders() }
            guarded("Энкодер AAC") { encoder(ExportFormat.M4A) }
            guarded("Энкодер Opus") { encoder(ExportFormat.OPUS) }
            guarded("FLAC 24 бит") { flac24() }
            guarded("Скорость (NFR-7)") { benchmarks() }
        } finally {
            dir.deleteRecursively()
        }
        out.append('\n')
        line("== Итог: OK — $okCount, проблем — $problemCount")
        if (calibrated.isEmpty()) line("Откалибровано: ничего не менялось (значения по умолчанию подходят).")
        else { line("Откалибровано и сохранено:"); calibrated.forEach { line(" • $it") } }
        out.append('\n').append("Профиль устройства:\n").append(profile.describe())
        val j = profile.journal()
        out.append('\n').append("Журнал (последние ${minOf(60, j.size)} строк):\n")
        j.takeLast(60).forEach { out.append(it).append('\n') }
        return out.toString()
    }

    private fun guarded(title: String, block: () -> Unit) {
        section(title)
        try { block() } catch (e: Throwable) {
            profile.log("Самопроверка/$title: ${e.javaClass.simpleName}: ${e.message}")
            problem("$title: проверка прервана (${e.javaClass.simpleName}: ${e.message ?: "без описания"})")
        }
    }

    // ================================================================= 1. кодеки (FR-9, ОВ-3)

    private fun codecs() {
        CapabilityProbe.describe().trim().lines().forEach { info(it) }
        for ((m, n) in listOf("audio/ac3" to "AC-3", "audio/eac3" to "E-AC-3", "audio/vnd.dts" to "DTS"))
            info("$n: ${if (CapabilityProbe.hasDecoder(m)) "декодер есть — такие дорожки можно редактировать" else "декодера нет — только «Сохранить как есть» (FR-9)"}")
        info("Системный энкодер FLAC: ${if (CapabilityProbe.hasEncoder("audio/flac")) "есть" else "нет"} (не используется: FLAC пишется своим кодом)")
        if (!CapabilityProbe.hasEncoder("audio/mp4a-latm")) problem("нет энкодера AAC — сохранение в M4A невозможно")
        else ok("энкодер AAC есть")
        if (!CapabilityProbe.hasEncoder("audio/opus")) problem("нет энкодера Opus — сохранение в Opus невозможно") else ok("энкодер Opus есть")
    }

    // ================================================================= 2. задержка декодеров (FR-26)

    private class Ref(val file: String, val codec: String, val rate: Int, val ch: Int, val length: Long, val impulse: Long)
    private class Meas(val frames: Long, val impulse: Long, val rate: Int, val channels: Int)

    private fun manifest(): List<Ref> {
        val txt = app.assets.open("selftest/manifest.json").use { String(it.readBytes(), Charsets.UTF_8) }
        val arr = JSONObject(txt).getJSONArray("files")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Ref(o.getString("file"), o.getString("codec"), o.getInt("sampleRate"), o.getInt("channels"), o.getLong("lengthFrames"), o.getLong("impulseFrame"))
        }
    }

    private fun asset(name: String): File {
        val f = File(dir, name)
        app.assets.open("selftest/$name").use { inp -> f.outputStream().use { inp.copyTo(it) } }
        return f
    }

    private fun openFile(f: File, mime: String?): OpenedSource =
        OpenedSource(Uri.fromFile(f), f.name, mime, ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY), null)

    /** Позиция максимума |x| и длина. */
    private class ImpulseScan { var frames = 0L; var best = -1f; var at = -1L; var ch = 1
        fun push(buf: FloatArray, n: Int) {
            for (i in 0 until n * ch) { val v = abs(buf[i]); if (v > best) { best = v; at = frames + i / ch } }
            frames += n
        }
    }

    private fun decoders() {
        val refs = manifest()
        val lossy = setOf("aac", "mp3", "opus")
        // Проход 1: «сырой» декодер (без поправки профиля) → X по каждому кодеку.
        // mime → (X_imp, X_len, заявленная задержка ЭТОГО файла): у файлов одного кодека она может быть разной (аудит T21).
        val xs = LinkedHashMap<String, MutableList<DecoderDropCalibration.Sample>>()
        for (r in refs.filter { it.codec in lossy }) {
            val f = asset(r.file)
            openFile(f, null).use { s ->
                val p = TrackProbe.probe(s)
                val t = p.tracks[p.defaultTrack]
                if (!t.decodable) { problem("${r.file}: нет декодера ${t.codecName}"); return@use }
                val g = GaplessResolver.resolve(s.source, t, p.tracks.size, profile::log)
                val sc = ImpulseScan()
                MediaDecoder(s.fileDescriptor, t.extractorIndex, t.format, g, profile, decoderDropsOverride = 0L)
                    .run(Progress.NONE, { fm -> sc.ch = fm.channels }) { b, n -> sc.push(b, n) }
                val xImp = r.impulse - sc.at
                val xLen = r.length - sc.frames
                info("${r.file}: без поправки длина ${sc.frames} (ожид. ${r.length}), импульс ${sc.at} (ожид. ${r.impulse}) → декодер сам отбрасывает ≈ $xImp")
                xs.getOrPut(t.mime.lowercase()) { ArrayList() } += DecoderDropCalibration.Sample(xImp, xLen, g.info?.delayFrames ?: 0L)
            }
        }
        for ((mime, list) in xs) {
            val name = ExtractPlanner_codec(mime)
            // Вывод по двум оценкам (импульс ± 2, длина — точно) — DecoderDropCalibration (:core, аудит T21).
            val tail: Long?
            when (val r = DecoderDropCalibration.infer(list, tol = 2, max = 8192, allowWholeDelay = mime == "audio/opus")) {
                is DecoderDropCalibration.Result.Inconsistent -> {
                    problem("$name: сдвиг на разных файлах разный (${r.imps.joinToString()}) — калибровка не сохранена"); continue
                }
                is DecoderDropCalibration.Result.Implausible -> {
                    problem("$name: неправдоподобная задержка декодера ${r.head} — калибровка не сохранена"); continue
                }
                is DecoderDropCalibration.Result.WholeDelay -> {
                    // Декодер отбрасывает весь pre-skip — это допущение по умолчанию; оставить его (pre-skip бывает разный).
                    profile.setDecoderDropsFrames(mime, null)
                    calibrated += "$name: декодер сам отбрасывает весь pre-skip (${list.joinToString { it.declaredDelay.toString() }}) — по умолчанию"
                    tail = r.tail
                }
                is DecoderDropCalibration.Result.Absolute -> {
                    profile.setDecoderDropsFrames(mime, r.head)
                    calibrated += "$name: декодер сам отбрасывает ${r.head} кадров"
                    tail = r.tail
                }
            }
            // Хвост в профиль не пишется (GaplessTrimmer его не моделирует) — только диагностика, см. timeline.md T21.
            when {
                tail == null -> info("$name: длина без поправки на разных файлах меняется по-разному — хвост декодера не определён")
                tail > 0 -> info("$name: декодер, вероятно, сам обрезает в конце $tail кадров — длина будет короче на $tail")
                tail < 0 -> info("$name: декодер, вероятно, дописывает в конце ${-tail} кадров — длина будет длиннее на ${-tail}")
            }
        }
        // Проход 2: путь редактора (DecodePipeline) с профилем.
        for (r in refs) {
            val f = File(dir, r.file).takeIf { it.exists() } ?: asset(r.file)
            openFile(f, null).use { s ->
                val p = TrackProbe.probe(s)
                val t = p.tracks[p.defaultTrack]
                if (!t.decodable) return@use
                val res = DecodePipeline(app, s, t, p, profile).run(Progress.NONE)
                try {
                    val sc = ImpulseScan().apply { ch = res.spec.channels }
                    val buf = FloatArray(8192 * res.spec.channels)
                    var pos = 0L
                    while (true) { val n = res.cache.read(pos, buf, 8192); if (n <= 0) break; sc.push(buf, n); pos += n }
                    val tolImp = if (r.codec in lossy) 2 else 0
                    val fl = if (res.decodedBits == 32) "float" else "${res.decodedBits} бит"
                    val head = "${r.file}: длина ${sc.frames}/${r.length}, импульс ${sc.at}/${r.impulse}, выход декодера $fl"
                    when {
                        res.spec.sampleRate != r.rate -> problem("$head — частота ${res.spec.sampleRate} вместо ${r.rate}")
                        sc.frames != r.length -> problem("$head — длина не совпала (FR-26: ± 0)")
                        abs(sc.at - r.impulse) > tolImp -> problem("$head — начало сдвинуто на ${sc.at - r.impulse}")
                        else -> ok(head)
                    }
                    if (r.codec == "flac" && res.decodedBits != 32) info("FLAC раскодирован в ${res.decodedBits} бит (FR-4: для 24-битных FLAC будет предупреждение)")
                } finally { res.cache.close() }
            }
        }
    }

    @Suppress("FunctionName")
    private fun ExtractPlanner_codec(mime: String) = com.rassul.audioeditor.core.pipeline.ExtractPlanner.codecName(mime)

    // ================================================================= 3. задержка энкодеров (FR-25)

    /** Тестовый сигнал как в gen_fixtures.py: тон 440·(c+1) Гц амплитудой 0,03 и импульс 0,8 в кадре [imp]. */
    private fun signal(rate: Int, ch: Int, n: Int, imp: Int): PcmSource {
        val d = FloatArray(n * ch)
        for (i in 0 until n) for (c in 0 until ch) d[i * ch + c] = if (i == imp) 0.8f else (0.03 * sin(2 * PI * 440.0 * (c + 1) * i / rate)).toFloat()
        return ArrayPcmSource(PcmSpec(rate, ch), d)
    }

    private class FileTarget(f: File) : OutputTarget, AutoCloseable {
        private val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
        private val s = FileChannelSink(pfd.fileDescriptor)
        override val sink: SeekableSink get() = s
        override val fd: FileDescriptor get() = pfd.fileDescriptor
        override fun close() { try { s.truncateToSize() } catch (_: Exception) {}; pfd.close() }
    }

    private class EncMeas(val delayUsed: Int, val frames: Long, val impulse: Long, val codec: String)

    /** Кодирование сигнала → файл (свой писатель или MediaMuxer) → декодирование путём редактора. */
    private fun encodeMeasure(format: ExportFormat, rate: Int, n: Int, imp: Int, muxer: Boolean, delayOverride: Int?, name: String): EncMeas {
        val src = signal(rate, 2, n, imp)
        val renderer = Renderer(src, EditList(), RenderSettings())
        val plan = ExportPlanner.plan(src.spec, 16, ExportRequest(format))
        val f = File(dir, name)
        var used = -1
        var codecName = ""
        FileTarget(f).use { tgt ->
            val spec = plan.outSpec
            val enc: MediaCodecEncoderSink = if (format == ExportFormat.M4A) {
                val fac: PacketSinkFactory = { fmt, asc, _ ->
                    if (muxer) MuxerPacketSink(tgt.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, fmt, spec.sampleRate, profile)
                    else Mp4PacketSink(Mp4AacWriter(tgt.sink, spec.sampleRate, spec.channels, asc, 1024))
                }
                // profile = null: задержка — из формата энкодера (или допущение), без прежней калибровки.
                AacEncoderSink(spec, plan.bitrateKbps, null, fac, delayOverride, profile.preferFloatEncoderInput, 1L)
            } else {
                val fac: PacketSinkFactory = { fmt, _, delay ->
                    if (muxer) MuxerPacketSink(tgt.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, fmt, 48000, profile)
                    else OggOpusPacketSink(OggOpusWriter(tgt.sink, spec.channels, rate, delay))
                }
                OpusEncoderSink(spec, plan.bitrateKbps, null, fac, delayOverride, profile.preferFloatEncoderInput, 1L)
            }
            codecName = enc.codecName
            try { ExportPipeline(renderer, plan, 1L).encodeTo(enc, Progress.NONE) } catch (e: Throwable) { enc.release(); throw e }
            used = enc.encoderDelay
        }
        val m = decodeFile(f)
        return EncMeas(used, m.frames, m.impulse, codecName)
    }

    /** Декодирование файла тем же путём, что вход редактора (GaplessResolver + MediaDecoder с профилем). */
    private fun decodeFile(f: File): Meas {
        val pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            val fd = pfd.fileDescriptor
            val ex = MediaExtractor()
            val (idx, fmt) = try {
                ex.setDataSource(fd)
                val i = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                i to MediaFormat(ex.getTrackFormat(i))
            } finally { ex.release() }
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, 48000)
            val g = GaplessResolver.resolve(FdSource(fd), mime, fmt, rate, 1, profile::log)
            val sc = ImpulseScan()
            var outRate = rate
            MediaDecoder(fd, idx, fmt, g, profile).run(Progress.NONE, { fm -> sc.ch = fm.channels; outRate = fm.sampleRate }) { b, n -> sc.push(b, n) }
            return Meas(sc.frames, sc.at, outRate, sc.ch)
        } finally { pfd.close() }
    }

    private fun encoder(format: ExportFormat) {
        val aac = format == ExportFormat.M4A
        val name = if (aac) "AAC" else "Opus"
        val mime = if (aac) "audio/mp4a-latm" else "audio/opus"
        if (!CapabilityProbe.hasEncoder(mime)) { problem("$name: энкодера нет — проверка пропущена"); return }
        val rate = 48000; val n = 96000; val imp = 24000
        val tolLen = 1L
        // Свой писатель, задержка из энкодера.
        val a = encodeMeasure(format, rate, n, imp, muxer = false, delayOverride = null, name = "enc_own.${format.ext}")
        val s = a.impulse - imp
        info("$name (${a.codec}): задержка из энкодера/допущение ${a.delayUsed}; после декодирования длина ${a.frames}/$n, импульс ${a.impulse}/$imp")
        var ownOk: Boolean
        var delay: Int? = null
        if (abs(s) <= 1) {
            ownOk = abs(a.frames - n) <= tolLen
            if (ownOk) ok("$name: свой писатель — длина и начало точные (задержка ${a.delayUsed})")
            else problem("$name: свой писатель — длина ${a.frames} вместо $n")
        } else {
            val d = EncoderDelay.trueDelay(a.delayUsed, a.impulse, imp.toLong()).toInt()   // D = D_исп + (p − I)
            info("$name: начало сдвинуто на $s → истинная задержка энкодера ≈ $d")
            if (d in 0..8192) {
                val b = encodeMeasure(format, rate, n, imp, muxer = false, delayOverride = d, name = "enc_cal.${format.ext}")
                ownOk = abs(b.impulse - imp) <= 1 && abs(b.frames - n) <= tolLen
                if (ownOk) { delay = d; ok("$name: с задержкой $d длина ${b.frames}/$n, импульс ${b.impulse}/$imp") }
                else problem("$name: даже с задержкой $d длина ${b.frames}/$n, импульс ${b.impulse}/$imp")
            } else { ownOk = false; problem("$name: неправдоподобная задержка $d") }
        }
        // Проверка частоты 44,1 кГц (AAC): задержка может зависеть от частоты (plan.md R2).
        if (aac && ownOk) {
            val c = encodeMeasure(format, 44100, 88200, 22050, muxer = false, delayOverride = delay, name = "enc_441.m4a")
            if (abs(c.impulse - 22050) <= 1 && abs(c.frames - 88200) <= tolLen) ok("AAC 44,1 кГц: длина ${c.frames}/88200, импульс ${c.impulse}/22050")
            else problem("AAC 44,1 кГц: длина ${c.frames}/88200, импульс ${c.impulse}/22050 — задержка энкодера зависит от частоты")
        }
        // Запасной путь: MediaMuxer.
        val mux = try { encodeMeasure(format, rate, n, imp, muxer = true, delayOverride = delay, name = "enc_mux.${format.ext}") } catch (e: Throwable) {
            info("$name через MediaMuxer: ошибка ${e.javaClass.simpleName}: ${e.message}"); null
        }
        val muxOk = mux != null && abs(mux.impulse - imp) <= 1 && abs(mux.frames - n) <= tolLen
        if (mux != null) info("$name через MediaMuxer: длина ${mux.frames}/$n, импульс ${mux.impulse}/$imp — ${if (muxOk) "точно" else "неточно (ожидаемо: MediaMuxer не пишет elst/гранулу конца)"}")
        // Решение (T19): свой писатель основной; MediaMuxer — только если свой неточен, а MediaMuxer точен.
        val useMux = !ownOk && muxOk
        if (aac) {
            if (profile.useMediaMuxerForAac != useMux) calibrated += "AAC: запись через ${if (useMux) "MediaMuxer" else "свой писатель"}"
            profile.useMediaMuxerForAac = useMux
            if (profile.encoderDelayAac != delay) calibrated += "AAC: задержка энкодера ${delay ?: "из формата энкодера"}"
            profile.encoderDelayAac = delay
        } else {
            if (profile.useMediaMuxerForOpus != useMux) calibrated += "Opus: запись через ${if (useMux) "MediaMuxer" else "свой писатель"}"
            profile.useMediaMuxerForOpus = useMux
            if (profile.encoderDelayOpus != delay) calibrated += "Opus: задержка энкодера ${delay ?: "из OpusHead"}"
            profile.encoderDelayOpus = delay
        }
        if (!ownOk && !muxOk) problem("$name: ни один путь записи не дал точную длину — сохраняйте в WAV/FLAC, если важна точность")
    }

    // ================================================================= 4. FLAC 24 бит

    private fun flac24() {
        val rate = 48000
        val d = FloatArray(rate * 2)
        for (i in 0 until rate) { val v = (0.5 * sin(2 * PI * 997.0 * i / rate)).toFloat(); d[2 * i] = v; d[2 * i + 1] = -v }
        val src = ArrayPcmSource(PcmSpec(rate, 2), d)
        val plan = ExportPlanner.plan(src.spec, 24, ExportRequest(ExportFormat.FLAC, bits = 24))
        val f = File(dir, "flac24.flac")
        val res = FileTarget(f).use { ExportPipeline(Renderer(src, EditList(), RenderSettings()), plan, 1L).writeTo(it.sink, Progress.NONE) }
        val rep = DeviceVerifier(app, profile).verifyExport(Uri.fromFile(f), plan, res, null, Progress.NONE)
        if (rep.ok) ok("свой FLAC 24 бит: ${rep.message}") else problem("свой FLAC 24 бит: ${rep.message}")
        rep.warnings.forEach { info(it) }
    }

    // ================================================================= 5. NFR-7

    private fun benchmarks() {
        val rate = 48000; val ch = 2; val sec = 30
        val n = rate * sec
        val d = FloatArray(n * ch)
        var x = 0x2545F4914F6CDD1DL
        for (i in 0 until n) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            val noise = ((x ushr 40).toInt() / 16777216.0 - 0.5) * 0.05
            val v = 0.3 * sin(2 * PI * 220.0 * i / rate) * (0.6 + 0.4 * sin(2 * PI * 0.3 * i / rate)) + noise
            d[i * ch] = v.toFloat(); d[i * ch + 1] = (v * 0.9).toFloat()
        }
        val src = ArrayPcmSource(PcmSpec(rate, ch), d)
        val renderer = Renderer(src, EditList(), RenderSettings())
        fun report(what: String, seconds: Double, targetSec: Int?) {
            val est = com.rassul.audioeditor.core.pipeline.Nfr7.extrapolate(seconds, sec)   // ×60 для 30 с → 30 мин
            val txt = "$what: 30 с за ${fmt(seconds)} с → 30 мин ≈ ${fmt(est)} с" + (targetSec?.let { " (цель ≤ $it с)" } ?: "")
            if (targetSec == null || est <= targetSec) ok(txt) else problem(txt)
        }
        fun <T> timed(block: () -> T): Pair<T, Double> { val t0 = System.nanoTime(); val r = block(); return r to (System.nanoTime() - t0) / 1e9 }

        // Анализ громкости (FR-22): LUFS + true peak.
        val (_, tL) = timed { LoudnessMeter.measure(renderer.asSource(), Progress.NONE) }
        report("Анализ громкости LUFS", tL, 30)

        // Экспорт WAV.
        val wavPlan = ExportPlanner.plan(src.spec, 16, ExportRequest(ExportFormat.WAV))
        val wav = File(dir, "bench.wav")
        val (wavRes, tW) = timed { FileTarget(wav).use { ExportPipeline(renderer, wavPlan).writeTo(it.sink, Progress.NONE) } }
        report("Экспорт WAV 16 бит", tW, 30)

        // Экспорт M4A (рендер + AAC + свой писатель / профиль).
        val m4aPlan = ExportPlanner.plan(src.spec, 16, ExportRequest(ExportFormat.M4A))
        val m4a = File(dir, "bench.m4a")
        var delay: Int? = null
        val (m4aRes, tA) = timed {
            FileTarget(m4a).use { t ->
                val enc = com.rassul.audioeditor.media.Encoders.aacToM4a(t, m4aPlan.outSpec, m4aPlan.bitrateKbps, profile)
                try { ExportPipeline(renderer, m4aPlan).encodeTo(enc, Progress.NONE).also { delay = enc.encoderDelay } } catch (e: Throwable) { enc.release(); throw e }
            }
        }
        report("Экспорт / перекодирование в M4A (AAC 192)", tA, 90)

        // Открытие в редактор: декодирование + пирамида (цель NFR-7 — для MP3; здесь AAC, энкодера MP3 в телефоне нет).
        openFile(m4a, "audio/mp4").use { s ->
            val p = TrackProbe.probe(s)
            val t = p.tracks[0]
            val (res, tD) = timed { DecodePipeline(app, s, t, p, profile).run(Progress.NONE) }
            res.cache.close()
            report("Открытие в редакторе (декодирование AAC + волна)", tD, 60)
            // «Как есть» (FR-6).
            val copy = File(dir, "bench_copy.m4a")
            val (_, tC) = timed { FileTarget(copy).use { RemuxCopier.copy(s, p, t, it, profile, Progress.NONE) } }
            report("Извлечение «как есть»", tC, 10)
        }

        // Автопроверка (FR-33).
        val v = DeviceVerifier(app, profile)
        val useDelay = delay?.takeIf { !profile.useMediaMuxerForAac }
        val (rA, tVA) = timed { v.verifyExport(Uri.fromFile(m4a), m4aPlan, m4aRes, useDelay, Progress.NONE) }
        report("Автопроверка M4A", tVA, 40)
        if (rA.ok) ok("автопроверка M4A: ${rA.message}") else problem("автопроверка M4A: ${rA.message}")
        val (rW, tVW) = timed { v.verifyExport(Uri.fromFile(wav), wavPlan, wavRes, null, Progress.NONE) }
        report("Автопроверка WAV", tVW, 15)
        if (rW.ok) ok("автопроверка WAV: ${rW.message}") else problem("автопроверка WAV: ${rW.message}")
        info("Оценка линейная (×60); прокрутку волны (≥ 50 кадров/с) проверяйте на файле 30 мин вручную.")
    }

    private fun fmt(v: Double): String {
        val t = Math.round(v * 10)
        return "${t / 10},${t % 10}"
    }
}
