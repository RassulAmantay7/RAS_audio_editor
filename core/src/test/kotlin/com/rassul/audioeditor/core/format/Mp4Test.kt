package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.mp4.Mp4Timing
import com.rassul.audioeditor.core.format.raw.AdtsScanner
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Mp4Test {
    @Test fun timingVectors() {
        // timeline_vectors.json fr25_mp4 (ISO/IEC 14496-12 §8.6.6; ffmpeg-конвенция stts).
        for (v0 in T.vectors["fr25_mp4"].l()) {
            val v = v0.m()
            val t = Mp4Timing.compute(v["n_out"].long(), v["enc_delay"].long(), v["fs"].int(), v["n_frames"].int(), 1024, v["mvhd_timescale"].int())
            assertEquals(v["mdhd_timescale"].int(), t.mdhdTimescale)
            assertEquals(v["elst_media_time"].long(), t.elstMediaTime)
            assertEquals(v["elst_segment_duration"].long(), t.elstSegmentDuration)
            assertEquals(v["mdhd_duration"].long(), t.mdhdDuration)
            assertEquals(v["stts"].l().map { e -> e.l().map { it.long() } }, t.stts.map { it.toList() })
        }
    }

    private fun writeFake(n: Long, delay: Int, fs: Int, packets: Int, ch: Int = 2): ByteArray {
        val sink = ByteArraySink()
        val w = Mp4AacWriter(sink, fs, ch, byteArrayOf(0x12, 0x10))
        val p = byteArrayOf(0x21, 0x00, 0x49)
        repeat(packets) { w.writePacket(p, 0, 3) }
        w.finish(delay, n)
        return sink.toByteArray()
    }

    @Test fun structureOwnParser() {
        // Контрольный пример: N = 45 100, delay 1024, 44,1 кГц, 46 кадров (timeline.md).
        val f = FileFacts.mp4Audio(ByteArraySource(writeFake(45100, 1024, 44100, 46)))
        assertEquals(44100L, f.mvhdTimescale); assertEquals(44100L, f.mdhdTimescale)
        assertEquals(1024L, f.elstMediaTime); assertEquals(45100L, f.elstSegmentDuration); assertEquals(45100L, f.lengthFrames)
        assertEquals(46124L, f.sttsTotal); assertEquals(46, f.sampleCount); assertEquals("mp4a", f.codec); assertEquals(2, f.channels)
        assertEquals(listOf(listOf(45L, 1024L), listOf(1L, 44L)), f.stts.map { it.toList() })
        assertEquals(2, f.audioObjectType)
        val smpb = Mp4AudioTrack.parseITunSmpb(f.iTunSmpb!!)!!
        assertEquals(listOf(1024L, 46L * 1024 - 1024 - 45100, 45100L), smpb.toList())
        // 24 ч @ 48 кГц: 84 378 кадров, delay 2112 (вектор 3).
        val g = FileFacts.mp4Audio(ByteArraySource(writeFake(86_400_000, 2112, 48000, 84378)))
        assertEquals(86_400_000L, g.elstSegmentDuration); assertEquals(86_402_112L, g.sttsTotal); assertEquals(84378, g.sampleCount)
        // Смещения сэмплов из stsc/stco указывают на данные кадров.
        val bytes = writeFake(5000, 1024, 44100, 6)
        val t = Mp4AudioTrack.read(ByteArraySource(bytes))!!
        for (o in t.sampleOffsets) assertEquals(0x21.toByte(), bytes[o.toInt()])
    }

    @Test fun gaplessProbeFfmpegFixture() {
        // fr26.mp4_ffmpeg_ms: mvhd 1000, seg 1022/1023, Σstts 46 124 → пропуск 1024, длина 45 100 (не 45 070).
        val src = T.src("aac_44100_2ch_45100.m4a")
        val t = Mp4AudioTrack.read(src)!!
        assertEquals(1000L, t.mvhdTimescale); assertEquals(46124L, t.sttsTotal); assertEquals(1024L, t.elstMediaTime)
        val tr = GaplessProbe.mp4Trim(t)!!
        assertEquals(1024L, tr.skip); assertEquals(45100L, tr.length)
        val g = GaplessProbe.probe(src)!!
        assertEquals(1024L, g.delayFrames); assertEquals(46L * 1024 - 1024 - 45100, g.paddingFrames)
        val six = Mp4AudioTrack.read(T.src("aac51_48000_6ch_48000.m4a"))!!
        assertEquals(6, six.channels); assertEquals(2, six.audioObjectType)
        try { FileFacts.mp4Audio(T.src("aac_truncated_44100_2ch_45100.m4a")); fail() } catch (e: CoreException) { assertTrue(e.message!!.contains("M4A")) }
    }

    private fun srcWav(n: Int, fs: Int): File {
        val f = T.tmp(".wav")
        FileSink(f).use { s ->
            val w = WavWriter(s, PcmSpec(fs, 2), WavSampleFormat.PCM16)
            val x = IntArray(n * 2) { i -> val t = i / 2; (Math.sin(2 * Math.PI * (300 + 200 * (i % 2)) * t / fs) * 8000).toInt() + (if (t % 4410 == 100) 20000 else 0) }
            w.writeInt(x, n); w.finish()
        }
        return f
    }

    @Test fun adtsToM4aDecodesLikeFfmpeg() {
        T.need("ffmpeg", "ffprobe")
        val wav = srcWav(45100, 44100)
        val adts = T.tmp(".aac"); val ref = T.tmp(".m4a"); val ours = T.tmp(".m4a")
        T.ffmpeg("-i", wav.path, "-c:a", "aac", "-b:a", "128k", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-f", "adts", adts.path)
        T.ffmpeg("-i", wav.path, "-c:a", "aac", "-b:a", "128k", "-fflags", "+bitexact", "-flags:a", "+bitexact", ref.path)
        val frames = AdtsScanner.scan(FileSource(adts))
        val all = adts.readBytes()
        FileSink(ours).use { s ->
            val w = Mp4AacWriter(s, 44100, 2, AdtsScanner.audioSpecificConfig(frames[0]))
            for (f in frames) w.writePacket(all, (f.offset + f.headerSize).toInt(), f.size - f.headerSize)
            w.finish(1024, 45100)
        }
        assertEquals(46, frames.size)
        // ffmpeg 6.1 не обрезает хвост AAC → длина по duration_ts (timeline.md «КРИТИЧНО для CI»).
        val p = T.probe(ours, "stream=duration_ts,sample_rate,channels,codec_name")
        assertEquals("45100", p["duration_ts"]); assertEquals("44100", p["sample_rate"]); assertEquals("aac", p["codec_name"])
        val a = T.decodeF32(ours); val b = T.decodeF32(ref)
        assertTrue(a.size >= 45100 * 2)
        // Начало обрезано по media_time так же, как в файле ffmpeg: первые N сэмплов совпадают бит в бит.
        assertArrayEquals(b.copyOfRange(0, 45100 * 2), a.copyOfRange(0, 45100 * 2), 0f)
    }

    @Test fun fastTrimAacFromM4a() {
        T.need("ffmpeg", "ffprobe")
        // Быстрая обрезка AAC (FR-23, вектор fr23_fast_trim[1]): D = 1024, [10 000, 20 000) → A = 9216, B = 20 480, hidden 1024.
        val fx = T.fixture("aac_44100_2ch_45100.m4a")
        val src = FileSource(fx)
        val t = Mp4AudioTrack.read(src)!!
        val D = 1024L
        val starts = LongArray(t.sampleCount) { it * 1024L - D }
        val plan = FastTrimPlanner.planFull(starts, IntArray(t.sampleCount) { 1024 }, 10000, 20000, 44100, 1, 0, 45100, true)
        assertEquals(9216L, plan.start); assertEquals(20480L, plan.end); assertEquals(1024L, plan.hidden); assertEquals(9, plan.firstPacket)
        val out = T.tmp(".m4a")
        FileSink(out).use { s ->
            val w = Mp4AacWriter(s, 44100, 2, t.audioSpecificConfig!!)
            for (i in plan.firstPacket..plan.lastPacket) w.writePacket(Src.readFully(src, t.sampleOffsets[i], t.sampleSizes[i])!!, 0, t.sampleSizes[i])
            w.finish(plan.hidden.toInt(), plan.length)
        }
        assertEquals("${plan.length}", T.probe(out, "stream=duration_ts")["duration_ts"])
        val orig = T.decodeF32(fx); val cut = T.decodeF32(out)
        val n = plan.length.toInt() * 2
        // Сравнение по началу и длине: ошибка относительно исходного декодирования мала (SNR), сдвиг 0.
        var err = 0.0; var en = 0.0; var maxHead = 0.0; var maxTail = 0.0
        for (i in 0 until n) {
            val d = Math.abs(cut[i] - orig[plan.start.toInt() * 2 + i]).toDouble(); val r = orig[plan.start.toInt() * 2 + i].toDouble()
            err += d * d; en += r * r; if (i < 2048) maxHead = maxOf(maxHead, d) else maxTail = maxOf(maxTail, d)
        }
        System.err.println("AAC fast trim: SNR=${10 * Math.log10(en / err)} дБ, max|d| первые 1024 = $maxHead, далее = $maxTail")
        assertTrue("SNR ${10 * Math.log10(en / err)}", err < en * 1e-6)
    }
}
