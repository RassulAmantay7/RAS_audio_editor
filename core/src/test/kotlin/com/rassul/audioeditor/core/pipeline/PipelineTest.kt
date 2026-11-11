package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.format.GaplessProbe
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.int
import com.rassul.audioeditor.core.format.l
import com.rassul.audioeditor.core.format.long
import com.rassul.audioeditor.core.format.m
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTest {
    @Test fun gapFillVectors() {
        // timeline_vectors.json fr7_gap_fill (FR-7, ф. 7).
        for (v0 in T.vectors["fr7_gap_fill"].l()) {
            val v = v0.m()
            val g = PtsGapFiller(v["fs"].int())
            val ops = ArrayList<List<Any>>()
            for (fr in v["frames"].l()) {
                val pts = fr.l()[0].long(); val n = fr.l()[1].int()
                val a = g.onBlock(pts, n)
                if (a.insertSilenceFrames > 0) ops += listOf("zeros", a.insertSilenceFrames)
                if (a.dropFrames > 0) ops += listOf("drop", a.dropFrames.toLong())
                ops += listOf("keep", (n - a.dropFrames).toLong())
            }
            assertEquals(v.toString(), v["ops"].l().map { o -> listOf(o.l()[0] as String, o.l()[1].long()) }, ops)
            assertEquals(v["total"].long(), g.written)
        }
    }

    @Test fun gapFixture100ms() {
        // FR-7 проверка: разрыв 100 мс → длина = исходная + 100 мс ± 1 сэмпл (по меткам пакетов ffprobe).
        T.need("ffprobe")
        val out = String(T.run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=pts,duration", "-of", "csv=p=0",
            T.fixture("ptsgap100ms_pcm_48000_2ch_48000.mkv").path).stdout)
        val g = PtsGapFiller(48000)
        // PCM s16 стерео: сэмплов в пакете = длина/4; pts в мс (шкала MKV 1/1000).
        val sizes = String(T.run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=size", "-of", "csv=p=0",
            T.fixture("ptsgap100ms_pcm_48000_2ch_48000.mkv").path).stdout).lines().filter { it.isNotBlank() }.map { it.trim().toInt() / 4 }
        val pts = out.lines().filter { it.isNotBlank() }.map { it.split(',')[0].trim().toLong() * 1000 }
        for (i in pts.indices) g.onBlock(pts[i], sizes[i])
        assertTrue("written ${g.written}", Math.abs(g.written - (48000 + 4800)) <= 48)   // метки MKV в мс: ±1 мс
    }

    @Test fun spaceVectors() {
        for (v0 in T.vectors["fr10"].l()) {
            val v = v0.m()
            assertEquals(v["required_bytes"].long(), SpaceEstimator.requiredBytes(v["duration_us"].long(), v["fs"].int(), v["ch"].int()))
            val n = Math.floorDiv(-(v["duration_us"].long() * v["fs"].int()), 1_000_000L).let { -it }
            assertEquals(v["cache_bytes"].long(), SpaceEstimator.pcmCacheBytes(n, v["ch"].int()))
        }
        assertEquals("Недостаточно места: нужно 892 МБ, свободно 500 МБ.", SpaceEstimator.check(891_200_000, 500_999_999))
        assertNull(SpaceEstimator.check(891_200_000, 891_200_000))
    }

    /** Вектор написан до решения главной сессии о формате «м:сс.ммм» (timeline.md, «Решения»): «00:00.209» → «0:00.209». */
    private fun decisionFormat(s: String) = s.replace(Regex("(?<= )0(\\d:\\d\\d\\.\\d\\d\\d)"), "$1")

    @Test fun fastTrimVectors() {
        for (v0 in T.vectors["fr23_fast_trim"].l()) {
            val v = v0.m()
            val D = v["D"].long()
            val L = v["L"].long()
            val sizes: IntArray = if (v.containsKey("packet_sizes")) v["packet_sizes"].l().map { it.int() }.toIntArray()
                                  else IntArray(((L + D + v["F"].long() - 1) / v["F"].long()).toInt()) { v["F"].int() }
            val starts = LongArray(sizes.size); for (i in 1 until sizes.size) starts[i] = starts[i - 1] + sizes[i - 1]
            for (i in starts.indices) starts[i] -= D
            val codec = v["codec"] as String
            val p = FastTrimPlanner.planFull(starts, sizes, v["a"].long(), v["b"].long(), v["fs"].int(),
                FastTrimPlanner.warmupPackets(codec), if (codec == "opus") FastTrimPlanner.OPUS_PREROLL_48K else 0, L, true)
            assertEquals(v.toString(), v["first_pkt"].int(), p.firstPacket)
            assertEquals(v["first_audible_pkt"].int(), p.firstAudiblePacket)
            assertEquals(v["last_pkt"].int(), p.lastPacket)
            assertEquals(v["A"].long(), p.start); assertEquals(v["B"].long(), p.end)
            assertEquals(v["hidden"].long(), p.hidden); assertEquals(v["length"].long(), p.length)
            (v["message"] as String?)?.let { assertEquals(decisionFormat(it), p.message) }
        }
        // Контрактная обёртка: AAC, без задержки, 1 кадр разгона.
        val r = FastTrimPlanner.plan(LongArray(10) { it * 1024L }, IntArray(10) { 1024 }, 2048, 4096, 48000, true)
        assertEquals(1, r.firstPacket); assertEquals(4, r.lastPacketExclusive); assertEquals(2048L, r.actualStartFrame); assertEquals(4096L, r.actualEndFrame)
        assertEquals("Фактическое начало 0:00.043 (совпадает с выделением), конец 0:00.085 (совпадает с выделением).", r.message)
        // Сырой AC-3: разгон не скрыть → начало на кадр раньше.
        val raw = FastTrimPlanner.planFull(LongArray(10) { it * 1536L }, IntArray(10) { 1536 }, 4000, 6000, 48000, 1, 0, 15360, false)
        assertEquals(1536L, raw.start); assertEquals(0L, raw.hidden)
    }

    @Test fun timeText() {
        for (v0 in T.vectors["fr_c1_display"].l()) {
            val v = v0.m()
            assertEquals(decisionFormat(" " + v["text"] as String).trim(), TimeText.format(v["n"].long(), v["fs"].int()))
        }
    }

    @Test fun naming() {
        // FR-27: x_edit.m4a, x_edit (2).m4a, x_edit (3).m4a
        val ex = HashSet<String>()
        val names = (1..3).map { FileNaming.next("x", "edit", "m4a", ex).also { ex += it } }
        assertEquals(listOf("x_edit.m4a", "x_edit (2).m4a", "x_edit (3).m4a"), names)
        assertEquals("видео_audio.m4a", FileNaming.next("видео.mp4", "_audio", "m4a", emptySet()))
        assertEquals("a_b_trim (2).mp3", FileNaming.next("a/b", "trim", "mp3", setOf("A_B_TRIM.mp3")))
    }

    @Test fun exportPlanner() {
        val d = ExportPlanner.plan(PcmSpec(44100, 2), 16, ExportRequest())
        assertEquals(ExportFormat.M4A, d.format); assertEquals(192, d.bitrateKbps); assertEquals(44100, d.outSpec.sampleRate); assertTrue(d.notes.isEmpty())
        val o = ExportPlanner.plan(PcmSpec(44100, 2), 16, ExportRequest(ExportFormat.OPUS))
        assertEquals(48000, o.outSpec.sampleRate); assertEquals(128, o.bitrateKbps); assertEquals(1, o.notes.size)
        val hi = ExportPlanner.plan(PcmSpec(96000, 2), 24, ExportRequest(ExportFormat.FLAC))
        assertEquals(48000, hi.outSpec.sampleRate); assertEquals(24, hi.bits); assertTrue(!hi.dither); assertTrue(hi.notes[0].contains("48 кГц"))
        val w = ExportPlanner.plan(PcmSpec(96000, 2), 16, ExportRequest(ExportFormat.WAV))
        assertEquals(96000, w.outSpec.sampleRate); assertEquals(16, w.bits); assertTrue(w.dither)
        assertEquals(32, ExportPlanner.plan(PcmSpec(48000, 1), 32, ExportRequest(ExportFormat.WAV, bits = 32)).bits)
        assertEquals(24, ExportPlanner.plan(PcmSpec(48000, 1), 32, ExportRequest(ExportFormat.WAV)).bits)
        // Д-14: моно 8 кГц — не выше 6·8000·1 = 48 кбит/с, с пометкой.
        val amr = ExportPlanner.plan(PcmSpec(8000, 1), 0, ExportRequest())
        assertEquals(48, amr.bitrateKbps); assertTrue(amr.notes.single().contains("48 кбит/с"))
        assertEquals(44100, ExportPlanner.plan(PcmSpec(37800, 2), 16, ExportRequest()).outSpec.sampleRate)
    }

    @Test fun extractPlanner() {
        assertEquals(ExtractPlanner.Action.COPY_AAC, ExtractPlanner.plan("audio/mp4a-latm", true).action)
        val t = ExtractPlanner.plan("audio/opus", true)
        assertEquals("Перекодирование: Opus → AAC 192 кбит/с", t.note); assertEquals("m4a", t.ext)
        val n = ExtractPlanner.plan("audio/ac3", false)
        assertEquals(ExtractPlanner.Action.AS_IS, n.action); assertEquals("ac3", n.ext)
        assertEquals("Этот телефон не умеет раскодировать звук Dolby Digital (AC-3). Его можно сохранить без изменений, но нельзя редактировать или перекодировать.", n.note)
        assertTrue(ExtractPlanner.plan("audio/flac", true).hint!!.startsWith("Исходник без потерь"))
    }

    @Test fun gaplessTrimmer() {
        // MP3 (fr26): 41·1152 = 47 232 на выходе декодера; delay 1105, padding 1027 → 45 100.
        val t = GaplessTrimmer(1105, 1027, 0, 47232)
        var out = 0L; var first = -1L; var pos = 0L
        for (blk in generateSequence { 1152 }.take(41)) {
            val d = t.dropAtStart(blk); val k = t.keepFrames(blk)
            if (k > 0 && first < 0) first = pos + d
            out += k; pos += blk
        }
        assertEquals(45100L, out); assertEquals(1105L, first)
        // Декодер уже отбросил 529: обрезаем только 576.
        val t2 = GaplessTrimmer(1105, 1027, 529, 47232 - 529L)
        assertEquals(576, t2.dropAtStart(4096))
        // Без известной длины хвост — в конце.
        assertEquals(1027L, GaplessTrimmer(1105, 1027, 0, null).tailToTruncateAtFinish)
    }

    @Test fun mp4InputTrimVectors() {
        // fr26.mp4_*: приоритет elst → iTunSMPB → Σstts (timeline.md).
        val fr = T.vectors["fr26"].m()
        for (k in listOf("mp4_ffmpeg_ms", "mp4_exact", "mp4_apple")) {
            val v = fr[k].m()
            val total = v["stts_total"].long()
            val K = ((total + 1023) / 1024).toInt()
            val stts = listOf(longArrayOf(K - 1L, 1024), longArrayOf(1, total - (K - 1L) * 1024))
            val t = Mp4AudioTrack(v["mvhd_ts"].long(), 0, v["mdhd_ts"].long(), total, listOf(longArrayOf(v["seg"].long(), v["media_time"].long())),
                "mp4a", 2, 44100, null, 0x40, stts, IntArray(K) { 1 }, LongArray(K), null, 1, 1)
            val r = GaplessProbe.mp4Trim(t)!!
            assertEquals(k, v["skip"].long(), r.skip); assertEquals(k, v["length"].long(), r.length)
        }
    }
}
