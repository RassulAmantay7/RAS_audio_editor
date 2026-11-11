package com.rassul.audioeditor.core.peaks

import com.rassul.audioeditor.core.model.PcmSource

/* КОНТРАКТ (реализация — агент DSP). FR-12, FR-14, FR-C8. */

/**
 * Пирамида мин/макс-пиков. Уровень 0 — BASE (256) сэмплов на точку, каждый следующий ×4.
 * Хранит для каждого канала отображения min и max (Float). Строится потоково (append).
 *
 * Формулы (docs/math/timeline.md ф-ла 4, FR-C8): B_k = 256·4^k; блоков ⌈L/B_k⌉; уровень k+1 = min/max
 * четвёрок блоков уровня k (min/max ассоциативны и не округляют → равенство точное).
 * Уровней K = 1 + ⌈log₄⌈L/256⌉⌉ (до блока, покрывающего весь файл).
 * Во время построения доступны только завершённые блоки; finish() дописывает неполные.
 * NaN в данных игнорируется (сравнения с NaN ложны; timeline.md «NaN от декодера»).
 */
class PeakPyramid(val channels: Int) {
    companion object { const val BASE = 256; const val FACTOR = 4; private const val MAX_LEVELS = 20 }

    init { require(channels >= 1) { "channels=$channels" } }

    // Уровень k: mins[k], maxs[k] — по channels значений на точку (чередование), counts[k] — число точек.
    private val mins = ArrayList<FloatArray>()
    private val maxs = ArrayList<FloatArray>()
    private val counts = IntArray(MAX_LEVELS)
    // Незавершённый блок уровня k: накоплено accN[k] элементов (сэмплов для k=0, точек k−1 для k>0).
    private val accMin = Array(MAX_LEVELS) { FloatArray(channels) }
    private val accMax = Array(MAX_LEVELS) { FloatArray(channels) }
    private val accN = IntArray(MAX_LEVELS)
    private var total = 0L
    private var finished = false

    init { for (k in 0 until MAX_LEVELS) resetAcc(k) }

    private fun resetAcc(k: Int) {
        java.util.Arrays.fill(accMin[k], Float.POSITIVE_INFINITY)
        java.util.Arrays.fill(accMax[k], Float.NEGATIVE_INFINITY)
        accN[k] = 0
    }

    /** Добавить кадры в конец (во время декодирования). */
    fun append(interleaved: FloatArray, frames: Int) {
        check(!finished) { "finish() уже вызван" }
        val ch = channels
        val mn = accMin[0]
        val mx = accMax[0]
        var idx = 0
        var left = frames
        while (left > 0) {
            val n = minOf(left, BASE - accN[0])
            for (f in 0 until n) {
                for (c in 0 until ch) {
                    val v = interleaved[idx + c]
                    if (v < mn[c]) mn[c] = v
                    if (v > mx[c]) mx[c] = v
                }
                idx += ch
            }
            accN[0] += n
            left -= n
            total += n
            if (accN[0] == BASE) emit(0)
        }
    }

    /** Завершить блок уровня k (полный или неполный) и передать его на уровень k+1. */
    private fun emit(k: Int) {
        push(k, accMin[k], accMax[k])
        val nk = counts[k]
        if (k + 1 < MAX_LEVELS) {
            val off = (nk - 1) * channels
            val pm = mins[k]; val px = maxs[k]
            val am = accMin[k + 1]; val ax = accMax[k + 1]
            for (c in 0 until channels) {
                if (pm[off + c] < am[c]) am[c] = pm[off + c]
                if (px[off + c] > ax[c]) ax[c] = px[off + c]
            }
            accN[k + 1]++
        }
        resetAcc(k)
        if (k + 1 < MAX_LEVELS && accN[k + 1] == FACTOR) emit(k + 1)
    }

    private fun push(k: Int, mn: FloatArray, mx: FloatArray) {
        while (mins.size <= k) { mins.add(FloatArray(64 * channels)); maxs.add(FloatArray(64 * channels)) }
        val need = (counts[k] + 1) * channels
        if (need > mins[k].size) {
            val cap = maxOf(need, mins[k].size * 2)
            mins[k] = mins[k].copyOf(cap); maxs[k] = maxs[k].copyOf(cap)
        }
        val off = counts[k] * channels
        // Пустой по NaN блок (все NaN) → 0, чтобы не рисовать бесконечности.
        for (c in 0 until channels) {
            mins[k][off + c] = if (mn[c] == Float.POSITIVE_INFINITY) 0f else mn[c]
            maxs[k][off + c] = if (mx[c] == Float.NEGATIVE_INFINITY) 0f else mx[c]
        }
        counts[k]++
    }

    /** Завершить построение (дописать неполные блоки). */
    fun finish() {
        if (finished) return
        // Снизу вверх: неполный блок уровня k (если есть) завершается и попадает в накопитель k+1.
        for (k in 0 until MAX_LEVELS - 1) {
            if (accN[k] > 0) emit(k)
            if (k + 1 >= levelsNeeded()) break
        }
        finished = true
    }

    /** Число уровней для текущих точек: уровень k+1 нужен, пока на уровне k больше одной точки. */
    private fun levelsNeeded(): Int {
        var k = 0
        while (k + 1 < MAX_LEVELS && counts[k] > 1) k++
        return k + 1
    }

    val lengthFrames: Long get() = total
    val levelCount: Int get() = if (total == 0L) 0 else if (counts[0] == 0) 1 else levelsNeeded()
    /** Число точек на уровне. */
    fun size(level: Int): Int = if (level in 0 until levelCount) counts[level] else 0
    /** min/max точки index уровня level для канала ch. */
    fun min(level: Int, ch: Int, index: Int): Float = mins[level][index * channels + ch]
    fun max(level: Int, ch: Int, index: Int): Float = maxs[level][index * channels + ch]

    /** Число сэмплов на точку уровня k: B_k = 256·4^k. */
    fun blockFrames(level: Int): Long = BASE.toLong() shl (2 * level)

    /** Пересобрать начиная с кадра fromFrame из нового источника (после правок), с новой длиной. */
    fun rebuildFrom(fromFrame: Long, src: PcmSource) {
        require(src.spec.channels == channels) { "channels ${src.spec.channels} != $channels" }
        val p = fromFrame.coerceIn(0L, minOf(total, src.length))
        // Блоки уровня k с номерами < ⌊p/B_k⌋ не затронуты (timeline.md «Первый изменённый сэмпл p»).
        val keep = IntArray(MAX_LEVELS) { k ->
            val b = blockFrames(k)
            if (b <= 0 || k >= 31) 0 else minOf((p / b).toInt(), counts[k])
        }
        for (k in 0 until MAX_LEVELS) { counts[k] = keep[k]; resetAcc(k) }
        // Накопитель уровня k+1 = точки уровня k с номерами [4·keep[k+1], keep[k]).
        for (k in 0 until MAX_LEVELS - 1) {
            val from = keep[k + 1] * FACTOR
            for (i in from until keep[k]) {
                val off = i * channels
                for (c in 0 until channels) {
                    if (mins[k][off + c] < accMin[k + 1][c]) accMin[k + 1][c] = mins[k][off + c]
                    if (maxs[k][off + c] > accMax[k + 1][c]) accMax[k + 1][c] = maxs[k][off + c]
                }
                accN[k + 1]++
            }
        }
        val start = keep[0].toLong() * BASE
        total = start
        finished = false
        val step = 65_536
        val buf = FloatArray(step * channels)
        var at = start
        val len = src.length
        while (at < len) {
            val want = minOf(step.toLong(), len - at).toInt()
            val got = src.read(at, buf, want)
            if (got <= 0) break
            append(buf, got)
            at += got
        }
        finish()
    }
}

/** Видимая область волны: перевод px ↔ кадры и выбор уровня пирамиды. */
data class WaveViewport(val startFrame: Double, val framesPerPx: Double, val widthPx: Int) {
    fun frameAt(px: Float): Double = startFrame + px * framesPerPx
    fun pxOf(frame: Double): Float = ((frame - startFrame) / framesPerPx).toFloat()
    /** Подходящий уровень пирамиды (−1 — рисовать сырые сэмплы). */
    fun level(): Int {
        // timeline.md ф-ла 5: наибольшее k с B_k = 256·4^k ≤ spp; при spp < 256 — сырые сэмплы.
        val spp = framesPerPx
        if (!(spp >= PeakPyramid.BASE)) return -1
        var k = 0
        while (k < 30 && PeakPyramid.BASE * Math.pow(4.0, (k + 1).toDouble()) <= spp) k++
        return k
    }
}
