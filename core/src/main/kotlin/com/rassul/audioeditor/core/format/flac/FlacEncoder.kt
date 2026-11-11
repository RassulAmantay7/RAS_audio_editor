package com.rassul.audioeditor.core.format.flac

import com.rassul.audioeditor.core.format.bits.BitWriter
import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.io.SeekableSink
import java.security.MessageDigest

/*
 * Собственный FLAC-энкодер. Источник — RFC 9639 «Free Lossless Audio Codec (FLAC)» (2024):
 *  §8.1–8.2 METADATA_BLOCK_HEADER и STREAMINFO; §8.6 VORBIS_COMMENT;
 *  §9.1 заголовок кадра (§9.1.1 размер блока, §9.1.2 частота, §9.1.3 каналы, §9.1.4 разрядность,
 *  §9.1.5 номер кадра «UTF-8», §9.1.8 CRC-8);
 *  §9.2 подкадры: §9.2.1 заголовок, §9.2.3 CONSTANT, §9.2.4 VERBATIM, §9.2.5 FIXED (предикторы 0–4),
 *  §9.2.7 остаток: Rice с разбиением (метод 0 — параметр 4 бита, метод 1 — 5 бит);
 *  §9.3 CRC-16 кадра; §4.2 межканальная декорреляция left/side, side/right, mid/side.
 * Блок фиксированной длины (blocking strategy 0), кадры нумеруются с 0.
 */
class FlacEncoder(
    private val sink: SeekableSink,
    private val sampleRate: Int,
    private val channels: Int,
    private val bps: Int,
    private val blockSize: Int,
    vendor: String = "RAS audio editor",
) {
    private val start = sink.position
    private val streamInfoPos: Long
    private val pending = IntArray(blockSize * channels)
    private var pendingFrames = 0
    private var frameNumber = 0L
    var totalSamples = 0L; private set
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private val md5 = MessageDigest.getInstance("MD5")
    private var md5Buf = ByteArray(0)
    var md5Digest: ByteArray? = null; private set
    private val bytesPerSample = (bps + 7) / 8

    // Рабочие буферы.
    private val chan = Array(channels) { IntArray(blockSize) }
    private val side = IntArray(blockSize)
    private val mid = IntArray(blockSize)
    private val res = IntArray(blockSize)
    private val frameBw = BitWriter(blockSize * channels * 4 + 64)
    private val subBw = Array(4) { BitWriter(blockSize * 4 + 64) }

    init {
        require(channels in 1..8) { "channels=$channels" }                 // RFC 9639 §8.2: 1..8 каналов
        require(bps == 16 || bps == 24) { "bits=$bps" }
        require(blockSize in 16..65535) { "blockSize=$blockSize" }        // §8.2: мин. размер блока ≥ 16
        require(sampleRate in 1..1_048_575) { "sampleRate=$sampleRate" }  // §8.2: 20 бит
        val h = ByteBuf(128)
        h.ascii("fLaC")
        // METADATA_BLOCK_HEADER (§8.1): last=0, type=0 (STREAMINFO), length=34; содержимое — в finish().
        h.u8(0x00).u24be(34)
        val siOff = h.size
        h.zeros(34)
        // VORBIS_COMMENT (§8.6), последний блок: vendor (LE длина + UTF-8), 0 комментариев.
        val v = vendor.toByteArray(Charsets.UTF_8)
        h.u8(0x80 or 4).u24be(4 + v.size + 4)
        h.u32le(v.size).bytes(v).u32le(0)
        streamInfoPos = start + siOff
        sink.write(h.data, 0, h.size)
    }

    fun write(samples: IntArray, frames: Int) {
        val lo = -(1 shl (bps - 1)); val hi = (1 shl (bps - 1)) - 1
        updateMd5(samples, frames, lo, hi)
        var i = 0
        while (i < frames) {
            val n = minOf(frames - i, blockSize - pendingFrames)
            System.arraycopy(samples, i * channels, pending, pendingFrames * channels, n * channels)
            pendingFrames += n; i += n
            if (pendingFrames == blockSize) { encodeFrame(blockSize); pendingFrames = 0 }
        }
        totalSamples += frames
    }

    /** MD5 несжатого сигнала: знаковые сэмплы LE шириной ⌈bps/8⌉ байт, чередование каналов (RFC 9639 §8.2). */
    private fun updateMd5(s: IntArray, frames: Int, lo: Int, hi: Int) {
        val n = frames * channels
        if (md5Buf.size < n * bytesPerSample) md5Buf = ByteArray(n * bytesPerSample)
        var p = 0
        for (i in 0 until n) {
            val v = s[i]
            require(v in lo..hi) { "сэмпл $v вне $bps бит" }
            md5Buf[p++] = v.toByte(); md5Buf[p++] = (v shr 8).toByte()
            if (bytesPerSample == 3) md5Buf[p++] = (v shr 16).toByte()
        }
        md5.update(md5Buf, 0, p)
    }

    fun finish() {
        if (pendingFrames > 0) { encodeFrame(pendingFrames); pendingFrames = 0 }
        val end = sink.position
        md5Digest = md5.digest()
        // STREAMINFO (§8.2): min/max block (16+16), min/max frame (24+24; 0 = неизвестно), fs (20), C−1 (3), bps−1 (5),
        // total samples (36), MD5 (128).
        val bw = BitWriter(64)
        bw.write(blockSize, 16); bw.write(blockSize, 16)
        bw.write(if (frameNumber == 0L) 0 else minFrame, 24); bw.write(maxFrame, 24)
        bw.write(sampleRate, 20); bw.write(channels - 1, 3); bw.write(bps - 1, 5)
        bw.writeLong(totalSamples, 36)
        bw.writeBytes(md5Digest!!, 0, 16)
        val si = bw.bytes()
        check(si.size == 34)
        sink.seek(streamInfoPos); sink.write(si, 0, 34); sink.seek(end); sink.flush()
    }

    // ---------------------------------------------------------------- кадр

    private fun encodeFrame(n: Int) {
        for (c in 0 until channels) { val a = chan[c]; for (i in 0 until n) a[i] = pending[i * channels + c] }
        val fw = frameBw; fw.reset()
        var assignment = channels - 1        // §9.1.3: 0..7 — независимые каналы
        if (channels == 2) {
            val l = chan[0]; val r = chan[1]
            for (i in 0 until n) { side[i] = l[i] - r[i]; mid[i] = (l[i] + r[i]) shr 1 }  // §4.2: side = L−R, mid = (L+R)>>1
            val bL = encodeSubframe(l, n, bps, subBw[0])
            val bR = encodeSubframe(r, n, bps, subBw[1])
            val bS = encodeSubframe(side, n, bps + 1, subBw[2])               // side — на 1 бит шире (§9.2)
            val bM = encodeSubframe(mid, n, bps, subBw[3])
            val costs = longArrayOf(bL + bR, bL + bS, bS + bR, bM + bS)
            var best = 0
            for (k in 1..3) if (costs[k] < costs[best]) best = k
            assignment = if (best == 0) 1 else 7 + best                        // 8 left/side, 9 side/right, 10 mid/side
            writeHeader(fw, n, assignment)
            val order = when (best) { 0 -> intArrayOf(0, 1); 1 -> intArrayOf(0, 2); 2 -> intArrayOf(2, 1); else -> intArrayOf(3, 2) }
            for (k in order) appendBits(fw, subBw[k])
        } else {
            writeHeader(fw, n, assignment)
            for (c in 0 until channels) { encodeSubframe(chan[c], n, bps, subBw[0]); appendBits(fw, subBw[0]) }
        }
        fw.alignByte()                                                       // §9.2: после подкадров — до байта нулями
        val bytes = fw.flushedBuffer(); val len = fw.byteLength
        val crc16 = Crc.crc16(bytes, 0, len)                                 // §9.3: CRC-16 всего кадра до поля CRC
        fw.write(crc16, 16)
        val out = fw.flushedBuffer()           // сначала сбросить накопитель, затем брать длину
        val total = fw.byteLength
        sink.write(out, 0, total)
        if (total < minFrame) minFrame = total
        if (total > maxFrame) maxFrame = total
        frameNumber++
    }

    private fun appendBits(dst: BitWriter, src: BitWriter) {
        // Подкадр не выровнен по байту: переносим целые байты и остаток бит.
        val bits = src.bitLength
        val whole = (bits / 8).toInt()
        val rem = (bits % 8).toInt()
        val pad = if (rem == 0) 0 else 8 - rem
        if (pad > 0) src.write(0, pad)
        val b = src.flushedBuffer()
        for (i in 0 until whole) dst.write(b[i].toInt() and 0xFF, 8)
        if (rem > 0) dst.write((b[whole].toInt() and 0xFF) ushr (8 - rem), rem)
    }

    private fun writeHeader(fw: BitWriter, n: Int, assignment: Int) {
        fw.write(0x3FFE, 14)          // §9.1: синхрокод 0b11111111111110
        fw.write(0, 1)                // резерв
        fw.write(0, 1)                // blocking strategy: 0 = фиксированный размер блока
        val bsCode: Int; var bsExtra = -1; var bsExtraBits = 0
        // §9.1.1: коды размера блока
        when {
            n == 192 -> bsCode = 1
            n == 576 || n == 1152 || n == 2304 || n == 4608 -> bsCode = 2 + Integer.numberOfTrailingZeros(n / 576)
            n >= 256 && n <= 32768 && Integer.bitCount(n) == 1 -> bsCode = 8 + Integer.numberOfTrailingZeros(n / 256)
            n <= 256 -> { bsCode = 6; bsExtra = n - 1; bsExtraBits = 8 }
            else -> { bsCode = 7; bsExtra = n - 1; bsExtraBits = 16 }
        }
        fw.write(bsCode, 4)
        // §9.1.2: коды частоты
        var srExtra = -1; var srExtraBits = 0
        val srCode = when (sampleRate) {
            88200 -> 1; 176400 -> 2; 192000 -> 3; 8000 -> 4; 16000 -> 5; 22050 -> 6; 24000 -> 7
            32000 -> 8; 44100 -> 9; 48000 -> 10; 96000 -> 11
            else -> when {
                sampleRate % 1000 == 0 && sampleRate / 1000 <= 255 -> { srExtra = sampleRate / 1000; srExtraBits = 8; 12 }
                sampleRate <= 65535 -> { srExtra = sampleRate; srExtraBits = 16; 13 }
                sampleRate % 10 == 0 && sampleRate / 10 <= 65535 -> { srExtra = sampleRate / 10; srExtraBits = 16; 14 }
                else -> 0             // взять из STREAMINFO
            }
        }
        fw.write(srCode, 4)
        fw.write(assignment, 4)
        fw.write(if (bps == 16) 4 else 6, 3)   // §9.1.4: 0b100 = 16 бит, 0b110 = 24 бит
        fw.write(0, 1)                // резерв
        writeUtf8Number(fw, frameNumber)
        if (bsExtraBits > 0) fw.write(bsExtra, bsExtraBits)
        if (srExtraBits > 0) fw.write(srExtra, srExtraBits)
        val hb = fw.flushedBuffer()
        fw.write(Crc.crc8(hb, 0, fw.byteLength), 8)   // §9.1.8: CRC-8 заголовка
    }

    /** §9.1.5: номер кадра кодируется как расширенный UTF-8 (до 36 бит). */
    private fun writeUtf8Number(fw: BitWriter, v: Long) {
        if (v < 0x80) { fw.write(v.toInt(), 8); return }
        val nBytes = when { v < 0x800 -> 2; v < 0x10000 -> 3; v < 0x200000 -> 4; v < 0x4000000 -> 5; v < 0x80000000L -> 6; else -> 7 }
        val first = if (nBytes == 7) 0xFE else ((0xFF00 shr nBytes) and 0xFF) or (v ushr (6 * (nBytes - 1))).toInt()
        fw.write(first and 0xFF, 8)
        for (i in nBytes - 2 downTo 0) fw.write(0x80 or ((v ushr (6 * i)).toInt() and 0x3F), 8)
    }

    // ---------------------------------------------------------------- подкадр

    /** Закодировать подкадр в bw (сброшенный), вернуть длину в битах. */
    private fun encodeSubframe(x: IntArray, n: Int, sbps: Int, bw: BitWriter): Long {
        bw.reset()
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) {
            bw.write(0, 1); bw.write(0, 6); bw.write(0, 1)   // §9.2.1: тип 000000 = CONSTANT, wasted bits = 0
            bw.write(x[0], sbps)                             // §9.2.3
            return bw.bitLength
        }
        // Выбор порядка FIXED по оценке размера остатка.
        val maxOrder = minOf(4, n - 1)
        var bestOrder = -1; var bestBits = n.toLong() * sbps   // VERBATIM: n·bps бит (§9.2.4)
        var bestPlan: RicePlan? = null
        for (o in 0..maxOrder) {
            fixedResidual(x, n, o, res)
            val plan = planRice(res, n, o)
            val bits = o.toLong() * sbps + 2 + 4 + plan.bits
            if (bits < bestBits) { bestBits = bits; bestOrder = o; bestPlan = plan }
        }
        if (bestOrder < 0) {
            bw.write(0, 1); bw.write(1, 6); bw.write(0, 1)   // 000001 = VERBATIM
            for (i in 0 until n) bw.write(x[i], sbps)
            return bw.bitLength
        }
        bw.write(0, 1); bw.write(8 or bestOrder, 6); bw.write(0, 1)   // 001xxx = FIXED порядка xxx
        for (i in 0 until bestOrder) bw.write(x[i], sbps)            // разгонные сэмплы (warm-up)
        fixedResidual(x, n, bestOrder, res)
        writeRice(bw, res, n, bestOrder, bestPlan!!)
        return bw.bitLength
    }

    /**
     * Остаток фиксированного предиктора (RFC 9639 §9.2.5, табл. «Fixed predictor»):
     * 0: x[i]; 1: x[i]−x[i−1]; 2: x[i]−2x[i−1]+x[i−2]; 3: x[i]−3x[i−1]+3x[i−2]−x[i−3]; 4: x[i]−4x[i−1]+6x[i−2]−4x[i−3]+x[i−4].
     * Для 24 бит (side — 25 бит) |остаток| ≤ 16·2^24 = 2^28 — помещается в Int.
     */
    private fun fixedResidual(x: IntArray, n: Int, o: Int, r: IntArray) {
        when (o) {
            0 -> for (i in 0 until n) r[i] = x[i]
            1 -> for (i in 1 until n) r[i] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) r[i] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) r[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            else -> for (i in 4 until n) r[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    private class RicePlan(val partitionOrder: Int, val params: IntArray, val method: Int, val bits: Long)

    /**
     * Подбор порядка разбиения и параметров Rice. Оценка числа бит раздела из c отсчётов с суммой s
     * «свёрнутых» значений u = 2r (r ≥ 0) / −2r−1 (r < 0) (§9.2.7.3): bits(k) ≈ c·(k+1) + (s >> k).
     * Разбиение допустимо, если n делится на 2^po и в первом разделе (n>>po) − order > 0 отсчётов (§9.2.7.2).
     */
    private fun planRice(r: IntArray, n: Int, order: Int): RicePlan {
        var maxPo = 0
        while (maxPo < 8 && (n and ((1 shl (maxPo + 1)) - 1)) == 0 && (n shr (maxPo + 1)) > order) maxPo++
        val parts = 1 shl maxPo
        val sums = LongArray(parts)
        val psize = n shr maxPo
        for (p in 0 until parts) {
            var s = 0L
            val a = if (p == 0) order else p * psize
            for (i in a until (p + 1) * psize) { val v = r[i]; s += ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL }
            sums[p] = s
        }
        var best: RicePlan? = null
        var cur = sums
        for (po in maxPo downTo 0) {
            val np = 1 shl po
            if (po < maxPo) { val nx = LongArray(np); for (p in 0 until np) nx[p] = cur[2 * p] + cur[2 * p + 1]; cur = nx }
            val size = n shr po
            val params = IntArray(np)
            var bits = 0L
            var maxK = 0
            for (p in 0 until np) {
                val c = if (p == 0) size - order else size
                val (k, b) = bestK(cur[p], c)
                params[p] = k; bits += b; if (k > maxK) maxK = k
            }
            val method = if (maxK > 14) 1 else 0
            bits += np.toLong() * (if (method == 0) 4 else 5)
            if (best == null || bits < best.bits) best = RicePlan(po, params, method, bits)
        }
        return best!!
    }

    private fun bestK(s: Long, c: Int): Pair<Int, Long> {
        if (c <= 0) return 0 to 0L
        var k0 = 0
        val mean = s / c
        while (k0 < 30 && (1L shl (k0 + 1)) <= mean) k0++
        var bk = 0; var bb = Long.MAX_VALUE
        for (k in maxOf(0, k0 - 1)..minOf(30, k0 + 1)) {
            val b = c.toLong() * (k + 1) + (s shr k)
            if (b < bb) { bb = b; bk = k }
        }
        return bk to bb
    }

    /** §9.2.7: метод (2 бита), порядок разбиения (4 бита), разделы: параметр (4/5 бит) + коды Rice. */
    private fun writeRice(bw: BitWriter, r: IntArray, n: Int, order: Int, plan: RicePlan) {
        bw.write(plan.method, 2)
        bw.write(plan.partitionOrder, 4)
        val np = 1 shl plan.partitionOrder
        val size = n shr plan.partitionOrder
        val pbits = if (plan.method == 0) 4 else 5
        for (p in 0 until np) {
            val k = plan.params[p]
            bw.write(k, pbits)
            val a = if (p == 0) order else p * size
            val mask = (1 shl k) - 1
            for (i in a until (p + 1) * size) {
                val v = r[i]
                val u = (v shl 1) xor (v shr 31)            // свёртка знака (§9.2.7.3)
                bw.writeUnary(u ushr k)                     // частное — унарно (нули, затем 1)
                if (k > 0) bw.write(u and mask, k)          // k младших бит
            }
        }
    }
}
