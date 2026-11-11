package com.rassul.audioeditor.core.format.raw

import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.format.bits.Crc
import com.rassul.audioeditor.core.format.bits.ascii
import com.rassul.audioeditor.core.format.bits.u32be
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.io.RandomAccessSource

/*
 * MPEG-1/2/2.5 Audio (ISO/IEC 11172-3 §2.4.1.3, ISO/IEC 13818-3 §2.4.1.3): заголовок кадра 32 бит,
 * длина кадра, side info Layer III (§2.4.1.7: main_data_begin 9 бит MPEG-1 / 8 бит MPEG-2).
 * Тег Xing/Info + LAME: «Mp3 Info Tag rev 1 specifications» (LAME), ffmpeg libavformat/mp3dec.c.
 * timeline.md: длина = frames·spf − delay − padding; пропуск в начале = delay + 529 (задержка декодера).
 */

/** Задержка декодера MP3 (сэмплов): 528 + 1 (ffmpeg mp3dec.c; timeline.md ф. 8). */
const val MP3_DECODER_DELAY = 529

class Mp3Header(val raw: Int) {
    val versionId = (raw ushr 19) and 3          // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5, 1 — резерв
    val layer = 4 - ((raw ushr 17) and 3)        // 1, 2, 3 (код 0 — резерв → 4)
    val protection = ((raw ushr 16) and 1) == 0  // CRC-16 после заголовка
    val bitrateIndex = (raw ushr 12) and 15
    val srIndex = (raw ushr 10) and 3
    val padding = (raw ushr 9) and 1
    val channelMode = (raw ushr 6) and 3         // 3 = моно
    val channels get() = if (channelMode == 3) 1 else 2
    val valid: Boolean get() = (raw ushr 21) == 0x7FF && versionId != 1 && layer in 1..3 && bitrateIndex in 1..14 && srIndex != 3
    val sampleRate: Int get() = SR[srIndex] shr (when (versionId) { 3 -> 0; 2 -> 1; else -> 2 })
    val bitrateKbps: Int get() {
        val v1 = versionId == 3
        val t = when {
            v1 && layer == 1 -> BR_V1L1; v1 && layer == 2 -> BR_V1L2; v1 -> BR_V1L3
            layer == 1 -> BR_V2L1; else -> BR_V2L23
        }
        return t[bitrateIndex]
    }
    /** Сэмплов в кадре: L1 384, L2 1152, L3 1152 (MPEG-1) / 576 (MPEG-2/2.5) — timeline.md ф. 12. */
    val samples: Int get() = when (layer) { 1 -> 384; 2 -> 1152; else -> if (versionId == 3) 1152 else 576 }
    /** Длина кадра в байтах (11172-3 §2.4.3.1): L1 (12·br/fs + pad)·4; L2 и L3 MPEG-1 144·br/fs + pad; L3 MPEG-2/2.5 72·br/fs + pad. */
    val frameLength: Int get() {
        val br = bitrateKbps * 1000L
        return when {
            layer == 1 -> ((12 * br / sampleRate + padding) * 4).toInt()
            layer == 3 && versionId != 3 -> (72 * br / sampleRate + padding).toInt()
            else -> (144 * br / sampleRate + padding).toInt()
        }
    }
    /** Длина side info Layer III: MPEG-1 17 (моно) / 32; MPEG-2/2.5 9 / 17 байт (§2.4.1.7). */
    val sideInfoLength: Int get() = if (versionId == 3) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)

    companion object {
        private val SR = intArrayOf(44100, 48000, 32000, 0)
        private val BR_V1L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
        private val BR_V1L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
        private val BR_V1L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
        private val BR_V2L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
        private val BR_V2L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
        /** Совместимость заголовков одного потока: версия, слой, частота (биты 19–10 без битрейта/паддинга). */
        fun sameStream(a: Int, b: Int): Boolean = (a and 0xFFFE0C00.toInt()) == (b and 0xFFFE0C00.toInt())
    }
}

class Mp3Frame(offset: Long, size: Int, val header: Mp3Header, val mainDataBegin: Int, val mainDataSize: Int) :
    CodedFrame(offset, size, header.samples, header.sampleRate, header.channels)

/** Тег Xing/Info (+ LAME). frames — число аудиокадров без кадра Info; delay/padding — из LAME (12 + 12 бит). */
data class Mp3InfoTag(val frameOffset: Long, val frameSize: Int, val isVbr: Boolean, val frames: Long?, val bytes: Long?,
                      val encoder: String?, val delay: Int?, val padding: Int?)

class Mp3Stream(val frames: List<Mp3Frame>, val info: Mp3InfoTag?, val id3v2Size: Long, val resyncs: Int) {
    val sampleRate: Int get() = frames.firstOrNull()?.sampleRate ?: 0
    val samplesPerFrame: Int get() = frames.firstOrNull()?.samples ?: 0
    /** Номинальная длина по тегу LAME: frames·spf − delay − padding (timeline.md ф. 8); null — тега нет. */
    val lameLength: Long? get() {
        val i = info ?: return null
        val d = i.delay ?: return null; val p = i.padding ?: return null
        return (i.frames ?: frames.size.toLong()) * samplesPerFrame - d - p
    }
}

object Mp3Scanner {
    fun scan(src: RandomAccessSource): Mp3Stream {
        val w = SrcWindow(src)
        var pos = 0L
        // ID3v2 (id3.org, ID3v2.4 §3.1): "ID3", версия 2, флаги, размер 4×7 бит; флаг footer (0x10) +10 байт.
        var id3 = 0L
        while (true) {
            val h = w.bytes(pos, 10) ?: break
            if (h.ascii(0, 3) != "ID3") break
            val sz = ((h.u8(6) and 0x7F) shl 21) or ((h.u8(7) and 0x7F) shl 14) or ((h.u8(8) and 0x7F) shl 7) or (h.u8(9) and 0x7F)
            pos += 10 + sz + (if (h.u8(5) and 0x10 != 0) 10 else 0)
            id3 = pos
        }
        val frames = ArrayList<Mp3Frame>()
        var resync = 0
        var ref = 0
        val end = w.size
        while (pos + 4 <= end) {
            val hb = w.bytes(pos, 4) ?: break
            val raw = hb.u32be(0).toInt()
            val h = Mp3Header(raw)
            val ok = h.valid && (ref == 0 || Mp3Header.sameStream(ref, raw)) && pos + h.frameLength <= end
            if (ok && (ref != 0 || confirm(w, pos, h))) {
                if (ref == 0) ref = raw
                frames += makeFrame(w, pos, h)
                pos += h.frameLength
                continue
            }
            // Конец потока: ID3v1 «TAG» / APE.
            val t = w.bytes(pos, 3)
            if (t != null && (t.ascii(0, 3) == "TAG" || t.ascii(0, 3) == "APE")) break
            // Поиск синхронизации.
            if (frames.isNotEmpty()) resync++
            pos++
            while (pos + 4 <= end && !(w.u8(pos) == 0xFF && (w.u8(pos + 1) and 0xE0) == 0xE0)) pos++
        }
        var info: Mp3InfoTag? = null
        if (frames.isNotEmpty()) info = parseInfo(w, frames[0])
        val audio = if (info != null) frames.subList(1, frames.size).toList() else frames
        return Mp3Stream(audio, info, id3, resync)
    }

    /** Новая синхронизация подтверждается следующим заголовком того же потока (или концом файла). */
    private fun confirm(w: SrcWindow, pos: Long, h: Mp3Header): Boolean {
        val np = pos + h.frameLength
        if (np + 4 > w.size) return true
        val nb = w.bytes(np, 4) ?: return true
        val nh = Mp3Header(nb.u32be(0).toInt())
        if (nb.ascii(0, 3) == "TAG") return true
        return nh.valid && Mp3Header.sameStream(h.raw, nh.raw)
    }

    private fun makeFrame(w: SrcWindow, pos: Long, h: Mp3Header): Mp3Frame {
        var mdb = 0; var mds = 0
        if (h.layer == 3) {
            val si = pos + 4 + (if (h.protection) 2 else 0)
            val b0 = w.u8(si); val b1 = w.u8(si + 1)
            mdb = if (h.versionId == 3) (b0 shl 1) or (b1 ushr 7) else b0     // main_data_begin: 9 / 8 бит
            mds = h.frameLength - 4 - (if (h.protection) 2 else 0) - h.sideInfoLength
        }
        return Mp3Frame(pos, h.frameLength, h, mdb, mds)
    }

    private fun parseInfo(w: SrcWindow, f: Mp3Frame): Mp3InfoTag? {
        val h = f.header
        if (h.layer != 3) return null
        val b = w.bytes(f.offset, f.size) ?: return null
        val x = 4 + (if (h.protection) 2 else 0) + h.sideInfoLength
        if (x + 8 > b.size) return null
        val id = b.ascii(x, 4)
        if (id != "Xing" && id != "Info") return null
        val flags = b.u32be(x + 4).toInt()
        var p = x + 8
        var frames: Long? = null; var bytes: Long? = null
        if (flags and 1 != 0) { frames = b.u32be(p); p += 4 }
        if (flags and 2 != 0) { bytes = b.u32be(p); p += 4 }
        if (flags and 4 != 0) p += 100
        if (flags and 8 != 0) p += 4
        var enc: String? = null; var delay: Int? = null; var pad: Int? = null
        if (p + 24 <= b.size) {
            val s = b.ascii(p, 9)
            if (s.startsWith("LAME") || s.startsWith("Lavf") || s.startsWith("Lavc") || s.startsWith("L3.99")) {
                enc = s.trimEnd('\u0000', ' ')
                // LAME tag +21: 12 бит delay, 12 бит padding.
                val v = (b.u8(p + 21) shl 16) or (b.u8(p + 22) shl 8) or b.u8(p + 23)
                delay = v ushr 12; pad = v and 0xFFF
            }
        }
        return Mp3InfoTag(f.offset, f.size, id == "Xing", frames, bytes, enc, delay, pad)
    }

    /**
     * Кадры разгона для быстрой обрезки MP3 (timeline.md, «FR-23, MP3»): W = 1 + r, где r — сколько предыдущих
     * кадров нужно, чтобы покрыть main_data_begin кадра i_a−1 (bit reservoir). Минимум 2.
     */
    fun warmupFrames(frames: List<Mp3Frame>, ia: Int): Int {
        if (ia <= 0) return 0
        val prev = ia - 1
        var need = frames[prev].mainDataBegin
        var r = 0
        var j = prev - 1
        while (need > 0 && j >= 0) { need -= frames[j].mainDataSize; r++; j-- }
        return minOf(ia, maxOf(2, 1 + r))
    }

    /**
     * Кадр Xing/LAME «Info» для сырого .mp3 после быстрой обрезки (timeline.md, «FR-23, сырые выходы»):
     * delay = hidden − 529 (0..4095), padding = K·spf − delay − (B−A) (0..4095). Параметры кадра — как у шаблона,
     * битрейт подбирается минимальный, чтобы поместились тег Xing (120 байт) и LAME (36 байт).
     * @param template заголовок первого аудиокадра; audioFrames — K; audioBytes — объём K кадров.
     */
    fun buildInfoFrame(template: Mp3Header, audioFrames: Int, audioBytes: Long, delay: Int, padding: Int): ByteArray {
        require(template.layer == 3)
        require(delay in 0..4095) { "delay=$delay вне 0..4095 (LAME 12 бит)" }
        require(padding in 0..4095) { "padding=$padding вне 0..4095 (LAME 12 бит)" }
        val side = template.sideInfoLength
        val need = 4 + side + 120 + 36
        var hdr: Mp3Header? = null
        for (bi in 1..14) {
            // protection_bit = 1 (без CRC), padding_bit = 0
            val raw = (template.raw and 0xFFFE0FFF.toInt() and (1 shl 9).inv()) or (1 shl 16) or (bi shl 12)
            val h = Mp3Header(raw)
            if (h.frameLength >= need) { hdr = h; break }
        }
        val h = hdr ?: throw IllegalStateException("кадр Info не помещается")
        val len = h.frameLength
        val b = ByteBuf(len)
        b.u32be(h.raw)
        b.zeros(side)                                    // side info = 0 (кадр звучит как тишина у декодеров без LAME)
        val totalBytes = len + audioBytes
        b.ascii("Info").u32be(0x0F)                      // CBR-тег «Info», флаги: frames|bytes|TOC|quality
        b.u32be(audioFrames).u32be(totalBytes)
        for (i in 0 until 100) b.u8(i * 256 / 100)       // TOC для равномерного (CBR) потока: i% файла → i·256/100
        b.u32be(0)                                       // quality
        val lameStart = b.size
        b.ascii("LAME3.100")                             // 9 байт: строка версии (парсеры ищут префикс «LAME»)
        b.u8(0x01)                                       // ревизия тега 0, метод VBR = 1 (CBR)
        b.u8(0)                                          // lowpass
        b.u32be(0); b.u16be(0); b.u16be(0)               // peak, radio/audiophile gain
        b.u8(0); b.u8(0)                                 // флаги кодирования/ATH, битрейт
        b.u24be((delay shl 12) or padding)               // +21: delay (12 бит), padding (12 бит)
        b.u8(0); b.u8(0); b.u16be(0)                     // misc, mp3gain, preset/surround
        b.u32be(totalBytes)                              // music length (вкл. этот кадр)
        b.u16be(0)                                       // music CRC (не используется декодерами)
        val crcPos = b.size
        check(crcPos - lameStart == 34)
        val crc = Crc.crc16Arc(b.data, 0, crcPos)        // Info Tag CRC: CRC-16/ARC по байтам кадра до этого поля
        b.u16be(crc)
        b.zeros(len - b.size)
        return b.toByteArray()
    }
}
