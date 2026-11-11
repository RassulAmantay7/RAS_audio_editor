package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.time.TimeConv

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

/**
 * FR-24, Д-14. Правила:
 *  - частота по умолчанию — как в исходнике; Opus → всегда 48 кГц; AAC/FLAC при > 48 кГц → 48 кГц (с пометкой);
 *    допустимые диапазоны: AAC/FLAC 8–48 кГц, WAV 8–192 кГц; для AAC — ближайшая стандартная частота не ниже
 *    (ISO/IEC 14496-3 табл. 1.18: 8; 11,025; 12; 16; 22,05; 24; 32; 44,1; 48 кГц);
 *  - разрядность WAV/FLAC: 24, если исходник ≥ 24 бит, иначе 16; 32 (float) — только WAV по выбору;
 *    16 бит — с дизерингом (FR-C7), 24 и float — без;
 *  - битрейт AAC 192 (64–320), Opus 128 (32–256) кбит/с; для AAC не выше 6·fs·C бит/с —
 *    предел буфера декодера 6144 бит на канал на кадр 1024 (ISO/IEC 14496-3 §4.5.3.2), Д-14.
 */
object ExportPlanner {
    private val AAC_RATES = intArrayOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)

    /** srcBits — разрядность исходника (16/24/32, 0 — неизвестна/сжатый). */
    fun plan(source: PcmSpec, srcBits: Int, req: ExportRequest): ExportPlan {
        val notes = ArrayList<String>()
        val wanted = req.sampleRate ?: source.sampleRate
        // Точное значение без округления и без зависимости от локали: 11025 → «11,025 кГц», 22050 → «22,05 кГц».
        fun khz(r: Int): String = if (r % 1000 == 0) "${r / 1000} кГц"
            else "${r / 1000},${(r % 1000).toString().padStart(3, '0').trimEnd('0')} кГц"
        val rate = when (req.format) {
            ExportFormat.OPUS -> 48000.also { if (wanted != 48000) notes += "Opus всегда сохраняется с частотой 48 кГц (исходная ${khz(wanted)})." }
            ExportFormat.M4A, ExportFormat.FLAC -> {
                val name = if (req.format == ExportFormat.M4A) "AAC" else "FLAC"
                var r = wanted
                if (r > 48000) { notes += "Частота понижена до 48 кГц: $name сохраняется с частотой не выше 48 кГц (исходная ${khz(wanted)})."; r = 48000 }
                if (r < 8000) { notes += "Частота повышена до 8 кГц (минимум для $name)."; r = 8000 }
                if (req.format == ExportFormat.M4A && r !in AAC_RATES) {
                    val std = AAC_RATES.first { it >= r }
                    notes += "Частота ${khz(r)} не поддерживается AAC, будет ${khz(std)}."
                    r = std
                }
                r
            }
            ExportFormat.WAV -> wanted.coerceIn(8000, 192000).also { if (it != wanted) notes += "Частота WAV ограничена диапазоном 8–192 кГц: будет ${khz(it)}." }
        }
        val defBits = if (srcBits >= 24) 24 else 16
        val bits = when (req.format) {
            ExportFormat.WAV -> (req.bits ?: defBits).let { if (it in intArrayOf(16, 24, 32)) it else defBits }
            ExportFormat.FLAC -> (req.bits ?: defBits).let {
                if (it == 16 || it == 24) it else { notes += "FLAC поддерживает 16 или 24 бит: будет 24 бит."; 24 }
            }
            else -> 0
        }
        var bitrate = 0
        if (req.format == ExportFormat.M4A) {
            val want = (req.bitrateKbps ?: 192).coerceIn(64, 320)
            val maxKbps = (6L * rate * source.channels / 1000).toInt()      // 6·fs·C бит/с (Д-14)
            bitrate = if (want > maxKbps) {
                notes += "Битрейт $want кбит/с недоступен при ${khz(rate)}, ${channelsText(source.channels)}: будет $maxKbps кбит/с."
                maxKbps
            } else want
        } else if (req.format == ExportFormat.OPUS) bitrate = (req.bitrateKbps ?: 128).coerceIn(32, 256)
        return ExportPlan(req.format, PcmSpec(rate, source.channels), bits, bitrate, bits == 16, notes)
    }

    private fun channelsText(c: Int) = when (c) { 1 -> "моно"; 2 -> "стерео"; else -> "$c кан." }
}

/** FR-6a, FR-6: что делает главная кнопка «Сохранить в M4A» и в каком контейнере сохраняется «как есть». */
object ExtractPlanner {
    enum class Action { COPY_AAC, TRANSCODE_AAC, AS_IS }
    data class Extract(val action: Action, val ext: String, val buttonText: String, val note: String, val hint: String?)

    /** Название кодека для текстов по MIME (MediaFormat.KEY_MIME). */
    fun codecName(mime: String): String = when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/mpeg" -> "MP3"; "audio/flac" -> "FLAC"
        "audio/raw" -> "PCM"; "audio/3gpp" -> "AMR-NB"; "audio/amr-wb" -> "AMR-WB"
        "audio/ac3" -> "Dolby Digital (AC-3)"; "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"
        "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
        else -> mime.substringAfter('/').uppercase()
    }

    /** Расширение «как есть» (таблица FR-6); null — не поддерживается. */
    fun asIsExt(mime: String): String? = when (mime.lowercase()) {
        "audio/mp4a-latm", "audio/aac" -> "m4a"; "audio/opus" -> "ogg"; "audio/vorbis" -> "webm"; "audio/mpeg" -> "mp3"
        "audio/ac3" -> "ac3"; "audio/eac3" -> "eac3"; "audio/vnd.dts", "audio/vnd.dts.hd" -> "dts"; "audio/flac" -> "flac"
        "audio/raw" -> "wav"; "audio/3gpp", "audio/amr-wb" -> "3gp"
        else -> null
    }

    fun plan(mime: String, hasDecoder: Boolean, bitrateKbps: Int = 192): Extract {
        val name = codecName(mime)
        val m = mime.lowercase()
        if (m == "audio/mp4a-latm" || m == "audio/aac")
            return Extract(Action.COPY_AAC, "m4a", "Сохранить в M4A", "Без потерь: копирование AAC", null)
        if (!hasDecoder)
            return Extract(Action.AS_IS, asIsExt(mime) ?: "bin", "Сохранить как есть",
                "Этот телефон не умеет раскодировать звук $name. Его можно сохранить без изменений, но нельзя редактировать или перекодировать.", null)
        val lossless = m == "audio/flac" || m == "audio/raw"
        val hint = if (lossless) "Исходник без потерь. Для архива выберите FLAC или WAV в «Другой формат…»."
                   else "Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV."
        return Extract(Action.TRANSCODE_AAC, "m4a", "Сохранить в M4A", "Перекодирование: $name → AAC $bitrateKbps кбит/с", hint)
    }
}

/** FR-27: имена файлов. */
object FileNaming {
    /** «<base>_edit.<ext>», при занятости — «<base>_edit (2).<ext>», (3)… existing — имена в папке (сравнение без учёта регистра). */
    fun next(baseName: String, suffix: String, ext: String, existing: Set<String>): String {
        val base = baseName.substringBeforeLastDotIfExt().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifEmpty { "audio" }.limitUtf8(180)
        val suf = if (suffix.isEmpty()) "" else "_" + suffix.trimStart('_')
        val taken = existing.mapTo(HashSet()) { it.lowercase() }
        var i = 1
        while (true) {
            val name = if (i == 1) "$base$suf.$ext" else "$base$suf ($i).$ext"
            if (name.lowercase() !in taken) return name
            i++
        }
    }

    /**
     * Обрезать до maxBytes байт UTF-8, не разрывая суррогатную пару: предел имени файла на ext4/FAT/sdcardfs —
     * 255 байт, а не символов (кириллица — 2 байта на символ, take(180) давало до 360 байт).
     */
    private fun String.limitUtf8(maxBytes: Int): String {
        var bytes = 0; var i = 0
        while (i < length) {
            val cp = codePointAt(i); val n = Character.charCount(cp)
            val b = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            if (bytes + b > maxBytes) break
            bytes += b; i += n
        }
        return substring(0, i)
    }

    /** Убрать расширение исходника («видео.mp4» → «видео»), если оно похоже на расширение (1–5 символов после точки). */
    private fun String.substringBeforeLastDotIfExt(): String {
        val d = lastIndexOf('.')
        return if (d > 0 && length - d - 1 in 1..5 && substring(d + 1).all { it.isLetterOrDigit() }) substring(0, d) else this
    }
}

/** FR-10: место на диске. Мегабайты десятичные (10⁶ Б), timeline.md ф. 6. */
object SpaceEstimator {
    const val MARGIN_BYTES = 200_000_000L

    /** Байт для кэша PCM float32 (FR-10): кадры·C·4. */
    fun pcmCacheBytes(frames: Long, channels: Int): Long = frames * channels * 4

    /** Требуемое место: ⌈dur_us·fs/10⁶⌉·C·4 + 200·10⁶ байт (timeline.md ф. 6). */
    fun requiredBytes(durationUs: Long, sampleRate: Int, channels: Int): Long {
        val n = Math.floorDiv(-(durationUs * sampleRate), 1_000_000L).let { -it }    // ceil
        return pcmCacheBytes(n, channels) + MARGIN_BYTES
    }

    /** Сообщение, если места мало (null — достаточно). Нужно — вверх до МБ, свободно — вниз (честно в обе стороны). */
    fun check(requiredBytes: Long, freeBytes: Long): String? {
        if (freeBytes >= requiredBytes) return null
        val need = (requiredBytes + 999_999) / 1_000_000
        val free = maxOf(0L, freeBytes) / 1_000_000
        return "Недостаточно места: нужно $need МБ, свободно $free МБ."
    }
}

/**
 * Заполнение разрывов PTS тишиной (FR-7, timeline.md ф. 7). Потоковый: на каждый блок декодера отдаёт, сколько тишины
 * вставить/сколько отбросить. Ожидаемая позиция — от pts₀ первого блока (без накопления ошибки):
 * δ = Δpts·fs − written·10⁶ (мкс·Гц); δ > 1000·fs — разрыв: вставить round_half_up(Δpts·fs/10⁶) − written нулей;
 * δ < −1000·fs — перекрытие: отбросить min(n, written − max(0, pos)); |δ| ≤ 1 мс — дрожание.
 */
class PtsGapFiller(val sampleRate: Int) {
    data class Adjust(val insertSilenceFrames: Long, val dropFrames: Int)
    private var pts0 = Long.MIN_VALUE
    /** Выдано кадров (с учётом вставок и отбрасываний). */
    var written = 0L; private set

    fun onBlock(ptsUs: Long, frames: Int): Adjust {
        if (pts0 == Long.MIN_VALUE) pts0 = ptsUs
        val d = ptsUs - pts0
        val diff = d * sampleRate - written * 1_000_000L
        val thr = 1000L * sampleRate
        return when {
            diff > thr -> {
                val pos = Math.floorDiv(d * sampleRate + 500_000L, 1_000_000L)
                val z = pos - written
                written += z + frames
                Adjust(z, 0)
            }
            diff < -thr -> {
                val pos = maxOf(0L, Math.floorDiv(d * sampleRate + 500_000L, 1_000_000L))
                val drop = minOf(frames.toLong(), written - pos).toInt()
                written += frames - drop
                Adjust(0, drop)
            }
            else -> { written += frames; Adjust(0, 0) }
        }
    }
}

/**
 * Отбрасывание разгона/хвоста декодера (FR-26). Координаты — выход декодера (после того, что он отбросил сам).
 * Начало: отбросить max(0, delay − decoderAlreadyDrops) первых кадров.
 * Хвост: если известна полная длина полного (без обрезки) декодированного потока T = expectedTotalFrames,
 * полезная длина = T − delay − padding, и всё после неё отбрасывается. Иначе хвост режется в конце вызывающим:
 * [tailToTruncateAtFinish] кадров.
 * Порядок вызовов на блок длиной n: d = dropAtStart(n); k = keepFrames(n) → выдать кадры [d, d+k) блока.
 */
class GaplessTrimmer(
    /** Заявлено в файле. */
    val delayFrames: Long,
    val paddingFrames: Long,
    /** Уже отбрасывает системный декодер (из DeviceProfile). */
    val decoderAlreadyDropsFrames: Long,
    /** Полная длина декодированного потока, если известна заранее (иначе хвост режется в finish). */
    val expectedTotalFrames: Long?,
) {
    private val head = maxOf(0L, delayFrames - decoderAlreadyDropsFrames)
    /**
     * Полезная длина, если известна. expectedTotalFrames — в координатах выхода декодера (уже без того, что он
     * отбросил сам), поэтому вычитается только остаток задержки head, а не вся delay: иначе часть, отброшенная
     * декодером, вычиталась бы дважды (аудит code-formula-auditor, timeline.md FR-26 «двойная обрезка»).
     */
    val validLength: Long? = expectedTotalFrames?.let { maxOf(0L, it - head - paddingFrames) }
    private var pos = 0L        // позиция начала текущего блока на выходе декодера
    var emitted = 0L; private set

    /** Сколько кадров отбросить в начале блока длиной frames (позиция блока — накопленная). */
    fun dropAtStart(frames: Int): Int = minOf(frames.toLong(), maxOf(0L, head - pos)).toInt()

    /** Сколько кадров из блока оставить, с учётом хвоста (после dropAtStart); сдвигает позицию на frames. */
    fun keepFrames(frames: Int): Int {
        val d = dropAtStart(frames)
        var k = (frames - d).toLong()
        validLength?.let { k = minOf(k, maxOf(0L, it - emitted)) }
        pos += frames
        emitted += k
        return k.toInt()
    }

    /** Без известной полной длины: сколько последних выданных кадров удалить в конце (паддинг). */
    val tailToTruncateAtFinish: Long get() = if (validLength == null) maxOf(0L, paddingFrames) else 0L
}

/** Быстрая обрезка по кадрам сжатого потока (FR-23). */
data class FastTrimResult(
    val firstPacket: Int, val lastPacketExclusive: Int,
    val actualStartFrame: Long, val actualEndFrame: Long,
    /** Текст о фактическом сдвиге для пользователя. */
    val message: String,
)

/** Полный план быстрой обрезки (timeline.md, «FR-23: план быстрой обрезки»). */
data class FastTrimPlan(
    /** i_s — первый копируемый пакет (с разгоном). */
    val firstPacket: Int,
    /** i_a — первый слышимый пакет. */
    val firstAudiblePacket: Int,
    /** i_l — последний копируемый пакет (включительно). */
    val lastPacket: Int,
    /** A, B — фактические границы на шкале пользователя. */
    val start: Long, val end: Long,
    /** hidden = A + D − s_{i_s}: скрыть в начале нового файла (elst.media_time / pre-skip / LAME delay+529). */
    val hidden: Long,
    val message: String,
) {
    val length: Long get() = end - start
    fun toResult() = FastTrimResult(firstPacket, lastPacket + 1, start, end, message)
}

object FastTrimPlanner {
    /** RFC 7845 §4.6: пре-ролл Opus ≥ 80 мс = 3840 сэмплов 48 кГц. */
    const val OPUS_PREROLL_48K = 3840

    /**
     * packetStartFrames[i] — позиция начала i-го пакета в кадрах (после учёта задержки), packetFrames[i] — длина.
     * Границы округляются наружу; для AAC/MDCT добавляется кадр разгона (needsPreroll).
     */
    fun plan(packetStartFrames: LongArray, packetFrames: IntArray, selStart: Long, selEnd: Long, sampleRate: Int, needsPreroll: Boolean): FastTrimResult {
        val n = packetStartFrames.size
        val end = if (n == 0) 0L else packetStartFrames[n - 1] + packetFrames[n - 1]
        return planFull(packetStartFrames, packetFrames, selStart, selEnd, sampleRate, if (needsPreroll) 1 else 0, 0, end, true).toResult()
    }

    /**
     * Формула 13 timeline.md (в шкале пользователя u = s − D, т.е. starts уже сдвинуты на −D):
     *  i_a = max{i: u_i ≤ a}, i_l = max{i: u_i ≤ b−1}, A = max(0, u_{i_a}), B = min(L, u_{i_l} + len_{i_l});
     *  i_s = i_a − W (W пакетов разгона) либо, при warmupSamples > 0, наибольшее i_s ≤ i_a с u_{i_a} − u_{i_s} ≥ warmupSamples
     *  (Opus, ≥ 3840); hidden = A − u_{i_s}.
     * canHideWarmup = false (сырые AC-3/E-AC-3/DTS): разгон скрыть нечем — фактическое начало = u_{i_s} (timeline.md).
     */
    fun planFull(starts: LongArray, frames: IntArray, a: Long, b: Long, sampleRate: Int, warmupPackets: Int, warmupSamples: Int,
                 lengthFrames: Long, canHideWarmup: Boolean): FastTrimPlan {
        require(starts.isNotEmpty() && starts.size == frames.size) { "нет пакетов" }
        require(a in 0 until b) { "пустое выделение" }
        fun idx(x: Long): Int { var lo = 0; var hi = starts.size - 1; var r = 0; while (lo <= hi) { val m = (lo + hi) ushr 1; if (starts[m] <= x) { r = m; lo = m + 1 } else hi = m - 1 }; return r }
        val ia = idx(a)
        val il = idx(b - 1)
        var A = maxOf(0L, starts[ia])
        val B = minOf(lengthFrames, starts[il] + frames[il])
        var iS = if (warmupSamples > 0) {
            var j = ia
            while (j > 0 && starts[ia] - starts[j] < warmupSamples) j--
            j
        } else maxOf(0, ia - warmupPackets)
        var hidden = A - starts[iS]
        if (!canHideWarmup) { A = maxOf(0L, starts[iS]); hidden = A - starts[iS] }
        return FastTrimPlan(iS, ia, il, A, B, hidden, message(a, b, A, B, sampleRate))
    }

    /** Кадров разгона по кодеку (timeline.md, «FR-23: план»): AAC, AC-3, E-AC-3, DTS — 1; MP3 — ≥ 2 (см. Mp3Scanner.warmupFrames); FLAC, PCM — 0. */
    fun warmupPackets(codec: String): Int = when (codec.lowercase()) {
        "aac", "he-aac", "ac3", "eac3", "dts", "vorbis" -> 1
        "mp3" -> 2
        else -> 0
    }

    /** Сдвиг = разность ПОКАЗАННЫХ значений в мс (timeline.md): текст согласован сам с собой. */
    fun message(a: Long, b: Long, A: Long, B: Long, fs: Int): String {
        val da = TimeText.ms(a, fs) - TimeText.ms(A, fs)
        val db = TimeText.ms(B, fs) - TimeText.ms(b, fs)
        val s1 = if (da != 0L) "на $da мс раньше выделения" else "совпадает с выделением"
        val s2 = if (db != 0L) "на $db мс позже" else "совпадает с выделением"
        return "Фактическое начало ${TimeText.format(A, fs)} ($s1), конец ${TimeText.format(B, fs)} ($s2)."
    }
}

/**
 * Показ времени (timeline.md ф. 2 и «Решения главной сессии»). Единый форматтер приложения — TimeConv.format;
 * здесь только делегирование (раньше был дубликат на String.format — зависел от локали и расходился при n < 0).
 */
object TimeText {
    /** ms = ⌊(2·n·1000 + fs)/(2·fs)⌋ — округление half-up ДО разбиения на поля (n ≥ 0). */
    fun ms(n: Long, fs: Int): Long = (2 * n * 1000 + fs) / (2L * fs)
    fun format(n: Long, fs: Int): String = TimeConv.format(n, fs)
}
