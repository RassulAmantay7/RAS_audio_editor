package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.PcmSpec

/*
 * КОНТРАКТ (реализация — агент FORMATS). FR-3, FR-6, FR-23…FR-26.
 * Формулы elst/pre-skip/гранул — docs/math/timeline.md.
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
    override fun writeInt(samples: IntArray, frames: Int): Unit = TODO("FORMATS")
    /** Для FLOAT32: писать float напрямую. */
    fun writeFloat(samples: FloatArray, frames: Int): Unit = TODO("FORMATS")
    override fun finish(): Unit = TODO("FORMATS")
}

/** Свой FLAC-энкодер + контейнер .flac (16 или 24 бит), MD5 в STREAMINFO. */
class FlacWriter(sink: SeekableSink, spec: PcmSpec, val bitsPerSample: Int, val blockSize: Int = 4096) : PcmWriter {
    override fun writeInt(samples: IntArray, frames: Int): Unit = TODO("FORMATS")
    override fun finish(): Unit = TODO("FORMATS")
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
    fun writePacket(data: ByteArray, off: Int, len: Int): Unit = TODO("FORMATS")
    /** Завершить: elst с media_time = encoderDelay, длительность = validFrames. */
    fun finish(encoderDelay: Int, validFrames: Long): Unit = TODO("FORMATS")
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
    /** Пакет Opus и его длительность в кадрах 48 кГц (по TOC). */
    fun writePacket(data: ByteArray, off: Int, len: Int, frames48k: Int): Unit = TODO("FORMATS")
    /** Завершить: последняя страница с гранулой = preSkip + validFrames48k (EOS). */
    fun finish(validFrames48k: Long): Unit = TODO("FORMATS")
}

/** WAV на входе (8/16/24/32 int, 32/64 float, EXTENSIBLE). */
class WavReader(val src: RandomAccessSource) {
    val spec: PcmSpec get() = TODO("FORMATS")
    val bitsPerSample: Int get() = TODO("FORMATS")
    val isFloat: Boolean get() = TODO("FORMATS")
    val channelMask: Int get() = TODO("FORMATS")
    val lengthFrames: Long get() = TODO("FORMATS")
    /** Прочитать кадры в float [−1,1] (чередование). */
    fun read(fromFrame: Long, dst: FloatArray, frames: Int): Int = TODO("FORMATS")
    companion object { fun isWav(src: RandomAccessSource): Boolean = TODO("FORMATS") }
}

/** Сведения о «разгоне» и «хвосте» сжатого потока на входе (FR-26). */
data class GaplessInfo(val delayFrames: Long, val paddingFrames: Long, val source: String)

object GaplessProbe {
    /** Найти задержку/паддинг в файле: elst / iTunSMPB (MP4), LAME/Xing (MP3), OpusHead pre-skip (Ogg). null — не найдено. */
    fun probe(src: RandomAccessSource): GaplessInfo? = TODO("FORMATS")
}

/** Определение неподдерживаемых контейнеров по сигнатуре (FR-2). */
object ContainerSniffer {
    /** null — можно пытаться открыть; иначе понятное сообщение («AVI не поддерживается…»). */
    fun unsupportedMessage(src: RandomAccessSource): String? = TODO("FORMATS")
}
