package com.rassul.audioeditor.core.format.mp4

import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.u16be
import com.rassul.audioeditor.core.format.bits.u32be
import com.rassul.audioeditor.core.format.bits.u64be
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.io.RandomAccessSource

/*
 * Чтение боксов ISO/IEC 14496-12 (§4.2 Box: size 32 бит, 1 → largesize 64 бит, 0 → до конца файла).
 * Нужно для: копирования AAC «как есть» и быстрой обрезки из .m4a (stsz/stco/stsc), GaplessProbe (elst, iTunSMPB),
 * автопроверки FR-33 (FileFacts.mp4Audio). Фрагментированный MP4 (moof) не поддерживается.
 */
class Box(val type: String, val offset: Long, val size: Long, val headerSize: Int) {
    val children = ArrayList<Box>()
    val bodyOffset: Long get() = offset + headerSize
    val bodySize: Long get() = size - headerSize
    fun child(t: String): Box? = children.firstOrNull { it.type == t }
    fun all(t: String): List<Box> = children.filter { it.type == t }
    fun path(vararg p: String): Box? { var b: Box? = this; for (t in p) b = b?.child(t); return b }
    override fun toString() = "$type@$offset+$size"
}

object Mp4BoxReader {
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "udta", "dinf", "mvex", "moof", "traf", "ilst", "----", "sinf", "schi")

    /** Разобрать дерево боксов (верхний уровень). */
    fun parse(src: RandomAccessSource): List<Box> = parseRange(src, 0, src.size, 0)

    private fun parseRange(src: RandomAccessSource, from: Long, to: Long, depth: Int): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val h = Src.readFully(src, pos, 8) ?: break
            var size = h.u32be(0)
            val type = h.ascii(4, 4)
            var hs = 8
            if (size == 1L) { val l = Src.readFully(src, pos + 8, 8) ?: break; size = l.u64be(0); hs = 16 }
            else if (size == 0L) size = to - pos
            if (size < hs || pos + size > to) {
                // Обрезанный бокс (например, файл усечён): берём то, что есть, и прекращаем.
                val b = Box(type, pos, to - pos, hs); out += b; break
            }
            val b = Box(type, pos, size, hs)
            if (depth < 12) when {
                type in CONTAINERS -> b.children += parseRange(src, pos + hs, pos + size, depth + 1)
                type == "meta" -> {
                    // meta — FullBox (§8.11.1); у QuickTime бывает без version/flags: проверяем, что дальше бокс hdlr.
                    val peek = Src.readFully(src, pos + hs, 12)
                    val skip = if (peek != null && peek.ascii(4, 4) == "hdlr") 0 else 4
                    b.children += parseRange(src, pos + hs + skip, pos + size, depth + 1)
                }
                type == "stsd" -> b.children += parseRange(src, pos + hs + 8, pos + size, depth + 1)
                type == "mp4a" || type == "enca" -> b.children += parseRange(src, pos + hs + 28, pos + size, depth + 1)
            }
            out += b
            pos += size
        }
        return out
    }

    /** Предел тела бокса, читаемого целиком (stsz 64 Мсэмплов ≈ 256 МБ; больше — признак повреждения). */
    const val MAX_BODY = 256 shl 20

    fun body(src: RandomAccessSource, b: Box, max: Int = Int.MAX_VALUE): ByteArray {
        val n = minOf(b.bodySize, max.toLong())
        if (n < 0 || n > MAX_BODY) throw Mp4FormatException("бокс ${b.type}: размер тела $n байт вне допустимого")
        return Src.readFully(src, b.bodyOffset, n.toInt()) ?: ByteArray(0)
    }

    /**
     * Защита от повреждённого счётчика записей (ISO/IEC 14496-12: entry_count — 32 бит без знака):
     * header + n·entry должно поместиться в тело бокса — проверяется ДО выделения массива на n элементов.
     */
    fun entryCount(b: ByteArray, at: Int, entry: Int, header: Int, type: String): Int {
        if (at + 4 > b.size) throw Mp4FormatException("бокс $type обрезан")
        val n = b.u32be(at)
        if (header + n * entry > b.size) throw Mp4FormatException("бокс $type: $n записей не помещаются в ${b.size} байт")
        return n.toInt()
    }
}

/** Повреждённая структура MP4 (читатели ловят как RuntimeException и считают файл нечитаемым). */
class Mp4FormatException(msg: String) : RuntimeException(msg)

/** Сведения о звуковой дорожке MP4 (первая дорожка с hdlr 'soun'). */
class Mp4AudioTrack(
    val mvhdTimescale: Long,
    val mvhdDuration: Long,
    val mdhdTimescale: Long,
    val mdhdDuration: Long,
    /** Сегменты elst: (segment_duration [mvhd], media_time [mdhd]); пусто — нет elst. */
    val editList: List<LongArray>,
    val codec: String,
    val channels: Int,
    val sampleEntryRate: Int,
    val audioSpecificConfig: ByteArray?,
    val objectTypeIndication: Int,
    val stts: List<LongArray>,
    val sampleSizes: IntArray,
    val sampleOffsets: LongArray,
    val iTunSmpb: String?,
    val trackCount: Int,
    val audioTrackCount: Int,
) {
    val sampleCount: Int get() = sampleSizes.size
    val sttsTotal: Long get() = stts.sumOf { it[0] * it[1] }
    /** media_time первого непустого сегмента elst (−1 у пустого сегмента — пропуск, §8.6.6). */
    val elstMediaTime: Long? get() = editList.firstOrNull { it[1] >= 0 }?.get(1)
    val elstSegmentDuration: Long? get() = editList.firstOrNull { it[1] >= 0 }?.get(0)
    /** Длительность каждого сэмпла по stts (в шкале mdhd). */
    fun sampleDurations(): LongArray {
        val d = LongArray(sampleCount); var i = 0
        for (e in stts) for (k in 0 until e[0]) { if (i < d.size) d[i++] = e[1] }
        return d
    }
    /** Аудиообъект AAC из AudioSpecificConfig (ISO/IEC 14496-3 §1.6.2.1): 2 = LC, 5 = SBR (HE), 29 = PS (HE v2), 42 = USAC. */
    val audioObjectType: Int get() {
        val a = audioSpecificConfig ?: return 0
        if (a.isEmpty()) return 0
        val aot = a.u8(0) ushr 3
        return if (aot == 31 && a.size >= 2) 32 + (((a.u8(0) and 7) shl 3) or (a.u8(1) ushr 5)) else aot
    }

    companion object {
        fun read(src: RandomAccessSource): Mp4AudioTrack? {
            val top = Mp4BoxReader.parse(src)
            val moov = top.firstOrNull { it.type == "moov" } ?: return null
            val mvhd = moov.child("mvhd") ?: return null
            val mv = Mp4BoxReader.body(src, mvhd, 32)
            val (mvTs, mvDur) = if (mv.u8(0) == 1) mv.u32be(20) to mv.u64be(24) else mv.u32be(12) to mv.u32be(16)
            val traks = moov.all("trak")
            var audio: Box? = null; var nAudio = 0
            for (t in traks) {
                val hdlr = t.path("mdia", "hdlr") ?: continue
                val hb = Mp4BoxReader.body(src, hdlr, 12)
                if (hb.size >= 12 && hb.ascii(8, 4) == "soun") { nAudio++; if (audio == null) audio = t }
            }
            val trak = audio ?: return null
            val mdhd = trak.path("mdia", "mdhd") ?: return null
            val md = Mp4BoxReader.body(src, mdhd, 36)
            val (mdTs, mdDur) = if (md.u8(0) == 1) md.u32be(20) to md.u64be(24) else md.u32be(12) to md.u32be(16)
            // elst (§8.6.6)
            val edits = ArrayList<LongArray>()
            trak.path("edts", "elst")?.let { e ->
                val b = Mp4BoxReader.body(src, e)
                val v = b.u8(0); val n = Mp4BoxReader.entryCount(b, 4, if (b.u8(0) == 1) 20 else 12, 8, "elst")
                var p = 8
                for (i in 0 until n) {
                    if (v == 1) { if (p + 20 > b.size) break; edits += longArrayOf(b.u64be(p), b.u64be(p + 8)); p += 20 }
                    else { if (p + 12 > b.size) break; edits += longArrayOf(b.u32be(p), b.u32be(p + 4).toInt().toLong()); p += 12 }
                }
            }
            val stbl = trak.path("mdia", "minf", "stbl") ?: return null
            val stsd = stbl.child("stsd")
            val entry = stsd?.children?.firstOrNull()
            var codec = entry?.type ?: "?"
            var ch = 0; var rate = 0; var asc: ByteArray? = null; var oti = 0
            if (entry != null) {
                val eb = Src.readFully(src, entry.bodyOffset, 28)
                if (eb != null) { ch = eb.u16be(16); rate = (eb.u32be(24) ushr 16).toInt() }
                entry.child("esds")?.let { es ->
                    val r = parseEsds(Mp4BoxReader.body(src, es)); asc = r.second; oti = r.first
                }
            }
            val stts = ArrayList<LongArray>()
            stbl.child("stts")?.let { b0 ->
                val b = Mp4BoxReader.body(src, b0); val n = Mp4BoxReader.entryCount(b, 4, 8, 8, "stts")
                for (i in 0 until n) { val p = 8 + i * 8; if (p + 8 > b.size) break; stts += longArrayOf(b.u32be(p), b.u32be(p + 4)) }
            }
            // stsz (§8.7.3)
            var sizes = IntArray(0)
            stbl.child("stsz")?.let { b0 ->
                val b = Mp4BoxReader.body(src, b0)
                if (b.size < 12) throw Mp4FormatException("бокс stsz обрезан")
                val fixed = b.u32be(4)
                val n = if (fixed == 0L) Mp4BoxReader.entryCount(b, 8, 4, 12, "stsz") else {
                    // Общий размер: n не ограничен телом бокса — ограничиваем размером файла (n сэмплов по fixed байт).
                    val c = b.u32be(8)
                    if (fixed > Int.MAX_VALUE || c * fixed > src.size) throw Mp4FormatException("stsz: $c сэмплов по $fixed байт больше файла")
                    c.toInt()
                }
                sizes = if (fixed != 0L) IntArray(n) { fixed.toInt() } else IntArray(n) { b.u32be(12 + it * 4).toInt() }
            }
            // stco/co64 + stsc → смещения сэмплов (§8.7.4–8.7.5)
            val chunkOff: LongArray = stbl.child("stco")?.let { b0 ->
                val b = Mp4BoxReader.body(src, b0); LongArray(Mp4BoxReader.entryCount(b, 4, 4, 8, "stco")) { b.u32be(8 + it * 4) }
            } ?: stbl.child("co64")?.let { b0 ->
                val b = Mp4BoxReader.body(src, b0); LongArray(Mp4BoxReader.entryCount(b, 4, 8, 8, "co64")) { b.u64be(8 + it * 8) }
            } ?: LongArray(0)
            val stsc = ArrayList<IntArray>()
            stbl.child("stsc")?.let { b0 ->
                val b = Mp4BoxReader.body(src, b0); val n = Mp4BoxReader.entryCount(b, 4, 12, 8, "stsc")
                for (i in 0 until n) { val p = 8 + i * 12; stsc += intArrayOf(b.u32be(p).toInt(), b.u32be(p + 4).toInt()) }
            }
            val offsets = LongArray(sizes.size)
            var si = 0
            var ei = 0; var spc = 0
            for (c in chunkOff.indices) {
                if (si >= sizes.size) break
                val chunkNo = c + 1
                // stsc упорядочен по first_chunk (§8.7.4): один проход вместо O(чанков·записей) на повреждённом файле.
                while (ei < stsc.size && stsc[ei][0] <= chunkNo) { spc = stsc[ei][1]; ei++ }
                var o = chunkOff[c]
                for (k in 0 until spc) { if (si >= sizes.size) break; offsets[si] = o; o += sizes[si]; si++ }
            }
            var smpb: String? = null
            moov.child("udta")?.child("meta")?.child("ilst")?.all("----")?.forEach { f ->
                val name = f.child("name")?.let { String(Mp4BoxReader.body(src, it)).substring(4) }
                if (name == "iTunSMPB") f.child("data")?.let { d -> smpb = String(Mp4BoxReader.body(src, d)).substring(8) }
            }
            return Mp4AudioTrack(mvTs, mvDur, mdTs, mdDur, edits, codec, ch, rate, asc, oti, stts, sizes, offsets, smpb, traks.size, nAudio)
        }

        /** esds: найти DecoderConfigDescriptor (тег 4) и DecoderSpecificInfo (тег 5) (ISO/IEC 14496-1 §7.2.6). */
        fun parseEsds(b: ByteArray): Pair<Int, ByteArray?> {
            var p = 4
            var oti = 0; var dsi: ByteArray? = null
            fun len(): Int { var l = 0; for (i in 0 until 4) { val x = b.u8(p++); l = (l shl 7) or (x and 0x7F); if (x and 0x80 == 0) break }; return l }
            try {
                while (p < b.size) {
                    val tag = b.u8(p++); val l = len()
                    when (tag) {
                        0x03 -> { val flags = b.u8(p + 2); p += 3; if (flags and 0x80 != 0) p += 2; if (flags and 0x40 != 0) p += 1 + b.u8(p); if (flags and 0x20 != 0) p += 2 }
                        0x04 -> { oti = b.u8(p); p += 13 }
                        0x05 -> { dsi = b.copyOfRange(p, p + l); p += l }
                        else -> p += l
                    }
                }
            } catch (_: IndexOutOfBoundsException) { }
            return oti to dsi
        }

        /** iTunSMPB (Apple TN2258): поля 2–4 в hex — delay, padding, длина. */
        fun parseITunSmpb(s: String): LongArray? {
            val f = s.trim().split(Regex("\\s+"))
            if (f.size < 4) return null
            return try { longArrayOf(f[1].toLong(16), f[2].toLong(16), f[3].toLong(16)) } catch (_: NumberFormatException) { null }
        }
    }
}
