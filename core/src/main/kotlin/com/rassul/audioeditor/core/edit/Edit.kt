package com.rassul.audioeditor.core.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.loudness.Normalizer
import com.rassul.audioeditor.core.model.CancelledException
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
    /**
     * Нормализация всего файла (FR-22): усиление вычисляется по результату предшествующих правок.
     *
     * Усиление считается ОДИН раз и хранится в операции («заморожено», timeline.md «Нормализация (FR-22)
     * в линейном списке правок пересчитываться не может»): [resolvedGainDb] — применяемое усиление в дБ
     * (множитель g32 = float32(10^(G/20))), [notice] — пояснение FR-22 для пользователя (null — без пояснения).
     *
     * Способ получения: контроллер при добавлении правки (НЕ из UI-потока, это полный проход рендера)
     * вызывает `Renderer(source, текущийСписок, settings).resolveNormalize(op)` и кладёт в список
     * уже разрешённую операцию. Повтор (redo) берёт тот же объект — пересчёта нет, отсюда бит в бит.
     * Если в список всё же попала неразрешённая операция (resolvedGainDb == null), её разрешает
     * [Renderer.prepare] (результат — [Renderer.resolvedEdits]); render() до этого бросает IllegalStateException.
     */
    data class Normalize(
        val target: NormalizeTarget,
        val resolvedGainDb: Double? = null,
        val notice: String? = null,
    ) : EditOp() {
        val isResolved: Boolean get() = resolvedGainDb != null
    }
}

/** Проверки правок для показа под полем/кнопкой (тексты — на русском). */
object EditRules {
    const val MSG_EMPTY = "Выделите фрагмент"
    const val MSG_OUT_OF_RANGE = "Выделение выходит за конец файла"
    const val MSG_DELETE_ALL = "Нельзя удалить весь файл"

    /** null — правку можно применить к результату длиной [length] кадров. */
    fun check(op: EditOp, length: Long): String? {
        fun inside(r: Range) = r.end <= length
        return when (op) {
            is EditOp.Keep -> when {
                !inside(op.range) -> MSG_OUT_OF_RANGE
                op.range.length == 0L -> MSG_EMPTY
                else -> null
            }
            is EditOp.Delete -> when {
                !inside(op.range) -> MSG_OUT_OF_RANGE
                op.range.length == 0L -> MSG_EMPTY
                op.range.start == 0L && op.range.end == length -> MSG_DELETE_ALL
                else -> null
            }
            is EditOp.Volume -> if (op.range != null && !inside(op.range)) MSG_OUT_OF_RANGE else null
            is EditOp.Fade -> if (!inside(op.range)) MSG_OUT_OF_RANGE else null
            is EditOp.Normalize -> null
        }
    }

    /**
     * Fade без выделения (FR-21): D = ⌊(d_ms·fs + 500)/1000⌋, прижать к L; in → [0, D), out → [L−D, L)
     * (timeline.md «fade [a,b)»).
     */
    fun fadeRangeWithoutSelection(durationMs: Double, sampleRate: Int, length: Long, fadeIn: Boolean): Range {
        val d = minOf(com.rassul.audioeditor.core.time.TimeConv.msToSamples(durationMs, sampleRate), length)
        return if (fadeIn) Range(0, d) else Range(length - d, length)
    }
}

/** Настройки рендера (FR-19). */
data class RenderSettings(val smoothJoints: Boolean = true, val jointMs: Double = 5.0)

/** Неизменяемый список правок. */
data class EditList(val ops: List<EditOp> = emptyList()) {
    fun plus(op: EditOp) = EditList(ops + op)
}

/** Отмена/повтор (≥ 100 шагов). Не потокобезопасен — из UI-потока. */
class UndoStack(initial: EditList = EditList(), val limit: Int = 200) {
    init { require(limit >= 1) { "limit=$limit" } }
    private val undoList = ArrayDeque<EditList>()
    private val redoList = ArrayDeque<EditList>()
    private var cur: EditList = initial

    val current: EditList get() = cur
    val canUndo: Boolean get() = undoList.isNotEmpty()
    val canRedo: Boolean get() = redoList.isNotEmpty()
    /** Число доступных шагов отмены (≤ limit). */
    val undoDepth: Int get() = undoList.size

    /** Новое состояние: прежнее уходит в отмену (самое старое отбрасывается сверх [limit]), повтор очищается. */
    fun push(list: EditList) {
        undoList.addLast(cur)
        while (undoList.size > limit) undoList.removeFirst()
        redoList.clear()
        cur = list
    }

    /** Отменить: возвращает новое текущее состояние или null, если отменять нечего. */
    fun undo(): EditList? {
        val prev = undoList.removeLastOrNull() ?: return null
        redoList.addLast(cur)
        cur = prev
        return cur
    }

    /** Повторить: тот же объект списка, что был до отмены (без пересчёта нормализации). */
    fun redo(): EditList? {
        val next = redoList.removeLastOrNull() ?: return null
        undoList.addLast(cur)
        cur = next
        return cur
    }
}

/**
 * ЕДИНЫЙ рендер для плеера и экспорта (FR-13): чистая функция (source, edits, settings).
 * Подготовка (prepare) может считать громкость для Normalize — долгая, звать не из UI-потока.
 * После prepare: length и render() — быстрые, потокобезопасны для чтения (несколько читателей).
 *
 * Устройство (см. [RenderMap]): при создании список правок один раз «прокручивается» по координатам:
 * результат = упорядоченные сегменты исходника (после Keep/Delete), а каждый множитель (Volume, Fade,
 * Normalize, рампы стыков FR-19) переводится из координат своего шага в координаты ИСХОДНИКА — внутри
 * сегмента все отображения «шаг k → шаг k−1» являются сдвигами, а каждый кадр исходника попадает в
 * результат не более одного раза. Рендер блока = чтение исходника по сегментам + умножение на множители
 * строго в порядке операций, без предварительного перемножения коэффициентов (timeline.md «Бит-в-бит
 * рендера»: ((x·g1)·f2)·G во float32). Поэтому результат не зависит от разбиения на блоки.
 *
 * Сглаживание стыков: N = round_half_up(jointMs·fs/1000) из [settings] (FR-19/FR-C3) — общее для всех
 * Keep/Delete этого рендера; N_k = min(N, ⌊len_k/2⌋), сетка (i+0,5)/N (dsp.md П-3, решения главной сессии).
 */
class Renderer(val source: PcmSource, val edits: EditList, val settings: RenderSettings) {
    val spec: PcmSpec get() = source.spec

    private val jointN: Long =
        if (settings.smoothJoints) com.rassul.audioeditor.core.time.TimeConv.msToSamples(settings.jointMs, source.spec.sampleRate) else 0L

    /** Длины после каждого шага: stepLengths[0] = длина исходника, stepLengths[k+1] — после ops[k]. */
    private val stepLengths: LongArray = LongArray(edits.ops.size + 1).also { ls ->
        ls[0] = source.length
        edits.ops.forEachIndexed { k, op ->
            val l = ls[k]
            EditRules.check(op, l)?.let { throw IllegalArgumentException("Правка ${k + 1} ($op): $it") }
            ls[k + 1] = when (op) {
                is EditOp.Keep -> op.range.length
                is EditOp.Delete -> l - op.range.length
                else -> l
            }
        }
    }

    @Volatile private var resolved: EditList? = if (edits.ops.all { it !is EditOp.Normalize || it.isResolved }) edits else null
    @Volatile private var map: RenderMap? = resolved?.let { RenderMap.build(source.length, it.ops, jointN, source.spec.channels) }

    /** Готов ли рендер (все нормализации разрешены). */
    val isPrepared: Boolean get() = map != null

    /** Список правок с разрешёнными нормализациями (после [prepare]); его стоит положить в UndoStack. */
    val resolvedEdits: EditList get() = resolved ?: throw IllegalStateException("Renderer.prepare() не вызван")

    /** Выполнить подготовительные измерения (нормализация). Идемпотентно. */
    @Synchronized
    fun prepare(progress: Progress = Progress.NONE) {
        if (map != null) return
        val ops = edits.ops.toMutableList()
        val todo = ops.indices.filter { (ops[it] as? EditOp.Normalize)?.isResolved == false }
        todo.forEachIndexed { t, k ->
            val sub = SubProgress(progress, t.toDouble() / todo.size, 1.0 / todo.size)
            val prefix = Renderer(source, EditList(ops.subList(0, k).toList()), settings)
            ops[k] = prefix.resolveNormalize(ops[k] as EditOp.Normalize, sub)
        }
        val r = EditList(ops.toList())
        map = RenderMap.build(source.length, r.ops, jointN, source.spec.channels)
        resolved = r
    }

    /**
     * Разрешить нормализацию, ДОБАВЛЯЕМУЮ в конец этого списка: измерить громкость/пик результата этого рендера
     * (LoudnessMeter, BS.1770-5) и решить по FR-22 (Normalizer.decide). Долгая — не из UI-потока.
     * Режим недоступен (короче 0,4 с, тишина) → усиление 0 дБ и пояснение в notice.
     */
    fun resolveNormalize(op: EditOp.Normalize, progress: Progress = Progress.NONE): EditOp.Normalize {
        prepare(progress)
        val m = LoudnessMeter.measure(asSource(), progress)
        val d = Normalizer.decide(m, op.target)
        val g = if (d.available) d.gainDb else 0.0
        return op.copy(resolvedGainDb = g, notice = d.message)
    }

    /** Сообщения нормализации (FR-22) для показа пользователю — по одному на Normalize-операцию. */
    val notices: List<String>
        get() = (resolved ?: edits).ops.filterIsInstance<EditOp.Normalize>().map { n ->
            n.notice ?: n.resolvedGainDb?.let { "Нормализация: усиление ${if (it > 0) "+" else ""}${Normalizer.fmtDb(it)} дБ." }
                ?: "Нормализация ещё не рассчитана"
        }

    /** Длина результата в кадрах. */
    val length: Long get() = stepLengths[stepLengths.size - 1]

    /** Отрендерить до frames кадров с позиции from в dst (чередование). Возвращает число кадров. */
    fun render(from: Long, dst: FloatArray, frames: Int): Int = render(from, dst, 0, frames)

    /** То же с записью начиная с кадра [dstOffsetFrames] массива dst. */
    fun render(from: Long, dst: FloatArray, dstOffsetFrames: Int, frames: Int): Int {
        val m = map ?: throw IllegalStateException("Renderer.prepare() не вызван: в списке есть нерассчитанная нормализация")
        require(from >= 0 && frames >= 0 && dstOffsetFrames >= 0) { "from=$from frames=$frames off=$dstOffsetFrames" }
        return m.render(source, from, dst, dstOffsetFrames, frames)
    }

    /** Результат как PcmSource (для LoudnessMeter, PeakPyramid.rebuildFrom, экспорта). */
    fun asSource(): PcmSource = object : PcmSource {
        override val spec: PcmSpec get() = this@Renderer.spec
        override val length: Long get() = this@Renderer.length
        override fun read(from: Long, dst: FloatArray, frames: Int): Int = render(from, dst, frames)
    }

    /**
     * FR-14: есть ли в результате |x| > 1,0 (полный проход рендера, для состояния «перегрузка»).
     * Участки для подсветки даёт пирамида пиков по результату. Отмена → CancelledException.
     */
    fun hasOverload(progress: Progress = Progress.NONE): Boolean {
        prepare(progress)
        val ch = spec.channels
        val step = 65_536
        val buf = FloatArray(step * ch)
        val total = length
        var pos = 0L
        while (pos < total) {
            if (progress.isCancelled) throw CancelledException()
            val n = render(pos, buf, minOf(step.toLong(), total - pos).toInt())
            if (n <= 0) break
            for (i in 0 until n * ch) if (Math.abs(buf[i]) > 1.0f) return true
            pos += n
            progress.report(pos.toDouble() / total)
        }
        return false
    }

    /**
     * Первый кадр, начиная с которого результат может отличаться от рендера other (для частичного пересчёта волны).
     * Общий префикс списков правок даёт общий промежуточный результат R_k; для каждого хвоста считается длина
     * начального участка результата, совпадающего с R_k кадр в кадр (тождественное отображение без множителей);
     * ответ — минимум из двух. Для одной добавленной правки это p из timeline.md: delete → a − N_L (0 при a = 0),
     * trim → 0 при a > 0, иначе b − N; gain/fade → a; normalize → 0. Одинаковые списки → length.
     * other == null, другой источник или другие настройки → 0.
     */
    fun firstDifference(other: Renderer?): Long {
        if (other == null || other.source !== source || other.jointN != jointN) return 0L
        val a = (resolved ?: edits).ops
        val b = (other.resolved ?: other.edits).ops
        var k = 0
        while (k < a.size && k < b.size && a[k] == b[k]) k++
        return minOf(untouchedPrefix(k), other.untouchedPrefix(k))
    }

    /** Длина начала результата, тождественного результату первых k правок. */
    private fun untouchedPrefix(k: Int): Long {
        val ops = (resolved ?: edits).ops
        var u = stepLengths[k]
        for (j in k until ops.size) {
            val l = stepLengths[j]
            when (val op = ops[j]) {
                is EditOp.Keep -> {
                    if (op.range.start > 0) return 0L
                    u = minOf(u, op.range.end)
                    if (op.range.end < l) u = minOf(u, op.range.end - com.rassul.audioeditor.core.dsp.JointSmoother.pieceRamp(jointN, op.range.length))
                }
                is EditOp.Delete -> {
                    val a = op.range.start
                    u = minOf(u, if (a > 0) a - com.rassul.audioeditor.core.dsp.JointSmoother.pieceRamp(jointN, a) else 0L)
                }
                is EditOp.Volume -> u = minOf(u, op.range?.start ?: 0L)
                is EditOp.Fade -> u = minOf(u, op.range.start)
                is EditOp.Normalize -> if (op.resolvedGainDb == null || RenderMap.normalizeGain(op) != 1.0f) return 0L
            }
        }
        return u
    }

    private class SubProgress(val p: Progress, val base: Double, val span: Double) : Progress {
        override fun report(fraction: Double) = p.report(base + span * fraction)
        override val isCancelled: Boolean get() = p.isCancelled
    }
}
