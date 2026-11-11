package com.rassul.audioeditor.core.format.flac

import com.rassul.audioeditor.core.format.bits.BitReader
import com.rassul.audioeditor.core.format.bits.BitWriter
import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.toHex
import com.rassul.audioeditor.core.format.bits.u24be
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.format.raw.CodedFrame
import com.rassul.audioeditor.core.format.raw.SrcWindow
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CoreException

/*
 * Кадры FLAC на входе (RFC 9639 §8.2, §9.1, §9.3): разбор STREAMINFO, поиск кадров (синхрокод + CRC-8 заголовка
 * + CRC-16 кадра), перенумерация кадров с пересчётом CRC-8/CRC-16 для быстрой обрезки (timeline.md, FR-23: FLAC —
 * без разгона, total_samples пересчитан, MD5 = 0 «неизвестно»).
 */

data class FlacStreamInfo(
    val minBlock: Int, val maxBlock: Int, val minFrame: Int, val maxFrame: Int,
    val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val totalSamples: Long, val md5: ByteArray,
) {
    val md5Hex: String get() = md5.toHex()
    val md5Known: Boolean get() = md5.any { it.toInt() != 0 }

    fun toBytes(): ByteArray {
        val bw = BitWriter(64)
        bw.write(minBlock, 16); bw.write(maxBlock, 16); bw.write(minFrame, 24); bw.write(maxFrame, 24)
        bw.write(sampleRate, 20); bw.write(channels - 1, 3); bw.write(bitsPerSample - 1, 5)
        bw.writeLong(totalSamples, 36); bw.writeBytes(md5, 0, 16)
        return bw.bytes()
    }

    companion object {
        /** STREAMINFO (34 байта, §8.2). */
        fun parse(b: ByteArray, off: Int = 0): FlacStreamInfo {
            val r = BitReader(b, off, off + 34)
            val minB = r.read(16); val maxB = r.read(16); val minF = r.read(24); val maxF = r.read(24)
            val sr = r.read(20); val ch = r.read(3) + 1; val bps = r.read(5) + 1
            val total = r.readLong(36)
            return FlacStreamInfo(minB, maxB, minF, maxF, sr, ch, bps, total, b.copyOfRange(off + 18, off + 34))
        }
    }
}

class FlacFrame(offset: Long, size: Int, samples: Int, sampleRate: Int, channels: Int,
                val number: Long, val variableBlocksize: Boolean, val headerSize: Int) :
    CodedFrame(offset, size, samples, sampleRate, channels)

class FlacFile(val streamInfo: FlacStreamInfo, val audioOffset: Long, val frames: List<FlacFrame>, val crcErrors: Int)

object FlacFrames {
    /** Разобрать заголовок «fLaC» + блоки метаданных; вернуть STREAMINFO и смещение первого кадра. */
    fun readHeader(src: RandomAccessSource): Pair<FlacStreamInfo, Long> {
        val w = SrcWindow(src)
        var pos = 0L
        // ID3v2 перед fLaC встречается — пропустить.
        w.bytes(0, 10)?.let { h -> if (h.ascii(0, 3) == "ID3") pos = 10L + (((h.u8(6) and 0x7F) shl 21) or ((h.u8(7) and 0x7F) shl 14) or ((h.u8(8) and 0x7F) shl 7) or (h.u8(9) and 0x7F)) }
        val sig = w.bytes(pos, 4) ?: throw CoreException("Файл FLAC повреждён: слишком короткий.")
        if (sig.ascii(0, 4) != "fLaC") throw CoreException("Это не файл FLAC.")
        pos += 4
        var si: FlacStreamInfo? = null
        while (true) {
            val h = w.bytes(pos, 4) ?: throw CoreException("Файл FLAC повреждён: обрезаны метаданные.")
            val last = h.u8(0) and 0x80 != 0
            val type = h.u8(0) and 0x7F
            val len = h.u24be(1)
            if (type == 0) si = FlacStreamInfo.parse(w.bytes(pos + 4, 34) ?: throw CoreException("Файл FLAC повреждён: обрезан STREAMINFO."))
            pos += 4 + len
            if (last) break
        }
        return (si ?: throw CoreException("Файл FLAC повреждён: нет STREAMINFO.")) to pos
    }

    private class Hdr(val len: Int, val blockSize: Int, val number: Long, val variable: Boolean, val sampleRate: Int, val channels: Int)

    /** Разбор заголовка кадра с проверкой CRC-8 (§9.1); null — не заголовок. */
    private fun header(w: SrcWindow, pos: Long, si: FlacStreamInfo): Hdr? {
        val b = w.bytes(pos, 16) ?: w.bytes(pos, (w.size - pos).toInt().coerceAtMost(16)) ?: return null
        if (b.size < 6) return null
        if (b.u8(0) != 0xFF || (b.u8(1) and 0xFE) != 0xF8) return null
        val variable = b.u8(1) and 1 == 1
        val bsCode = b.u8(2) ushr 4
        val srCode = b.u8(2) and 0xF
        val chCode = b.u8(3) ushr 4
        val ssCode = (b.u8(3) ushr 1) and 7
        if (bsCode == 0 || srCode == 15 || chCode > 10 || ssCode == 3 || b.u8(3) and 1 != 0) return null
        var p = 4
        // Номер кадра/сэмпла: расширенный UTF-8 (§9.1.5).
        val first = b.u8(p++)
        var n: Long; var extra: Int
        when {
            first < 0x80 -> { n = first.toLong(); extra = 0 }
            first and 0xE0 == 0xC0 -> { n = (first and 0x1F).toLong(); extra = 1 }
            first and 0xF0 == 0xE0 -> { n = (first and 0x0F).toLong(); extra = 2 }
            first and 0xF8 == 0xF0 -> { n = (first and 0x07).toLong(); extra = 3 }
            first and 0xFC == 0xF8 -> { n = (first and 0x03).toLong(); extra = 4 }
            first and 0xFE == 0xFC -> { n = (first and 0x01).toLong(); extra = 5 }
            first == 0xFE -> { n = 0; extra = 6 }
            else -> return null
        }
        if (p + extra > b.size) return null
        repeat(extra) { val c = b.u8(p++); if (c and 0xC0 != 0x80) return null; n = (n shl 6) or (c and 0x3F).toLong() }
        val bs = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> { if (p >= b.size) return null; b.u8(p++) + 1 }
            7 -> { if (p + 1 >= b.size) return null; ((b.u8(p) shl 8) or b.u8(p + 1)).also { p += 2 } + 1 }
            else -> 256 shl (bsCode - 8)
        }
        val sr = when (srCode) {
            0 -> si.sampleRate
            1 -> 88200; 2 -> 176400; 3 -> 192000; 4 -> 8000; 5 -> 16000; 6 -> 22050; 7 -> 24000
            8 -> 32000; 9 -> 44100; 10 -> 48000; 11 -> 96000
            12 -> { if (p >= b.size) return null; b.u8(p++) * 1000 }
            13 -> { if (p + 1 >= b.size) return null; ((b.u8(p) shl 8) or b.u8(p + 1)).also { p += 2 } }
            else -> { if (p + 1 >= b.size) return null; ((b.u8(p) shl 8) or b.u8(p + 1)).also { p += 2 } * 10 }
        }
        if (p >= b.size) return null
        if (Crc.crc8(b, 0, p) != b.u8(p)) return null
        return Hdr(p + 1, bs, n, variable, sr, if (chCode <= 7) chCode + 1 else 2)
    }

    /**
     * Найти все кадры. Конец кадра — следующий заголовок с верным CRC-8, при котором CRC-16 предыдущего
     * кадра (последние 2 байта) совпадает с CRC-16 его содержимого (§9.3).
     */
    fun scan(src: RandomAccessSource): FlacFile {
        val (si, audio) = readHeader(src)
        val w = SrcWindow(src)
        val frames = ArrayList<FlacFrame>()
        var errors = 0
        var pos = audio
        val end = w.size
        var h = header(w, pos, si)
        while (h == null && pos < end) { pos++; h = header(w, pos, si) }
        while (h != null) {
            val start = pos
            var crc = 0
            var q = start
            val minNext = start + h.len + 1
            // crc покрывает [start, q−2)
            var found: Hdr? = null; var foundAt = end
            while (q < minNext) { if (q - 2 >= start) crc = crc16Step(crc, w.u8(q - 2)); q++ }
            while (q + 2 <= end) {
                if (w.u8(q) == 0xFF && (w.u8(q + 1) and 0xFE) == 0xF8) {
                    val stored = (w.u8(q - 2) shl 8) or w.u8(q - 1)
                    if (stored == crc) { val nh = header(w, q, si); if (nh != null) { found = nh; foundAt = q; break } }
                }
                crc = crc16Step(crc, w.u8(q - 2)); q++
            }
            if (found == null) {
                // Последний кадр: до конца файла.
                while (q < end) { crc = crc16Step(crc, w.u8(q - 2)); q++ }
                val stored = (w.u8(end - 2) shl 8) or w.u8(end - 1)
                if (stored != crc) errors++
                foundAt = end
            }
            frames += FlacFrame(start, (foundAt - start).toInt(), h.blockSize, h.sampleRate, h.channels, h.number, h.variable, h.len)
            pos = foundAt; h = found
        }
        return FlacFile(si, audio, frames, errors)
    }

    private val T16 = IntArray(256) { i -> var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }; c and 0xFFFF }
    private fun crc16Step(c: Int, b: Int): Int = ((c shl 8) xor T16[((c ushr 8) xor b) and 0xFF]) and 0xFFFF

    /**
     * Перенумеровать кадр: newNumber — номер кадра (фиксированный блок) или номер первого сэмпла (переменный блок).
     * Заголовок пересобирается (длина UTF-8 может измениться), CRC-8 и CRC-16 пересчитываются (§9.1.8, §9.3).
     */
    fun renumber(frame: ByteArray, newNumber: Long): ByteArray {
        // Длина старого номера.
        val first = frame.u8(4)
        val extra = when { first < 0x80 -> 0; first and 0xE0 == 0xC0 -> 1; first and 0xF0 == 0xE0 -> 2; first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4; first and 0xFE == 0xFC -> 5; else -> 6 }
        val oldNumEnd = 5 + extra
        val bsCode = frame.u8(2) ushr 4; val srCode = frame.u8(2) and 0xF
        val tail = (if (bsCode == 6) 1 else if (bsCode == 7) 2 else 0) + (if (srCode == 12) 1 else if (srCode == 13 || srCode == 14) 2 else 0)
        val oldHdrEnd = oldNumEnd + tail          // позиция CRC-8
        val out = ByteBuf(frame.size + 8)
        out.bytes(frame, 0, 4)
        val v = newNumber
        if (v < 0x80) out.u8(v.toInt()) else {
            val nBytes = when { v < 0x800 -> 2; v < 0x10000 -> 3; v < 0x200000 -> 4; v < 0x4000000 -> 5; v < 0x80000000L -> 6; else -> 7 }
            out.u8(if (nBytes == 7) 0xFE else ((0xFF00 shr nBytes) and 0xFF) or (v ushr (6 * (nBytes - 1))).toInt())
            for (i in nBytes - 2 downTo 0) out.u8(0x80 or ((v ushr (6 * i)).toInt() and 0x3F))
        }
        out.bytes(frame, oldNumEnd, tail)
        out.u8(Crc.crc8(out.data, 0, out.size))
        out.bytes(frame, oldHdrEnd + 1, frame.size - (oldHdrEnd + 1) - 2)
        out.u16be(Crc.crc16(out.data, 0, out.size))
        return out.toByteArray()
    }

    /**
     * Записать .flac из выбранных кадров (быстрая обрезка/копирование): STREAMINFO с новым total_samples,
     * MD5 = 0 (RFC 9639 §8.2: «0 — подпись неизвестна»), кадры перенумерованы с 0.
     */
    fun writeStream(sink: SeekableSink, si: FlacStreamInfo, frames: List<ByteArray>, frameSamples: IntArray, totalSamples: Long) {
        val h = ByteBuf(64)
        h.ascii("fLaC").u8(0x80).u24be(34)
        var minF = Int.MAX_VALUE; var maxF = 0
        val renum = ArrayList<ByteArray>(frames.size)
        var sampleNo = 0L
        for ((i, f) in frames.withIndex()) {
            val variable = f.u8(1) and 1 == 1
            val r = renumber(f, if (variable) sampleNo else i.toLong())
            renum += r; sampleNo += frameSamples[i]
            minF = minOf(minF, r.size); maxF = maxOf(maxF, r.size)
        }
        val nsi = si.copy(minFrame = if (frames.isEmpty()) 0 else minF, maxFrame = maxF, totalSamples = totalSamples, md5 = ByteArray(16))
        h.bytes(nsi.toBytes())
        sink.write(h.data, 0, h.size)
        for (r in renum) sink.write(r, 0, r.size)
        sink.flush()
    }
}
