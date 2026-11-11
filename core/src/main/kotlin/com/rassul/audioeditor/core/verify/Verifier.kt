package com.rassul.audioeditor.core.verify

import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPlan
import com.rassul.audioeditor.core.pipeline.ExportResult
import com.rassul.audioeditor.core.time.TimeConv

/*
 * FR-33, часть :core (агент EDIT): независимая сверка сохранённого файла по его метаданным
 * (docs/math/timeline.md «FR-33: автопроверка сохранённого файла»). Полное декодирование M4A/Opus/FLAC
 * системным декодером — в :app (DeviceVerifier); здесь — структура и длины, которые должны совпасть точно.
 */

/** Что ожидается от файла (из ExportPlan и ExportResult того же экспорта, а не пересчёт). */
data class VerifyExpect(
    val format: ExportFormat,
    val sampleRate: Int,
    val channels: Int,
    /** 16/24/32 для WAV/FLAC, 0 — сжатый. */
    val bits: Int,
    /** Ожидаемая длина N в кадрах выходной частоты (для Opus — она же 48 кГц). */
    val frames: Long,
    /** MD5 PCM (ExportResult.pcmMd5): FLAC — против STREAMINFO, WAV — против области data. */
    val pcmMd5: ByteArray? = null,
    /** Задержка энкодера (elst.media_time / pre_skip); null — не сверять. */
    val encoderDelay: Int? = null,
    /** Заданный битрейт (кбит/с) для предупреждения ± 15 %; 0 — не проверять. */
    val bitrateKbps: Int = 0,
) {
    companion object {
        fun of(plan: ExportPlan, result: ExportResult, encoderDelay: Int? = null) =
            VerifyExpect(plan.format, plan.outSpec.sampleRate, plan.outSpec.channels, plan.bits, result.frames, result.pcmMd5, encoderDelay, plan.bitrateKbps)
    }
}

/** Итог проверки: ok, текст для пользователя (FR-33), предупреждения (в журнал / мелким текстом). */
data class VerifyReport(val ok: Boolean, val message: String, val warnings: List<String> = emptyList(), val actualFrames: Long? = null)

object Verifier {
    private const val ADVICE = "Попробуйте сохранить ещё раз или выберите WAV."
    private const val ADVICE_WAV = "Попробуйте сохранить ещё раз."

    /** Проверить файл; никогда не бросает исключение из-за содержимого файла. */
    fun verify(src: RandomAccessSource, e: VerifyExpect, flacFullDecode: Boolean = false): VerifyReport = try {
        when (e.format) {
            ExportFormat.WAV -> wav(src, e)
            ExportFormat.FLAC -> flac(src, e, flacFullDecode)
            ExportFormat.M4A -> m4a(src, e)
            ExportFormat.OPUS -> opus(src, e)
        }
    } catch (x: Exception) {
        fail(e, "контейнер не читается (${x.message ?: x.javaClass.simpleName})", null)
    }

    // --- тексты (FR-33) ---
    private fun fmtTime(n: Long, fs: Int) = TimeConv.format(n, fs)
    private fun khz(r: Int) = if (r % 1000 == 0) "${r / 1000} кГц" else String.format(java.util.Locale.ROOT, "%.1f кГц", r / 1000.0).replace('.', ',')
    private fun chText(c: Int) = when (c) { 1 -> "моно"; 2 -> "стерео"; else -> "$c кан." }
    private fun what(e: VerifyExpect) = when (e.format) {
        ExportFormat.M4A -> "AAC"; ExportFormat.OPUS -> "Opus"
        ExportFormat.FLAC -> "FLAC ${e.bits} бит"; ExportFormat.WAV -> if (e.bits == 32) "WAV float" else "WAV ${e.bits} бит"
    }
    private fun okReport(e: VerifyExpect, n: Long, warnings: List<String> = emptyList()) =
        VerifyReport(true, "Проверено: длина совпала (${fmtTime(n, e.sampleRate)}), ${what(e)} ${khz(e.sampleRate)} ${chText(e.channels)}", warnings, n)
    private fun fail(e: VerifyExpect, problem: String, n: Long?) =
        VerifyReport(false, "Файл записан с ошибкой: $problem. ${if (e.format == ExportFormat.WAV) ADVICE_WAV else ADVICE}", emptyList(), n)
    private fun lengthFail(e: VerifyExpect, n: Long) =
        fail(e, "длина ${fmtTime(n, e.sampleRate)} ($n сэмпл.) вместо ${fmtTime(e.frames, e.sampleRate)} (${e.frames} сэмпл.)", n)
    private fun paramFail(e: VerifyExpect, rate: Int, ch: Int, n: Long?): VerifyReport? = when {
        rate != e.sampleRate -> fail(e, "частота ${khz(rate)} вместо ${khz(e.sampleRate)}", n)
        ch != e.channels -> fail(e, "каналов $ch вместо ${e.channels}", n)
        else -> null
    }

    private fun wav(src: RandomAccessSource, e: VerifyExpect): VerifyReport {
        val w = FileFacts.wav(src)
        // timeline.md FR-33, WAV: N = фактические байты data / blockAlign; усечён — если байт меньше заявленного.
        val n = w.frames
        paramFail(e, w.sampleRate, w.channels, n)?.let { return it }
        val wantFloat = e.bits == 32
        if (w.isFloat != wantFloat || w.validBits != e.bits) return fail(e, "разрядность ${w.validBits}${if (w.isFloat) " float" else ""} вместо ${e.bits}", n)
        if (w.truncated || n != e.frames) return lengthFail(e, n)
        if (e.pcmMd5 != null) {
            val md5 = md5Range(src, w.dataOffset, w.availableDataSize)
            if (!md5.contentEquals(e.pcmMd5)) return fail(e, "данные отличаются от записанных (MD5)", n)
        }
        return okReport(e, n)
    }

    private fun flac(src: RandomAccessSource, e: VerifyExpect, fullDecode: Boolean): VerifyReport {
        val si = FileFacts.flacStreamInfo(src)
        paramFail(e, si.sampleRate, si.channels, si.totalSamples)?.let { return it }
        if (si.bitsPerSample != e.bits) return fail(e, "разрядность ${si.bitsPerSample} вместо ${e.bits}", si.totalSamples)
        if (si.totalSamples != e.frames) return lengthFail(e, si.totalSamples)
        if (!si.md5Known) return fail(e, "в заголовке нет контрольной суммы MD5", si.totalSamples)
        if (e.pcmMd5 != null && !si.md5.contentEquals(e.pcmMd5)) return fail(e, "контрольная сумма MD5 не совпала с записанным звуком", si.totalSamples)
        // Фактическая длина по кадрам (заголовок может быть цел, а файл — усечён): Σ размеров блоков, CRC-16.
        val scan = FlacFrames.scan(src)
        val n = scan.frames.fold(0L) { a, f -> a + f.samples }
        if (n != e.frames) return lengthFail(e, n)
        if (scan.crcErrors > 0) return fail(e, "повреждены кадры FLAC (${scan.crcErrors} с ошибкой CRC)", n)
        val warnings = ArrayList<String>()
        if (fullDecode) {
            // Запасная проверка своим декодером (основная — системный декодер на телефоне, timeline.md).
            val r = FlacDecoder(src).decode()
            if (r.error != null) return fail(e, "поток FLAC не декодируется (${r.error})", r.frames)
            if (r.frames != e.frames) return lengthFail(e, r.frames)
            if (!r.md5.contentEquals(si.md5)) return fail(e, "MD5 декодированного звука не совпал с STREAMINFO", r.frames)
        }
        return okReport(e, n, warnings)
    }

    private fun m4a(src: RandomAccessSource, e: VerifyExpect): VerifyReport {
        val f = FileFacts.mp4Audio(src)
        if (f.audioTrackCount != 1) return fail(e, "звуковых дорожек ${f.audioTrackCount} вместо одной", null)
        if (f.codec != "mp4a") return fail(e, "кодек ${f.codec} вместо AAC", null)
        paramFail(e, f.mdhdTimescale.toInt(), f.channels, f.lengthFrames)?.let { return it }
        val mt = f.elstMediaTime ?: return fail(e, "нет списка правок (elst) — длина не определена", null)
        val n = f.lengthFrames ?: return fail(e, "нет списка правок (elst)", null)
        if (e.encoderDelay != null && mt != e.encoderDelay.toLong())
            return fail(e, "задержка в заголовке $mt вместо ${e.encoderDelay}", n)
        if (n != e.frames) return lengthFail(e, n)
        // Σstts = delay + N (timeline.md FR-33, строка AAC) — точно. Меньше → кадры потеряны.
        if (f.sttsTotal < mt + n) return lengthFail(e, maxOf(0L, f.sttsTotal - mt))
        val warnings = ArrayList<String>()
        bitrateWarning(e, src.size, n, false)?.let { warnings += it }
        return okReport(e, n, warnings)
    }

    private fun opus(src: RandomAccessSource, e: VerifyExpect): VerifyReport {
        val f = FileFacts.oggOpus(src)
        // RFC 7845 §4.4: N₄₈ = гранула последней страницы − pre_skip.
        val n = f.length48
        if (f.channels != e.channels) return fail(e, "каналов ${f.channels} вместо ${e.channels}", n)
        if (e.encoderDelay != null && f.preSkip != e.encoderDelay) return fail(e, "pre-skip ${f.preSkip} вместо ${e.encoderDelay}", n)
        if (f.crcErrors > 0) return fail(e, "повреждены страницы Ogg (${f.crcErrors} с ошибкой CRC)", n)
        if (!f.hasEos || n != e.frames) return lengthFail(e, maxOf(0L, n))
        if (f.totalSamples48 < f.lastGranule) return lengthFail(e, maxOf(0L, f.totalSamples48 - f.preSkip))
        val warnings = ArrayList<String>()
        bitrateWarning(e, src.size, n, true)?.let { warnings += it }
        return okReport(e, n, warnings)
    }

    /**
     * Средний битрейт avg = size·8·fs/N; предупреждение при |avg/target − 1| > 0,15 (FR-33).
     * Для Opus — только превышение; файлы < 10 с не проверяются (timeline.md, замечания к битрейту).
     */
    fun bitrateWarning(e: VerifyExpect, sizeBytes: Long, n: Long, onlyAbove: Boolean): String? {
        if (e.bitrateKbps <= 0 || n < 10L * e.sampleRate) return null
        val avg = sizeBytes * 8.0 * e.sampleRate / n
        val dev = avg / (e.bitrateKbps * 1000.0) - 1.0
        if (Math.abs(dev) <= 0.15 || (onlyAbove && dev < 0)) return null
        return "Средний битрейт ${Math.round(avg / 1000)} кбит/с отличается от заданного ${e.bitrateKbps} кбит/с более чем на 15 %."
    }

    private fun md5Range(src: RandomAccessSource, off: Long, len: Long): ByteArray {
        val md = java.security.MessageDigest.getInstance("MD5")
        val buf = ByteArray(1 shl 16)
        var p = 0L
        while (p < len) {
            val r = src.read(off + p, buf, 0, minOf(buf.size.toLong(), len - p).toInt())
            if (r <= 0) break
            md.update(buf, 0, r); p += r
        }
        return md.digest()
    }
}
