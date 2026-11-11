package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.format.int
import com.rassul.audioeditor.core.format.l
import com.rassul.audioeditor.core.format.long
import com.rassul.audioeditor.core.format.m
import com.rassul.audioeditor.core.format.raw.MP3_DECODER_DELAY
import com.rassul.audioeditor.core.format.raw.Mp3Header
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит FastTrimPlanner (timeline.md ф. 13, «FR-23: план»), Info-кадра MP3 (LAME rev 1) и сверка с эталоном. */
class FastTrimAuditTest {
    private fun startsOf(sizes: IntArray, D: Long) = LongArray(sizes.size).also { for (i in 1 until sizes.size) it[i] = it[i - 1] + sizes[i - 1]; for (i in it.indices) it[i] -= D }

    /** 300 случайных планов (AAC/MP3/AC-3/Opus/FLAC) против fast_trim_plan + fast_trim_message эталона. */
    @Test fun matchesReference() {
        for (v0 in A.vec["fast_trim"].l()) {
            val v = v0.m()
            val sizes = v["sizes"].l().map { it.int() }.toIntArray()
            val p = FastTrimPlanner.planFull(startsOf(sizes, v["D"].long()), sizes, v["a"].long(), v["b"].long(), v["fs"].int(),
                v["W"].int(), v["Ws"].int(), v["L"].long(), true)
            val tag = "${v["codec"]} a=${v["a"]} b=${v["b"]} D=${v["D"]}"
            assertEquals(tag, v["first_pkt"].int(), p.firstPacket)
            assertEquals(tag, v["first_audible_pkt"].int(), p.firstAudiblePacket)
            assertEquals(tag, v["last_pkt"].int(), p.lastPacket)
            assertEquals(tag, v["A"].long(), p.start); assertEquals(tag, v["B"].long(), p.end)
            assertEquals(tag, v["hidden"].long(), p.hidden)
            assertEquals(tag, v["message"], p.message)
        }
    }

    /** Свойство: [A,B) ⊇ [a,b), расширение не больше пакета с каждой стороны, разгон — ровно по правилу кодека. */
    @Test fun outwardRoundingProperty() {
        val r = A.Rng(23)
        repeat(3000) {
            val opus = r.bool()
            val D = if (opus) 312L else r.pick(listOf(0L, 1024L, 1105L, 2112L))
            val L = r.long(1, 300_000)
            val sz = ArrayList<Int>(); var tot = 0L
            while (tot < L + D) { val s = if (opus) r.pick(listOf(120, 240, 480, 960, 1920, 2880)) else 1024; sz += s; tot += s }
            val sizes = sz.toIntArray(); val u = startsOf(sizes, D)
            val a = r.long(0, L - 1); val b = r.long(a + 1, L)
            val W = r.range(0, 4)
            val p = FastTrimPlanner.planFull(u, sizes, a, b, 48000, W, if (opus) 3840 else 0, L, true)
            val tag = "L=$L D=$D a=$a b=$b W=$W opus=$opus"
            assertTrue(tag, p.start <= a && p.end >= b && p.start >= 0 && p.end <= L)
            // A — начало пакета, содержащего a (или 0 внутри задержки); B — конец пакета, содержащего b−1 (или L).
            val ia = p.firstAudiblePacket; val il = p.lastPacket
            assertTrue(tag, u[ia] <= a && a < u[ia] + sizes[ia]); assertEquals(tag, maxOf(0L, u[ia]), p.start)
            assertTrue(tag, u[il] <= b - 1 && b - 1 < u[il] + sizes[il]); assertEquals(tag, minOf(L, u[il] + sizes[il]), p.end)
            assertTrue(tag, a - p.start < sizes[ia] && p.end - b < sizes[il])
            if (opus) {
                val pre = (p.firstPacket until ia).sumOf { sizes[it].toLong() }
                assertTrue(tag, pre >= 3840 || p.firstPacket == 0)                                  // RFC 7845 §4.6
                assertTrue(tag, p.firstPacket == ia || pre - sizes[p.firstPacket] < 3840)           // минимально
                assertTrue(tag, p.hidden <= 65535)                                                  // поле pre_skip u16
            } else assertEquals(tag, maxOf(0, ia - W), p.firstPacket)
            assertEquals(tag, p.start - u[p.firstPacket], p.hidden)
            assertTrue(tag, p.hidden >= 0)
        }
    }

    @Test fun invalidSelection() {
        val u = LongArray(4) { it * 1024L }; val s = IntArray(4) { 1024 }
        for ((a, b) in listOf(10L to 10L, 20L to 10L, -1L to 5L))
            try { FastTrimPlanner.planFull(u, s, a, b, 48000, 1, 0, 4096, true); throw AssertionError("$a,$b") } catch (e: IllegalArgumentException) { }
        try { FastTrimPlanner.planFull(LongArray(0), IntArray(0), 0, 1, 48000, 1, 0, 1, true); throw AssertionError() } catch (e: IllegalArgumentException) { }
    }

    /** Info-кадр MP3: delay/padding по 12 бит на смещении +21 тега LAME, CRC-16/ARC по байтам до поля CRC. */
    @Test fun infoFrameFields() {
        val tpl = Mp3Header(0xFFFB9064.toInt())            // MPEG-1 L3, 128 кбит/с, 44,1 кГц, joint stereo
        assertTrue(tpl.valid); assertEquals(44100, tpl.sampleRate)
        for ((d, p) in listOf(0 to 0, 4095 to 0, 0 to 4095, 4095 to 4095, 576 to 1556, 1775 to 529)) {
            val f = Mp3Scanner.buildInfoFrame(tpl, 39, 39 * 417L, d, p)
            val h = Mp3Header(((f.u8(0) shl 24) or (f.u8(1) shl 16) or (f.u8(2) shl 8) or f.u8(3)))
            assertTrue(h.valid); assertEquals(h.frameLength, f.size); assertTrue(!h.protection)
            val x = 4 + h.sideInfoLength
            assertEquals("Info", String(f, x, 4, Charsets.ISO_8859_1))
            val lame = x + 8 + 4 + 4 + 100 + 4
            assertEquals("LAME", String(f, lame, 4, Charsets.ISO_8859_1))
            val v = (f.u8(lame + 21) shl 16) or (f.u8(lame + 22) shl 8) or f.u8(lame + 23)
            assertEquals(d, v ushr 12); assertEquals(p, v and 0xFFF)
            val crc = (f.u8(lame + 34) shl 8) or f.u8(lame + 35)
            assertEquals(A.crcBitwise(f.copyOf(lame + 34), 16, 0x8005, reflect = true), crc.toLong())
            assertEquals(190, lame + 34)                                                            // «первые 190 байт» (MPEG-1 стерео)
            // Свой сканер читает тег обратно.
            val s = Mp3Scanner.scan(ByteArraySource(f))
            assertEquals(d, s.info!!.delay); assertEquals(p, s.info!!.padding); assertEquals(39L, s.info!!.frames)
        }
        for ((d, p) in listOf(4096 to 0, 0 to 4096, -1 to 0))
            try { Mp3Scanner.buildInfoFrame(tpl, 1, 417, d, p); throw AssertionError("$d $p") } catch (e: IllegalArgumentException) { }
    }

    /**
     * Свойство (ffmpeg): случайные выделения на MP3-фикстуре → разгон по bit reservoir, Info-кадр с
     * delay = hidden − 529, padding = K·1152 − delay − (B − A) → ffmpeg декодирует ровно B − A сэмплов, совпадающих с исходником.
     */
    @Test fun mp3RandomCutsViaFfmpeg() {
        T.need("ffmpeg")
        val fx = T.fixture("mp3_lame_44100_2ch_45100.mp3")
        val src = T.src("mp3_lame_44100_2ch_45100.mp3")
        val s = Mp3Scanner.scan(src)
        val D = (s.info!!.delay!! + MP3_DECODER_DELAY).toLong()
        val L = s.lameLength!!
        val sizes = IntArray(s.frames.size) { 1152 }
        val u = startsOf(sizes, D)
        val orig = T.decodeF32(fx)
        val r = A.Rng(1152)
        var tested = 0; var tooLong = 0
        repeat(12) { iter ->
            val a = if (iter == 0) 0L else r.long(0, L - 2000); val b = if (iter == 1) L else r.long(a + 1, minOf(L, a + 30000))
            val probe = FastTrimPlanner.planFull(u, sizes, a, b, 44100, 0, 0, L, true)
            val W = Mp3Scanner.warmupFrames(s.frames, probe.firstAudiblePacket)
            assertTrue(W >= minOf(2, probe.firstAudiblePacket))
            val p = FastTrimPlanner.planFull(u, sizes, a, b, 44100, W, 0, L, true)
            val sel = s.frames.subList(p.firstPacket, p.lastPacket + 1)
            val K = sel.size
            val delay = (p.hidden - MP3_DECODER_DELAY).toInt()
            val pad = (K * 1152L - delay - p.length).toInt()
            if (delay > 4095) { tooLong++; return@repeat }
            val info = Mp3Scanner.buildInfoFrame(s.frames[0].header, K, sel.sumOf { it.size.toLong() }, delay, pad)
            val out = T.tmp(".mp3")
            FileSink(out).use { o -> o.write(info); for (f in sel) o.write(Src.readFully(src, f.offset, f.size)!!) }
            val cut = T.decodeF32(out)
            val tag = "a=$a b=$b A=${p.start} B=${p.end} W=$W hidden=${p.hidden}"
            assertEquals(tag, p.length, cut.size / 2L)
            var err = 0.0; var en = 1e-30
            for (i in cut.indices) { val ref = orig[p.start.toInt() * 2 + i].toDouble(); err += (cut[i] - ref) * (cut[i] - ref); en += ref * ref }
            assertTrue("$tag SNR ${10 * Math.log10(en / err)} дБ", err <= en * 1e-6)
            tested++; out.delete()
        }
        assertTrue("проверено $tested, не поместилось в 12 бит: $tooLong", tested >= 8)
    }
}
