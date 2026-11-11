package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.pipeline.Mp4PacketSink
import com.rassul.audioeditor.core.pipeline.OggOpusPacketSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Отчёт самопроверки с устройства (Nothing A059, Android 16, c2.android.*): AAC-энкодер выдаёт на пакет больше
 * ⌈(delay + N)/F⌉ («K=96 … укладывается в 95», K=570/569, 1409/1408), Opus-энкодер — пакеты ровно на N без хвоста под
 * pre-skip («заявлено 96312, в пакетах 96000»). Модель энкодера + адаптеры контейнеров (TailPacketHoldback) + тишина
 * delay + 2 кадра перед EOS (как Encoders.finish в :app). Контроль — длина файла по метаданным = N, K = ⌈(delay+N)/F⌉.
 */
class AuditEncoderTailTest {
    private val asc = byteArrayOf(0x11, 0x90.toByte())        // AAC-LC 48 кГц, 2 канала (ISO/IEC 14496-3 §1.6.2.1)
    private val pkt = ByteArray(10) { 1 }
    private val opusPkt = byteArrayOf((19 shl 3).toByte(), 0, 0) // CELT 20 мс (RFC 6716 §3.1)

    /** c2.android.aac.encoder: пакетов ⌈(delay + поданных)/F⌉ + 1. */
    private fun aacPackets(delay: Int, fed: Long) = ((delay + fed + 1023) / 1024 + 1).toInt()
    /** c2.android.opus.encoder: пакетов ⌊поданных / 960⌋, хвост под pre-skip не выдаётся. */
    private fun opusPackets(fed: Long) = (fed / 960).toInt()

    private fun m4a(n: Long, delay: Int, silence: Int): Pair<Int, Long?> {
        val out = ByteArraySink()
        val s = Mp4PacketSink(Mp4AacWriter(out, 48_000, 2, asc, 1024))
        repeat(aacPackets(delay, n + silence)) { s.writePacket(pkt, 0, pkt.size, 1024) }
        s.finish(delay, n)
        val src = ByteArraySource(out.toByteArray())
        return Mp4AudioTrack.read(src)!!.sampleCount to FileFacts.mp4Audio(src).lengthFrames
    }

    @Test fun aacExtraPacketDropped() {
        for ((n, k) in listOf(96_000L to 95, 581_632L - 1024 to 569, 1_441_000L to 1409)) {
            val needed = ((1024 + n + 1023) / 1024).toInt()
            val (count, len) = m4a(n, 1024, silence = 0)
            assertEquals(needed, count); assertEquals(n, len)
            if (n == 96_000L) assertEquals(k, count)
            // С тишиной delay + 2 кадра лишних пакетов больше — тоже отбрасываются.
            val (c2, l2) = m4a(n, 1024, silence = 1024 + 2048)
            assertEquals(needed, c2); assertEquals(n, l2)
        }
    }

    @Test fun aacTooFewPacketsStillError() {
        val s = Mp4PacketSink(Mp4AacWriter(ByteArraySink(), 48_000, 2, asc, 1024))
        repeat(94) { s.writePacket(pkt, 0, pkt.size, 1024) }
        try { s.finish(1024, 96_000); fail() } catch (_: IllegalArgumentException) {}
    }

    private fun ogg(n: Long, preSkip: Int, silence: Int): Long {
        val out = ByteArraySink()
        val s = OggOpusPacketSink(OggOpusWriter(out, 2, 48_000, preSkip))
        repeat(opusPackets(n + silence)) { s.writePacket(opusPkt, 0, opusPkt.size, 960) }
        s.finish(preSkip, n)
        val src = ByteArraySource(out.toByteArray())
        val audio = OggReader.read(src).packets.size - 2
        assertEquals("пакетов", ((preSkip + n + 959) / 960).toInt(), audio)   // после покрывающего — отброшены
        return FileFacts.oggOpus(src).length48
    }

    @Test fun opusNeedsSilenceThenExact() {
        // Без тишины: 100 пакетов = 96 000 < 96 312 — ошибка писателя (как на телефоне).
        try { ogg(96_000, 312, silence = 0); fail() } catch (e: IllegalArgumentException) { assertTrue("96312" in e.message!!) }
        // С тишиной pre-skip + 2 кадра (Encoders.finish) — гранула конца preSkip + N, длина N.
        assertEquals(96_000L, ogg(96_000, 312, silence = 312 + 2 * 960))
        assertEquals(48_100L, ogg(48_100, 312, silence = 312 + 2 * 960))
        assertEquals(1L, ogg(1, 312, silence = 312 + 2 * 960))
    }
}
