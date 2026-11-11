package com.rassul.audioeditor.core.format.raw

import com.rassul.audioeditor.core.format.bits.u16be
import com.rassul.audioeditor.core.format.bits.u32be
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.io.RandomAccessSource

/*
 * AC-3 и E-AC-3: ATSC A/52:2018 §5.3 (syncinfo, bsi), табл. 5.18 (frmsizecod), Annex E §E.2.2 (frmsiz, numblkscod).
 * DTS: ETSI TS 102 114 §5.3 (кадр ядра, SYNC 0x7FFE8001, NBLKS, FSIZE, SFREQ), §7.4 (заголовок substream расширения 0x64582025).
 * ADTS: ISO/IEC 14496-3 §1.A.2.2 (adts_fixed_header/adts_variable_header).
 */

class Ac3Frame(offset: Long, size: Int, samples: Int, sampleRate: Int, channels: Int, val isEac3: Boolean) :
    CodedFrame(offset, size, samples, sampleRate, channels)

object Ac3Scanner {
    /** Битрейты AC-3 (кбит/с) по frmsizecod/2 (A/52 табл. 5.18). */
    private val BR = intArrayOf(32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)
    private val FS = intArrayOf(48000, 44100, 32000)
    /** Каналов (без LFE) по acmod (A/52 табл. 5.8). */
    private val ACMOD_CH = intArrayOf(2, 1, 2, 3, 3, 4, 4, 5)

    /** Длина кадра AC-3 в байтах (A/52 табл. 5.18): 48 кГц — 4·br; 32 кГц — 6·br; 44,1 кГц — 2·(⌊br·1536/(44,1·16)⌋ + (frmsizecod & 1)). */
    fun ac3FrameBytes(fscod: Int, frmsizecod: Int): Int {
        val br = BR[frmsizecod shr 1]
        return when (fscod) {
            0 -> 4 * br
            2 -> 6 * br
            else -> 2 * (br * 1536 * 1000 / (44100 * 16) + (frmsizecod and 1))
        }
    }

    /** E-AC-3: сэмплов в кадре = 256·{1,2,3,6}[numblkscod]; при fscod = 3 всегда 6 блоков (Annex E, timeline.md ф. 12). */
    fun eac3Samples(numblkscod: Int, fscod: Int): Int = 256 * (if (fscod == 3) 6 else intArrayOf(1, 2, 3, 6)[numblkscod])

    /**
     * Пакеты потока. Для E-AC-3 зависимые подпотоки (strmtyp = 1) и независимые с substreamid > 0
     * присоединяются к предыдущему кадру независимого подпотока 0 (один пакет = одна единица времени).
     */
    fun scan(src: RandomAccessSource): List<Ac3Frame> {
        val w = SrcWindow(src)
        val out = ArrayList<Ac3Frame>()
        var pos = 0L
        while (pos + 8 <= w.size) {
            val b = w.bytes(pos, 8) ?: break
            if (b.u16be(0) != 0x0B77) { pos++; continue }
            val bsid = b.u8(5) ushr 3
            if (bsid <= 10) {
                val fscod = b.u8(4) ushr 6
                val fsc = b.u8(4) and 0x3F
                if (fscod == 3 || fsc >= 38) { pos++; continue }
                val len = ac3FrameBytes(fscod, fsc)
                val acmod = b.u8(6) ushr 5
                out += Ac3Frame(pos, len, 1536, FS[fscod], ACMOD_CH[acmod], false)
                pos += len
            } else if (bsid in 11..16) {
                val strmtyp = b.u8(2) ushr 6
                val substreamid = (b.u8(2) ushr 3) and 7
                val frmsiz = ((b.u8(2) and 7) shl 8) or b.u8(3)
                val len = (frmsiz + 1) * 2                               // Annex E: frmsiz — слов (16 бит) − 1
                val fscod = b.u8(4) ushr 6
                val numblk = (b.u8(4) ushr 4) and 3
                val rate = if (fscod == 3) intArrayOf(24000, 22050, 16000, 0)[numblk] else FS[fscod]
                val acmod = (b.u8(4) ushr 1) and 7
                val independent0 = strmtyp != 1 && substreamid == 0
                if (independent0 || out.isEmpty()) {
                    out += Ac3Frame(pos, len, eac3Samples(numblk, fscod), rate, ACMOD_CH[acmod], true)
                } else {
                    val p = out.removeAt(out.size - 1)
                    out += Ac3Frame(p.offset, p.size + len, p.samples, p.sampleRate, p.channels, true)
                }
                pos += len
            } else pos++
        }
        return out
    }
}

object DtsScanner {
    private val SFREQ = intArrayOf(0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 0, 0)

    /** Сэмплов в кадре ядра = (NBLKS+1)·32 (TS 102 114 §5.3, timeline.md ф. 12). */
    fun samples(nblks: Int): Int = (nblks + 1) * 32

    /** Кадры ядра DTS (16 бит big-endian); substream расширения (DTS-HD) присоединяется к кадру ядра. */
    fun scan(src: RandomAccessSource): List<CodedFrame> {
        val w = SrcWindow(src)
        val out = ArrayList<CodedFrame>()
        var pos = 0L
        while (pos + 12 <= w.size) {
            val b = w.bytes(pos, 12) ?: break
            val sync = b.u32be(0)
            if (sync == 0x7FFE8001L) {
                // FTYPE 1, SHORT 5, CPF 1, NBLKS 7, FSIZE 14, AMODE 6, SFREQ 4 …
                val v = b.u32be(4)
                val nblks = ((v ushr 18) and 0x7F).toInt()
                val fsize = ((v ushr 4) and 0x3FFF).toInt() + 1
                val amode = ((v and 0xF).toInt() shl 2) or (b.u8(8) ushr 6)
                val sfreq = (b.u8(8) ushr 2) and 0xF
                val ch = when (amode) { 0 -> 1; 1, 2, 3, 4 -> 2; 5, 6 -> 3; 7, 8 -> 4; 9 -> 5; else -> 6 }
                out += CodedFrame(pos, fsize, samples(nblks), SFREQ[sfreq], ch)
                pos += fsize
            } else if (sync == 0x64582025L && out.isNotEmpty()) {
                // Заголовок substream (§7.4): UserDefinedBits 8, ExtSSIndex 2, bHeaderSizeType 1, затем размеры 8/16 или 12/20 бит.
                // Биты от MSB 64-битного слова после sync: 0–7 user, 8–9 index, 10 hst; hst=0: 11–18 header, 19–34 fsize;
                // hst=1: 11–22 header, 23–42 fsize. nuExtSSFsize — размер − 1.
                val vv = (b.u32be(4) shl 32) or b.u32be(8)
                val hst = ((vv ushr 53) and 1).toInt()
                val fs = if (hst == 0) ((vv ushr 29) and 0xFFFF).toInt() + 1 else ((vv ushr 21) and 0xFFFFF).toInt() + 1
                val p = out.removeAt(out.size - 1)
                out += CodedFrame(p.offset, p.size + fs, p.samples, p.sampleRate, p.channels)
                pos += fs
            } else pos++
        }
        return out
    }
}

/** Кадр ADTS: payload — сырой AAC без заголовка (7 или 9 байт). */
class AdtsFrame(offset: Long, size: Int, sampleRate: Int, channels: Int, val headerSize: Int, val profile: Int,
                val srIndex: Int, val channelConfig: Int, val rawBlocks: Int) :
    CodedFrame(offset, size, 1024 * rawBlocks, sampleRate, channels)

object AdtsScanner {
    val SAMPLE_RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    fun scan(src: RandomAccessSource): List<AdtsFrame> {
        val w = SrcWindow(src)
        val out = ArrayList<AdtsFrame>()
        var pos = 0L
        while (pos + 7 <= w.size) {
            val b = w.bytes(pos, 7) ?: break
            if (b.u8(0) != 0xFF || (b.u8(1) and 0xF6) != 0xF0) { pos++; continue }   // syncword 0xFFF, layer 00
            val protAbsent = b.u8(1) and 1
            val profile = b.u8(2) ushr 6                                              // AOT − 1
            val sfi = (b.u8(2) ushr 2) and 0xF
            val cc = ((b.u8(2) and 1) shl 2) or (b.u8(3) ushr 6)
            val len = ((b.u8(3) and 3) shl 11) or (b.u8(4) shl 3) or (b.u8(5) ushr 5)  // aac_frame_length (13 бит, с заголовком)
            val blocks = (b.u8(6) and 3) + 1
            if (sfi >= SAMPLE_RATES.size || len < 7) { pos++; continue }
            val hs = if (protAbsent == 1) 7 else 9
            out += AdtsFrame(pos, len, SAMPLE_RATES[sfi], if (cc == 7) 8 else cc, hs, profile, sfi, cc, blocks)
            pos += len
        }
        return out
    }

    /** AudioSpecificConfig из ADTS (14496-3 §1.6.2.1): AOT(5) = profile+1, samplingFrequencyIndex(4), channelConfiguration(4), 3 нулевых бита GASpecificConfig. */
    fun audioSpecificConfig(f: AdtsFrame): ByteArray {
        val v = ((f.profile + 1) shl 11) or (f.srIndex shl 7) or (f.channelConfig shl 3)
        return byteArrayOf((v ushr 8).toByte(), v.toByte())
    }
}
