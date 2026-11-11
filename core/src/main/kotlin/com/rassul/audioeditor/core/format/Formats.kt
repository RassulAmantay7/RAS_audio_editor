package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.flac.FlacEncoder
import com.rassul.audioeditor.core.format.mp4.Mp4AacWriterImpl
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.ogg.OggOpusWriterImpl
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.ogg.OpusHead
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.format.raw.MP3_DECODER_DELAY
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.format.wav.WavEncoder
import com.rassul.audioeditor.core.format.wav.WavInfo
import com.rassul.audioeditor.core.format.wav.WavParser
import com.rassul.audioeditor.core.format.wav.WavSampleReader
import com.rassul.audioeditor.core.format.wav.defaultChannelMask
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.PcmSpec

/*
 * КОНТРАКТ (реализация — агент FORMATS). FR-3, FR-6, FR-23…FR-26.
 * Формулы elst/pre-skip/гранул — docs/math/timeline.md.
 * Реализация разнесена по подпакетам format/{bits,wav,flac,mp4,ogg,raw}.
 */

/** Писатель несжатого PCM в файл (WAV, FLAC). */
interface PcmWriter {
    /** Подать уже квантованные целые сэмплы (чередование) либо float — см. реализацию. */
    fun writeInt(samples: IntArray, frames: Int)
    /** Завершить: дописать заголовки/STREAMINFO/MD5. */
    fun finish()
}

enum class WavSampleFormat(val bits: Int, val isFloat: Boolean) { PCM16(16, false), PCM24(24, false), FLOAT32(32, true) }

class WavWriter(sink: SeekableSink, spec: PcmSpec, val format: WavSampleFormat) : PcmWriter {
    private val enc = WavEncoder(sink, spec.sampleRate, spec.channels, format.bits, format.isFloat, defaultChannelMask(spec.channels))
    /** writeInt: целые в диапазоне разрядности (16/24 бит); для FLOAT32 — только writeFloat. */
    override fun writeInt(samples: IntArray, frames: Int) = enc.writeInt(samples, frames)
    /** Для FLOAT32: писать float напрямую. */
    fun writeFloat(samples: FloatArray, frames: Int) = enc.writeFloat(samples, frames)
    override fun finish() = enc.finish()
    /** MD5 байтов области data (после finish) — для FR-33. */
    val dataMd5: ByteArray? get() = enc.dataMd5
    val framesWritten: Long get() = enc.frames
}

/** Свой FLAC-энкодер + контейнер .flac (16 или 24 бит), MD5 в STREAMINFO. */
class FlacWriter(
    sink: SeekableSink, spec: PcmSpec, val bitsPerSample: Int, val blockSize: Int = 4096,
    /** Разрешить блок вне субсета RFC 9639 (до 65 535) — только для тестов самого энкодера. */
    allowNonSubset: Boolean = false,
) : PcmWriter {
    init {
        if (!allowNonSubset) {
            val max = maxSubsetBlockSize(spec.sampleRate)
            require(blockSize <= max) {
                "Размер блока FLAC $blockSize больше допустимого $max для ${spec.sampleRate} Гц (субсет RFC 9639 §7): " +
                    "такие файлы не воспроизводят многие плееры."
            }
        }
    }
    companion object {
        /** Субсет RFC 9639 §7 «Streamable subset»: блок ≤ 16 384, а при fs ≤ 48 кГц — ≤ 4608. */
        fun maxSubsetBlockSize(sampleRate: Int): Int = if (sampleRate <= 48_000) 4608 else 16_384
    }
    private val enc = FlacEncoder(sink, spec.sampleRate, spec.channels, bitsPerSample, blockSize)
    override fun writeInt(samples: IntArray, frames: Int) = enc.write(samples, frames)
    override fun finish() = enc.finish()
    /** MD5 несжатого сигнала, записанный в STREAMINFO (после finish). */
    val md5: ByteArray? get() = enc.md5Digest
    val totalSamples: Long get() = enc.totalSamples
}

/** Писатель контейнера .m4a для готовых AAC-кадров (от MediaCodec или копирование). */
class Mp4AacWriter(
    sink: SeekableSink,
    val sampleRate: Int,
    val channels: Int,
    /** AudioSpecificConfig (csd-0 из MediaCodec / esds источника). */
    val audioSpecificConfig: ByteArray,
    /** Кадров на AAC-кадр (1024 для LC, 2048 для HE на выходной частоте). */
    val framesPerPacket: Int = 1024,
) {
    private val impl = Mp4AacWriterImpl(sink, sampleRate, channels, audioSpecificConfig, framesPerPacket)
    /** Писать ли iTunSMPB (Apple TN2258) в udta; по умолчанию да. */
    var writeITunSmpb: Boolean
        get() = impl.writeITunSmpb
        set(v) { impl.writeITunSmpb = v }
    fun writePacket(data: ByteArray, off: Int, len: Int) = impl.writePacket(data, off, len)
    /** Завершить: elst с media_time = encoderDelay, длительность = validFrames. */
    fun finish(encoderDelay: Int, validFrames: Long) = impl.finish(encoderDelay, validFrames)
}

/** Писатель Ogg Opus (RFC 7845). Гранулы — в 48 кГц. */
class OggOpusWriter(
    sink: SeekableSink,
    val channels: Int,
    /** Исходная частота для OpusHead (информативно). */
    val inputSampleRate: Int,
    val preSkip: Int,
    val serial: Int = 0x52415321,
) {
    /** Для > 2 каналов: [streams, coupled, mapping×C] (RFC 7845 §5.1.1); null — раскладка libopus по умолчанию. Задать до первого пакета. */
    var channelMapping: ByteArray? = null
    private var impl: OggOpusWriterImpl? = null
    private fun w(): OggOpusWriterImpl = impl ?: OggOpusWriterImpl(sinkRef, channels, inputSampleRate, preSkip, serial, channelMapping).also { impl = it }
    private val sinkRef = sink
    /** Пакет Opus и его длительность в кадрах 48 кГц (по TOC). */
    fun writePacket(data: ByteArray, off: Int, len: Int, frames48k: Int) = w().writePacket(data, off, len, frames48k)
    /** Завершить: последняя страница с гранулой = preSkip + validFrames48k (EOS). */
    fun finish(validFrames48k: Long) = w().finish(validFrames48k)
}

/** WAV на входе (8/16/24/32 int, 32/64 float, EXTENSIBLE). */
class WavReader(val src: RandomAccessSource) {
    /** Разобранный заголовок (CoreException с понятным текстом, если файл не WAV/повреждён). */
    val info: WavInfo = WavParser.parse(src)
    private val reader = WavSampleReader(src, info)
    val spec: PcmSpec get() = PcmSpec(info.sampleRate, info.channels)
    val bitsPerSample: Int get() = info.validBits
    val isFloat: Boolean get() = info.isFloat
    val channelMask: Int get() = info.channelMask
    /** Фактическая длина (по байтам в файле, timeline.md FR-33); заявленная — info.declaredFrames. */
    val lengthFrames: Long get() = info.frames
    /** Прочитать кадры в float [−1,1] (чередование). */
    fun read(fromFrame: Long, dst: FloatArray, frames: Int): Int = reader.read(fromFrame, dst, frames)
    /** Целые сэмплы (только int-форматы). */
    fun readInt(fromFrame: Long, dst: IntArray, frames: Int): Int = reader.readInt(fromFrame, dst, frames)
    companion object { fun isWav(src: RandomAccessSource): Boolean = WavParser.isWav(src) }
}

/** Сведения о «разгоне» и «хвосте» сжатого потока на входе (FR-26). */
data class GaplessInfo(val delayFrames: Long, val paddingFrames: Long, val source: String)

object GaplessProbe {
    /**
     * Найти задержку/паддинг в файле: elst / iTunSMPB (MP4), LAME/Xing (MP3), OpusHead pre-skip (Ogg). null — не найдено.
     * Значения — в шкале полного выхода декодера (каждый пакет даёт свою полную длину):
     *  MP3: delay = enc_delay + 529, padding = enc_padding − 529 (timeline.md ф. 8, задержка декодера 529);
     *  MP4: delay = media_time, padding = K·F − media_time − длина (длина по приоритету timeline.md «Приоритет источников»);
     *  Opus: delay = pre_skip, padding = Σ длительностей пакетов − последняя гранула (RFC 7845 §4.2, §4.4), в 48 кГц.
     */
    fun probe(src: RandomAccessSource): GaplessInfo? {
        val h = Src.readUpTo(src, 0, 12)
        if (h.size < 12) return null
        // Повреждённый файл (счётчики, обрезанные боксы/страницы) — сведений о задержке нет, а не падение (как FileFacts).
        return try {
            when {
                h.ascii(4, 4) == "ftyp" -> probeMp4(src)
                h.ascii(0, 4) == "OggS" -> probeOgg(src)
                h.ascii(0, 3) == "ID3" || ((h[0].toInt() and 0xFF) == 0xFF && (h[1].toInt() and 0xE0) == 0xE0) -> probeMp3(src)
                else -> null
            }
        } catch (e: RuntimeException) { null }
    }

    /** Длина и задержка MP4 по приоритету timeline.md: elst (точно) → iTunSMPB → Σstts − media_time. */
    class Mp4Trim(val skip: Long, val length: Long, val padding: Long, val source: String)

    fun mp4Trim(t: Mp4AudioTrack): Mp4Trim? {
        val mt = t.elstMediaTime
        val seg = t.elstSegmentDuration
        val smpb = t.iTunSmpb?.let { Mp4AudioTrack.parseITunSmpb(it) }
        val frame = t.stts.firstOrNull()?.get(1) ?: 1024L
        val total = t.sampleCount * frame
        val mvTs = t.mvhdTimescale; val mdTs = t.mdhdTimescale
        if (mt != null && seg != null && mvTs > 0) {
            val exact = (seg * mdTs) % mvTs == 0L
            var n = (seg * mdTs + mvTs / 2) / mvTs
            if (!exact || mvTs < mdTs) {
                // Сегмент записан грубо (шкала mvhd = 1000 у ffmpeg): длина из iTunSMPB или Σstts − media_time, если в пределах тика.
                if (smpb != null) n = smpb[2]
                else if (t.sttsTotal - mt <= n + mdTs / mvTs) n = t.sttsTotal - mt
            }
            return Mp4Trim(mt, n, maxOf(0L, total - mt - n), "elst")
        }
        if (smpb != null) return Mp4Trim(smpb[0], smpb[2], maxOf(0L, total - smpb[0] - smpb[2]), "iTunSMPB")
        return null
    }

    private fun probeMp4(src: RandomAccessSource): GaplessInfo? {
        val t = Mp4AudioTrack.read(src) ?: return null
        val r = mp4Trim(t) ?: return null
        return GaplessInfo(r.skip, r.padding, r.source)
    }

    private fun probeMp3(src: RandomAccessSource): GaplessInfo? {
        val s = Mp3Scanner.scan(src)
        val i = s.info ?: return null
        val d = i.delay ?: return null; val p = i.padding ?: return null
        return GaplessInfo((d + MP3_DECODER_DELAY).toLong(), (p - MP3_DECODER_DELAY).toLong(), "LAME")
    }

    private fun probeOgg(src: RandomAccessSource): GaplessInfo? {
        val s = OggReader.read(src)
        val head = s.packets.firstOrNull()?.let { OpusHead.parse(it.data) } ?: return null
        var total = 0L
        for (k in 2 until s.packets.size) { val d = s.packets[k].data; if (d.isNotEmpty()) total += OpusToc.samples48(d) }
        val pad = if (s.lastGranule >= 0) total - s.lastGranule else 0L
        return GaplessInfo(head.preSkip.toLong(), maxOf(0L, pad), "OpusHead")
    }
}

/** Определение неподдерживаемых контейнеров по сигнатуре (FR-2). */
object ContainerSniffer {
    private const val SUPPORTED = "Поддерживаются: MP4, MOV, 3GP, MKV, WebM, TS."
    /** ASF Header Object GUID 75B22630-668E-11CF-A6D9-00AA0062CE6C (в файле — little-endian поля). */
    private val ASF_GUID = intArrayOf(0x30, 0x26, 0xB2, 0x75, 0x8E, 0x66, 0xCF, 0x11, 0xA6, 0xD9, 0x00, 0xAA, 0x00, 0x62, 0xCE, 0x6C)

    /** null — можно пытаться открыть; иначе понятное сообщение («AVI не поддерживается…»). */
    fun unsupportedMessage(src: RandomAccessSource): String? {
        if (src.size == 0L) return "Файл пустой."
        val h = Src.readUpTo(src, 0, 16)
        if (h.size >= 12 && h.ascii(0, 4) == "RIFF" && h.ascii(8, 4) == "AVI ") return "Формат AVI не поддерживается. $SUPPORTED"
        if (h.size >= 4 && h.ascii(0, 3) == "FLV" && h[3].toInt() == 1) return "Формат FLV не поддерживается. $SUPPORTED"
        if (h.size >= 16 && (0 until 16).all { (h[it].toInt() and 0xFF) == ASF_GUID[it] }) return "Формат WMV/ASF не поддерживается. $SUPPORTED"
        if (h.size >= 4 && h.ascii(0, 4) == ".RMF") return "Формат RealMedia не поддерживается. $SUPPORTED"
        return null
    }
}
