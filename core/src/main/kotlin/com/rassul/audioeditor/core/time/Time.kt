package com.rassul.audioeditor.core.time

import java.math.BigDecimal
import java.math.RoundingMode

/* КОНТРАКТ (реализация — агент DSP). FR-C1, FR-15. Без double в разборе строки. */

/** Результат разбора поля времени: либо сэмплы, либо понятная ошибка для показа под полем. */
sealed class TimeParse {
    data class Ok(val samples: Long) : TimeParse()
    data class Error(val message: String) : TimeParse()
}

object TimeConv {
    // Формат ввода: [[ч:]мм:]сс[.ммм], запятая вместо точки допустима (docs/math/timeline.md, «Границы применимости»).
    private val TIME_RE = Regex("""^\s*(?:(?:([0-9]+):)?([0-9]+):)?([0-9]+)(?:[.,]([0-9]{1,3}))?\s*$""")
    private val TOO_MANY_FRACTION_RE = Regex("""^\s*(?:(?:[0-9]+:)?[0-9]+:)?[0-9]+[.,][0-9]{4,}\s*$""")

    /** Верхняя граница времени (мс): 10^12 мс ≈ 31 год; T_ms·fs ≤ 10^12·768 000 < Long.MAX (timeline.md ф-ла 1). */
    private const val MAX_MS = 1_000_000_000_000L
    private const val MAX_FIELD_DIGITS = 12

    const val MSG_EMPTY = "Введите время, например 1:23.456"
    const val MSG_FORMAT = "Неверный формат времени. Пример: 1:23.456 или 1:02:03.500"
    const val MSG_FRACTION = "После точки — не больше трёх цифр (миллисекунды)"
    const val MSG_NEGATIVE = "Время не может быть отрицательным"
    const val MSG_SECONDS = "Секунд должно быть меньше 60"
    const val MSG_MINUTES = "Минут должно быть меньше 60"
    const val MSG_TOO_BIG = "Слишком большое время"

    /** Разобрать «ч:мм:сс.ммм» / «мм:сс.ммм» / «сс.ммм» → n = round_half_up(t × fs). */
    fun parse(text: String, sampleRate: Int): TimeParse {
        require(sampleRate > 0) { "sampleRate=$sampleRate" }
        return when (val r = parseMs(text)) {
            is MsParse.Err -> TimeParse.Error(r.message)
            is MsParse.Ok -> TimeParse.Ok(msToSamplesExact(r.ms, sampleRate))
        }
    }

    /**
     * Разбор с учётом длины файла [lengthFrames]. Показанная длительность округлена до мс,
     * поэтому при 0 < n − L ≤ ⌈fs/2000⌉ значение молча прижимается к L (docs/math/timeline.md,
     * «Ввод конца, равного показанной длительности»). Больше — ошибка «за пределами».
     */
    fun parseWithin(text: String, sampleRate: Int, lengthFrames: Long): TimeParse {
        val r = parse(text, sampleRate)
        if (r !is TimeParse.Ok) return r
        val over = r.samples - lengthFrames
        if (over <= 0) return r
        val slack = (sampleRate + 1999L) / 2000L // ⌈fs/2000⌉
        return if (over <= slack) TimeParse.Ok(lengthFrames)
        else TimeParse.Error("Больше длительности файла (${format(lengthFrames, sampleRate)})")
    }

    /**
     * Проверка выделения [start, end) (FR-15): текст ошибки для показа под полем конца или null.
     */
    fun rangeError(start: Long, end: Long, lengthFrames: Long, sampleRate: Int): String? = when {
        start < 0 || end < 0 -> MSG_NEGATIVE
        end > lengthFrames -> "Больше длительности файла (${format(lengthFrames, sampleRate)})"
        end <= start -> "Конец должен быть позже начала"
        else -> null
    }

    /** Показ позиции: «м:сс.ммм» или «ч:мм:сс.ммм» (правило округления — docs/math/timeline.md). */
    fun format(samples: Long, sampleRate: Int): String {
        require(sampleRate > 0) { "sampleRate=$sampleRate" }
        if (samples < 0) return "−" + format(-samples, sampleRate)
        // timeline.md ф-ла 2: ms = ⌊(2·n·1000 + fs)/(2·fs)⌋ — округление half-up ДО разбиения на поля.
        val ms = (2L * samples * 1000L + sampleRate) / (2L * sampleRate)
        return formatMs(ms)
    }

    /** Миллисекунды (целые) → строка. < 1 ч: «м:сс.ммм»; ≥ 1 ч: «ч:мм:сс.ммм» (решение главной сессии, timeline.md). */
    fun formatMs(ms: Long): String {
        if (ms < 0) return "−" + formatMs(-ms)
        val h = ms / 3_600_000L
        var r = ms % 3_600_000L
        val m = r / 60_000L
        r %= 60_000L
        val s = r / 1000L
        val f = r % 1000L
        val sb = StringBuilder(16)
        if (h > 0) {
            sb.append(h).append(':')
            if (m < 10) sb.append('0')
        }
        sb.append(m).append(':')
        if (s < 10) sb.append('0')
        sb.append(s).append('.')
        if (f < 100) sb.append('0')
        if (f < 10) sb.append('0')
        sb.append(f)
        return sb.toString()
    }

    /** Миллисекунды → сэмплы (half-up), для длительностей из настроек. */
    fun msToSamples(ms: Double, sampleRate: Int): Long {
        require(sampleRate > 0) { "sampleRate=$sampleRate" }
        require(ms.isFinite() && ms >= 0.0) { "ms=$ms" }
        // Целые мс — точная целочисленная формула timeline.md ф-ла 3: N = ⌊(d_ms·fs + 500)/1000⌋.
        if (ms == Math.floor(ms) && ms < 9.0e12) return msToSamplesExact(ms.toLong(), sampleRate)
        // Дробные мс: десятичная запись числа (как его ввёл пользователь), округление half-up без ошибки double (K-1).
        return BigDecimal(ms.toString()).multiply(BigDecimal.valueOf(sampleRate.toLong()))
            .divide(BigDecimal.valueOf(1000L)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** Целые мс → сэмплы: n = ⌊(T_ms·fs + 500)/1000⌋ (FR-C1, timeline.md ф-ла 1; half-up для T_ms ≥ 0). */
    fun msToSamplesExact(ms: Long, sampleRate: Int): Long {
        require(ms >= 0) { "ms=$ms" }
        return (ms * sampleRate + 500L) / 1000L
    }

    /** Разбор строки в целые миллисекунды (только целочисленная арифметика). */
    sealed class MsParse {
        data class Ok(val ms: Long) : MsParse()
        data class Err(val message: String) : MsParse()
    }

    fun parseMs(text: String): MsParse {
        val t = text.trim()
        if (t.isEmpty()) return MsParse.Err(MSG_EMPTY)
        if (t.startsWith("-") || t.startsWith("−")) return MsParse.Err(MSG_NEGATIVE)
        val m = TIME_RE.matchEntire(text)
        if (m == null) {
            return if (TOO_MANY_FRACTION_RE.matches(text)) MsParse.Err(MSG_FRACTION) else MsParse.Err(MSG_FORMAT)
        }
        val (hs, ms, ss, fs) = m.destructured
        for (f in listOf(hs, ms, ss)) if (f.length > MAX_FIELD_DIGITS) return MsParse.Err(MSG_TOO_BIG)
        val h = if (hs.isEmpty()) -1L else hs.toLong()
        val mi = if (ms.isEmpty()) -1L else ms.toLong()
        val se = ss.toLong()
        // Ведущее поле не ограничено, последующие < 60 (timeline.md, «Границы применимости»).
        if (mi >= 0 && se >= 60) return MsParse.Err(MSG_SECONDS)
        if (h >= 0 && mi >= 60) return MsParse.Err(MSG_MINUTES)
        val frac = if (fs.isEmpty()) 0L else fs.padEnd(3, '0').toLong() // «.5» = 500 мс
        // T_ms = ((h·60 + m)·60 + s)·1000 + ms (FR-C1, timeline.md ф-ла 1), с контролем переполнения.
        return try {
            val totalMin = Math.addExact(Math.multiplyExact(maxOf(h, 0L), 60L), maxOf(mi, 0L))
            val totalSec = Math.addExact(Math.multiplyExact(totalMin, 60L), se)
            val total = Math.addExact(Math.multiplyExact(totalSec, 1000L), frac)
            if (total > MAX_MS) MsParse.Err(MSG_TOO_BIG) else MsParse.Ok(total)
        } catch (e: ArithmeticException) {
            MsParse.Err(MSG_TOO_BIG)
        }
    }
}
