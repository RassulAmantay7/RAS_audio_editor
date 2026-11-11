package com.rassul.audioeditor.core.verify

import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.format.flac.FlacStreamInfo
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.model.CoreException

/**
 * Минимальный FLAC-декодер по RFC 9639 (2024): §9.1 заголовок кадра, §9.2 подкадры CONSTANT / VERBATIM /
 * FIXED (§9.2.5, предикторы 0–4) / LPC (§9.2.6), wasted bits (§9.2.2), остаток Rice с разбиением
 * (§9.2.7, параметры 4/5 бит, escape), межканальная декорреляция (§4.2), CRC-8/CRC-16 (§9.1.8, §9.3).
 *
 * Назначение: вход CLI (.flac) и ЗАПАСНАЯ проверка FR-33. Основной независимый декодер для FR-33 —
 * системный MediaCodec на телефоне (timeline.md «FLAC: чем независимо сверять MD5 — выбор»): этот
 * декодер написан тем же автором, что и энкодер, поэтому сам по себе не независим. Его правильность
 * проверяется на файлах, закодированных ffmpeg (в т.ч. LPC), против декодирования ffmpeg.
 */
class FlacDecoder(private val src: RandomAccessSource) {
    val streamInfo: FlacStreamInfo
    private val audioOffset: Long

    init {
        val (si, off) = FlacFrames.readHeader(src)
        streamInfo = si; audioOffset = off
    }

    /** Итог декодирования: кадры (сэмплов на канал), ошибки CRC, MD5 декодированного PCM (RFC 9639 §8.2). */
    class Result(val frames: Long, val crcErrors: Int, val md5: ByteArray, val error: String?)

    /**
     * Декодировать весь поток; [onBlock] получает целые сэмплы (чередование) и число кадров.
     * Ошибка структуры не бросает исключение, а возвращается в [Result.error] (декодирование прекращается).
     */
    fun decode(onBlock: (IntArray, Int) -> Unit = { _, _ -> }): Result {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        val bin = BitIn(src, audioOffset)
        var frames = 0L
        var crcErr = 0
        var error: String? = null
        var buf = IntArray(0)
        var md5buf = ByteArray(0)
        val bps0 = streamInfo.bitsPerSample
        while (!bin.eof()) {
            val start = bin.bytePos
            try {
                val f = decodeFrame(bin) ?: break
                val end = bin.bytePos
                if (!crcOk(start, end)) crcErr++
                val n = f.n; val ch = f.ch
                if (buf.size < n * ch) buf = IntArray(n * ch)
                for (i in 0 until n) for (c in 0 until ch) buf[i * ch + c] = f.data[c][i].toInt()
                val bytes = (f.bps + 7) / 8
                if (md5buf.size < n * ch * bytes) md5buf = ByteArray(n * ch * bytes)
                var k = 0
                for (i in 0 until n * ch) { var v = buf[i]; repeat(bytes) { md5buf[k++] = v.toByte(); v = v shr 8 } }
                md5.update(md5buf, 0, k)
                onBlock(buf, n)
                frames += n
                if (f.bps != bps0) error = "разрядность кадра ${f.bps} ≠ STREAMINFO $bps0"
            } catch (e: EndOfData) {
                error = "поток FLAC оборван на байте ${bin.bytePos}"
                break
            } catch (e: CoreException) {
                error = e.message
                break
            }
        }
        return Result(frames, crcErr, md5.digest(), error)
    }

    /** Весь поток в float [−1, 1] (для CLI; файл целиком в памяти). */
    fun decodeToFloat(): FloatArray {
        val ch = streamInfo.channels
        val scale = 1.0 / Math.scalb(1.0, streamInfo.bitsPerSample - 1)
        val out = FloatArray(maxOf(0L, streamInfo.totalSamples * ch).toInt().coerceAtLeast(0))
        var list = out
        var pos = 0
        val r = decode { s, n ->
            if ((pos + n) * ch > list.size) list = list.copyOf(maxOf((pos + n) * ch, list.size * 2))
            for (i in 0 until n * ch) list[pos * ch + i] = (s[i] * scale).toFloat()
            pos += n
        }
        r.error?.let { throw CoreException("Файл FLAC повреждён: $it") }
        return if (list.size == pos * ch) list else list.copyOf(pos * ch)
    }

    private fun crcOk(start: Long, end: Long): Boolean {
        val len = (end - start).toInt()
        if (len < 3) return false
        val b = ByteArray(len)
        var got = 0
        while (got < len) { val r = src.read(start + got, b, got, len - got); if (r <= 0) return false; got += r }
        val stored = ((b[len - 2].toInt() and 0xFF) shl 8) or (b[len - 1].toInt() and 0xFF)
        return Crc.crc16(b, 0, len - 2) == stored
    }

    private class Frame(val n: Int, val ch: Int, val bps: Int, val data: Array<LongArray>)

    private fun decodeFrame(b: BitIn): Frame? {
        b.alignToByte()
        if (b.eof()) return null
        val hStart = b.bytePos
        // §9.1: синхрокод 0b11111111111110 (14 бит), резерв 1 бит, стратегия блока 1 бит.
        val sync = b.bits(14)
        if (sync != 0x3FFE) throw CoreException("нет синхрокода кадра на байте $hStart")
        if (b.bits(1) != 0) throw CoreException("резервный бит кадра ≠ 0")
        b.bits(1) // blocking strategy
        val bsCode = b.bits(4)
        val srCode = b.bits(4)
        val chCode = b.bits(4)
        val ssCode = b.bits(3)
        b.bits(1)
        // §9.1.5: номер кадра/сэмпла в «UTF-8»-кодировании (до 7 байт).
        val first = b.bits(8)
        var extra = when {
            first and 0x80 == 0 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> throw CoreException("неверный номер кадра")
        }
        while (extra-- > 0) b.bits(8)
        // §9.1.1: размер блока.
        val n = when (bsCode) {
            0 -> throw CoreException("резервный код размера блока")
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> b.bits(8) + 1
            7 -> b.bits(16) + 1
            else -> 256 shl (bsCode - 8)
        }
        // §9.1.2: частота (значение не нужно — берётся из STREAMINFO, но поля надо прочитать).
        when (srCode) { 12 -> b.bits(8); 13, 14 -> b.bits(16); 15 -> throw CoreException("неверный код частоты") }
        // §9.1.4: разрядность.
        val bps = when (ssCode) {
            0 -> streamInfo.bitsPerSample
            1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32
            else -> throw CoreException("резервный код разрядности")
        }
        // §9.1.8: CRC-8 заголовка.
        val hEnd = b.bytePos
        val crc8 = b.bits(8)
        val hb = ByteArray((hEnd - hStart).toInt())
        src.read(hStart, hb, 0, hb.size)
        if (Crc.crc8(hb, 0, hb.size) != crc8) throw CoreException("ошибка CRC-8 заголовка кадра на байте $hStart")
        // §9.1.3 / §4.2: раскладка каналов.
        val ch = if (chCode <= 7) chCode + 1 else if (chCode <= 10) 2 else throw CoreException("резервная раскладка каналов")
        val data = Array(ch) { LongArray(n) }
        for (c in 0 until ch) {
            // Сторона (side) хранится с разрядностью +1 (§4.2).
            val sideBps = when (chCode) { 8 -> if (c == 1) 1 else 0; 9 -> if (c == 0) 1 else 0; 10 -> if (c == 1) 1 else 0; else -> 0 }
            subframe(b, n, bps + sideBps, data[c])
        }
        when (chCode) {
            8 -> for (i in 0 until n) data[1][i] = data[0][i] - data[1][i]                 // left/side: R = L − S
            9 -> for (i in 0 until n) data[0][i] = data[0][i] + data[1][i]                 // side/right: L = S + R
            10 -> for (i in 0 until n) {                                                   // mid/side
                val side = data[1][i]
                val mid = (data[0][i] shl 1) or (side and 1L)
                data[0][i] = (mid + side) shr 1
                data[1][i] = (mid - side) shr 1
            }
        }
        // §9.3: выравнивание и CRC-16 (проверяется по байтам в crcOk).
        b.alignToByte()
        b.bits(16)
        return Frame(n, ch, bps, data)
    }

    private fun subframe(b: BitIn, n: Int, bps0: Int, out: LongArray) {
        // §9.2.1: 1 бит 0, 6 бит тип, 1 бит wasted.
        if (b.bits(1) != 0) throw CoreException("неверный заголовок подкадра")
        val type = b.bits(6)
        var wasted = 0
        if (b.bits(1) == 1) { wasted = 1; while (b.bits(1) == 0) wasted++ }
        val bps = bps0 - wasted
        when {
            type == 0 -> { val v = b.signed(bps); out.fill(v) }                         // CONSTANT §9.2.3
            type == 1 -> for (i in 0 until n) out[i] = b.signed(bps)                    // VERBATIM §9.2.4
            type in 8..12 -> {                                                          // FIXED §9.2.5
                val order = type - 8
                for (i in 0 until order) out[i] = b.signed(bps)
                residual(b, n, order, out)
                for (i in order until n) {
                    // Предсказание фиксированными полиномами (RFC 9639 §9.2.5, табл. 21).
                    out[i] += when (order) {
                        0 -> 0L
                        1 -> out[i - 1]
                        2 -> 2 * out[i - 1] - out[i - 2]
                        3 -> 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3]
                        else -> 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4]
                    }
                }
            }
            type >= 32 -> {                                                             // LPC §9.2.6
                val order = type - 31
                for (i in 0 until order) out[i] = b.signed(bps)
                val prec = b.bits(4) + 1
                if (prec == 16) throw CoreException("неверная точность коэффициентов LPC")
                val shift = b.signed(5).toInt()
                if (shift < 0) throw CoreException("отрицательный сдвиг LPC")
                val coef = LongArray(order) { b.signed(prec) }
                residual(b, n, order, out)
                for (i in order until n) {
                    var s = 0L
                    for (j in 0 until order) s += coef[j] * out[i - 1 - j]
                    out[i] += s shr shift
                }
            }
            else -> throw CoreException("резервный тип подкадра $type")
        }
        if (wasted > 0) for (i in 0 until n) out[i] = out[i] shl wasted
    }

    /** §9.2.7: остаток в out[order until n] (затем к нему прибавляется предсказание). */
    private fun residual(b: BitIn, n: Int, order: Int, out: LongArray) {
        val method = b.bits(2)
        if (method > 1) throw CoreException("резервный метод кодирования остатка")
        val pBits = if (method == 0) 4 else 5
        val escape = (1 shl pBits) - 1
        val po = b.bits(4)
        val parts = 1 shl po
        if (n % parts != 0 || (n shr po) < order) throw CoreException("неверное разбиение остатка")
        var i = order
        for (p in 0 until parts) {
            val cnt = (n shr po) - (if (p == 0) order else 0)
            val k = b.bits(pBits)
            if (k == escape) {
                val raw = b.bits(5)
                for (j in 0 until cnt) out[i++] = if (raw == 0) 0L else b.signed(raw)
            } else {
                for (j in 0 until cnt) {
                    var q = 0L
                    while (b.bits(1) == 0) q++
                    val u = (q shl k) or (if (k > 0) b.bits(k).toLong() else 0L)
                    out[i++] = (u ushr 1) xor -(u and 1L)                                 // зигзаг
                }
            }
        }
    }

    private class EndOfData : RuntimeException()

    /** Чтение битов MSB-first из источника с буфером. */
    private class BitIn(private val src: RandomAccessSource, start: Long) {
        private val buf = ByteArray(1 shl 16)
        private var bufStart = start
        private var bufLen = 0
        private var idx = 0           // байт в буфере
        private var bit = 0           // бит в текущем байте (0 — старший)
        val bytePos: Long get() = bufStart + idx + (if (bit > 0) 1 else 0)

        private fun fill() {
            bufStart += idx; idx = 0
            bufLen = maxOf(0, src.read(bufStart, buf, 0, buf.size))
        }
        fun eof(): Boolean { if (idx >= bufLen && bit == 0) fill(); return idx >= bufLen }
        fun alignToByte() { if (bit != 0) { bit = 0; idx++ } }
        fun bits(n: Int): Int {
            var v = 0
            var left = n
            while (left > 0) {
                if (idx >= bufLen) { if (bit != 0) throw IllegalStateException(); fill(); if (bufLen == 0) throw EndOfData() }
                val cur = buf[idx].toInt() and 0xFF
                val avail = 8 - bit
                val take = minOf(avail, left)
                val part = (cur ushr (avail - take)) and ((1 shl take) - 1)
                v = (v shl take) or part
                left -= take
                bit += take
                if (bit == 8) { bit = 0; idx++ }
            }
            return v
        }
        fun signed(n: Int): Long {
            if (n == 0) return 0L
            var v = 0L
            var left = n
            while (left > 0) { val t = minOf(left, 24); v = (v shl t) or bits(t).toLong(); left -= t }
            return (v shl (64 - n)) shr (64 - n)
        }
    }
}
