package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.raw.Ac3Scanner
import com.rassul.audioeditor.core.format.raw.AdtsScanner
import com.rassul.audioeditor.core.format.raw.CodedFrame
import com.rassul.audioeditor.core.format.raw.DtsScanner
import com.rassul.audioeditor.core.format.raw.MP3_DECODER_DELAY
import com.rassul.audioeditor.core.format.raw.Mp3Scanner
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawFramesTest {
    @Test fun mp3LameFixture() {
        // fr26.mp3_ffmpeg: 41 кадр (без Info), delay 576, padding 1556 → длина 45 100, пропуск 1105.
        val s = Mp3Scanner.scan(T.src("mp3_lame_44100_2ch_45100.mp3"))
        val i = s.info!!
        assertEquals(41L, i.frames); assertEquals(576, i.delay); assertEquals(1556, i.padding)
        assertEquals(41, s.frames.size); assertEquals(1152, s.samplesPerFrame); assertEquals(44100, s.sampleRate)
        assertEquals(45100L, s.lameLength); assertEquals(0, s.resyncs)
        val g = GaplessProbe.probe(T.src("mp3_lame_44100_2ch_45100.mp3"))!!
        assertEquals(1105L, g.delayFrames); assertEquals((1556 - MP3_DECODER_DELAY).toLong(), g.paddingFrames)
    }

    private fun countPackets(name: String): Int =
        String(T.run("ffprobe", "-v", "error", "-count_packets", "-select_streams", "a:0", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", T.fixture(name).path).stdout).trim().toInt()

    @Test fun scannersMatchFfprobe() {
        val ac3 = Ac3Scanner.scan(T.src("ac3_48000_2ch_48000.ac3"))
        val eac3 = Ac3Scanner.scan(T.src("eac3_48000_2ch_48000.eac3"))
        val dts = DtsScanner.scan(T.src("dts_48000_2ch_48000.dts"))
        val adts = AdtsScanner.scan(T.src("adts_44100_2ch_45100.aac"))
        for ((fr, spf) in listOf(ac3 to 1536, eac3 to 1536, dts to 512, adts to 1024)) {
            assertTrue(fr.isNotEmpty()); assertTrue(fr.all { it.samples == spf })
            // Кадры идут подряд, без промежутков, до конца файла.
            for (k in 1 until fr.size) assertEquals(fr[k - 1].offset + fr[k - 1].size, fr[k].offset)
        }
        assertEquals(48000, ac3[0].sampleRate); assertEquals(2, ac3[0].channels); assertTrue(!ac3[0].isEac3); assertTrue(eac3[0].isEac3)
        assertEquals(48000, dts[0].sampleRate); assertEquals(44100, adts[0].sampleRate)
        assertEquals(T.fixture("ac3_48000_2ch_48000.ac3").length(), ac3.last().offset + ac3.last().size)
        assertEquals(listOf(0x12.toByte(), 0x10.toByte()), AdtsScanner.audioSpecificConfig(adts[0]).toList())   // AAC-LC, 44,1 кГц, стерео
        T.need("ffprobe")
        assertEquals(countPackets("ac3_48000_2ch_48000.ac3"), ac3.size)
        assertEquals(countPackets("eac3_48000_2ch_48000.eac3"), eac3.size)
        assertEquals(countPackets("dts_48000_2ch_48000.dts"), dts.size)
        assertEquals(countPackets("adts_44100_2ch_45100.aac"), adts.size)
        assertEquals(countPackets("mp3_lame_44100_2ch_45100.mp3"), Mp3Scanner.scan(T.src("mp3_lame_44100_2ch_45100.mp3")).frames.size)
    }

    @Test fun frameLengthTables() {
        // timeline.md ф. 12, векторы eac3: numblkscod 0..3 → 256/512/768/1536; fscod = 3 → 1536.
        assertEquals(listOf(256, 512, 768, 1536), (0..3).map { Ac3Scanner.eac3Samples(it, 0) })
        assertEquals(1536, Ac3Scanner.eac3Samples(0, 3))
        assertEquals(512, DtsScanner.samples(15))
        // AC-3 44,1 кГц, 32 кбит/с: 69/70 слов (A/52 табл. 5.18).
        assertEquals(138, Ac3Scanner.ac3FrameBytes(1, 0)); assertEquals(140, Ac3Scanner.ac3FrameBytes(1, 1))
        assertEquals(1536, Ac3Scanner.ac3FrameBytes(0, 28))   // 384 кбит/с @48 кГц
    }

    @Test fun sniffer() {
        // FR-2: тексты.
        assertEquals("Формат AVI не поддерживается. Поддерживаются: MP4, MOV, 3GP, MKV, WebM, TS.", ContainerSniffer.unsupportedMessage(T.src("container_avi_44100.avi")))
        assertTrue(ContainerSniffer.unsupportedMessage(T.src("container_flv_44100.flv"))!!.startsWith("Формат FLV"))
        assertTrue(ContainerSniffer.unsupportedMessage(T.src("container_asf_44100.wma"))!!.startsWith("Формат WMV/ASF"))
        for (ok in listOf("aac_44100_2ch_45100.m4a", "mkv_2tracks_48000.mkv", "video_noaudio.mp4", "wav_s16_44100_2ch_45100.wav", "opus_48000_2ch_48100.ogg"))
            assertNull(ok, ContainerSniffer.unsupportedMessage(T.src(ok)))
    }

    @Test fun mp3FastTrimWithInfoFrame() {
        T.need("ffmpeg")
        // Вектор fr23_fast_trim[mp3]: D = 1105, [14 567, 30 000) → A = 13 871, B = 31 151, пакеты 11…27, hidden 2304.
        val fx = T.fixture("mp3_lame_44100_2ch_45100.mp3")
        val src = T.src("mp3_lame_44100_2ch_45100.mp3")
        val s = Mp3Scanner.scan(src)
        val D = 1105L
        val starts = LongArray(s.frames.size) { it * 1152L - D }
        val a = 14567L; val b = 30000L
        val probe = FastTrimPlanner.planFull(starts, IntArray(starts.size) { 1152 }, a, b, 44100, 0, 0, 45100, true)
        val w = Mp3Scanner.warmupFrames(s.frames, probe.firstAudiblePacket)
        assertTrue(w >= 2)
        val p = FastTrimPlanner.planFull(starts, IntArray(starts.size) { 1152 }, a, b, 44100, w, 0, 45100, true)
        if (w == 2) { assertEquals(11, p.firstPacket); assertEquals(2304L, p.hidden) }
        assertEquals(13871L, p.start); assertEquals(31151L, p.end); assertEquals(27, p.lastPacket)
        val sel: List<CodedFrame> = s.frames.subList(p.firstPacket, p.lastPacket + 1)
        val K = sel.size
        val bytes = sel.sumOf { it.size.toLong() }
        val delay = (p.hidden - MP3_DECODER_DELAY).toInt()
        val pad = (K * 1152L - delay - p.length).toInt()
        val info = Mp3Scanner.buildInfoFrame(s.frames[0].header, K, bytes, delay, pad)
        val out = T.tmp(".mp3")
        FileSink(out).use { o -> o.write(info); for (f in sel) o.write(Src.readFully(src, f.offset, f.size)!!) }
        // Свой сканер читает наш тег.
        val s2 = Mp3Scanner.scan(com.rassul.audioeditor.core.io.FileSource(out))
        assertEquals(p.length, s2.lameLength); assertEquals(K, s2.frames.size)
        val cut = T.decodeF32(out); val orig = T.decodeF32(fx)
        assertEquals(p.length.toInt(), cut.size / 2)
        var err = 0.0; var en = 0.0
        for (i in cut.indices) { val r = orig[p.start.toInt() * 2 + i].toDouble(); err += (cut[i] - r) * (cut[i] - r); en += r * r }
        assertTrue("SNR ${10 * Math.log10(en / err)}", err < en * 1e-6)
    }
}
