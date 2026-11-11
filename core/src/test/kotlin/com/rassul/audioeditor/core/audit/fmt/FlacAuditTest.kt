package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.FlacWriter
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.format.bits.toHex
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит FLAC-энкодера (RFC 9639): CRC-8/CRC-16, MD5 (LE, знаковые, ⌈bps/8⌉ байт), side +1 бит,
 * переполнения при 24 бит + side, Rice (метод 0/1), номера кадров «UTF-8», короткий последний блок.
 */
class FlacAuditTest {
    private val check = "123456789".toByteArray()

    @Test fun crcCheckValues() {
        // Каталог CRC (reveng): CRC-8/SMBUS 0xF4; CRC-16/UMTS (=BUYPASS, 0x8005, init 0) 0xFEE8;
        // CRC-16/ARC 0xBB3D; Ogg (0x04C11DB7, init 0, без отражения и xorout) = ~CRC-32/CKSUM-без-длины = 0x89A1897F.
        assertEquals(0xF4, Crc.crc8(check, 0, 9))
        assertEquals(0xFEE8, Crc.crc16(check, 0, 9))
        assertEquals(0xBB3D, Crc.crc16Arc(check, 0, 9))
        assertEquals(0x89A1897F.toInt(), Crc.crc32Ogg(check, 0, 9))
        val r = A.Rng(1)
        repeat(200) {
            val b = r.bytes(r.range(0, 700))
            assertEquals(A.crcBitwise(b, 8, 0x07), Crc.crc8(b, 0, b.size).toLong())
            assertEquals(A.crcBitwise(b, 16, 0x8005), Crc.crc16(b, 0, b.size).toLong())
            assertEquals(A.crcBitwise(b, 16, 0x8005, reflect = true), Crc.crc16Arc(b, 0, b.size).toLong())
            assertEquals(A.crcBitwise(b, 32, 0x04C11DB7), Crc.crc32Ogg(b, 0, b.size).toLong() and 0xFFFFFFFFL)
            // Инкрементальность (страница Ogg считается по кускам).
            val k = if (b.isEmpty()) 0 else r.int(b.size)
            assertEquals(Crc.crc32Ogg(b, 0, b.size), Crc.crc32Ogg(b, k, b.size - k, Crc.crc32Ogg(b, 0, k)))
        }
    }

    private fun encode(spec: PcmSpec, bits: Int, s: IntArray, block: Int, chunk: Int): ByteArray {
        val sink = ByteArraySink()
        val w = FlacWriter(sink, spec, bits, block, allowNonSubset = true)  // проверяется сам энкодер, вкл. блоки вне субсета
        val n = s.size / spec.channels
        var p = 0
        while (p < n) { val k = minOf(chunk, n - p); w.writeInt(s.copyOfRange(p * spec.channels, (p + k) * spec.channels), k); p += k }
        w.finish()
        return sink.toByteArray()
    }

    private fun signal(r: A.Rng, kind: Int, n: Int, ch: Int, bits: Int): IntArray {
        val max = (1 shl (bits - 1)) - 1; val min = -max - 1
        val walk = IntArray(ch)
        return IntArray(n * ch) { i ->
            val t = i / ch; val c = i % ch
            when (kind) {
                0 -> r.range(min, max)                                                 // полный шум: Rice k > 14 → метод 1 (5 бит)
                1 -> if ((t + c) % 2 == 0) max else min                                // side = ±(2^bits − 1): 25 бит при 24
                2 -> { walk[c] = (walk[c] + r.range(-2000, 2000)).coerceIn(min, max); walk[c] }
                3 -> (Math.sin(t * 0.01 * (c + 1)) * max * 0.9).toInt() + r.range(-3, 3)
                4 -> if (t % 777 < 400) max else min                                   // ступени: CONSTANT/FIXED, огромные остатки
                5 -> 0
                6 -> if (c == 0) r.range(min / 2, max / 2) else 0                      // тишина в остальных каналах (CONSTANT)
                else -> if (t % 2 == 0) max else min                                   // L = R = ±max: side = 0, mid = экстремум
            }
        }
    }

    /** Свойство: случайные сигналы 1–8 кан., 16/24 бит, разные блоки → ffmpeg декодирует бит в бит, MD5 = STREAMINFO. */
    @Test fun randomSignalsBitExactViaFfmpeg() {
        T.need("ffmpeg")
        val r = A.Rng(20261111)
        val blocks = listOf(16, 17, 192, 255, 256, 576, 1000, 1152, 4096, 4608, 32768, 65535)
        for (iter in 0 until 28) {
            val ch = if (iter < 8) iter + 1 else r.range(1, 8)
            val bits = if (iter % 2 == 0) 16 else 24
            // ffmpeg 6.1 (flac_parser) молча теряет кадры больше ~200 КБ — и свои тоже (проверено: -frame_size 32768, 6 кан.
            // → 0 сэмплов). Это не ошибка энкодера; FlacWriter по умолчанию пишет блок 4096. Ограничиваем block·C ≤ 65 536.
            val block = r.pick(blocks.filter { it * ch <= 65536 })
            val n = r.pick(listOf(1, 15, block - 1, block, block + 1, r.range(1, 40000)).filter { it >= 1 })
            val kind = iter % 8
            val fs = r.pick(listOf(8000, 11025, 44100, 48000, 96000, 37800, 192000))
            val s = signal(r, kind, n, ch, bits)
            val tag = "iter=$iter ch=$ch bits=$bits block=$block n=$n kind=$kind fs=$fs"
            val f = encode(PcmSpec(fs, ch), bits, s, block, r.range(1, 5000))
            // MD5 по определению RFC 9639 §8.2, посчитанный здесь независимо от энкодера.
            val pcm = A.pcmLe(s, bits / 8)
            val si = FileFacts.flacStreamInfo(ByteArraySource(f))
            assertEquals(tag, A.md5(pcm).toHex(), si.md5Hex)
            assertEquals(tag, n.toLong(), si.totalSamples); assertEquals(ch, si.channels); assertEquals(bits, si.bitsPerSample); assertEquals(fs, si.sampleRate)
            // Свой сканер: все CRC-8/CRC-16 сходятся, номера кадров подряд (проверка «UTF-8» номеров > 127, > 2047).
            val ff = FlacFrames.scan(ByteArraySource(f))
            assertEquals(tag, 0, ff.crcErrors)
            assertEquals(tag, (n + block - 1) / block, ff.frames.size)
            for ((i, fr) in ff.frames.withIndex()) { assertEquals(tag, i.toLong(), fr.number); assertEquals(tag, if (i < ff.frames.size - 1) block else n - i * block, fr.samples) }
            assertTrue(tag, si.minFrame <= si.maxFrame && si.maxFrame == ff.frames.maxOf { it.size })
            // ffmpeg (чужой декодер, проверяет CRC): ровно те же байты PCM, без сообщений об ошибках.
            val file = A.write(f, ".flac")
            val out = A.ffmpegRaw(file, "-c:a", if (bits == 16) "pcm_s16le" else "pcm_s24le", "-f", if (bits == 16) "s16le" else "s24le")
            assertEquals(tag + "\n" + out.stderr, "", out.stderr.trim())
            assertEquals(tag, pcm.size, out.stdout.size)
            assertArrayEquals(tag, pcm, out.stdout)
            file.delete()
        }
    }

    /** Длинный поток с блоком 16: номера кадров 2- и 3-байтовые (≥ 128, ≥ 2048) — ffmpeg без ошибок. */
    @Test fun frameNumbersMultiByte() {
        T.need("ffmpeg")
        val r = A.Rng(7)
        val n = 16 * 2600 + 5
        val s = IntArray(n * 2) { r.range(-300, 300) }
        val f = encode(PcmSpec(48000, 2), 16, s, 16, 4096)
        val ff = FlacFrames.scan(ByteArraySource(f))
        assertEquals(0, ff.crcErrors); assertEquals(2601, ff.frames.size); assertEquals(2600L, ff.frames.last().number)
        val out = A.ffmpegRaw(A.write(f, ".flac"), "-c:a", "pcm_s16le", "-f", "s16le")
        assertEquals("", out.stderr.trim())
        assertArrayEquals(A.pcmLe(s, 2), out.stdout)
    }

    /** Перенумерация кадров (быстрая обрезка FLAC): отрезок из середины, CRC пересчитаны, MD5 = 0, ffmpeg = исходные сэмплы. */
    @Test fun renumberedCutDecodesBitExact() {
        T.need("ffmpeg")
        val r = A.Rng(99)
        for (bits in intArrayOf(16, 24)) {
            val block = 1152; val n = block * 300 + 77
            val s = signal(r, 2, n, 2, bits)
            val src = encode(PcmSpec(44100, 2), bits, s, block, 10000)
            val ff = FlacFrames.scan(ByteArraySource(src))
            for ((i0, i1) in listOf(0 to 1, 127 to 129, 5 to 299, 200 to 301)) {
                val frames = ff.frames.subList(i0, i1)
                val sink = ByteArraySink()
                val total = frames.sumOf { it.samples.toLong() }
                FlacFrames.writeStream(sink, ff.streamInfo, frames.map { src.copyOfRange(it.offset.toInt(), (it.offset + it.size).toInt()) },
                    IntArray(frames.size) { frames[it].samples }, total)
                val cut = sink.toByteArray()
                val si = FileFacts.flacStreamInfo(ByteArraySource(cut))
                assertEquals(total, si.totalSamples); assertTrue(!si.md5Known)
                val sc = FlacFrames.scan(ByteArraySource(cut))
                assertEquals(0, sc.crcErrors); assertEquals((0 until frames.size).map { it.toLong() }, sc.frames.map { it.number })
                val out = A.ffmpegRaw(A.write(cut, ".flac"), "-c:a", if (bits == 16) "pcm_s16le" else "pcm_s24le", "-f", if (bits == 16) "s16le" else "s24le")
                assertEquals("", out.stderr.trim())
                assertArrayEquals("bits=$bits [$i0,$i1)", A.pcmLe(s.copyOfRange(i0 * block * 2, (i0 * block + total.toInt()) * 2), bits / 8), out.stdout)
            }
        }
    }

    @Test fun invalidInput() {
        val sink = ByteArraySink()
        val w = FlacWriter(sink, PcmSpec(48000, 1), 16)
        try { w.writeInt(intArrayOf(32768), 1); throw AssertionError("ожидалась ошибка") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("16 бит")) }
        val w24 = FlacWriter(ByteArraySink(), PcmSpec(48000, 1), 24)
        try { w24.writeInt(intArrayOf(-8388609), 1); throw AssertionError("ожидалась ошибка") } catch (e: IllegalArgumentException) { }
        try { FlacWriter(ByteArraySink(), PcmSpec(48000, 1), 20); throw AssertionError() } catch (e: IllegalArgumentException) { }
        try { FlacWriter(ByteArraySink(), PcmSpec(48000, 1), 16, 15); throw AssertionError() } catch (e: IllegalArgumentException) { }
    }
}
