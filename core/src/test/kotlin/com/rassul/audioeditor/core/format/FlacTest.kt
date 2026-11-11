package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.bits.toHex
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class FlacTest {
    /** Сигнал edit_signal из timeline_vectors.json: x[n,c] = float32(((n·7919 + 13c) mod 2001) − 1000)/1024. */
    private fun editSignal(L: Int, C: Int) = FloatArray(L * C) { i -> val n = i / C; val c = i % C; (((n.toLong() * 7919 + 13 * c) % 2001) - 1000).toFloat() / 1024f }

    private fun encode(spec: PcmSpec, bits: Int, s: IntArray, block: Int = 4096): ByteArray {
        val sink = ByteArraySink()
        val w = FlacWriter(sink, spec, bits, block)
        val n = s.size / spec.channels
        var p = 0
        val step = 1000
        while (p < n) { val k = minOf(step, n - p); w.writeInt(s.copyOfRange(p * spec.channels, (p + k) * spec.channels), k); p += k }
        w.finish()
        return sink.toByteArray()
    }

    @Test fun md5Vectors() {
        // timeline_vectors.json fr33_flac_md5 (сверено рецензентом с STREAMINFO ffmpeg).
        val v = T.vectors["fr33_flac_md5"].l()
        val x = editSignal(4800, 2)
        val s16 = IntArray(x.size) { (x[it] * 32767f).toInt() }                         // усечение к нулю
        val s24 = IntArray(x.size) { Math.rint(x[it].toDouble() * 8388607).toInt() }     // half-even
        for ((i, s) in listOf(s16 to 16, s24 to 24, IntArray(0) to 16).withIndex()) {
            val f = encode(PcmSpec(48000, 2), s.second, s.first)
            val si = FileFacts.flacStreamInfo(ByteArraySource(f))
            assertEquals(v[i].m()["md5"], si.md5Hex)
            assertEquals((s.first.size / 2).toLong(), si.totalSamples)
            assertEquals(s.second, si.bitsPerSample)
        }
    }

    private fun lcg(seed: Long): () -> Int { var x = seed; return { x = x * 6364136223846793005L + 1442695040888963407L; (x ushr 33).toInt() } }

    private fun signal(kind: String, n: Int, ch: Int, bits: Int): IntArray {
        val max = (1 shl (bits - 1)) - 1; val r = lcg(kind.hashCode().toLong() + n)
        return IntArray(n * ch) { i ->
            val t = i / ch; val c = i % ch
            when (kind) {
                "sine" -> (Math.sin(2 * Math.PI * 440.0 * (c + 1) * t / 48000) * max * 0.7).toInt() + (r() % 64)
                "noise" -> (r() % (max + 1))
                "extreme" -> if ((t + c) % 2 == 0) max else -max - 1     // макс. side = 2^bits−1: проверка расширения разрядности side
                "constant" -> if (t < n / 2) 1234 else -77
                "silence" -> 0
                else -> if (c == 0) (t % 300) * 50 else -(t % 300) * 50  // «пила» — хорошо предсказуема
            }
        }
    }

    private class Case(val kind: String, val n: Int, val ch: Int, val bits: Int, val rate: Int, val block: Int)

    @Test fun ffmpegDecodesBitExact() {
        T.need("ffmpeg", "ffprobe")
        val cases = listOf(
            Case("sine", 10007, 2, 16, 44100, 4096), Case("sine", 5000, 2, 24, 48000, 4096), Case("noise", 4097, 1, 16, 8000, 1152),
            Case("extreme", 3000, 2, 16, 48000, 192), Case("extreme", 3000, 2, 24, 96000, 4000), Case("constant", 3000, 3, 16, 37800, 576),
            Case("silence", 2000, 8, 24, 100000, 4096), Case("saw", 9000, 2, 16, 655350, 16), Case("noise", 1, 2, 24, 22050, 4096),
            Case("sine", 7000, 6, 24, 192000, 2304),
        )
        for (c in cases) {
            val s = signal(c.kind, c.n, c.ch, c.bits)
            val bytes = encode(PcmSpec(c.rate, c.ch), c.bits, s, c.block)
            val file = T.tmp(".flac").also { it.writeBytes(bytes) }
            val name = "${c.kind} n=${c.n} ch=${c.ch} bits=${c.bits} fs=${c.rate} bs=${c.block}"
            val out = T.ffmpeg("-i", file.path, "-f", "s32le", "-c:a", "pcm_s32le", "-")
            assertEquals(name + ": " + out.stderr, "", out.stderr.trim())   // ошибки CRC/разбора ffmpeg пишет в stderr
            val bb = java.nio.ByteBuffer.wrap(out.stdout).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val dec = IntArray(out.stdout.size / 4) { bb.getInt() shr (32 - c.bits) }
            assertArrayEquals(name, s, dec)
            // MD5 STREAMINFO = MD5 по сэмплам (LE, ⌈bits/8⌉ байт).
            val w = c.bits / 8
            val raw = ByteArray(s.size * w); for (i in s.indices) for (k in 0 until w) raw[i * w + k] = (s[i] shr (8 * k)).toByte()
            val si = FileFacts.flacStreamInfo(ByteArraySource(bytes))
            assertEquals(name, MessageDigest.getInstance("MD5").digest(raw).toHex(), si.md5Hex)
            assertEquals(c.rate, si.sampleRate); assertEquals(c.ch, si.channels); assertEquals(c.n.toLong(), si.totalSamples)
            assertTrue(si.minFrame in 1..si.maxFrame)
            val p = T.probe(file, "stream=sample_rate,channels,duration_ts,bits_per_raw_sample")
            assertEquals(name, "${c.rate}", p["sample_rate"]); assertEquals("${c.ch}", p["channels"]); assertEquals("${c.bits}", p["bits_per_raw_sample"])
            assertEquals(name, "${c.n}", p["duration_ts"])
        }
    }

    @Test fun compressionComparableToFfmpeg() {
        val s = signal("sine", 48000, 2, 16)
        val b = encode(PcmSpec(48000, 2), 16, s)
        assertTrue("размер ${b.size}", b.size < s.size * 2 * 6 / 10)
        T.need("ffmpeg")
        val wav = T.tmp(".wav"); val ref = T.tmp(".flac")
        FileSink(wav).use { val w = WavWriter(it, PcmSpec(48000, 2), WavSampleFormat.PCM16); w.writeInt(s, 48000); w.finish() }
        T.ffmpeg("-i", wav.path, "-c:a", "flac", ref.path)
        // Только fixed-предикторы (без LPC): допускаем до +15 % к ffmpeg (уровень 5, LPC).
        System.err.println("FLAC: наш ${b.size} Б, ffmpeg ${ref.length()} Б")
        assertTrue("наш ${b.size}, ffmpeg ${ref.length()}", b.size < ref.length() * 115 / 100)
    }

    @Test fun scanAndRenumberFrames() {
        val s = signal("sine", 30000, 2, 16)
        val bytes = encode(PcmSpec(48000, 2), 16, s, 1024)
        val ff = FlacFrames.scan(ByteArraySource(bytes))
        assertEquals(0, ff.crcErrors)
        assertEquals(30, ff.frames.size)                       // ⌈30000/1024⌉
        assertEquals((0L until 30L).toList(), ff.frames.map { it.number })
        assertEquals(30000, ff.frames.sumOf { it.samples })
        // Кадры 3..6 → новый файл, номера с 0, total = 4·1024, MD5 = 0.
        val sel = (3..6).map { bytes.copyOfRange(ff.frames[it].offset.toInt(), (ff.frames[it].offset + ff.frames[it].size).toInt()) }
        val sink = ByteArraySink()
        FlacFrames.writeStream(sink, ff.streamInfo, sel, IntArray(4) { 1024 }, 4096)
        val out = sink.toByteArray()
        val ff2 = FlacFrames.scan(ByteArraySource(out))
        assertEquals(0, ff2.crcErrors); assertEquals(listOf(0L, 1L, 2L, 3L), ff2.frames.map { it.number })
        assertEquals(4096L, ff2.streamInfo.totalSamples); assertTrue(!ff2.streamInfo.md5Known)
        // Номер с многобайтным UTF-8: перенумерация 3 → 1000 и обратно.
        val big = FlacFrames.renumber(sel[0], 100000)
        val back = FlacFrames.renumber(big, 3)
        assertArrayEquals(sel[0], back)
        if (T.has("ffmpeg")) {
            val f = T.tmp(".flac").also { it.writeBytes(out) }
            val o = T.ffmpeg("-i", f.path, "-f", "s16le", "-c:a", "pcm_s16le", "-")
            assertEquals("", o.stderr.trim())
            val bb = java.nio.ByteBuffer.wrap(o.stdout).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val dec = IntArray(o.stdout.size / 2) { bb.getShort().toInt() }
            assertArrayEquals(s.copyOfRange(3 * 1024 * 2, 7 * 1024 * 2), dec)
        }
    }

    @Test fun ffmpegFlacFixtureScan() {
        T.need("ffmpeg")
        val f = T.tmp(".flac")
        T.ffmpeg("-i", T.fixture("wav_s16_44100_2ch_45100.wav").path, "-c:a", "flac", f.path)
        val ff = FlacFrames.scan(FileSource(f))
        assertEquals(0, ff.crcErrors)
        assertEquals(45100L, ff.frames.sumOf { it.samples.toLong() })
        assertEquals(45100L, ff.streamInfo.totalSamples)
    }
}
