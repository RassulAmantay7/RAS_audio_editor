package com.rassul.audioeditor.core.format.mp4

import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CoreException

/*
 * Запись .m4a (ISO/IEC 14496-12 ISOBMFF + ISO/IEC 14496-14 MP4) для готовых AAC-кадров.
 * Раскладка: ftyp, free(8)+mdat (заголовок 16 байт: при > 4 ГБ превращается в mdat с largesize), moov в конце.
 * Тайминг — docs/math/timeline.md (FR-25, формула 10):
 *  mvhd.timescale = mdhd.timescale = fs (иначе segment_duration грубее сэмпла);
 *  elst: media_time = encoderDelay (шкала mdhd), segment_duration = validFrames (шкала mvhd), §8.6.6;
 *  stts: все кадры по F, последний = delay + N − (K−1)·F, так что Σstts = delay + N (конвенция ffmpeg);
 *  sgpd/sbgp 'roll', roll_distance = −1 (§10.1, AudioRollRecoveryEntry);
 *  iTunSMPB (Apple TN2258) — дублирует delay/padding/длину для плееров Apple.
 */

/** Расчёт тайминга дорожки (чистая функция, проверяется векторами fr25_mp4). */
data class Mp4Timing(
    val mvhdTimescale: Int,
    val mdhdTimescale: Int,
    val elstMediaTime: Long,
    val elstSegmentDuration: Long,
    /** Пары (число сэмплов, длительность). */
    val stts: List<LongArray>,
    val mdhdDuration: Long,
    val padding: Long,
) {
    companion object {
        /**
         * nPackets — K, frame — F (1024 для AAC-LC), delay — media_time, nOut — N.
         * Условие: (K−1)·F < delay + N ≤ K·F (иначе пакетов больше/меньше, чем нужно — IllegalArgumentException).
         * segment_duration = N, если mvhd_ts = fs; иначе round_half_up(N·mvhd_ts/fs) (ISO/IEC 14496-12 §8.6.6, timeline.md ф.10).
         */
        fun compute(nOut: Long, delay: Long, fs: Int, nPackets: Int, frame: Int, mvhdTs: Int = fs): Mp4Timing {
            require(nOut >= 0 && delay >= 0 && nPackets >= 0)
            val total = delay + nOut
            require(total <= nPackets.toLong() * frame) { "delay+N=$total > K·F=${nPackets.toLong() * frame}" }
            // Σstts должна быть ровно delay + N (конвенция ffmpeg, timeline.md «FR-25»; проверка FR-33): последний пакет
            // получает длительность delay + N − (K−1)·F ∈ [1, F]. Лишние пакеты сделать этого не дают — ошибка вызывающего.
            require(nPackets == 0 || total > (nPackets - 1).toLong() * frame) {
                "Пакетов больше, чем нужно: K=$nPackets по $frame сэмплов, а delay+N=$total укладывается в " +
                    "${(total + frame - 1) / frame} — уберите лишние пакеты в конце (иначе Σstts ≠ delay+N)."
            }
            val seg = if (mvhdTs == fs) nOut else (nOut * mvhdTs + fs / 2) / fs
            val stts = ArrayList<LongArray>()
            if (nPackets > 0) {
                val last = total - (nPackets - 1).toLong() * frame
                if (nPackets > 1) stts += longArrayOf((nPackets - 1).toLong(), frame.toLong())
                if (last == frame.toLong() && stts.isNotEmpty()) stts[0][0]++ else stts += longArrayOf(1, last)
            }
            val mdhdDur = stts.sumOf { it[0] * it[1] }
            return Mp4Timing(mvhdTs, fs, delay, seg, stts, mdhdDur, nPackets.toLong() * frame - total)
        }
    }
}

class Mp4AacWriterImpl(
    private val sink: SeekableSink,
    private val sampleRate: Int,
    private val channels: Int,
    private val asc: ByteArray,
    private val framesPerPacket: Int,
) {
    var writeITunSmpb = true
    private val start = sink.position
    private val mdatHeaderPos: Long
    private val mdatDataStart: Long
    private var sizes = IntArray(1024)
    private var count = 0
    private var dataBytes = 0L
    private var maxPacket = 0
    private var finished = false

    init {
        require(asc.size >= 2) { "AudioSpecificConfig пуст" }
        val h = ByteBuf(64)
        // ftyp (§4.3): major 'M4A ', minor 0, совместимые 'M4A ', 'mp42', 'isom'.
        h.u32be(28).ascii("ftyp").ascii("M4A ").u32be(0).ascii("M4A ").ascii("mp42").ascii("isom")
        mdatHeaderPos = start + h.size
        // Место под mdat с 64-битным размером: сейчас 'free'(8) + 'mdat'(8, size 32 бита).
        h.u32be(8).ascii("free").u32be(0).ascii("mdat")
        mdatDataStart = start + h.size
        sink.write(h.data, 0, h.size)
    }

    fun writePacket(data: ByteArray, off: Int, len: Int) {
        check(!finished)
        require(len > 0) { "пустой AAC-кадр" }
        sink.write(data, off, len)
        if (count == sizes.size) sizes = sizes.copyOf(count * 2)
        sizes[count++] = len
        dataBytes += len
        if (len > maxPacket) maxPacket = len
    }

    fun finish(encoderDelay: Int, validFrames: Long) {
        check(!finished); finished = true
        val t = Mp4Timing.compute(validFrames, encoderDelay.toLong(), sampleRate, count, framesPerPacket)
        val mdatEnd = sink.position
        // Заголовок mdat (§8.1.1): size = 8 + данные, либо size = 1 + largesize (64 бит) при > 4 ГБ.
        val hb = ByteBuf(16)
        val mdatSize = 8 + dataBytes
        if (mdatSize <= 0xFFFFFFFFL) hb.u32be(8).ascii("free").u32be(mdatSize).ascii("mdat")
        else hb.u32be(1).ascii("mdat").u64be(16 + dataBytes)
        sink.seek(mdatHeaderPos); sink.write(hb.data, 0, 16); sink.seek(mdatEnd)
        val moov = buildMoov(t, encoderDelay.toLong(), validFrames)
        sink.write(moov, 0, moov.size)
        sink.flush()
    }

    // ------------------------------------------------------------------ moov

    private inline fun ByteBuf.box(type: String, body: ByteBuf.() -> Unit): ByteBuf {
        val p = size; u32be(0); ascii(type); body(); putU32beAt(p, (size - p).toLong()); return this
    }
    private inline fun ByteBuf.fullBox(type: String, version: Int, flags: Int, body: ByteBuf.() -> Unit): ByteBuf =
        box(type) { u8(version); u24be(flags); body() }

    private fun ByteBuf.matrix() {
        // Единичная матрица (§8.2.2): {0x00010000,0,0, 0,0x00010000,0, 0,0,0x40000000}
        u32be(0x00010000); u32be(0); u32be(0); u32be(0); u32be(0x00010000); u32be(0); u32be(0); u32be(0); u32be(0x40000000)
    }

    private fun buildMoov(t: Mp4Timing, delay: Long, n: Long): ByteArray {
        val ts = t.mvhdTimescale
        val movieDur = t.elstSegmentDuration
        val v1 = movieDur > 0xFFFFFFFFL || t.mdhdDuration > 0xFFFFFFFFL
        val ver = if (v1) 1 else 0
        val b = ByteBuf(4096 + count * 4)
        b.box("moov") {
            // mvhd (§8.2.2)
            fullBox("mvhd", ver, 0) {
                if (v1) { u64be(0); u64be(0); u32be(ts); u64be(movieDur) } else { u32be(0); u32be(0); u32be(ts); u32be(movieDur) }
                u32be(0x00010000); u16be(0x0100); zeros(10); matrix(); zeros(24); u32be(2)
            }
            box("trak") {
                // tkhd (§8.3.2): flags 3 = enabled | in_movie; duration — в шкале mvhd.
                fullBox("tkhd", ver, 3) {
                    if (v1) { u64be(0); u64be(0); u32be(1); u32be(0); u64be(movieDur) } else { u32be(0); u32be(0); u32be(1); u32be(0); u32be(movieDur) }
                    zeros(8); u16be(0); u16be(1); u16be(0x0100); u16be(0); matrix(); u32be(0); u32be(0)
                }
                // edts/elst (§8.6.5–8.6.6): один сегмент (segment_duration, media_time, rate 1.0).
                box("edts") {
                    val ev1 = t.elstSegmentDuration > 0xFFFFFFFFL || t.elstMediaTime > 0x7FFFFFFFL
                    fullBox("elst", if (ev1) 1 else 0, 0) {
                        u32be(1)
                        if (ev1) { u64be(t.elstSegmentDuration); u64be(t.elstMediaTime) } else { u32be(t.elstSegmentDuration); u32be(t.elstMediaTime) }
                        u16be(1); u16be(0)
                    }
                }
                box("mdia") {
                    // mdhd (§8.4.2): timescale = fs, duration = Σstts; язык 'und' (ISO-639-2/T, упакованный: 0x55C4).
                    fullBox("mdhd", ver, 0) {
                        if (v1) { u64be(0); u64be(0); u32be(t.mdhdTimescale); u64be(t.mdhdDuration) } else { u32be(0); u32be(0); u32be(t.mdhdTimescale); u32be(t.mdhdDuration) }
                        u16be(0x55C4); u16be(0)
                    }
                    // hdlr (§8.4.3): 'soun'
                    fullBox("hdlr", 0, 0) { u32be(0); ascii("soun"); zeros(12); ascii("SoundHandler"); u8(0) }
                    box("minf") {
                        fullBox("smhd", 0, 0) { u16be(0); u16be(0) }                      // §12.2.2
                        box("dinf") { fullBox("dref", 0, 0) { u32be(1); fullBox("url ", 0, 1) {} } }   // §8.7.2: данные в этом файле
                        box("stbl") { stbl(t) }
                    }
                }
            }
            if (writeITunSmpb) udta(delay, t.padding, n)
        }
        return b.toByteArray()
    }

    private fun ByteBuf.stbl(t: Mp4Timing) {
        // stsd (§8.5.2) + AudioSampleEntry 'mp4a' (§12.2.3, ISO/IEC 14496-14 §5.6)
        fullBox("stsd", 0, 0) {
            u32be(1)
            box("mp4a") {
                zeros(6); u16be(1)                    // reserved, data_reference_index = 1
                zeros(8)                              // reserved (version/revision/vendor)
                u16be(channels); u16be(16)            // channelcount, samplesize
                u16be(0); u16be(0)                    // pre_defined, reserved
                u32be(if (sampleRate <= 0xFFFF) sampleRate.toLong() shl 16 else 0L)   // samplerate 16.16
                esds()
            }
        }
        // stts (§8.6.1.2)
        fullBox("stts", 0, 0) { u32be(t.stts.size); for (e in t.stts) { u32be(e[0]); u32be(e[1]) } }
        // stsc (§8.7.4): куски по spc пакетов (~1 с), последний — остаток.
        val spc = maxOf(1, sampleRate / framesPerPacket)
        val fullChunks = count / spc
        val rem = count % spc
        val nChunks = fullChunks + (if (rem > 0) 1 else 0)
        fullBox("stsc", 0, 0) {
            val entries = (if (fullChunks > 0) 1 else 0) + (if (rem > 0) 1 else 0)
            u32be(entries)
            if (fullChunks > 0) { u32be(1); u32be(spc); u32be(1) }
            if (rem > 0) { u32be(fullChunks + 1); u32be(rem); u32be(1) }
        }
        // stsz (§8.7.3)
        fullBox("stsz", 0, 0) { u32be(0); u32be(count); for (i in 0 until count) u32be(sizes[i]) }
        // stco/co64 (§8.7.5): смещения кусков от начала файла.
        val offsets = LongArray(nChunks)
        var off = mdatDataStart - start
        var si = 0
        for (c in 0 until nChunks) {
            offsets[c] = off
            val m = if (c < fullChunks) spc else rem
            for (k in 0 until m) off += sizes[si++]
        }
        if (off > 0xFFFFFFFFL) fullBox("co64", 0, 0) { u32be(nChunks); for (o in offsets) u64be(o) }
        else fullBox("stco", 0, 0) { u32be(nChunks); for (o in offsets) u32be(o) }
        // sgpd/sbgp 'roll' (§8.9.2–8.9.3, §10.1): roll_distance = −1 — для декодирования нужен предыдущий кадр.
        fullBox("sgpd", 1, 0) { ascii("roll"); u32be(2); u32be(1); u16be(-1 and 0xFFFF) }
        fullBox("sbgp", 0, 0) { ascii("roll"); u32be(1); u32be(count); u32be(1) }
    }

    /** esds (ISO/IEC 14496-14 §5.6, 14496-1 §7.2.6): ES_Descriptor → DecoderConfigDescriptor → DecoderSpecificInfo (ASC), SLConfig. */
    private fun ByteBuf.esds() {
        fun ByteBuf.descr(tag: Int, len: Int) { u8(tag); u8(0x80 or ((len ushr 21) and 0x7F)); u8(0x80 or ((len ushr 14) and 0x7F)); u8(0x80 or ((len ushr 7) and 0x7F)); u8(len and 0x7F) }
        val dsiLen = asc.size
        val dcdLen = 13 + 5 + dsiLen
        val esLen = 3 + 5 + dcdLen + 5 + 1
        // Средний битрейт = байт·8·fs/(K·F); максимальный — оценка по самому большому кадру.
        val totalFrames = count.toLong() * framesPerPacket
        val avg = if (totalFrames > 0) dataBytes * 8 * sampleRate / totalFrames else 0
        val mx = maxPacket.toLong() * 8 * sampleRate / framesPerPacket
        fullBox("esds", 0, 0) {
            descr(0x03, esLen); u16be(1); u8(0)                    // ES_ID, flags
            descr(0x04, dcdLen)
            u8(0x40)                                               // objectTypeIndication: Audio ISO/IEC 14496-3
            u8((0x05 shl 2) or 1)                                  // streamType = AudioStream, upStream 0, reserved 1
            u24be(maxPacket); u32be(mx); u32be(avg)                // bufferSizeDB, maxBitrate, avgBitrate
            descr(0x05, dsiLen); bytes(asc)
            descr(0x06, 1); u8(0x02)                               // SLConfigDescriptor, predefined = 2 (MP4)
        }
    }

    /** udta/meta/ilst/'----' iTunSMPB (Apple TN2258): « 00000000 delay padding length 00000000×8» в hex. */
    private fun ByteBuf.udta(delay: Long, padding: Long, n: Long) {
        val s = " 00000000 %08X %08X %016X".format(delay, padding, n) + " 00000000".repeat(8)
        box("udta") {
            fullBox("meta", 0, 0) {
                fullBox("hdlr", 0, 0) { u32be(0); ascii("mdir"); ascii("appl"); zeros(8); u8(0) }
                box("ilst") {
                    box("----") {
                        fullBox("mean", 0, 0) { ascii("com.apple.iTunes") }
                        fullBox("name", 0, 0) { ascii("iTunSMPB") }
                        box("data") { u32be(1); u32be(0); ascii(s) }     // тип 1 = UTF-8
                    }
                }
            }
        }
    }
}

/** Ошибка формата MP4 на входе. */
class Mp4Exception(msg: String) : CoreException(msg)
