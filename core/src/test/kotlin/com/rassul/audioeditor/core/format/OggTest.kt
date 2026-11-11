package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.ogg.OggOpusWriterImpl
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.ogg.OpusHead
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.pipeline.FastTrimPlanner
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OggTest {
    @Test fun tocVectors() {
        // RFC 6716 §3.1, timeline_vectors.json opus_toc
        for (v0 in T.vectors["opus_toc"].l()) {
            val v = v0.m()
            assertEquals(v.toString(), v["samples48"].int(), OpusToc.samples48(byteArrayOf(v["toc"].int().toByte(), v["b1"].int().toByte())))
        }
    }

    private fun fixturePackets() = OggReader.read(T.src("opus_48000_2ch_48100.ogg"))

    @Test fun readerOnLibopusFixture() {
        val s = fixturePackets()
        assertEquals(0, s.crcErrors); assertTrue(s.hasEos)
        val h = OpusHead.parse(s.packets[0].data)!!
        assertEquals(312, h.preSkip); assertEquals(2, h.channels)
        assertEquals(48412L, s.lastGranule)                                   // fr26.opus_ffmpeg
        val f = FileFacts.oggOpus(T.src("opus_48000_2ch_48100.ogg"))
        assertEquals(48100L, f.length48)
        val g = GaplessProbe.probe(T.src("opus_48000_2ch_48100.ogg"))!!
        assertEquals(312L, g.delayFrames); assertEquals(f.totalSamples48 - 48412, g.paddingFrames)
    }

    private fun rewrite(packets: List<ByteArray>, preSkip: Int, valid: Long): ByteArray {
        val sink = ByteArraySink()
        val w = OggOpusWriter(sink, 2, 48000, preSkip)
        for (p in packets) w.writePacket(p, 0, p.size, OpusToc.samples48(p))
        w.finish(valid)
        return sink.toByteArray()
    }

    @Test fun finalGranuleVectors() {
        // fr25_opus: гранула = pre_skip + N₄₈ (RFC 7845 §4.4); пакеты по 20 мс.
        for (v0 in T.vectors["fr25_opus"].l().take(2)) {
            val v = v0.m()
            val pk = byteArrayOf(0xF8.toByte(), 0x00)    // CELT 20 мс
            val bytes = rewrite(List(v["packets_20ms"].int()) { pk }, v["pre_skip"].int(), v["n_out48"].long())
            val f = FileFacts.oggOpus(ByteArraySource(bytes))
            assertEquals(v["final_granule"].long(), f.lastGranule); assertEquals(0, f.crcErrors); assertTrue(f.hasEos)
            assertEquals(v["packets_20ms"].int(), f.packetCount)
        }
    }

    @Test fun packetSpanningPages() {
        val sink = ByteArraySink()
        val w = OggOpusWriterImpl(sink, 1, 48000, 0, 7, null)
        val big = ByteArray(70000) { (it * 31).toByte() }; big[0] = 0xF8.toByte()
        val small = byteArrayOf(0xF8.toByte(), 1, 2)
        w.writePacket(small, 0, 3, 960); w.writePacket(big, 0, big.size, 960); w.writePacket(small, 0, 3, 960)
        w.finish(2000)
        val s = OggReader.read(ByteArraySource(sink.toByteArray()))
        assertEquals(0, s.crcErrors); assertEquals(5, s.packets.size)
        assertArrayEquals(big, s.packets[3].data); assertEquals(2000L, s.lastGranule)
    }

    @Test fun ffmpegDecodesRewrittenOpus() {
        T.need("ffmpeg")
        val s = fixturePackets()
        val audio = s.packets.drop(2).map { it.data }
        val out = T.tmp(".ogg").also { it.writeBytes(rewrite(audio, 312, 48100)) }
        val a = T.decodeF32(out); val b = T.decodeF32(T.fixture("opus_48000_2ch_48100.ogg"))
        assertTrue("длина ${a.size / 2}", Math.abs(a.size / 2 - 48100) <= 1)
        assertArrayEquals(b, a, 0f)
    }

    @Test fun fastTrimOpus() {
        T.need("ffmpeg")
        val fx = T.fixture("opus_48000_2ch_48100.ogg")
        val audio = fixturePackets().packets.drop(2).map { it.data }
        val D = 312L
        val frames = IntArray(audio.size) { OpusToc.samples48(audio[it]) }
        val starts = LongArray(audio.size); for (i in 1 until audio.size) starts[i] = starts[i - 1] + frames[i - 1]
        for (i in starts.indices) starts[i] -= D
        val p = FastTrimPlanner.planFull(starts, frames, 16000, 30000, 48000, 0, FastTrimPlanner.OPUS_PREROLL_48K, 48100, true)
        assertTrue(p.hidden >= 3840)
        val out = T.tmp(".ogg").also { it.writeBytes(rewrite(audio.subList(p.firstPacket, p.lastPacket + 1), p.hidden.toInt(), p.length)) }
        val a = T.decodeF32(out); val b = T.decodeF32(fx)
        assertEquals(p.length.toInt(), a.size / 2)
        // Opus после ≥ 80 мс пре-ролла сходится: допускаем малую ошибку (декодер не бит в бит при другом старте).
        var err = 0.0; var en = 0.0
        for (i in a.indices) { val d = (a[i] - b[p.start.toInt() * 2 + i]).toDouble(); err += d * d; en += b[p.start.toInt() * 2 + i].toDouble().let { it * it } }
        assertTrue("SNR ${10 * Math.log10(en / err)} дБ", err < en * 1e-3)
    }
}
