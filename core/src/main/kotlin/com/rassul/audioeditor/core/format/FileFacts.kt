package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.format.flac.FlacStreamInfo
import com.rassul.audioeditor.core.format.mp4.Mp4AudioTrack
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.ogg.OpusHead
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.format.wav.WavInfo
import com.rassul.audioeditor.core.format.wav.WavParser
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.model.CoreException

/*
 * Независимый разбор метаданных сохранённого файла для автопроверки FR-33 (timeline.md, «FR-33: автопроверка»).
 * Все методы бросают CoreException с понятным текстом, если контейнер не читается.
 */

typealias WavFacts = WavInfo
typealias StreamInfo = FlacStreamInfo

data class Mp4Facts(
    val mvhdTimescale: Long,
    val mdhdTimescale: Long,
    val elstMediaTime: Long?,
    val elstSegmentDuration: Long?,
    val sampleCount: Int,
    val sttsTotal: Long,
    val stts: List<LongArray>,
    val codec: String,
    val channels: Int,
    val sampleRate: Int,
    val audioObjectType: Int,
    val iTunSmpb: String?,
    val audioTrackCount: Int,
    /** Длина по elst в шкале mdhd: segment_duration·mdhd_ts/mvhd_ts (timeline.md, FR-33 строка AAC); null — нет elst. */
    val lengthFrames: Long?,
    val dataBytes: Long,
)

data class OggFacts(
    val preSkip: Int,
    val lastGranule: Long,
    val channels: Int,
    val inputSampleRate: Long,
    val packetCount: Int,
    /** Σ длительностей аудиопакетов (48 кГц). */
    val totalSamples48: Long,
    val crcErrors: Int,
    val hasEos: Boolean,
) {
    /** N₄₈ = гранула последней страницы − pre_skip (RFC 7845 §4.4). */
    val length48: Long get() = lastGranule - preSkip
}

object FileFacts {
    fun wav(src: RandomAccessSource): WavFacts = WavParser.parse(src)

    fun flacStreamInfo(src: RandomAccessSource): StreamInfo = FlacFrames.readHeader(src).first

    fun mp4Audio(src: RandomAccessSource): Mp4Facts {
        val t = try { Mp4AudioTrack.read(src) } catch (e: RuntimeException) { null }
            ?: throw CoreException("Контейнер M4A не читается (нет описания дорожки — файл, возможно, обрезан).")
        val len = t.elstSegmentDuration?.let { seg -> if (t.mvhdTimescale > 0) (seg * t.mdhdTimescale + t.mvhdTimescale / 2) / t.mvhdTimescale else null }
        return Mp4Facts(t.mvhdTimescale, t.mdhdTimescale, t.elstMediaTime, t.elstSegmentDuration, t.sampleCount, t.sttsTotal,
            t.stts, t.codec, t.channels, t.sampleEntryRate, t.audioObjectType, t.iTunSmpb, t.audioTrackCount, len,
            t.sampleSizes.fold(0L) { a, b -> a + b })
    }

    fun oggOpus(src: RandomAccessSource): OggFacts {
        val s = OggReader.read(src)
        val head = s.packets.firstOrNull()?.let { OpusHead.parse(it.data) }
            ?: throw CoreException("Файл Ogg не читается: нет заголовка Opus.")
        var total = 0L
        for (k in 2 until s.packets.size) { val d = s.packets[k].data; if (d.isNotEmpty()) total += OpusToc.samples48(d) }
        return OggFacts(head.preSkip, s.lastGranule, head.channels, head.inputSampleRate, maxOf(0, s.packets.size - 2), total, s.crcErrors, s.hasEos)
    }
}
