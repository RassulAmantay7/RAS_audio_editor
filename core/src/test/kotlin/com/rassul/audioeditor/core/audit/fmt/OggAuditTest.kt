package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит Ogg Opus (RFC 3533 §6, RFC 7845 §3–§5): страницы, lacing, флаги, CRC, гранулы (вкл. pre-skip), конечная гранула.
 * Страницы разбираются здесь независимым кодом (не OggReader), CRC — побитово.
 */
class OggAuditTest {
    private class Page(val type: Int, val granule: Long, val serial: Int, val seq: Int, val lacing: IntArray, val body: ByteArray)

    private fun pages(f: ByteArray): List<Page> {
        val out = ArrayList<Page>(); var p = 0
        fun le(o: Int, n: Int): Long { var v = 0L; for (k in n - 1 downTo 0) v = (v shl 8) or (f[o + k].toLong() and 0xFF); return v }
        while (p < f.size) {
            assertEquals("OggS", String(f, p, 4, Charsets.ISO_8859_1)); assertEquals(0, f[p + 4].toInt())
            val nseg = f[p + 26].toInt() and 0xFF
            val lac = IntArray(nseg) { f[p + 27 + it].toInt() and 0xFF }
            val hl = 27 + nseg; val bl = lac.sum()
            val page = f.copyOfRange(p, p + hl + bl)
            val stored = le(p + 22, 4)
            for (i in 22..25) page[i] = 0
            assertEquals("CRC страницы ${out.size}", A.crcBitwise(page, 32, 0x04C11DB7), stored)
            out += Page(f[p + 5].toInt() and 0xFF, le(p + 6, 8), le(p + 14, 4).toInt(), le(p + 18, 4).toInt(), lac, f.copyOfRange(p + hl, p + hl + bl))
            p += hl + bl
        }
        return out
    }

    /** Проверить структуру; вернуть пакеты (кроме заголовков) и гранулы страниц. */
    private fun validate(f: ByteArray, packets: List<ByteArray>, dur: IntArray, preSkip: Int, finalGranule: Long, ch: Int) {
        val ps = pages(f)
        assertTrue(ps.size >= 3)
        var cur = java.io.ByteArrayOutputStream(); var pendingContinued = false
        val got = ArrayList<ByteArray>(); var completed = 0
        var gran = 0L
        for ((i, pg) in ps.withIndex()) {
            assertEquals(i, pg.seq); assertEquals(ps[0].serial, pg.serial)
            assertEquals("BOS только на первой", i == 0, pg.type and 2 != 0)
            assertEquals("EOS только на последней", i == ps.size - 1, pg.type and 4 != 0)
            assertEquals("флаг continued стр. $i", pendingContinued, pg.type and 1 != 0)
            assertTrue(pg.lacing.size in 1..255)
            var bp = 0; var endedHere = 0
            for (l in pg.lacing) { cur.write(pg.body, bp, l); bp += l; if (l < 255) { got += cur.toByteArray(); cur = java.io.ByteArrayOutputStream(); endedHere++ } }
            pendingContinued = pg.lacing.last() == 255
            // RFC 7845 §3: OpusHead — одна на первой странице; OpusTags — заканчивает свою страницу; аудио — с новой.
            if (i == 0) { assertEquals(1, endedHere); assertEquals(0L, pg.granule) }
            for (k in 0 until endedHere) { val idx = completed + k; if (idx >= 2) gran += dur[idx - 2] }
            completed += endedHere
            if (i == 1) { assertEquals("OpusTags завершает 2-ю страницу", 2, completed); assertEquals(0L, pg.granule) }
            when {
                i == ps.size - 1 -> assertEquals("конечная гранула (RFC 7845 §4.4)", finalGranule, pg.granule)
                endedHere == 0 -> assertEquals("стр. $i без завершённых пакетов: −1", -1L, pg.granule)
                i >= 2 -> assertEquals("гранула стр. $i = Σ длительностей завершённых пакетов", gran, pg.granule)
            }
        }
        assertTrue(!pendingContinued)
        assertEquals(packets.size + 2, got.size)
        val head = got[0]
        assertEquals("OpusHead", String(head, 0, 8, Charsets.ISO_8859_1)); assertEquals(1, head[8].toInt()); assertEquals(ch, head[9].toInt())
        assertEquals(preSkip, (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8))
        for (k in packets.indices) assertArrayEquals("пакет $k", packets[k], got[k + 2])
        // Наш читатель видит то же.
        val facts = FileFacts.oggOpus(ByteArraySource(f))
        assertEquals(0, facts.crcErrors); assertEquals(finalGranule - preSkip, facts.length48); assertEquals(packets.size, facts.packetCount)
    }

    private fun write(packets: List<ByteArray>, dur: IntArray, preSkip: Int, valid: Long, ch: Int = 2): ByteArray {
        val sink = ByteArraySink()
        val w = OggOpusWriter(sink, ch, 48000, preSkip)
        for ((k, p) in packets.withIndex()) w.writePacket(p, 0, p.size, dur[k])
        w.finish(valid)
        return sink.toByteArray()
    }

    /** Свойство: случайные длины пакетов (вкл. 255·k, > 65 025 байт — пакет через несколько страниц) → страницы и гранулы валидны. */
    @Test fun randomPacketLengthsStructure() {
        val r = A.Rng(3533)
        val lens = listOf(1, 2, 254, 255, 256, 509, 510, 511, 1275, 8000, 8192, 8193, 65024, 65025, 65026, 70000, 140000)
        for (iter in 0 until 60) {
            val n = r.range(1, 120)
            val packets = List(n) { val len = if (r.int(4) == 0) r.pick(lens) else r.range(1, 1500); r.bytes(len).also { it[0] = (r.int(32) shl 3).toByte() } }
            val dur = IntArray(n) { OpusToc.samples48(packets[it]) }
            val total = dur.sum().toLong()
            val preSkip = minOf(r.pick(listOf(0, 312, 3840, 6000)), (total - 1).toInt())
            val trim = r.int(dur.last())                    // обрезка хвоста не больше последнего пакета
            val valid = total - preSkip - trim
            if (valid < 0) continue
            val f = write(packets, dur, preSkip, valid)
            validate(f, packets, dur, preSkip, preSkip + valid, 2)
        }
    }

    /** ffprobe (чужой демультиплексор, проверяет CRC страниц): пакеты и их размеры совпадают, ошибок нет. */
    @Test fun ffprobeSeesSamePackets() {
        T.need("ffprobe")
        val r = A.Rng(7845)
        val real = OggReader.read(T.src("opus_48000_2ch_48100.ogg")).packets.drop(2).map { it.data }
        for (iter in 0 until 6) {
            // Настоящие пакеты libopus + случайные «длинные» (padding Opus код 3 не нужен: ffprobe без декодирования).
            val packets = List(r.range(5, 60)) { if (iter % 2 == 0) r.pick(real) else r.bytes(r.pick(listOf(10, 255, 510, 4000, 70000))).also { it[0] = 0xFC.toByte() } }
            val dur = IntArray(packets.size) { OpusToc.samples48(packets[it]) }
            val total = dur.sum().toLong()
            val f = A.write(write(packets, dur, 312, total - 312 - r.int(dur.last())), ".ogg")
            val o = T.run("ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "packet=size", "-of", "csv=p=0", f.path)
            // Ошибки демультиплексора («[ogg @…] CRC mismatch!» и т.п.) недопустимы; жалобы декодера opus на случайную
            // полезную нагрузку (ffprobe пробует декодировать для параметров) к контейнеру не относятся.
            val demuxErr = o.stderr.lines().filter { it.isNotBlank() && !it.contains("[opus @") }
            assertEquals("iter=$iter: ${o.stderr}", emptyList<String>(), demuxErr)
            val sizes = String(o.stdout).lines().filter { it.isNotBlank() }.map { it.trim().substringBefore(',').toInt() }
            assertEquals("iter=$iter", packets.map { it.size }, sizes)
        }
    }

    /** ffmpeg декодирует ровно N₄₈ = гранула − pre_skip при случайных pre-skip и обрезке хвоста (RFC 7845 §4.2, §4.4). */
    @Test fun ffmpegDecodesExactLength() {
        T.need("ffmpeg")
        val r = A.Rng(48100)
        val real = OggReader.read(T.src("opus_48000_2ch_48100.ogg")).packets.drop(2).map { it.data }
        for (iter in 0 until 6) {
            val k = r.range(4, real.size)
            val packets = real.subList(0, k)
            val dur = IntArray(k) { OpusToc.samples48(packets[it]) }
            val total = dur.sum().toLong()
            val preSkip = r.pick(listOf(0, 312, 960, 3840))
            val valid = total - preSkip - r.int(dur.last())
            val f = A.write(write(packets, dur, preSkip, valid), ".ogg")
            val out = A.ffmpegRaw(f, "-c:a", "pcm_f32le", "-ar", "48000", "-f", "f32le")
            assertEquals("", out.stderr.trim())
            assertEquals("iter=$iter k=$k preSkip=$preSkip", valid, out.stdout.size / 8L)
        }
    }

    @Test fun invalidInput() {
        val w = OggOpusWriter(ByteArraySink(), 2, 48000, 312)
        w.writePacket(byteArrayOf(0x08), 0, 1, 960)
        try { w.finish(960 - 312 + 1); throw AssertionError("больше, чем в пакетах") } catch (e: IllegalArgumentException) { }
        try { OggOpusWriter(ByteArraySink(), 2, 48000, 312).writePacket(byteArrayOf(0x08), 0, 1, 5761); throw AssertionError() } catch (e: IllegalArgumentException) { }
        try { OggOpusWriter(ByteArraySink(), 2, 48000, 70000).writePacket(byteArrayOf(0x08), 0, 1, 960); throw AssertionError() } catch (e: IllegalArgumentException) { }
    }
}
