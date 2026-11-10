package com.rassul.audioeditor.core.time

/* КОНТРАКТ (реализация — агент DSP). FR-C1, FR-15. Без double в разборе строки. */

/** Результат разбора поля времени: либо сэмплы, либо понятная ошибка для показа под полем. */
sealed class TimeParse {
    data class Ok(val samples: Long) : TimeParse()
    data class Error(val message: String) : TimeParse()
}

object TimeConv {
    /** Разобрать «ч:мм:сс.ммм» / «мм:сс.ммм» / «сс.ммм» → n = round_half_up(t × fs). */
    fun parse(text: String, sampleRate: Int): TimeParse = TODO("DSP")
    /** Показ позиции: «м:сс.ммм» или «ч:мм:сс.ммм» (правило округления — docs/math/timeline.md). */
    fun format(samples: Long, sampleRate: Int): String = TODO("DSP")
    /** Миллисекунды → сэмплы (half-up), для длительностей из настроек. */
    fun msToSamples(ms: Double, sampleRate: Int): Long = TODO("DSP")
}
