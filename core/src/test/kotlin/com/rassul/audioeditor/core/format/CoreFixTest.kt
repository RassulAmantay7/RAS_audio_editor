package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.mp4.Mp4BoxReader
import com.rassul.audioeditor.core.format.mp4.Mp4FormatException
import com.rassul.audioeditor.core.format.mp4.Mp4Timing
import com.rassul.audioeditor.core.format.raw.MP3_DECODER_DELAY
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Исправления по аудиту форматов (timeline.md, «Аудит кода форматов», «Не исправлено»). */
class CoreFixTest {

    /** Обычный случай MP3 (вектор fr23_fast_trim[mp3]): planFastTrim = прежний ручной расчёт, разгон скрыт. */
    @Test fun mp3PlanNormalUnchanged() {
        val src = T.src("mp3_lame_44100_2ch_45100.mp3")
        val s = Mp3Scanner.scan(src)
        val t = Mp3Scanner.planFastTrim(s, 1105, 14567, 30000, 45100)
        assertTrue(t.warmupHidden)
        assertEquals(13871L, t.plan.start); assertEquals(31151L, t.plan.end); assertEquals(27, t.plan.lastPacket)
        val starts = LongArray(s.frames.size) { it * 1152L - 1105 }
        val w = Mp3Scanner.warmupFrames(s.frames, 11 + 1)   // i_a = 12
        val ref = FastTrimPlanner.planFull(starts, IntArray(starts.size) { 1152 }, 14567, 30000, 44100, w, 0, 45100, true)
        assertEquals(ref, t.plan)
        // Формула timeline.md: delay = hidden − 529, padding = K·1152 − delay − (B − A).
        val k = t.audioFrames
        assertEquals((ref.hidden - MP3_DECODER_DELAY).toInt(), t.info.delay)
        assertEquals((k * 1152L - t.info.delay - ref.length).toInt(), t.info.padding)
        val bytes = s.frames.subList(t.plan.firstPacket, t.plan.lastPacket + 1).sumOf { it.size.toLong() }
        assertArrayEquals(Mp3Scanner.buildInfoFrame(s.frames[0].header, k, bytes, t.info.delay, t.info.padding), Mp3Scanner.buildInfoFrame(s, t))
        // Понятная ошибка вместо падения в buildInfoFrame.
        try { Mp3Scanner.infoDelayPadding(4625, 1000, 10, 1152); throw AssertionError() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("529…4624")) }
    }

    /** Низкий битрейт (32 кбит/с моно, libmp3lame): разгон ≥ 5 кадров → запасной путь, без исключения, честный текст. */
    @Test fun mp3LowBitrateFallback() {
        T.need("ffmpeg")
        val f = T.tmp(".mp3")
        T.ffmpeg("-f", "lavfi", "-i", "anoisesrc=d=3:c=pink:r=44100:a=0.5:seed=7", "-ac", "1", "-c:a", "libmp3lame", "-b:a", "32k", f.path)
        val src = FileSource(f)
        val s = Mp3Scanner.scan(src)
        val g = GaplessProbe.probe(src)!!
        val D = g.delayFrames; val L = s.lameLength!!
        val starts = LongArray(s.frames.size) { it * 1152L - D }
        val ia = (20 until s.frames.size - 30).first { Mp3Scanner.warmupFrames(s.frames, it) >= 5 }
        val a = starts[ia] + 300; val b = a + 20000
        val t = Mp3Scanner.planFastTrim(s, D, a, b, L)
        assertFalse(t.warmupHidden)
        val iS = t.plan.firstPacket
        assertEquals(ia - Mp3Scanner.warmupFrames(s.frames, ia), iS)
        assertEquals(starts[iS] + 4624, t.plan.start)                     // скрыто максимум: delay 4095 + 529
        assertTrue(t.plan.start < starts[ia])
        assertEquals(4095, t.info.delay)
        assertEquals(t.audioFrames * 1152L - 4095 - t.plan.length, t.info.padding.toLong())
        // Текст: сдвиг = разность показанных мс.
        val da = FastTrimPlanner.message(a, b, t.plan.start, t.plan.end, 44100)
        assertEquals(da, t.plan.message)
        val ms = { n: Long -> (2 * n * 1000 + 44100) / 88200 }
        assertTrue(t.plan.message, t.plan.message.contains("на ${ms(a) - ms(t.plan.start)} мс раньше выделения"))
        // Файл: Info + кадры i_s…i_l; ffmpeg декодирует ровно B − A, со слышимого кадра i_a — как исходник.
        val out = T.tmp(".mp3")
        FileSink(out).use { o ->
            o.write(Mp3Scanner.buildInfoFrame(s, t))
            for (fr in s.frames.subList(iS, t.plan.lastPacket + 1)) o.write(Src.readFully(src, fr.offset, fr.size)!!)
        }
        assertEquals(t.plan.length, Mp3Scanner.scan(FileSource(out)).lameLength)
        val cut = T.decodeF32(out); val orig = T.decodeF32(f)
        assertEquals(t.plan.length.toInt(), cut.size)
        val i0 = (starts[ia] - t.plan.start).toInt()
        var err = 0.0; var en = 0.0
        for (i in i0 until cut.size) { val r = orig[t.plan.start.toInt() + i].toDouble(); err += (cut[i] - r) * (cut[i] - r); en += r * r }
        assertTrue("SNR ${10 * Math.log10(en / err)}", err < en * 1e-6)
    }

    /** Mp4Timing: лишние пакеты (Σstts ≠ delay + N) — понятная ошибка; граничные случаи проходят. */
    @Test fun mp4TooManyPackets() {
        try { Mp4Timing.compute(1000, 0, 44100, 3, 1024); throw AssertionError() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("Пакетов больше")) }
        try { Mp4Timing.compute(0, 0, 44100, 1, 1024); throw AssertionError() } catch (e: IllegalArgumentException) { }
        val t = Mp4Timing.compute(2049, 0, 44100, 3, 1024)
        assertEquals(2049L, t.stts.sumOf { it[0] * it[1] })
        assertEquals(1L, t.stts.last()[1])
        assertEquals(3072L, Mp4Timing.compute(2048, 1024, 44100, 3, 1024).mdhdDuration)
        assertEquals(0L, Mp4Timing.compute(0, 0, 44100, 0, 1024).mdhdDuration)
    }

    /** Испорченные счётчики stsz/stco: быстрое Mp4FormatException (без IntArray(2³¹)), GaplessProbe → null. */
    @Test fun mp4CorruptCounters() {
        val good = T.fixture("aac_44100_2ch_45100.m4a").readBytes()
        val stbl = Mp4BoxReader.parse(ByteArraySource(good)).first { it.type == "moov" }.path("trak", "mdia", "minf", "stbl")!!
        fun corrupt(type: String, at: Int, v: Long): ByteArray {
            val b = good.copyOf(); val p = (stbl.child(type)!!.bodyOffset + at).toInt()
            for (i in 0 until 4) b[p + i] = (v ushr (24 - 8 * i)).toByte()
            return b
        }
        val cases = listOf(
            corrupt("stsz", 8, 0x7FFFFFFF), corrupt("stsz", 8, 0xFFFFFFFFL),           // число сэмплов
            corrupt("stsz", 4, 1).let { b -> val p = (stbl.child("stsz")!!.bodyOffset + 8).toInt(); for (i in 0 until 4) b[p + i] = 0x7F; b },
            corrupt("stco", 4, 0x7FFFFFFF), corrupt("stsc", 4, 0x10000000), corrupt("stts", 4, 0xFFFFFFF0L),
        )
        for ((i, b) in cases.withIndex()) {
            try { Mp4AudioTrack.read(ByteArraySource(b)); throw AssertionError("случай $i") } catch (e: Mp4FormatException) { }
            assertNull("случай $i", GaplessProbe.probe(ByteArraySource(b)))
        }
        // Целый файл читается как раньше.
        assertTrue(GaplessProbe.probe(ByteArraySource(good)) != null)
        assertEquals(45100L, GaplessProbe.mp4Trim(Mp4AudioTrack.read(ByteArraySource(good))!!)!!.length)
    }

    /** FlacWriter: блок в субсете RFC 9639 §7 (≤ 4608 при fs ≤ 48 кГц, ≤ 16 384 иначе). */
    @Test fun flacSubsetBlock() {
        FlacWriter(ByteArraySink(), PcmSpec(48000, 2), 16, 4608)
        FlacWriter(ByteArraySink(), PcmSpec(96000, 2), 24, 16384)
        for ((fs, blk) in listOf(48000 to 4609, 44100 to 8192, 96000 to 16385, 8000 to 65535)) {
            try { FlacWriter(ByteArraySink(), PcmSpec(fs, 2), 16, blk); throw AssertionError("$fs/$blk") }
            catch (e: IllegalArgumentException) { assertTrue(e.message!!, e.message!!.contains("Размер блока FLAC")) }
        }
        assertEquals(4608, FlacWriter.maxSubsetBlockSize(48000)); assertEquals(16384, FlacWriter.maxSubsetBlockSize(48001))
        FlacWriter(ByteArraySink(), PcmSpec(48000, 1), 16, 32768, allowNonSubset = true)   // только для тестов энкодера
    }

    /** ExportPlanner: недопустимая разрядность FLAC → разрядность по исходнику (16 → 16, 24 → 24). */
    @Test fun exportPlannerFlacBits() {
        val p16 = ExportPlanner.plan(PcmSpec(44100, 2), 16, ExportRequest(ExportFormat.FLAC, bits = 32))
        assertEquals(16, p16.bits)
        assertTrue(p16.notes.toString(), p16.notes.any { it.contains("будет 16 бит") })
        val p24 = ExportPlanner.plan(PcmSpec(44100, 2), 24, ExportRequest(ExportFormat.FLAC, bits = 20))
        assertEquals(24, p24.bits)
        assertEquals(24, ExportPlanner.plan(PcmSpec(44100, 2), 16, ExportRequest(ExportFormat.FLAC, bits = 24)).bits)
    }
}
