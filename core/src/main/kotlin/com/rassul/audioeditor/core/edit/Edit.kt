package com.rassul.audioeditor.core.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.model.Progress

/*
 * КОНТРАКТ (реализация — агент EDIT). FR-13, FR-16…FR-22. Семантика — docs/math/timeline.md.
 * Координаты каждой операции — в кадрах РЕЗУЛЬТАТА ВСЕХ ПРЕДЫДУЩИХ операций списка; [start, end).
 * range == null — ко всему файлу.
 */

data class Range(val start: Long, val end: Long) {
    init { require(start in 0..end) { "range $start..$end" } }
    val length: Long get() = end - start
}

sealed class EditOp {
    /** «Оставить выделенное» (FR-17). */
    data class Keep(val range: Range) : EditOp()
    /** «Удалить выделенное» (FR-18). */
    data class Delete(val range: Range) : EditOp()
    /** Громкость (FR-20). */
    data class Volume(val range: Range?, val db: Double) : EditOp()
    /** Fade (FR-21): fadeIn — от тишины к уровню на всём range. */
    data class Fade(val range: Range, val fadeIn: Boolean, val curve: FadeCurve) : EditOp()
    /** Нормализация всего файла (FR-22): усиление вычисляется рендером по предшествующим правкам. */
    data class Normalize(val target: NormalizeTarget) : EditOp()
}

/** Настройки рендера (FR-19). */
data class RenderSettings(val smoothJoints: Boolean = true, val jointMs: Double = 5.0)

/** Неизменяемый список правок. */
data class EditList(val ops: List<EditOp> = emptyList()) {
    fun plus(op: EditOp) = EditList(ops + op)
}

/** Отмена/повтор (≥ 100 шагов). Не потокобезопасен — из UI-потока. */
class UndoStack(initial: EditList = EditList(), val limit: Int = 200) {
    val current: EditList get() = TODO("EDIT")
    val canUndo: Boolean get() = TODO("EDIT")
    val canRedo: Boolean get() = TODO("EDIT")
    fun push(list: EditList): Unit = TODO("EDIT")
    fun undo(): EditList? = TODO("EDIT")
    fun redo(): EditList? = TODO("EDIT")
}

/**
 * ЕДИНЫЙ рендер для плеера и экспорта (FR-13): чистая функция (source, edits, settings).
 * Подготовка (prepare) может считать громкость для Normalize — долгая, звать не из UI-потока.
 * После prepare: length и render() — быстрые, потокобезопасны для чтения (несколько читателей).
 */
class Renderer(val source: PcmSource, val edits: EditList, val settings: RenderSettings) {
    val spec: PcmSpec get() = source.spec
    /** Выполнить подготовительные измерения (нормализация). */
    fun prepare(progress: Progress = Progress.NONE): Unit = TODO("EDIT")
    /** Сообщения нормализации (FR-22) для показа пользователю — по одному на Normalize-операцию. */
    val notices: List<String> get() = TODO("EDIT")
    /** Длина результата в кадрах. */
    val length: Long get() = TODO("EDIT")
    /** Отрендерить до frames кадров с позиции from в dst (чередование). Возвращает число кадров. */
    fun render(from: Long, dst: FloatArray, frames: Int): Int = TODO("EDIT")
    /** Первый кадр, начиная с которого результат может отличаться от рендера other (для частичного пересчёта волны). */
    fun firstDifference(other: Renderer?): Long = TODO("EDIT")
}
