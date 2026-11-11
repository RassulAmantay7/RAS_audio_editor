package com.rassul.audioeditor.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.format.WavSampleFormat
import com.rassul.audioeditor.core.format.WavWriter
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.u16be
import com.rassul.audioeditor.core.format.bits.u32be
import com.rassul.audioeditor.core.format.bits.u32le
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.format.flac.FlacStreamInfo
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.format.raw.Ac3Scanner
import com.rassul.audioeditor.core.format.raw.DtsScanner
import com.rassul.audioeditor.core.format.raw.MP3_DECODER_DELAY
import com.rassul.audioeditor.core.format.raw.Mp3Header
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.ExtractPlanner
import com.rassul.audioeditor.core.pipeline.FastTrimPlan
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import com.rassul.audioeditor.core.pipeline.TimeText
import com.rassul.audioeditor.diag.DeviceProfile
import java.nio.ByteBuffer
import java.util.zip.CRC32

/*
 * Зона MEDIA, T18. FR-6 «как есть» (пакеты бит в бит, контейнер по кодеку) и FR-23 «Быстро, без потерь»
 * (FastTrimPlanner, timeline.md «FR-23: план быстрой обрезки»). Контейнеры — свои писатели :core:
 *   AAC → Mp4AacWriter (elst = задержка/длина), Opus → OggOpusWriter (pre-skip, гранула конца),
 *   MP3 → сырой поток (+ кадр Info/LAME, если известна задержка), AC-3/E-AC-3/DTS → сырой поток,
 *   FLAC → «fLaC» + STREAMINFO + перенумерованные кадры (свой потоковый писатель), PCM → WAV,
 *   Vorbis → MediaMuxer WebM, AMR → MediaMuxer 3GP (своих писателей нет; таблица FR-6).
 * Отклонение от таблицы FR-6: AAC/Opus пишутся своими писателями, а не MediaMuxer — точные elst/гранула (FR-25).
 */

/** Итог копирования — эталон для автопроверки FR-33 (сравнение пакетов). */
class CopyResult(
    val ext: String,
    val mime: String,
    /** CRC-32 полезных данных каждого пакета (FLAC — без заголовка кадра и CRC-16; MP3 — без кадра Info). */
    val packetCrcs: IntArray,
    val payloadBytes: Long,
    /** CRC-32 всех полезных данных подряд. */
    val totalCrc: Long,
    /** Длительность результата в кадрах [sampleRate] (− скрытое); 0 — неизвестна. */
    val durationFrames: Long,
    val sampleRate: Int,
    /** Кадров на пакет (для допуска ±1 кадр); 0 — переменная. */
    val packetFrames: Int,
    /** Для WAV — что ожидать от файла (сверка ядром). */
    val wavSpec: PcmSpec? = null,
    val wavBits: Int = 0,
    /** Текст о фактических границах (FR-23) или null. */
    val message: String? = null,
)

/** Предпросмотр быстрой обрезки (FR-23): фактические границы и текст для показа до сохранения. */
class FastTrimPreview internal constructor(
    val plan: FastTrimPlan,
    /** Текст «Фактическое начало … конец …» + подсказка. */
    val message: String,
    internal val kind: RemuxCopier.Kind,
    internal val sampleRate: Int,
)

object RemuxCopier {
    const val HINT_FAST_TRIM = "Нужна точность до сэмпла? Используйте обычный экспорт; для архива выберите FLAC/WAV."

    enum class Kind { AAC, OPUS, MP3, AC3, EAC3, DTS, FLAC, PCM, VORBIS, AMR, WAV_FILE }

    /** Расширение «как есть» по таблице FR-6 (с учётом WAV-файла). */
    fun asIsExt(track: AudioTrackInfo, probe: ProbeResult): String? = if (probe.isWav) "wav" else ExtractPlanner.asIsExt(track.mime)

    fun mimeForExt(ext: String): String = when (ext) {
        "m4a" -> "audio/mp4"; "ogg" -> "audio/ogg"; "webm" -> "audio/webm"; "mp3" -> "audio/mpeg"
        "ac3" -> "audio/ac3"; "eac3" -> "audio/eac3"; "dts" -> "audio/vnd.dts"; "flac" -> "audio/flac"
        "wav" -> "audio/x-wav"; "3gp" -> "audio/3gpp"; else -> "application/octet-stream"
    }

    fun kindOf(track: AudioTrackInfo, probe: ProbeResult): Kind? = if (probe.isWav) Kind.WAV_FILE else when (track.mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> Kind.AAC
        "audio/opus" -> Kind.OPUS; "audio/mpeg" -> Kind.MP3; "audio/ac3" -> Kind.AC3; "audio/eac3" -> Kind.EAC3
        "audio/vnd.dts", "audio/vnd.dts.hd" -> Kind.DTS; "audio/flac" -> Kind.FLAC; "audio/raw" -> Kind.PCM
        "audio/vorbis" -> Kind.VORBIS; "audio/3gpp", "audio/amr-wb" -> Kind.AMR
        else -> null
    }

    /** FR-23 доступна (timeline.md: xHE-AAC и Vorbis — нет; AMR и PCM в контейнере — не реализовано в v1). */
    fun fastTrimSupported(track: AudioTrackInfo, probe: ProbeResult): Boolean {
        val k = kindOf(track, probe) ?: return false
        if (k == Kind.AAC && aot(track) == 42) return false
        return k != Kind.VORBIS && k != Kind.AMR && k != Kind.PCM
    }

    private fun aot(track: AudioTrackInfo): Int {
        val asc = GaplessResolver.csd(track.format, "csd-0") ?: return 2
        if (asc.isEmpty()) return 2
        val a = asc.u8(0) ushr 3
        return if (a == 31 && asc.size >= 2) 32 + (((asc.u8(0) and 7) shl 3) or (asc.u8(1) ushr 5)) else a
    }

    // ------------------------------------------------------------------ пакеты

    internal class Pkt(var data: ByteArray = ByteArray(1 shl 16), var len: Int = 0, var ptsUs: Long = 0, var flags: Int = 0, var frames: Int = 0)

    /** Сырые файлы (.mp3, .flac) читаются своими сканерами :core — точнее MediaExtractor (кадр Info, PCM-выход FLACExtractor). */
    private fun rawFileKind(src: OpenedSource, kind: Kind): Kind? {
        val h = Src.readUpTo(src.source, 0, 4)
        if (h.size < 4) return null
        val s = String(h, Charsets.ISO_8859_1)
        if (kind == Kind.FLAC && s == "fLaC") return Kind.FLAC
        if (kind == Kind.MP3 && (s.startsWith("ID3") || (h.u8(0) == 0xFF && h.u8(1) and 0xE0 == 0xE0))) return Kind.MP3
        return null
    }

    /** Обход пакетов дорожки по порядку. */
    private fun forEachPacket(src: OpenedSource, track: AudioTrackInfo, kind: Kind, progress: Progress, block: (Pkt) -> Unit) {
        val pkt = Pkt()
        when (rawFileKind(src, kind)) {
            Kind.MP3 -> {
                val s = Mp3Scanner.scan(src.source)
                var t = 0L
                for ((i, f) in s.frames.withIndex()) {
                    if (progress.isCancelled) throw CancelledException()
                    readAt(src, f.offset, f.size, pkt)
                    pkt.ptsUs = t * 1_000_000L / maxOf(1, f.sampleRate); pkt.flags = 0; pkt.frames = f.samples
                    t += f.samples
                    block(pkt)
                    if (i and 255 == 0) progress.report(i.toDouble() / s.frames.size)
                }
            }
            Kind.FLAC -> {
                val ff = FlacFrames.scan(src.source)
                var t = 0L
                for ((i, f) in ff.frames.withIndex()) {
                    if (progress.isCancelled) throw CancelledException()
                    readAt(src, f.offset, f.size, pkt)
                    pkt.ptsUs = t * 1_000_000L / maxOf(1, f.sampleRate); pkt.flags = 0; pkt.frames = f.samples
                    t += f.samples
                    block(pkt)
                    if (i and 255 == 0) progress.report(i.toDouble() / ff.frames.size)
                }
            }
            else -> {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(src.fileDescriptor)
                    ex.selectTrack(track.extractorIndex)
                    var bb = ByteBuffer.allocate(1 shl 16)
                    while (true) {
                        if (progress.isCancelled) throw CancelledException()
                        val sz = ex.sampleSize
                        if (sz < 0) break
                        if (bb.capacity() < sz) bb = ByteBuffer.allocate(sz.toInt())
                        bb.clear()
                        val n = ex.readSampleData(bb, 0)
                        if (n < 0) break
                        if (pkt.data.size < n) pkt.data = ByteArray(n)
                        bb.position(0); bb.get(pkt.data, 0, n)
                        pkt.len = n; pkt.ptsUs = ex.sampleTime; pkt.flags = ex.sampleFlags
                        pkt.frames = codecFrames(kind, pkt.data, n, track)
                        block(pkt)
                        if (track.durationUs > 0) progress.report((pkt.ptsUs.toDouble() / track.durationUs).coerceIn(0.0, 1.0))
                        ex.advance()
                    }
                } finally { ex.release() }
            }
        }
    }

    private fun readAt(src: OpenedSource, off: Long, size: Int, pkt: Pkt) {
        if (pkt.data.size < size) pkt.data = ByteArray(size)
        var got = 0
        while (got < size) { val r = src.source.read(off + got, pkt.data, got, size - got); if (r <= 0) break; got += r }
        if (got < size) throw CoreException("Файл обрезан: не удалось прочитать кадр.")
        pkt.len = size
    }

    /** Длительность пакета в кадрах по его заголовку; 0 — неизвестна (тогда по PTS). */
    private fun codecFrames(kind: Kind, b: ByteArray, len: Int, track: AudioTrackInfo): Int = try {
        when (kind) {
            Kind.OPUS -> OpusToc.samples48(b, 0, len)                                     // RFC 6716 §3.1
            Kind.MP3 -> Mp3Header(b.u32be(0).toInt()).let { if (it.valid) it.samples else 0 }
            Kind.AC3 -> 1536                                                               // A/52: 6 блоков × 256
            Kind.EAC3 -> Ac3Scanner.eac3Samples((b.u8(4) ushr 4) and 3, b.u8(4) ushr 6)    // A/52 Annex E
            Kind.DTS -> if (b.u32be(0) == 0x7FFE8001L) DtsScanner.samples(((b.u32be(4) ushr 18) and 0x7F).toInt()) else 0
            Kind.FLAC -> flacBlockSize(b)
            else -> 0
        }
    } catch (_: Exception) { 0 }

    /** RFC 9639 §9.1.1: размер блока по коду; длина заголовка — см. [flacHeaderLength]. */
    fun flacBlockSize(f: ByteArray): Int {
        val code = f.u8(2) ushr 4
        val numEnd = 5 + utf8Extra(f.u8(4))
        return when (code) {
            1 -> 192
            in 2..5 -> 576 shl (code - 2)
            6 -> f.u8(numEnd) + 1
            7 -> f.u16be(numEnd) + 1
            in 8..15 -> 256 shl (code - 8)
            else -> 0
        }
    }

    private fun utf8Extra(first: Int) = when { first < 0x80 -> 0; first and 0xE0 == 0xC0 -> 1; first and 0xF0 == 0xE0 -> 2
        first and 0xF8 == 0xF0 -> 3; first and 0xFC == 0xF8 -> 4; first and 0xFE == 0xFC -> 5; else -> 6 }

    /** Длина заголовка кадра FLAC с CRC-8 (RFC 9639 §9.1). */
    fun flacHeaderLength(f: ByteArray): Int {
        val bs = f.u8(2) ushr 4; val sr = f.u8(2) and 0xF
        val tail = (if (bs == 6) 1 else if (bs == 7) 2 else 0) + (if (sr == 12) 1 else if (sr == 13 || sr == 14) 2 else 0)
        return 5 + utf8Extra(f.u8(4)) + tail + 1
    }

    /** Полезные данные пакета для сравнения (FR-33): FLAC — подкадры без заголовка и CRC-16. */
    fun payloadRange(kind: Kind, b: ByteArray, len: Int): IntRange =
        if (kind == Kind.FLAC && len > 8) flacHeaderLength(b) until len - 2 else 0 until len

    // ------------------------------------------------------------------ копирование

    private class Collector(val kind: Kind) {
        var crcs = IntArray(1024); var n = 0; var bytes = 0L
        val total = CRC32(); private val one = CRC32()
        fun add(b: ByteArray, len: Int) {
            val r = payloadRange(kind, b, len)
            val l = r.last - r.first + 1
            one.reset(); one.update(b, r.first, l); total.update(b, r.first, l)
            if (n == crcs.size) crcs = crcs.copyOf(n * 2)
            crcs[n++] = one.value.toInt(); bytes += l
        }
        fun result(ext: String, dur: Long, rate: Int, pf: Int, msg: String? = null, wavSpec: PcmSpec? = null, wavBits: Int = 0) =
            CopyResult(ext, mimeForExt(ext), crcs.copyOf(n), bytes, total.value, dur, rate, pf, wavSpec, wavBits, msg)
    }

    /** FR-6: скопировать всю дорожку «как есть» в [target]. Долго — не из UI-потока. */
    fun copy(src: OpenedSource, probe: ProbeResult, track: AudioTrackInfo, target: OutputTarget, profile: DeviceProfile, progress: Progress): CopyResult {
        val kind = kindOf(track, probe) ?: throw CoreException("Кодек ${track.codecName} нельзя сохранить без перекодирования.")
        val ext = asIsExt(track, probe)!!
        val sink = target.sink
        val c = Collector(kind)
        val g = GaplessResolver.resolve(src.source, track, probe.tracks.size, profile::log)
        when (kind) {
            Kind.WAV_FILE -> return copyWavRange(src, target, 0, null, progress, null)
            Kind.AAC -> {
                val asc = GaplessResolver.csd(track.format, "csd-0") ?: throw CoreException("В дорожке AAC нет описания кодека (AudioSpecificConfig).")
                val f = aacFramesPerPacket(src, track)
                val w = Mp4AacWriter(sink, track.sampleRate, track.channels, asc, f)
                var k = 0L
                forEachPacket(src, track, kind, progress) { p -> w.writePacket(p.data, 0, p.len); c.add(p.data, p.len); k++ }
                val delay = (g.info?.delayFrames ?: 0L).toInt()
                val valid = maxOf(0L, k * f - delay - (g.info?.paddingFrames ?: 0L))
                w.finish(delay, valid)
                return c.result(ext, valid, track.sampleRate, f)
            }
            Kind.OPUS -> {
                val head = GaplessResolver.csd(track.format, "csd-0")?.let { b -> GaplessResolver.indexOf(b, "OpusHead").takeIf { it >= 0 }?.let { b.copyOfRange(it, b.size) } }
                val pre = g.info?.delayFrames?.toInt() ?: GaplessResolver.opusPreSkip(track.format) ?: 0
                val inRate = head?.takeIf { it.size >= 16 }?.u32le(12)?.toInt()?.takeIf { it > 0 } ?: track.sampleRate
                val w = OggOpusWriter(sink, track.channels, inRate, pre)
                if (head != null && head.size >= 21 && head.u8(18) != 0) w.channelMapping = head.copyOfRange(19, minOf(head.size, 21 + track.channels))
                var total = 0L; var lastF = 0
                forEachPacket(src, track, kind, progress) { p -> w.writePacket(p.data, 0, p.len, p.frames); total += p.frames; lastF = p.frames; c.add(p.data, p.len) }
                val valid = maxOf(0L, total - pre - (g.info?.paddingFrames ?: 0L))
                w.finish(valid)
                // Допуск длины — по последнему пакету (аудит T21 п. 9).
                return c.result(ext, valid, 48000, com.rassul.audioeditor.core.pipeline.LengthTolerance.opusFrame(lastF))
            }
            Kind.MP3 -> {
                // Сырой .mp3: кадр Info/LAME источника (если был) копируется как есть, затем аудиокадры.
                if (rawFileKind(src, kind) == Kind.MP3) {
                    Mp3Scanner.scan(src.source).info?.let { i -> val p = Pkt(); readAt(src, i.frameOffset, i.frameSize, p); sink.write(p.data, 0, p.len) }
                }
                var total = 0L
                forEachPacket(src, track, kind, progress) { p -> sink.write(p.data, 0, p.len); c.add(p.data, p.len); total += p.frames }
                val dur = g.info?.let { total - it.delayFrames - it.paddingFrames } ?: total
                return c.result(ext, maxOf(0L, dur), track.sampleRate, 1152)
            }
            Kind.AC3, Kind.EAC3, Kind.DTS -> {
                var total = 0L
                forEachPacket(src, track, kind, progress) { p -> sink.write(p.data, 0, p.len); c.add(p.data, p.len); total += p.frames }
                return c.result(ext, total, track.sampleRate, 0)
            }
            Kind.FLAC -> {
                val si = flacStreamInfo(src, track)
                val fw = FlacStreamOut(sink, si)
                forEachPacket(src, track, kind, progress) { p -> fw.frame(p.data, p.len, p.frames); c.add(p.data, p.len) }
                fw.finish(keepMd5 = true)
                return c.result(ext, fw.total, si.sampleRate, 0)
            }
            Kind.PCM -> {
                val enc = track.format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                val fmt = when (enc) {
                    AudioFormat.ENCODING_PCM_16BIT -> WavSampleFormat.PCM16; AudioFormat.ENCODING_PCM_24BIT_PACKED -> WavSampleFormat.PCM24
                    AudioFormat.ENCODING_PCM_FLOAT -> WavSampleFormat.FLOAT32
                    else -> throw CoreException("Эту разрядность PCM нельзя сохранить в WAV без преобразования. Выберите FLAC или WAV в «Другой формат…».")
                }
                val spec = PcmSpec(track.sampleRate, track.channels)
                val w = WavWriter(sink, spec, fmt)
                val bps = fmt.bits / 8
                var ints = IntArray(0); var fl = FloatArray(0)
                forEachPacket(src, track, kind, progress) { p ->
                    val n = p.len / bps; val frames = n / spec.channels
                    c.add(p.data, frames * spec.channels * bps)
                    if (fmt.isFloat) {
                        if (fl.size < n) fl = FloatArray(n)
                        ByteBuffer.wrap(p.data, 0, n * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(fl, 0, n)
                        w.writeFloat(fl, frames)
                    } else {
                        if (ints.size < n) ints = IntArray(n)
                        for (i in 0 until n) { val o = i * bps
                            ints[i] = if (bps == 2) ((p.data[o].toInt() and 0xFF) or (p.data[o + 1].toInt() shl 8))
                                      else ((p.data[o].toInt() and 0xFF) or ((p.data[o + 1].toInt() and 0xFF) shl 8) or (p.data[o + 2].toInt() shl 16)) }
                        w.writeInt(ints, frames)
                    }
                }
                w.finish()
                return c.result(ext, w.framesWritten, spec.sampleRate, 0, wavSpec = spec, wavBits = fmt.bits)
            }
            Kind.VORBIS, Kind.AMR -> {
                // FR-6: Vorbis → WebM, AMR → 3GP через MediaMuxer (своих писателей нет).
                val muxer = MediaMuxer(target.fd, if (kind == Kind.VORBIS) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
                var ok = false
                try {
                    val ti = muxer.addTrack(track.format)
                    muxer.start()
                    val bi = MediaCodec.BufferInfo()
                    var bb = ByteBuffer.allocate(1 shl 16)
                    var first = Long.MIN_VALUE
                    forEachPacket(src, track, kind, progress) { p ->
                        if (first == Long.MIN_VALUE) first = p.ptsUs
                        if (bb.capacity() < p.len) bb = ByteBuffer.allocate(p.len)
                        bb.clear(); bb.put(p.data, 0, p.len); bb.flip()
                        bi.set(0, p.len, maxOf(0L, p.ptsUs - first), if (p.flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(ti, bb, bi)
                        c.add(p.data, p.len)
                    }
                    muxer.stop(); ok = true
                } finally { muxer.release(); if (!ok) profile.log("MediaMuxer: копирование прервано") }
                return c.result(ext, com.rassul.audioeditor.core.pipeline.MediaMath.usToFrames(track.durationUs, track.sampleRate), track.sampleRate, 0)
            }
        }
    }

    /** Кадров на пакет AAC по шагу PTS первых пакетов (1024 LC, 2048 HE при шкале выхода, 960). */
    private fun aacFramesPerPacket(src: OpenedSource, track: AudioTrackInfo): Int {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(src.fileDescriptor); ex.selectTrack(track.extractorIndex)
            val t0 = ex.sampleTime
            if (!ex.advance()) return 1024
            val d = ex.sampleTime - t0
            if (d <= 0) return 1024
            val f = com.rassul.audioeditor.core.pipeline.MediaMath.usToFrames(d, track.sampleRate).toInt()
            return listOf(960, 1024, 2048).minByOrNull { Math.abs(it - f) }!!
        } catch (_: Exception) { return 1024 } finally { ex.release() }
    }

    private fun flacStreamInfo(src: OpenedSource, track: AudioTrackInfo): FlacStreamInfo {
        if (rawFileKind(src, Kind.FLAC) == Kind.FLAC) return FlacFrames.readHeader(src.source).first
        val csd = GaplessResolver.csd(track.format, "csd-0") ?: throw CoreException("В дорожке FLAC нет заголовка STREAMINFO.")
        val i = GaplessResolver.indexOf(csd, "fLaC")
        val off = if (i >= 0) i + 8 else if (csd.size >= 38) 4 else 0     // «fLaC» + заголовок блока (4) → 34 байта STREAMINFO
        if (csd.size < off + 34) throw CoreException("Заголовок FLAC повреждён.")
        return FlacStreamInfo.parse(csd, off)
    }

    /** Потоковая запись .flac (RFC 9639 §8): STREAMINFO дописывается в конце (total_samples, размеры кадров). */
    private class FlacStreamOut(private val sink: SeekableSink, private val si: FlacStreamInfo) {
        private val start = sink.position
        var total = 0L; private set
        private var count = 0L
        private var minF = Int.MAX_VALUE; private var maxF = 0
        init {
            val h = byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(), 0x80.toByte(), 0, 0, 34)
            sink.write(h, 0, 8); sink.write(si.toBytes(), 0, 34)
        }
        fun frame(b: ByteArray, len: Int, samples: Int) {
            val f = if (len == b.size) b else b.copyOf(len)
            val variable = f.u8(1) and 1 == 1
            val r = FlacFrames.renumber(f, if (variable) total else count)   // §9.1.5: номер кадра или первого сэмпла
            sink.write(r, 0, r.size)
            minF = minOf(minF, r.size); maxF = maxOf(maxF, r.size)
            total += samples; count++
        }
        fun finish(keepMd5: Boolean) {
            val end = sink.position
            val nsi = si.copy(minFrame = if (count == 0L) 0 else minF, maxFrame = maxF, totalSamples = total, md5 = if (keepMd5) si.md5 else ByteArray(16))
            sink.seek(start + 8); sink.write(nsi.toBytes(), 0, 34); sink.seek(end); sink.flush()
        }
    }

    /** WAV: копия диапазона кадров [from, to) (FR-23 для WAV — точно до сэмпла; to = null — до конца). */
    private fun copyWavRange(src: OpenedSource, target: OutputTarget, from: Long, to: Long?, progress: Progress, msg: String?): CopyResult {
        val r = WavReader(src.source)
        val fmt = when { r.isFloat && r.bitsPerSample == 32 -> WavSampleFormat.FLOAT32; !r.isFloat && r.bitsPerSample == 16 -> WavSampleFormat.PCM16
            !r.isFloat && r.bitsPerSample == 24 -> WavSampleFormat.PCM24
            else -> throw CoreException("Эту разновидность WAV (${r.bitsPerSample} бит) нельзя скопировать без преобразования. Используйте обычный экспорт.") }
        val w = WavWriter(target.sink, r.spec, fmt)
        val ch = r.spec.channels
        val end = minOf(to ?: r.lengthFrames, r.lengthFrames)
        val block = 8192
        val fl = FloatArray(block * ch); val ints = IntArray(block * ch)
        var pos = from
        while (pos < end) {
            if (progress.isCancelled) throw CancelledException()
            val n = minOf(block.toLong(), end - pos).toInt()
            val got = if (fmt.isFloat) r.read(pos, fl, n).also { w.writeFloat(fl, it) } else r.readInt(pos, ints, n).also { w.writeInt(ints, it) }
            if (got <= 0) break
            pos += got
            progress.report((pos - from).toDouble() / maxOf(1L, end - from))
        }
        w.finish()
        return CopyResult("wav", "audio/x-wav", IntArray(0), 0, 0, w.framesWritten, r.spec.sampleRate, 0, r.spec, fmt.bits, msg)
    }

    // ------------------------------------------------------------------ FR-23

    private class Index(val pts: LongArray, val frames: IntArray, val count: Int, val mp3: com.rassul.audioeditor.core.format.raw.Mp3Stream?)

    private fun index(src: OpenedSource, track: AudioTrackInfo, kind: Kind, rate: Int, progress: Progress): Index {
        var pts = LongArray(4096); var fr = IntArray(4096); var n = 0
        forEachPacket(src, track, kind, progress) { p ->
            if (n == pts.size) { pts = pts.copyOf(n * 2); fr = fr.copyOf(n * 2) }
            pts[n] = p.ptsUs; fr[n] = p.frames; n++
        }
        // Пакеты без длительности в заголовке — по шагу PTS (последний — как предыдущий).
        for (i in 0 until n) if (fr[i] <= 0) fr[i] = if (i + 1 < n) com.rassul.audioeditor.core.pipeline.MediaMath.usToFrames(pts[i + 1] - pts[i], rate).toInt() else if (i > 0) fr[i - 1] else 1024
        val mp3 = if (kind == Kind.MP3 && rawFileKind(src, kind) == Kind.MP3) Mp3Scanner.scan(src.source) else null
        return Index(pts, fr, n, mp3)
    }

    /**
     * Предпросмотр FR-23: выделение [selStart, selEnd) в кадрах шкалы редактора (после FR-26), lengthFrames — длина
     * исходника в редакторе. null — недоступно для этого кодека. Долго (обход пакетов) — не из UI-потока.
     */
    fun previewFastTrim(src: OpenedSource, probe: ProbeResult, track: AudioTrackInfo, selStart: Long, selEnd: Long,
                        lengthFrames: Long, profile: DeviceProfile, progress: Progress = Progress.NONE): FastTrimPreview? {
        if (!fastTrimSupported(track, probe)) return null
        val kind = kindOf(track, probe)!!
        val a = selStart.coerceIn(0, lengthFrames); val b = selEnd.coerceIn(0, lengthFrames)
        if (a >= b) throw CoreException("Выделите фрагмент")
        if (kind == Kind.WAV_FILE) {
            val fs = track.sampleRate
            val plan = FastTrimPlan(0, 0, 0, a, b, 0, FastTrimPlanner.message(a, b, a, b, fs))
            return FastTrimPreview(plan, plan.message + "\n" + HINT_FAST_TRIM, kind, fs)
        }
        val rate = if (kind == Kind.OPUS) 48000 else track.sampleRate
        val idx = index(src, track, kind, rate, progress)
        if (idx.count == 0) throw CoreException("В дорожке нет пакетов.")
        val g = GaplessResolver.resolve(src.source, track, probe.tracks.size, profile::log)
        val d = g.info?.delayFrames ?: 0L
        // Шкала пользователя u = s − D (timeline.md ф. 13); s — накопленные длительности пакетов.
        val starts = LongArray(idx.count); var s = 0L
        for (i in 0 until idx.count) { starts[i] = s - d; s += idx.frames[i] }
        val canHide = kind != Kind.AC3 && kind != Kind.EAC3 && kind != Kind.DTS
        val warmSamples = if (kind == Kind.OPUS) FastTrimPlanner.OPUS_PREROLL_48K else 0
        var w = when (kind) { Kind.OPUS, Kind.FLAC -> 0; Kind.MP3 -> 2; else -> 1 }
        var plan = FastTrimPlanner.planFull(starts, idx.frames.copyOf(idx.count), a, b, rate, w, warmSamples, lengthFrames, canHide)
        if (kind == Kind.MP3 && idx.mp3 != null && plan.firstAudiblePacket > 0) {
            // timeline.md «FR-23, MP3»: W = 1 + r по main_data_begin (bit reservoir), минимум 2.
            w = maxOf(2, Mp3Scanner.warmupFrames(idx.mp3.frames, plan.firstAudiblePacket))
            plan = FastTrimPlanner.planFull(starts, idx.frames.copyOf(idx.count), a, b, rate, w, warmSamples, lengthFrames, canHide)
        }
        var msg = plan.message
        if (!canHide) msg += " Начало сдвинуто на кадр разгона: в этом формате его нельзя скрыть."
        return FastTrimPreview(plan, msg + "\n" + HINT_FAST_TRIM, kind, rate)
    }

    /** FR-23: записать фрагмент по плану. */
    fun fastTrim(src: OpenedSource, probe: ProbeResult, track: AudioTrackInfo, preview: FastTrimPreview, target: OutputTarget,
                 profile: DeviceProfile, progress: Progress): CopyResult {
        val plan = preview.plan
        val kind = preview.kind
        val ext = asIsExt(track, probe)!!
        if (kind == Kind.WAV_FILE) return copyWavRange(src, target, plan.start, plan.end, progress, preview.message)
        val sink = target.sink
        val c = Collector(kind)
        val n = plan.length
        var i = 0
        val first = plan.firstPacket; val last = plan.lastPacket
        val inRange: (Pkt, (Pkt) -> Unit) -> Unit = { p, f -> if (i in first..last) f(p); i++ }
        when (kind) {
            Kind.AAC -> {
                val asc = GaplessResolver.csd(track.format, "csd-0") ?: throw CoreException("В дорожке AAC нет описания кодека.")
                val fpp = aacFramesPerPacket(src, track)
                val w = Mp4AacWriter(sink, track.sampleRate, track.channels, asc, fpp)
                forEachPacket(src, track, kind, progress) { pk -> inRange(pk) { p -> w.writePacket(p.data, 0, p.len); c.add(p.data, p.len) } }
                // elst.media_time = hidden (A + D − s_{i_s}), длина B − A (timeline.md ф. 13).
                w.finish(plan.hidden.toInt(), n)
                return c.result(ext, n, track.sampleRate, fpp, preview.message)
            }
            Kind.OPUS -> {
                val inRate = GaplessResolver.csd(track.format, "csd-0")?.let { b -> GaplessResolver.indexOf(b, "OpusHead").takeIf { it >= 0 && b.size >= it + 16 }?.let { b.u32le(it + 12).toInt() } } ?: track.sampleRate
                val w = OggOpusWriter(sink, track.channels, inRate, plan.hidden.toInt())   // pre-skip = hidden (RFC 7845 §4.2)
                var lastF = 0
                forEachPacket(src, track, kind, progress) { pk -> inRange(pk) { p -> w.writePacket(p.data, 0, p.len, p.frames); lastF = p.frames; c.add(p.data, p.len) } }
                w.finish(n)
                return c.result(ext, n, 48000, com.rassul.audioeditor.core.pipeline.LengthTolerance.opusFrame(lastF), preview.message)
            }
            Kind.MP3 -> {
                // Кадр Info/LAME: delay = hidden − 529, padding = K·spf − delay − (B − A) (timeline.md «FR-23, MP3»).
                // Место под кадр Info резервируется по первому кадру (его длина зависит только от заголовка-шаблона),
                // сам кадр дописывается в конце, когда известны K и объём (потоково, без буфера всего фрагмента).
                var k = 0
                var bytes = 0L
                var spf = 1152
                var tmpl: Mp3Header? = null
                var infoPos = -1L; var infoLen = 0
                forEachPacket(src, track, kind, progress) { pk -> inRange(pk) { p ->
                    if (tmpl == null) {
                        val t = Mp3Header(p.data.u32be(0).toInt())
                        tmpl = t; spf = t.samples
                        if (t.valid && t.layer == 3) {
                            val ph = Mp3Scanner.buildInfoFrame(t, 1, 0, 0, 0)
                            infoPos = sink.position; infoLen = ph.size
                            sink.write(ph, 0, ph.size)
                        }
                    }
                    sink.write(p.data, 0, p.len); c.add(p.data, p.len); k++; bytes += p.len
                } }
                val t = tmpl
                if (t != null && infoPos >= 0) {
                    var delay = plan.hidden - MP3_DECODER_DELAY
                    var pad = k.toLong() * spf - delay - n
                    if (delay !in 0..4095 || pad !in 0..4095) {
                        profile.log("FR-23 MP3: delay=$delay, padding=$pad вне 0..4095 — прижаты, границы приблизительны")
                        delay = delay.coerceIn(0, 4095); pad = pad.coerceIn(0, 4095)
                    }
                    val info = Mp3Scanner.buildInfoFrame(t, k, bytes, delay.toInt(), pad.toInt())
                    check(info.size == infoLen)
                    val end = sink.position
                    sink.seek(infoPos); sink.write(info, 0, info.size); sink.seek(end)
                }
                return c.result(ext, n, track.sampleRate, spf, preview.message)
            }
            Kind.AC3, Kind.EAC3, Kind.DTS -> {
                forEachPacket(src, track, kind, progress) { pk -> inRange(pk) { p -> sink.write(p.data, 0, p.len); c.add(p.data, p.len) } }
                return c.result(ext, n, track.sampleRate, 0, preview.message)
            }
            Kind.FLAC -> {
                val si = flacStreamInfo(src, track)
                val fw = FlacStreamOut(sink, si)
                forEachPacket(src, track, kind, progress) { pk -> inRange(pk) { p -> fw.frame(p.data, p.len, p.frames); c.add(p.data, p.len) } }
                fw.finish(keepMd5 = false)   // RFC 9639 §8.2: MD5 = 0 — «неизвестно» для части потока
                return c.result(ext, fw.total, si.sampleRate, 0, preview.message)
            }
            else -> throw CoreException("Быстрая обрезка недоступна для ${track.codecName}.")
        }
    }

    /** Текст длительности для сообщений. */
    fun formatFrames(n: Long, fs: Int): String = TimeText.format(n, fs)
}
