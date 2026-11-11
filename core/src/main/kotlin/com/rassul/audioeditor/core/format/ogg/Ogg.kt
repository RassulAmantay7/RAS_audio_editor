package com.rassul.audioeditor.core.format.ogg

import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.u16le
import com.rassul.audioeditor.core.format.bits.u32le
import com.rassul.audioeditor.core.format.bits.u64le
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink

/*
 * Ogg (RFC 3533) и Ogg Opus (RFC 7845).
 * Страница (RFC 3533 §6): "OggS", версия 0, header_type (1 продолжение, 2 BOS, 4 EOS), granule (64 бит LE),
 * serial, sequence, CRC-32 (0x04C11DB7, по странице с нулевым полем CRC), число сегментов, таблица сегментов (lacing).
 */

/** Писатель страниц одного логического потока. */
class OggPageWriter(private val sink: SeekableSink, private val serial: Int) {
    private var seq = 0
    private val segs = ByteBuf(256)
    private val body = ByteBuf(8192)
    private var firstContinued = false
    var pagesWritten = 0; private set

    /** Число сегментов в текущей (ещё не записанной) странице. */
    val pendingSegments: Int get() = segs.size
    val pendingBytes: Int get() = body.size

    /** Записать страницу из накопленного. */
    fun flushPage(granule: Long, bos: Boolean = false, eos: Boolean = false) {
        val h = ByteBuf(27 + segs.size)
        h.ascii("OggS").u8(0)
        h.u8((if (firstContinued) 1 else 0) or (if (bos) 2 else 0) or (if (eos) 4 else 0))
        h.u64le(granule).u32le(serial).u32le(seq++).u32le(0)
        h.u8(segs.size).bytes(segs.data, 0, segs.size)
        var crc = Crc.crc32Ogg(h.data, 0, h.size)
        crc = Crc.crc32Ogg(body.data, 0, body.size, crc)
        val p = 22
        h.data[p] = crc.toByte(); h.data[p + 1] = (crc ushr 8).toByte(); h.data[p + 2] = (crc ushr 16).toByte(); h.data[p + 3] = (crc ushr 24).toByte()
        sink.write(h.data, 0, h.size)
        sink.write(body.data, 0, body.size)
        segs.clear(); body.clear(); firstContinued = false; pagesWritten++
    }

    /** Сколько сегментов займёт пакет длиной len (RFC 3533 §5: 255-байтные куски + последний < 255, возможно 0). */
    fun segmentsFor(len: Int): Int = len / 255 + 1

    /**
     * Добавить пакет целиком в текущую страницу. Если он не помещается (255 сегментов), страница
     * закрывается с гранулой [granuleBefore] (гранула последнего завершённого на ней пакета) и пакет
     * продолжается на следующей (флаг continued; гранула страницы, где не завершился ни один пакет, = −1).
     */
    fun addPacket(data: ByteArray, off: Int, len: Int, granuleBefore: Long, packetsOnPage: () -> Int) {
        var rem = len
        var p = off
        var completedOnThisPage = packetsOnPage()
        while (true) {
            val free = 255 - segs.size
            val need = rem / 255 + 1
            if (need <= free) {
                repeat(rem / 255) { segs.u8(255) }
                segs.u8(rem % 255)
                body.bytes(data, p, rem)
                return
            }
            // Заполнить страницу полными сегментами и продолжить на следующей.
            val take = free * 255
            repeat(free) { segs.u8(255) }
            body.bytes(data, p, take)
            p += take; rem -= take
            flushPage(if (completedOnThisPage > 0) granuleBefore else -1L)
            completedOnThisPage = 0
            firstContinued = true
        }
    }
}

/**
 * Писатель Ogg Opus (RFC 7845).
 * §5.1 OpusHead: "OpusHead", версия 1, каналы, pre-skip (u16 LE, 48 кГц), исходная частота (u32), gain (s16, Q7.8) = 0,
 *  семейство раскладки 0 (≤ 2 кан.) или 1 (3–8 кан., Vorbis-порядок, §5.1.1.2) + таблица.
 * §5.2 OpusTags: "OpusTags", vendor, 0 комментариев.
 * §3: OpusHead — одна на первой странице (BOS), OpusTags начинается со второй, аудио — с новой страницы.
 * §4: гранула страницы = число сэмплов 48 кГц (включая pre-skip) всех пакетов, завершённых на странице.
 * §4.4: гранула последней (EOS) страницы = preSkip + validFrames48k — обрезка хвоста.
 */
class OggOpusWriterImpl(
    private val sink: SeekableSink,
    private val channels: Int,
    private val inputSampleRate: Int,
    private val preSkip: Int,
    serial: Int,
    mapping: ByteArray?,
    vendor: String = "RAS audio editor",
) {
    private val pw = OggPageWriter(sink, serial)
    private var granule = 0L                 // сумма длительностей завершённых пакетов
    private var granuleAtPageEnd = 0L
    private var packetsOnPage = 0
    private var finished = false
    /** Последняя записанная гранула (для проверок). */
    var lastPageGranule = -1L; private set

    init {
        require(channels in 1..8)
        require(preSkip in 0..65535)
        val head = ByteBuf(32)
        head.ascii("OpusHead").u8(1).u8(channels).u16le(preSkip).u32le(inputSampleRate).u16le(0)
        if (channels <= 2 && mapping == null) head.u8(0)
        else {
            head.u8(1)
            val m = mapping ?: defaultVorbisMapping(channels)
            require(m.size == 2 + channels) { "таблица раскладки: stream count, coupled count, $channels байт" }
            head.bytes(m)
        }
        pw.addPacket(head.data, 0, head.size, 0, { 0 })
        pw.flushPage(0, bos = true)
        val v = vendor.toByteArray(Charsets.UTF_8)
        val tags = ByteBuf(32 + v.size)
        tags.ascii("OpusTags").u32le(v.size).bytes(v).u32le(0)
        pw.addPacket(tags.data, 0, tags.size, 0, { 0 })
        pw.flushPage(0)
    }

    fun writePacket(data: ByteArray, off: Int, len: Int, frames48k: Int) {
        check(!finished)
        require(frames48k in 1..5760) { "длительность пакета $frames48k" }   // RFC 6716 §3.2.1: пакет ≤ 120 мс
        // Ленивая запись: новая страница начинается, только если пакет не помещается в текущую (≤ 255 сегментов,
        // ориентир ≤ 8 КБ данных). Так в конце на последней странице всегда есть хотя бы один пакет (для обрезки §4.4).
        if (packetsOnPage > 0 && (pw.pendingSegments + pw.segmentsFor(len) > 255 || pw.pendingBytes + len > 8192)) {
            pw.flushPage(granuleAtPageEnd); lastPageGranule = granuleAtPageEnd; packetsOnPage = 0
        }
        pw.addPacket(data, off, len, granuleAtPageEnd) { packetsOnPage }
        granule += frames48k
        granuleAtPageEnd = granule
        packetsOnPage++
    }

    fun finish(validFrames48k: Long) {
        check(!finished); finished = true
        val target = preSkip + validFrames48k                       // RFC 7845 §4.4
        val prev = if (lastPageGranule < 0) 0L else lastPageGranule
        require(target <= granule) { "заявлено больше сэмплов ($target), чем в пакетах ($granule)" }
        require(target >= prev) { "обрезка хвоста больше последней страницы: $target < $prev" }
        pw.flushPage(target, eos = true)
        lastPageGranule = target
        sink.flush()
    }

    companion object {
        /** RFC 7845 §5.1.1.2 (семейство 1) — раскладка libopus для 3–8 каналов: [streams, coupled, mapping...]. */
        fun defaultVorbisMapping(ch: Int): ByteArray = when (ch) {
            1 -> byteArrayOf(1, 0, 0)
            2 -> byteArrayOf(1, 1, 0, 1)
            3 -> byteArrayOf(2, 1, 0, 2, 1)
            4 -> byteArrayOf(2, 2, 0, 1, 2, 3)
            5 -> byteArrayOf(3, 2, 0, 4, 1, 2, 3)
            6 -> byteArrayOf(4, 2, 0, 4, 1, 2, 3, 5)
            7 -> byteArrayOf(5, 2, 0, 4, 1, 2, 3, 5, 6)
            8 -> byteArrayOf(5, 3, 0, 6, 1, 2, 3, 4, 5, 7)
            else -> throw IllegalArgumentException("channels=$ch")
        }
    }
}

/** Пакет Ogg: данные и гранула страницы, если пакет на ней последний завершённый (иначе −1). */
class OggPacket(val data: ByteArray, val granule: Long, val pageIndex: Int)

/** Результат чтения логического потока Ogg. */
class OggStream(
    val serial: Int,
    val packets: List<OggPacket>,
    val pageCount: Int,
    val crcErrors: Int,
    val lastGranule: Long,
    val hasEos: Boolean,
    val truncated: Boolean,
)

object OggReader {
    /** Прочитать первый логический поток (по BOS) целиком. Проверяет CRC каждой страницы. */
    fun read(src: RandomAccessSource): OggStream {
        var pos = 0L
        var serial: Int? = null
        val packets = ArrayList<OggPacket>()
        val cur = ByteBuf(8192)
        var pages = 0; var crcErr = 0; var lastGran = -1L; var eos = false; var truncated = false
        while (pos + 27 <= src.size) {
            val h = Src.readFully(src, pos, 27) ?: break
            if (h.ascii(0, 4) != "OggS") {
                // Поиск следующей сигнатуры (повреждение).
                val nxt = findCapture(src, pos + 1) ?: break
                pos = nxt; continue
            }
            val nseg = h.u8(26)
            val lacing = Src.readFully(src, pos + 27, nseg) ?: run { truncated = true; null } ?: break
            val bodyLen = lacing.sumOf { it.toInt() and 0xFF }
            val body = Src.readFully(src, pos + 27 + nseg, bodyLen) ?: run { truncated = true; null } ?: break
            val type = h.u8(5)
            val gran = h.u64le(6)
            val ser = h.u32le(14).toInt()
            if (serial == null) serial = ser
            if (ser == serial) {
                // CRC: по всей странице с нулевым полем CRC (RFC 3533 §6).
                val stored = h.u32le(22).toInt()
                for (i in 22..25) h[i] = 0
                var c = Crc.crc32Ogg(h, 0, 27); c = Crc.crc32Ogg(lacing, 0, nseg, c); c = Crc.crc32Ogg(body, 0, bodyLen, c)
                if (c != stored) crcErr++
                if (type and 1 == 0) cur.clear()      // не продолжение — незавершённый пакет отбрасывается
                var bp = 0
                var lastIdxOnPage = -1
                for (i in 0 until nseg) {
                    val l = lacing.u8(i)
                    cur.bytes(body, bp, l); bp += l
                    if (l < 255) { packets += OggPacket(cur.toByteArray(), -1, pages); cur.clear(); lastIdxOnPage = packets.size - 1 }
                }
                if (lastIdxOnPage >= 0) packets[lastIdxOnPage] = OggPacket(packets[lastIdxOnPage].data, gran, pages)
                if (gran != -1L) lastGran = gran
                if (type and 4 != 0) eos = true
                pages++
            }
            pos += 27 + nseg + bodyLen
        }
        return OggStream(serial ?: 0, packets, pages, crcErr, lastGran, eos, truncated)
    }

    private fun findCapture(src: RandomAccessSource, from: Long): Long? {
        var p = from
        while (p + 4 <= src.size) {
            val b = Src.readUpTo(src, p, 65536)
            for (i in 0..b.size - 4) if (b[i] == 'O'.code.toByte() && b[i + 1] == 'g'.code.toByte() && b[i + 2] == 'g'.code.toByte() && b[i + 3] == 'S'.code.toByte()) return p + i
            p += maxOf(1, b.size - 3)
        }
        return null
    }
}

/** Разобранный OpusHead (RFC 7845 §5.1). */
data class OpusHead(val version: Int, val channels: Int, val preSkip: Int, val inputSampleRate: Long, val outputGain: Int, val mappingFamily: Int) {
    companion object {
        fun parse(b: ByteArray): OpusHead? {
            if (b.size < 19 || b.ascii(0, 8) != "OpusHead") return null
            return OpusHead(b.u8(8), b.u8(9), b.u16le(10), b.u32le(12), b.u16le(16).toShort().toInt(), b.u8(18))
        }
    }
}

/** RFC 6716 §3.1: длительность пакета Opus в сэмплах 48 кГц по TOC (timeline.md, ф. 11). */
object OpusToc {
    /** config 0–11 SILK 10/20/40/60; 12–15 Hybrid 10/20; 16–31 CELT 2,5/5/10/20 мс — в сэмплах 48 кГц. */
    private val FRAME48 = intArrayOf(480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 1920, 2880, 480, 960, 480, 960,
        120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960, 120, 240, 480, 960)

    fun samples48(pkt: ByteArray, off: Int = 0, len: Int = pkt.size - off): Int {
        require(len >= 1) { "пустой пакет Opus" }
        val toc = pkt.u8(off)
        val fsz = FRAME48[toc ushr 3]
        val nf = when (toc and 3) {          // c: 0 → 1 кадр; 1, 2 → 2 кадра; 3 → M = b1 & 0x3F
            0 -> 1
            1, 2 -> 2
            else -> { require(len >= 2) { "пакет Opus c=3 без байта счётчика" }; pkt.u8(off + 1) and 0x3F }
        }
        val n = nf * fsz
        require(n <= 5760) { "пакет Opus длиннее 120 мс" }
        return n
    }
}
