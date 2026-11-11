package com.rassul.audioeditor.core.edit

/**
 * Поставщик кадров для плеера (FR-13) с повтором выделения (FR-32) и журналом для курсора.
 * Методика — docs/math/timeline.md «FR-32: цикл выделения».
 *
 * - [fill] заполняет блок выходного потока (AudioTrack). При [repeat] = true цикл — полуинтервал
 *   [start, end) выделения в координатах результата ([selection]; null или пустое — весь файл [0, L)).
 *   Перед каждым блоком: pos ∉ [start, end) → pos := start. Внутри блока: n = min(осталось, end − pos),
 *   render, pos += n; при pos == end сразу pos := start (цикл while — цикл короче блока проходит
 *   несколько раз). Тишина не вставляется, сглаживания на стыке нет (Д-15).
 * - Без повтора — воспроизведение от pos до конца результата (fill вернёт меньше кадров у конца).
 * - Журнал сегментов (w0, p0, n): кадры вывода w0…w0+n−1 содержат кадры рендера p0…p0+n−1.
 *   Курсор: [cursorAt] (по AudioTrack.getTimestamp) или [renderPositionAt].
 *
 * Новые границы выделения и новый [renderer] действуют с первого НЕзаписанного блока.
 * Методы синхронизированы: fill — из потока аудио, курсор/настройки — из UI.
 */
class LoopCursor(renderer: Renderer) {
    @Volatile var renderer: Renderer = renderer
    /** Выделение в координатах результата; null — без выделения. */
    @Volatile var selection: Range? = null
    /** Переключатель «Повтор». */
    @Volatile var repeat: Boolean = false

    private var pos = 0L
    private var written = 0L
    private val journal = ArrayList<LongArray>() // [w0, p0, n]

    /** Следующая позиция рендера. */
    @get:Synchronized val position: Long get() = pos
    /** Сколько кадров выдано в поток всего (номер следующего кадра вывода). */
    @get:Synchronized val framesWritten: Long get() = written

    /** Перемотка: следующий блок начнётся с [renderPos]. Журнал не трогается (номера вывода продолжаются). */
    @Synchronized fun seek(renderPos: Long) { pos = renderPos.coerceAtLeast(0L) }

    /** Сброс нумерации вывода (после AudioTrack.flush()/нового трека): журнал очищается, вывод снова с [outputFrame]. */
    @Synchronized fun resetOutput(outputFrame: Long = 0L) { journal.clear(); written = outputFrame }

    /** Текущие границы цикла [start, end) или null, если повтора нет. */
    fun loopBounds(): Range? {
        if (!repeat) return null
        val l = renderer.length
        val s = selection
        if (s == null || s.length == 0L || s.start >= l) return Range(0, l)
        return Range(s.start, minOf(s.end, l))
    }

    /** Заполнить dst (чередование) до [frames] кадров. Возвращает число кадров (0 — конец/пустой файл). */
    @Synchronized
    fun fill(dst: FloatArray, frames: Int): Int {
        val r = renderer
        val loop = loopBounds()
        var done = 0
        if (loop == null) {
            if (pos >= r.length) return 0
            val n = r.render(pos, dst, 0, minOf(frames.toLong(), r.length - pos).toInt())
            log(pos, n)
            pos += n
            return n
        }
        val start = loop.start; val end = loop.end
        if (end <= start) return 0
        // timeline.md FR-32 п. 1: pos вне цикла → начало цикла.
        if (pos !in start until end) pos = start
        while (done < frames) {
            // п. 2: n = min(осталось, end − pos) → render → pos += n; pos == end → pos := start.
            val want = minOf((frames - done).toLong(), end - pos).toInt()
            val n = r.render(pos, dst, done, want)
            if (n <= 0) break
            log(pos, n)
            done += n
            pos += n
            if (pos >= end) pos = start
        }
        return done
    }

    private fun log(p0: Long, n: Int) {
        if (n <= 0) return
        val last = journal.lastOrNull()
        if (last != null && last[0] + last[2] == written && last[1] + last[2] == p0) last[2] += n.toLong()
        else journal += longArrayOf(written, p0, n.toLong())
        written += n
        if (journal.size > MAX_JOURNAL) journal.subList(0, journal.size - MAX_JOURNAL).clear()
    }

    /** Позиция рендера, звучащая в кадре вывода F: p0 + (F − w0) по журналу; null — нет в журнале. */
    @Synchronized
    fun renderPositionAt(outputFrame: Long): Long? {
        var lo = 0; var hi = journal.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = journal[mid]
            when {
                outputFrame < e[0] -> hi = mid - 1
                outputFrame >= e[0] + e[2] -> lo = mid + 1
                else -> return e[1] + (outputFrame - e[0])
            }
        }
        return null
    }

    /**
     * Курсор по AudioTrack.getTimestamp: F = framePosition + round_half_up((now − nanoTime)·fs/10⁹)
     * (timeline.md «Курсор без накопленной ошибки»; задержка тракта уже учтена в framePosition).
     * Сегменты журнала старше F удаляются.
     */
    @Synchronized
    fun cursorAt(framePosition: Long, timestampNanos: Long, nowNanos: Long): Long? {
        val f = framePosition + roundHalfUpDiv((nowNanos - timestampNanos) * renderer.spec.sampleRate, 1_000_000_000L)
        val p = renderPositionAt(f)
        forgetBefore(f)
        return p
    }

    /** Удалить сегменты журнала, полностью лежащие до кадра вывода [outputFrame]. */
    @Synchronized
    fun forgetBefore(outputFrame: Long) {
        var k = 0
        while (k < journal.size - 1 && journal[k][0] + journal[k][2] <= outputFrame) k++
        if (k > 0) journal.subList(0, k).clear()
    }

    private companion object {
        const val MAX_JOURNAL = 4096
        /** round_half_up(x/d) для d > 0 и любого знака x. */
        fun roundHalfUpDiv(x: Long, d: Long): Long = Math.floorDiv(2 * x + d, 2 * d)
    }
}
