package com.rassul.audioeditor.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.rassul.audioeditor.core.format.GaplessInfo
import com.rassul.audioeditor.core.format.GaplessProbe
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.u16le
import com.rassul.audioeditor.core.format.bits.u64le
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.pipeline.DecoderTrim
import com.rassul.audioeditor.core.pipeline.GaplessTrimmer
import com.rassul.audioeditor.core.pipeline.MediaMath
import com.rassul.audioeditor.core.pipeline.PcmDecode
import com.rassul.audioeditor.core.pipeline.PtsGapFiller
import com.rassul.audioeditor.diag.DeviceProfile
import java.io.FileDescriptor
import java.nio.ByteOrder

/*
 * Зона MEDIA, T17. Низкоуровневое декодирование одной дорожки системным MediaCodec (синхронный режим,
 * вызывать НЕ из UI-потока). Общий путь для редактора (DecodePipeline), перекодирования и автопроверки FR-33
 * (timeline.md FR-33: «Декодирование для проверки идёт тем же путём, что вход редактора»).
 *
 * Порядок обработки (timeline.md «FR-7: разрывы PTS»): заполнение разрывов PTS (PtsGapFiller, шкала декодера)
 * → обрезка задержки/хвоста (GaplessTrimmer, FR-26) → NaN/Inf → 0 (заметка аудита T13) → выдача.
 * Сведение каналов (FR-11) делает вызывающий.
 *
 * FR-26: перед configure() из формата удаляются KEY_ENCODER_DELAY/KEY_ENCODER_PADDING, чтобы Codec2 не обрезал
 * сам (timeline.md «риск двойной обрезки»); обрезка — только GaplessTrimmer.
 */

/** Фактический выход декодера. bits: 32 — float, 16/24/32 — целые. */
data class DecodedFormat(val sampleRate: Int, val channels: Int, val channelMask: Int, val bits: Int, val isFloat: Boolean)

class DecodeStats(
    val frames: Long,
    val nanReplaced: Long,
    val silenceInserted: Long,
    val overlapDropped: Long,
    val headTrimmed: Long,
    val tailTrimmed: Long,
    val format: DecodedFormat?,
    val decoderName: String,
)

/** Задержка/хвост входа (FR-26) и шкала, в которой они записаны. */
data class GaplessSpec(val info: GaplessInfo?, val rate: Int)

object GaplessResolver {
    /**
     * Приоритет (timeline.md «Приоритет источников на входе»): разбор файла ядром (elst/iTunSMPB, LAME, OpusHead)
     * — только если звуковая дорожка в файле одна (иначе неясно, к какой относится); Opus — pre-skip из csd-0/csd-1;
     * затем ключи MediaExtractor; иначе — не обрезать (и записать в журнал).
     */
    fun resolve(src: RandomAccessSource?, track: AudioTrackInfo, audioTrackCount: Int, log: (String) -> Unit): GaplessSpec =
        resolve(src, track.mime, track.format, track.sampleRate, audioTrackCount, log)

    fun resolve(src: RandomAccessSource?, mimeIn: String, f: MediaFormat, sampleRate: Int, audioTrackCount: Int, log: (String) -> Unit): GaplessSpec {
        val mime = mimeIn.lowercase()
        val rate = if (mime == "audio/opus") 48000 else sampleRate
        if (mime != "audio/mp4a-latm" && mime != "audio/mpeg" && mime != "audio/opus") return GaplessSpec(null, rate)
        if (src != null && audioTrackCount == 1) {
            val g = try { GaplessProbe.probe(src) } catch (e: Exception) { null }
            if (g != null) { log("FR-26: задержка ${g.delayFrames}, хвост ${g.paddingFrames} (${g.source})"); return GaplessSpec(g, rate) }
        }
        if (mime == "audio/opus") opusPreSkip(f)?.let {
            log("FR-26: Opus pre-skip $it (csd)"); return GaplessSpec(GaplessInfo(it.toLong(), 0, "csd"), rate)
        }
        if (f.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
            val d = f.getInteger(MediaFormat.KEY_ENCODER_DELAY, 0).toLong()
            val p = f.getInteger(MediaFormat.KEY_ENCODER_PADDING, 0).toLong()
            // MediaExtractor (MP3Extractor) кладёт задержку энкодера LAME без 529 сэмплов декодера (timeline.md ф. 8).
            val dd = if (mime == "audio/mpeg") d + 529 else d
            val pp = if (mime == "audio/mpeg") maxOf(0L, p - 529) else p
            log("FR-26: задержка $dd, хвост $pp (MediaExtractor)")
            return GaplessSpec(GaplessInfo(dd, pp, "MediaExtractor"), rate)
        }
        log("FR-26: задержка энкодера не найдена — начало и конец не обрезаются")
        return GaplessSpec(null, rate)
    }

    /** pre-skip из csd-0 (OpusHead, RFC 7845 §5.1) или csd-1 (задержка в нс, формат Android). */
    fun opusPreSkip(f: MediaFormat): Int? {
        csd(f, "csd-0")?.let { b ->
            val i = indexOf(b, "OpusHead")
            if (i >= 0 && b.size >= i + 12) return b.u16le(i + 10)
        }
        csd(f, "csd-1")?.let { b -> if (b.size >= 8) return MediaMath.nsToFrames(b.u64le(0), 48000).toInt() }
        return null
    }

    fun csd(f: MediaFormat, key: String): ByteArray? = try {
        f.getByteBuffer(key)?.let { bb -> val d = bb.duplicate(); d.rewind(); ByteArray(d.remaining()).also { d.get(it) } }
    } catch (_: Exception) { null }

    fun indexOf(b: ByteArray, s: String): Int {
        outer@ for (i in 0..b.size - s.length) { for (k in s.indices) if (b[i + k].toInt() != s[k].code) continue@outer; return i }
        return -1
    }

    @Suppress("unused") private fun asciiAt(b: ByteArray, i: Int, n: Int) = b.ascii(i, n)
}

class MediaDecoder(
    private val fd: FileDescriptor,
    private val trackIndex: Int,
    private val trackFormat: MediaFormat,
    private val gapless: GaplessSpec,
    private val profile: DeviceProfile?,
    /** Запрашивать float-выход (FR-4); false — 16 бит (самопроверка). */
    private val requestFloat: Boolean = true,
    /** KEY_MAX_OUTPUT_CHANNEL_COUNT: просим все каналы, чтобы декодер не сводил сам (FR-11). */
    private val maxOutputChannels: Int = 8,
    /** Переопределение «декодер сам отбрасывает» для калибровки; null — из DeviceProfile / допущение. */
    private val decoderDropsOverride: Long? = null,
    /** Длительность для прогресса, мкс. */
    private val durationUs: Long = trackFormat.getLong(MediaFormat.KEY_DURATION, 0L),
) {
    private val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
    private fun log(s: String) = profile?.log(s)

    /**
     * Декодировать всю дорожку. onFormat — один раз до первого onPcm (и снова, если формат сменился — тогда
     * бросается CoreException: смена формата посреди потока не поддерживается). onPcm получает чередующиеся float
     * (массив переиспользуется).
     */
    fun run(progress: Progress, onFormat: (DecodedFormat) -> Unit, onPcm: (FloatArray, Int) -> Unit): DecodeStats {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        var name = ""
        try {
            ex.setDataSource(fd)
            ex.selectTrack(trackIndex)
            val cfg = MediaFormat(trackFormat)
            cfg.removeKey(MediaFormat.KEY_ENCODER_DELAY)       // FR-26: без двойной обрезки
            cfg.removeKey(MediaFormat.KEY_ENCODER_PADDING)
            cfg.setInteger(MediaFormat.KEY_MAX_OUTPUT_CHANNEL_COUNT, maxOutputChannels)
            if (requestFloat) cfg.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val decName = CapabilityProbe.decoderName(cfg)
            fun create(): MediaCodec = try {
                if (decName != null) MediaCodec.createByCodecName(decName) else MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw CoreException("На этом телефоне нет декодера для ${com.rassul.audioeditor.core.pipeline.ExtractPlanner.codecName(mime)}.", e)
            }
            codec = create()
            try {
                codec.configure(cfg, null, null, 0)
            } catch (e: Exception) {
                // Декодер не принял float-выход или число каналов: повтор без этих ключей (FR-4 — предупреждение по факту).
                log("Декодер не принял формат (${e.message}); повтор без float")
                codec.release()
                codec = create()
                cfg.removeKey(MediaFormat.KEY_PCM_ENCODING)
                try { codec.configure(cfg, null, null, 0) } catch (e2: Exception) {
                    throw CoreException("Не удалось запустить декодер ${com.rassul.audioeditor.core.pipeline.ExtractPlanner.codecName(mime)}.", e2)
                }
            }
            name = codec.name
            codec.start()
            return loop(ex, codec, name, progress, onFormat, onPcm)
        } catch (e: CoreException) {
            throw e
        } catch (e: IllegalStateException) {
            throw CoreException("Декодер остановился с ошибкой: файл, возможно, повреждён.", e)
        } catch (e: java.io.IOException) {
            throw CoreException("Не удалось прочитать файл.", e)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    private fun loop(ex: MediaExtractor, codec: MediaCodec, name: String, progress: Progress,
                     onFormat: (DecodedFormat) -> Unit, onPcm: (FloatArray, Int) -> Unit): DecodeStats {
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var fmt: DecodedFormat? = null
        var trimmer: GaplessTrimmer? = null
        var filler: PtsGapFiller? = null
        var hold: Holdback? = null
        var pcm = FloatArray(0)
        var zeros = FloatArray(0)
        var out = FloatArray(0)
        var frames = 0L; var nan = 0L; var silence = 0L; var overlap = 0L; var head = 0L
        val emit: (FloatArray, Int, Int) -> Unit = { buf, off, n ->
            // GaplessTrimmer: d = dropAtStart(n); k = keepFrames(n) → кадры [d, d+k) блока.
            val tr = trimmer!!
            val ch = fmt!!.channels
            val d = tr.dropAtStart(n)
            val k = tr.keepFrames(n)
            head += d
            if (k > 0) hold!!.push(buf, (off + d) * ch, k) { b, o, m ->
                if (out.size < m * ch) out = FloatArray(m * ch)
                System.arraycopy(b, o, out, 0, m * ch)
                nan += PcmDecode.sanitize(out, 0, m * ch)
                frames += m
                onPcm(out, m)
            }
        }
        fun onFormatChanged(of: MediaFormat) {
            val enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            val nf = DecodedFormat(
                of.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0), of.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0),
                of.getInteger(MediaFormat.KEY_CHANNEL_MASK, 0),
                when (enc) { AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 32; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24; AudioFormat.ENCODING_PCM_8BIT -> 8; else -> 16 },
                enc == AudioFormat.ENCODING_PCM_FLOAT)
            if (nf.sampleRate <= 0 || nf.channels !in 1..8) throw CoreException("Декодер сообщил неверный формат звука.")
            if (fmt == null) {
                fmt = nf
                log("Декодер $name: ${nf.sampleRate} Гц, ${nf.channels} кан., маска 0x${Integer.toHexString(nf.channelMask)}, ${if (nf.isFloat) "float" else "${nf.bits} бит"}")
                trimmer = makeTrimmer(nf.sampleRate)
                filler = PtsGapFiller(nf.sampleRate)
                hold = Holdback(nf.channels, trimmer.tailToTruncateAtFinish.toInt())
                onFormat(nf)
            } else if (nf.sampleRate != fmt.sampleRate || nf.channels != fmt.channels) {
                throw CoreException("Формат звука меняется посреди файла — такие файлы не поддерживаются.")
        }
        }
        var lastReport = 0L
        while (true) {
            if (progress.isCancelled) throw CancelledException()
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) {
                    val ib = codec.getInputBuffer(ii)!!
                    ib.clear()
                    val sz = ex.readSampleData(ib, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val t = ex.sampleTime
                        codec.queueInputBuffer(ii, 0, sz, maxOf(0L, t), 0)
                        ex.advance()
                        if (durationUs > 0 && t - lastReport > 200_000) { lastReport = t; progress.report((t.toDouble() / durationUs).coerceIn(0.0, 1.0)) }
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 10_000)
            when {
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> onFormatChanged(codec.outputFormat)
                oi >= 0 -> {
                    // Некоторые декодеры отдают первый буфер раньше INFO_OUTPUT_FORMAT_CHANGED.
                    if (fmt == null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) onFormatChanged(codec.outputFormat)
                    val f = fmt
                    if (info.size > 0 && f != null && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val ob = codec.getOutputBuffer(oi)!!
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.nativeOrder())
                        val ch = f.channels
                        val bps = PcmDecode.bytesPerSample(f.bits, f.isFloat)
                        val n = info.size / (bps * ch)
                        if (pcm.size < n * ch) pcm = FloatArray(n * ch)
                        // 16 бит ×1/32768, 24 бит ×1/2²³, 32 бит ×1/2³¹, 8 бит (u−128)/128 (dsp.md; аудит T21 — PcmDecode в :core).
                        PcmDecode.toFloat(ob, info.offset, f.bits, f.isFloat, n * ch, pcm)
                        codec.releaseOutputBuffer(oi, false)
                        if (n > 0) {
                            // FR-7 (timeline.md ф. 7): разрыв → тишина, перекрытие → отбросить начало блока.
                            val adj = filler!!.onBlock(info.presentationTimeUs, n)
                            var z = adj.insertSilenceFrames
                            if (z > 0) { silence += z; log("FR-7: разрыв PTS, вставлено $z кадров тишины") }
                            while (z > 0) {
                                val m = minOf(z, 8192L).toInt()
                                if (zeros.size < m * ch) zeros = FloatArray(m * ch)
                                java.util.Arrays.fill(zeros, 0, m * ch, 0f)
                                emit(zeros, 0, m); z -= m
                            }
                            val drop = adj.dropFrames
                            if (drop > 0) { overlap += drop; log("FR-7: перекрытие PTS, отброшено $drop кадров") }
                            if (n - drop > 0) emit(pcm, drop, n - drop)
                        }
                    } else codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        val tail = hold?.dropped?.toLong() ?: 0L
        if (nan > 0) log("Декодер выдал $nan значений NaN/Inf — заменены на 0")
        progress.report(1.0)
        return DecodeStats(frames, nan, silence, overlap, head, tail, fmt, name)
    }

    private fun makeTrimmer(outRate: Int): GaplessTrimmer {
        val g = gapless.info
        // Пересчёт HE-AAC, отсев неправдоподобных значений, «декодер сам отбрасывает» — DecoderTrim (:core, аудит T21).
        val p = DecoderTrim.plan(g?.delayFrames ?: 0L, g?.paddingFrames ?: 0L, if (g != null) gapless.rate else 0, outRate,
            decoderDropsOverride ?: profile?.decoderDropsFramesOrNull(mime), mime.equals("audio/opus", true))
        p.notes.forEach { log(it) }
        val delay = p.delay; val pad = p.padding; val drops = p.decoderDrops
        if (drops != 0L) log("FR-26: декодер $mime сам отбрасывает $drops кадров (профиль/допущение)")
        return GaplessTrimmer(delay, pad, drops, null)
    }

}

/**
 * Задержка выдачи на [holdFrames] кадров: последние holdFrames кадров потока никогда не выдаются
 * (хвост FR-26 при неизвестной полной длине — GaplessTrimmer.tailToTruncateAtFinish).
 */
internal class Holdback(private val channels: Int, private val holdFrames: Int) {
    private var buf = FloatArray(maxOf(1, holdFrames) * channels * 2)
    private var count = 0
    val dropped: Int get() = count

    fun push(src: FloatArray, off: Int, frames: Int, out: (FloatArray, Int, Int) -> Unit) {
        if (holdFrames <= 0) { out(src, off, frames); return }
        val need = (count + frames) * channels
        if (buf.size < need) buf = buf.copyOf(need)
        System.arraycopy(src, off, buf, count * channels, frames * channels)
        count += frames
        val emit = count - holdFrames
        if (emit > 0) {
            out(buf, 0, emit)
            System.arraycopy(buf, emit * channels, buf, 0, holdFrames * channels)
            count = holdFrames
        }
    }
}
