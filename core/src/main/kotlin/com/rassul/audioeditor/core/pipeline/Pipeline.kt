package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.model.PcmSpec

/* КОНТРАКТ (реализация — агент FORMATS, кроме ExportPipeline — агент EDIT). FR-7, 10, 23, 24, 26, 27. */

enum class ExportFormat(val ext: String, val mime: String, val title: String) {
    M4A("m4a", "audio/mp4", "M4A (AAC)"),
    OPUS("ogg", "audio/ogg", "Opus"),
    FLAC("flac", "audio/flac", "FLAC (без потерь)"),
    WAV("wav", "audio/wav", "WAV (без сжатия)"),
}

/** Параметры экспорта, выбранные пользователем (null — по умолчанию). */
data class ExportRequest(
    val format: ExportFormat = ExportFormat.M4A,
    val sampleRate: Int? = null,
    val bits: Int? = null,
    val bitrateKbps: Int? = null,
)

/** Итоговый план: что реально будет записано, и пометки для пользователя (авто-смена частоты и т.п.). */
data class ExportPlan(
    val format: ExportFormat,
    val outSpec: PcmSpec,
    /** 16/24 для WAV/FLAC, 32 — WAV float; 0 — сжатый формат. */
    val bits: Int,
    val bitrateKbps: Int,
    val dither: Boolean,
    val notes: List<String>,
)

object ExportPlanner {
    /** srcBits — разрядность исходника (16/24/32, 0 — неизвестна/сжатый). */
    fun plan(source: PcmSpec, srcBits: Int, req: ExportRequest): ExportPlan = TODO("FORMATS")
}

object FileNaming {
    /** «<base>_edit.<ext>», при занятости — «<base>_edit (2).<ext>», (3)… existing — имена в папке. */
    fun next(baseName: String, suffix: String, ext: String, existing: Set<String>): String = TODO("FORMATS")
}

object SpaceEstimator {
    /** Байт для кэша PCM float32 (FR-10). */
    fun pcmCacheBytes(frames: Long, channels: Int): Long = TODO("FORMATS")
    /** Сообщение, если места мало (null — достаточно). */
    fun check(requiredBytes: Long, freeBytes: Long): String? = TODO("FORMATS")
}

/** Заполнение разрывов PTS тишиной (FR-7). Потоковый: на каждый блок декодера отдаёт, сколько тишины вставить/сколько отбросить. */
class PtsGapFiller(val sampleRate: Int) {
    data class Adjust(val insertSilenceFrames: Long, val dropFrames: Int)
    fun onBlock(ptsUs: Long, frames: Int): Adjust = TODO("FORMATS")
}

/** Отбрасывание разгона/хвоста декодера (FR-26). */
class GaplessTrimmer(
    /** Заявлено в файле. */
    val delayFrames: Long,
    val paddingFrames: Long,
    /** Уже отбрасывает системный декодер (из DeviceProfile). */
    val decoderAlreadyDropsFrames: Long,
    /** Полная длина декодированного потока, если известна заранее (иначе хвост режется в finish). */
    val expectedTotalFrames: Long?,
) {
    /** Сколько кадров отбросить в начале блока длиной frames (позиция блока — накопленная). */
    fun dropAtStart(frames: Int): Int = TODO("FORMATS")
    /** Сколько кадров из блока оставить, с учётом хвоста. */
    fun keepFrames(frames: Int): Int = TODO("FORMATS")
}

/** Быстрая обрезка по кадрам сжатого потока (FR-23). */
data class FastTrimResult(
    val firstPacket: Int, val lastPacketExclusive: Int,
    val actualStartFrame: Long, val actualEndFrame: Long,
    /** Текст о фактическом сдвиге для пользователя. */
    val message: String,
)

object FastTrimPlanner {
    /**
     * packetStartFrames[i] — позиция начала i-го пакета в кадрах (после учёта задержки), packetFrames[i] — длина.
     * Границы округляются наружу; для AAC/MDCT добавляется кадр разгона (needsPreroll).
     */
    fun plan(packetStartFrames: LongArray, packetFrames: IntArray, selStart: Long, selEnd: Long, sampleRate: Int, needsPreroll: Boolean): FastTrimResult = TODO("FORMATS")
}
