package com.rassul.audioeditor.core.format.wav

import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.u16le
import com.rassul.audioeditor.core.format.bits.u32le
import com.rassul.audioeditor.core.format.bits.u64le
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CoreException
import java.security.MessageDigest

/*
 * WAV (RIFF/WAVE). Источники:
 *  - Microsoft «Multimedia Programming Interface and Data Specifications 1.0» (1991), RIFF, fmt/data, WAVE_FORMAT_PCM=1;
 *  - Microsoft «Multiple Channel Audio Data and WAVE Files» (WAVEFORMATEXTENSIBLE, 0xFFFE): обязателен при > 2 каналах
 *    или > 16 бит на сэмпл; dwChannelMask; SubFormat GUID KSDATAFORMAT_SUBTYPE_PCM/IEEE_FLOAT;
 *  - WAVE_FORMAT_IEEE_FLOAT = 3 + чанк 'fact' (dwSampleLength) для не-PCM;
 *  - EBU Tech 3306 (RF64/ds64) — только чтение.
 */

const val WAVE_FORMAT_PCM = 1
const val WAVE_FORMAT_IEEE_FLOAT = 3
const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

/** Хвост GUID KSDATAFORMAT_SUBTYPE_*: {xxxxxxxx-0000-0010-8000-00AA00389B71}, первые 2 байта = код формата. */
private val KS_GUID_TAIL = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71)

/** Маска каналов по умолчанию (dwChannelMask; порядок как в ffmpeg/Windows: FL FR FC LFE BL BR …, SL=0x200, SR=0x400). */
fun defaultChannelMask(ch: Int): Int = when (ch) {
    1 -> 0x4          // FC
    2 -> 0x3          // FL FR
    3 -> 0x7          // FL FR FC
    4 -> 0x33         // FL FR BL BR (квадро)
    5 -> 0x37         // FL FR FC BL BR
    6 -> 0x3F         // 5.1: FL FR FC LFE BL BR
    7 -> 0x13F        // 6.1: 5.1 + BC
    8 -> 0x63F        // 7.1: 5.1 + SL SR
    else -> 0
}

/**
 * Писатель WAV. Заголовок пишется сразу с нулевыми размерами, в finish() размеры переписываются.
 * Выбор fmt: int 16 бит ≤ 2 кан. → PCM (1); int 24 бит или > 2 кан. → EXTENSIBLE; float ≤ 2 кан. → IEEE_FLOAT (3) + fact;
 * float > 2 кан. → EXTENSIBLE(IEEE_FLOAT) + fact. Файлы > 4 ГБ не поддерживаются (ошибка).
 */
class WavEncoder(
    private val sink: SeekableSink,
    private val sampleRate: Int,
    private val channels: Int,
    private val bits: Int,
    private val isFloat: Boolean,
    private val channelMask: Int,
) {
    private val start = sink.position
    private val bytesPerSample = bits / 8
    private val blockAlign = channels * bytesPerSample
    private val extensible = channels > 2 || (!isFloat && bits > 16)
    private val hasFact = isFloat
    private var riffSizePos = 0L
    private var factPos = -1L
    private var dataSizePos = 0L
    private var dataBytes = 0L
    var frames = 0L; private set
    private val md5 = MessageDigest.getInstance("MD5")
    private var buf = ByteArray(0)
    var dataMd5: ByteArray? = null; private set

    init {
        require(bits == 16 || bits == 24 || (bits == 32)) { "bits=$bits" }
        val h = ByteBuf(80)
        h.ascii("RIFF"); riffSizePos = start + h.size; h.u32le(0); h.ascii("WAVE")
        h.ascii("fmt ")
        val fmtSize = if (extensible) 40 else if (isFloat) 18 else 16
        h.u32le(fmtSize)
        val code = if (isFloat) WAVE_FORMAT_IEEE_FLOAT else WAVE_FORMAT_PCM
        h.u16le(if (extensible) WAVE_FORMAT_EXTENSIBLE else code)
        h.u16le(channels)
        h.u32le(sampleRate)
        h.u32le(sampleRate.toLong() * blockAlign)      // nAvgBytesPerSec = fs·nBlockAlign
        h.u16le(blockAlign)                            // nBlockAlign = C·bits/8
        h.u16le(bits)
        if (extensible) {
            h.u16le(22)                                // cbSize
            h.u16le(bits)                              // wValidBitsPerSample
            h.u32le(channelMask)
            h.u16le(code); h.bytes(KS_GUID_TAIL)       // SubFormat
        } else if (isFloat) h.u16le(0)                 // cbSize = 0
        if (hasFact) { h.ascii("fact"); h.u32le(4); factPos = start + h.size; h.u32le(0) }
        h.ascii("data"); dataSizePos = start + h.size; h.u32le(0)
        sink.write(h.data, 0, h.size)
    }

    private fun out(n: Int): ByteArray { if (buf.size < n) buf = ByteArray(n); return buf }

    private fun emit(b: ByteArray, n: Int) {
        if (dataBytes + n > 0xFFFFFFF0L - 64) throw CoreException("Файл WAV больше 4 ГБ не поддерживается. Выберите FLAC или M4A.")
        sink.write(b, 0, n); md5.update(b, 0, n); dataBytes += n
    }

    fun writeInt(s: IntArray, nFrames: Int) {
        check(!isFloat) { "writeInt для float WAV — используйте writeFloat" }
        val n = nFrames * channels
        val b = out(n * bytesPerSample)
        var p = 0
        val lo = -(1 shl (bits - 1)); val hi = (1 shl (bits - 1)) - 1
        for (i in 0 until n) {
            val v = s[i]
            require(v in lo..hi) { "сэмпл $v вне $bits бит" }
            b[p++] = v.toByte(); b[p++] = (v shr 8).toByte()
            if (bits == 24) b[p++] = (v shr 16).toByte()
        }
        emit(b, p); frames += nFrames
    }

    fun writeFloat(s: FloatArray, nFrames: Int) {
        check(isFloat) { "writeFloat только для FLOAT32" }
        val n = nFrames * channels
        val b = out(n * 4)
        var p = 0
        for (i in 0 until n) {
            val v = java.lang.Float.floatToRawIntBits(s[i])
            b[p++] = v.toByte(); b[p++] = (v shr 8).toByte(); b[p++] = (v shr 16).toByte(); b[p++] = (v shr 24).toByte()
        }
        emit(b, p); frames += nFrames
    }

    fun finish() {
        val end0 = sink.position
        // RIFF: чанк нечётной длины дополняется нулевым байтом (RIFF spec, «pad byte»), в размер чанка не входит.
        if (dataBytes % 2 == 1L) sink.write(byteArrayOf(0), 0, 1)
        val end = sink.position
        val tmp = ByteBuf(4)
        fun put(pos: Long, v: Long) { tmp.clear(); tmp.u32le(v); sink.seek(pos); sink.write(tmp.data, 0, 4) }
        put(riffSizePos, end - start - 8)               // размер RIFF = файл − 8
        put(dataSizePos, dataBytes)
        if (factPos >= 0) put(factPos, frames)          // dwSampleLength — кадров на канал
        sink.seek(end)
        sink.flush()
        dataMd5 = md5.digest()
        check(end >= end0)
    }
}

/** Разобранные сведения о WAV (FR-3, FR-33). */
data class WavInfo(
    val formatTag: Int,          // 1, 3 или 0xFFFE
    val subFormat: Int,          // 1 (PCM) или 3 (float)
    val sampleRate: Int,
    val channels: Int,
    val containerBits: Int,
    val validBits: Int,
    val blockAlign: Int,
    val channelMask: Int,
    val dataOffset: Long,
    /** Заявлено в заголовке (байт). */
    val declaredDataSize: Long,
    /** Реально есть в файле (байт), ≤ declared. */
    val availableDataSize: Long,
    val isRf64: Boolean,
) {
    val isFloat: Boolean get() = subFormat == WAVE_FORMAT_IEEE_FLOAT
    val declaredFrames: Long get() = declaredDataSize / blockAlign
    /** N = фактические байты / blockAlign (timeline.md, FR-33, строка WAV). */
    val frames: Long get() = availableDataSize / blockAlign
    val truncated: Boolean get() = availableDataSize < declaredDataSize
}

object WavParser {
    fun isWav(src: RandomAccessSource): Boolean {
        val h = Src.readFully(src, 0, 12) ?: return false
        val id = h.ascii(0, 4)
        return (id == "RIFF" || id == "RF64") && h.ascii(8, 4) == "WAVE"
    }

    fun parse(src: RandomAccessSource): WavInfo {
        val bad = { why: String -> CoreException("Файл WAV повреждён или не поддерживается ($why).") }
        val h = Src.readFully(src, 0, 12) ?: throw bad("слишком короткий")
        val id = h.ascii(0, 4)
        if ((id != "RIFF" && id != "RF64") || h.ascii(8, 4) != "WAVE") throw bad("нет заголовка RIFF/WAVE")
        val rf64 = id == "RF64"
        var pos = 12L
        var fmt: ByteArray? = null
        var ds64Data = -1L
        var dataOff = -1L; var dataSize = -1L
        while (pos + 8 <= src.size) {
            val ch = Src.readFully(src, pos, 8) ?: break
            val cid = ch.ascii(0, 4)
            var csize = ch.u32le(4)
            val body = pos + 8
            when (cid) {
                "fmt " -> fmt = Src.readFully(src, body, minOf(csize, 64L).toInt()) ?: throw bad("обрезан fmt")
                "ds64" -> { val d = Src.readFully(src, body, 24) ?: throw bad("обрезан ds64"); ds64Data = d.u64le(8) }
                "data" -> {
                    if (rf64 && csize == 0xFFFFFFFFL && ds64Data >= 0) csize = ds64Data
                    // Размер 0xFFFFFFFF у потоковой записи (размер не известен) — до конца файла.
                    if (csize == 0xFFFFFFFFL) csize = src.size - body
                    dataOff = body; dataSize = csize
                }
            }
            if (dataOff >= 0 && fmt != null) break
            // Чанк нечётной длины дополнен байтом (RIFF).
            pos = body + csize + (csize and 1L)
        }
        val f = fmt ?: throw bad("нет чанка fmt")
        if (dataOff < 0) throw bad("нет чанка data")
        if (f.size < 16) throw bad("короткий fmt")
        val tag = f.u16le(0)
        val channels = f.u16le(2)
        val rate = f.u32le(4).toInt()
        val blockAlign = f.u16le(12)
        val bits = f.u16le(14)
        var sub = tag
        var valid = bits
        var mask = 0
        if (tag == WAVE_FORMAT_EXTENSIBLE) {
            if (f.size < 40) throw bad("короткий WAVE_FORMAT_EXTENSIBLE")
            valid = f.u16le(18).let { if (it == 0) bits else it }
            mask = f.u32le(20).toInt()
            sub = f.u16le(24)
        }
        if (channels !in 1..8) throw CoreException("WAV: $channels каналов не поддерживается (до 8).")
        if (rate !in 1..768_000) throw bad("частота $rate Гц")
        val okPcm = sub == WAVE_FORMAT_PCM && bits in intArrayOf(8, 16, 24, 32)
        val okFloat = sub == WAVE_FORMAT_IEEE_FLOAT && (bits == 32 || bits == 64)
        if (!okPcm && !okFloat) throw CoreException("Этот вид WAV не поддерживается (код формата $sub, $bits бит). Поддерживаются PCM 8/16/24/32 бит и float 32/64 бит.")
        if (blockAlign != channels * bits / 8) throw bad("nBlockAlign=$blockAlign")
        val avail = maxOf(0L, minOf(dataSize, src.size - dataOff))
        return WavInfo(tag, sub, rate, channels, bits, valid, blockAlign, mask, dataOff, dataSize, avail, rf64)
    }
}

/** Чтение сэмплов WAV во float [−1,1]. */
class WavSampleReader(private val src: RandomAccessSource, val info: WavInfo) {
    private var buf = ByteArray(0)

    fun read(fromFrame: Long, dst: FloatArray, frames: Int): Int {
        if (fromFrame >= info.frames || frames <= 0) return 0
        val n = minOf(frames.toLong(), info.frames - fromFrame).toInt()
        val bytes = n * info.blockAlign
        if (buf.size < bytes) buf = ByteArray(bytes)
        var done = 0
        val pos = info.dataOffset + fromFrame * info.blockAlign
        while (done < bytes) {
            val r = src.read(pos + done, buf, done, bytes - done)
            if (r <= 0) throw CoreException("Не удалось прочитать WAV: файл обрезан.")
            done += r
        }
        val cnt = n * info.channels
        val b = buf
        when {
            // Масштаб int → float: деление на 2^(bits−1) — точное для ≤ 24 бит (FR-3: 24 бит без потерь).
            !info.isFloat && info.containerBits == 8 -> for (i in 0 until cnt) dst[i] = ((b[i].toInt() and 0xFF) - 128) / 128f   // 8 бит беззнаковые (RIFF spec)
            !info.isFloat && info.containerBits == 16 -> for (i in 0 until cnt) {
                val p = i * 2; dst[i] = ((b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)) / 32768f
            }
            !info.isFloat && info.containerBits == 24 -> for (i in 0 until cnt) {
                val p = i * 3; dst[i] = ((b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)) / 8388608f
            }
            !info.isFloat && info.containerBits == 32 -> for (i in 0 until cnt) {
                val p = i * 4
                val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
                dst[i] = (v / 2147483648.0).toFloat()
            }
            info.containerBits == 32 -> for (i in 0 until cnt) {
                val p = i * 4
                val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
                dst[i] = java.lang.Float.intBitsToFloat(v)
            }
            else -> for (i in 0 until cnt) {
                val p = i * 8
                var v = 0L
                for (k in 7 downTo 0) v = (v shl 8) or (b[p + k].toLong() and 0xFF)
                dst[i] = java.lang.Double.longBitsToDouble(v).toFloat()
            }
        }
        return n
    }

    /** Целые сэмплы (для int-форматов), для проверок бит в бит. */
    fun readInt(fromFrame: Long, dst: IntArray, frames: Int): Int {
        check(!info.isFloat)
        if (fromFrame >= info.frames || frames <= 0) return 0
        val n = minOf(frames.toLong(), info.frames - fromFrame).toInt()
        val bps = info.containerBits / 8
        val b = Src.readFully(src, info.dataOffset + fromFrame * info.blockAlign, n * info.blockAlign)
            ?: throw CoreException("Не удалось прочитать WAV: файл обрезан.")
        for (i in 0 until n * info.channels) {
            val p = i * bps
            dst[i] = when (bps) {
                1 -> (b[p].toInt() and 0xFF) - 128
                2 -> (b[p].toInt() and 0xFF) or (b[p + 1].toInt() shl 8)
                3 -> (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or (b[p + 2].toInt() shl 16)
                else -> (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or ((b[p + 2].toInt() and 0xFF) shl 16) or (b[p + 3].toInt() shl 24)
            }
        }
        return n
    }
}
