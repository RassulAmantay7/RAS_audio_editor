package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.GaplessProbe
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.l
import com.rassul.audioeditor.core.format.long
import com.rassul.audioeditor.core.format.int
import com.rassul.audioeditor.core.format.m
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.mp4.Mp4Timing
import com.rassul.audioeditor.core.format.raw.AdtsScanner
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит MP4/M4A (ISO/IEC 14496-12 §8.6.6, timeline.md ф. 10, FR-25): mvhd = mdhd = fs, elst (media_time в шкале mdhd,
 * segment_duration в шкале mvhd), stts по конвенции ffmpeg (Σ = delay + N), приоритет источников на входе.
 */
class Mp4AuditTest {
    /** Mp4Timing.compute против mp4_edit эталона на 200 случайных наборах (вкл. mvhd ≠ fs). */
    @Test fun timingMatchesReference() {
        for (v0 in A.vec["mp4_edit"].l()) {
            val v = v0.m()
            val t = Mp4Timing.compute(v["n_out"].long(), v["delay"].long(), v["fs"].int(), v["K"].int(), 1024, v["mvhd_ts"].int())
            val tag = v.toString().take(200)
            assertEquals(tag, v["mvhd_timescale"].long(), t.mvhdTimescale.toLong())
            assertEquals(tag, v["mdhd_timescale"].long(), t.mdhdTimescale.toLong())
            assertEquals(tag, v["elst_media_time"].long(), t.elstMediaTime)
            assertEquals(tag, v["elst_segment_duration"].long(), t.elstSegmentDuration)
            assertEquals(tag, v["mdhd_duration"].long(), t.mdhdDuration)
            // Эталон всегда пишет [[K−1,F],[1,last]]; код сливает записи при last = F и опускает [0,F] — сравниваем развёрнуто.
            fun expand(s: List<List<Long>>) = s.flatMap { e -> List(e[0].toInt()) { e[1] } }
            assertEquals(tag, expand(v["stts"].l().map { e -> e.l().map { it.long() } }), expand(t.stts.map { it.toList() }))
            assertTrue(tag, t.stts.all { it[0] > 0 })
            assertEquals(tag, v["K"].long() * 1024 - v["delay"].long() - v["n_out"].long(), t.padding)
        }
    }

    /** GaplessProbe.mp4Trim против mp4_input_trim эталона (elst → iTunSMPB → Σstts). */
    @Test fun inputTrimMatchesReference() {
        for (v0 in A.vec["mp4_input"].l()) {
            val v = v0.m()
            val K = v["K"].int(); val total = v["stts_total"].long()
            val stts = if (K > 1) listOf(longArrayOf(K - 1L, 1024), longArrayOf(1, total - (K - 1L) * 1024)) else listOf(longArrayOf(1, total))
            val t = Mp4AudioTrack(v["mvhd_ts"].long(), 0, v["fs"].long(), total, listOf(longArrayOf(v["seg"].long(), v["media_time"].long())),
                "mp4a", 2, v["fs"].int(), null, 0x40, stts, IntArray(K) { 1 }, LongArray(K), v["smpb"] as String?, 1, 1)
            val r = GaplessProbe.mp4Trim(t)!!
            assertEquals(v.toString(), v["skip"].long(), r.skip)
            assertEquals(v.toString(), v["length"].long(), r.length)
            assertEquals(v.toString(), v["true_length"].long(), r.length)
            assertEquals(K * 1024L - r.skip - r.length, r.padding)
        }
    }

    private val adts by lazy {
        val src = T.src("adts_44100_2ch_45100.aac")
        val fr = AdtsScanner.scan(src)
        Triple(fr.map { Src.readFully(src, it.offset + it.headerSize, it.size - it.headerSize)!! }, AdtsScanner.audioSpecificConfig(fr[0]), fr[0].sampleRate)
    }

    private fun m4a(n: Long, delay: Int): Pair<ByteArray, Int> {
        val (pk, asc, fs) = adts
        val K = ((delay + n + 1023) / 1024).toInt()
        val sink = ByteArraySink()
        val w = Mp4AacWriter(sink, fs, 2, asc)
        for (i in 0 until K) { val p = pk[i % pk.size]; w.writePacket(p, 0, p.size) }
        w.finish(delay, n)
        return sink.toByteArray() to K
    }

    /** Свойство: разные N (кратные и некратные 1024), delay 0/1024/2112 → ffprobe duration_ts = N (±0), свой разбор точен. */
    @Test fun ffprobeDurationExact() {
        T.need("ffprobe")
        val r = A.Rng(14496)
        val ns = mutableListOf(1L, 1023, 1024, 1025, 2048, 45100, 44100, 1024L * 100, 1024L * 100 - 1024, 1024L * 100 - 2112)
        repeat(8) { ns += r.long(1, 3_000_000) }
        for ((i, n) in ns.withIndex()) {
            val delay = listOf(1024, 2112, 0)[i % 3]
            val (bytes, K) = m4a(n, delay)
            // Свой разбор (FR-33 структура — точно).
            val f = FileFacts.mp4Audio(ByteArraySource(bytes))
            assertEquals(44100L, f.mvhdTimescale); assertEquals(44100L, f.mdhdTimescale)
            assertEquals(delay.toLong(), f.elstMediaTime); assertEquals(n, f.elstSegmentDuration); assertEquals(n, f.lengthFrames)
            assertEquals(delay + n, f.sttsTotal); assertEquals(K, f.sampleCount)
            val smpb = Mp4AudioTrack.parseITunSmpb(f.iTunSmpb!!)!!
            assertEquals(listOf(delay.toLong(), K * 1024L - delay - n, n), smpb.toList())
            val g = GaplessProbe.probe(ByteArraySource(bytes))!!
            assertEquals(delay.toLong(), g.delayFrames); assertEquals(K * 1024L - delay - n, g.paddingFrames)
            // ffprobe (чужой парсер).
            val file = A.write(bytes, ".m4a")
            val o = T.run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=duration_ts,time_base,nb_frames", "-of", "default=nw=1", file.path)
            assertEquals("n=$n delay=$delay: ${o.stderr}", "", o.stderr.trim())
            val kv = String(o.stdout).lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("n=$n delay=$delay", "1/44100", kv["time_base"])
            assertEquals("n=$n delay=$delay", n.toString(), kv["duration_ts"])
            assertEquals(K.toString(), kv["nb_frames"])
            file.delete()
        }
    }

    @Test fun invalidInput() {
        // delay + N > K·F — ошибка вызывающего, не молчаливая порча.
        try { Mp4Timing.compute(1025, 0, 44100, 1, 1024); throw AssertionError() } catch (e: IllegalArgumentException) { }
        try { Mp4Timing.compute(-1, 0, 44100, 1, 1024); throw AssertionError() } catch (e: IllegalArgumentException) { }
        val (bytes, _) = m4a(5000, 1024)
        // Усечённый файл без moov: понятная ошибка, а не исключение разбора.
        try { FileFacts.mp4Audio(ByteArraySource(bytes.copyOf(bytes.size / 2))); throw AssertionError() }
        catch (e: com.rassul.audioeditor.core.model.CoreException) { assertNotNull(e.message); assertTrue(e.message!!.contains("M4A")) }
    }
}
