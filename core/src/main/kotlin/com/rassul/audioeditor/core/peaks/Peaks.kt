package com.rassul.audioeditor.core.peaks

/* КОНТРАКТ (реализация — агент DSP). FR-12, FR-14, FR-C8. */

/**
 * Пирамида мин/макс-пиков. Уровень 0 — BASE (256) сэмплов на точку, каждый следующий ×4.
 * Хранит для каждого канала отображения min и max (Float). Строится потоково (append).
 */
class PeakPyramid(val channels: Int) {
    companion object { const val BASE = 256; const val FACTOR = 4 }
    /** Добавить кадры в конец (во время декодирования). */
    fun append(interleaved: FloatArray, frames: Int): Unit = TODO("DSP")
    /** Завершить построение (дописать неполные блоки). */
    fun finish(): Unit = TODO("DSP")
    val lengthFrames: Long get() = TODO("DSP")
    val levelCount: Int get() = TODO("DSP")
    /** Число точек на уровне. */
    fun size(level: Int): Int = TODO("DSP")
    /** min/max точки index уровня level для канала ch. */
    fun min(level: Int, ch: Int, index: Int): Float = TODO("DSP")
    fun max(level: Int, ch: Int, index: Int): Float = TODO("DSP")
    /** Пересобрать начиная с кадра fromFrame из нового источника (после правок), с новой длиной. */
    fun rebuildFrom(fromFrame: Long, src: com.rassul.audioeditor.core.model.PcmSource): Unit = TODO("DSP")
}

/** Видимая область волны: перевод px ↔ кадры и выбор уровня пирамиды. */
data class WaveViewport(val startFrame: Double, val framesPerPx: Double, val widthPx: Int) {
    fun frameAt(px: Float): Double = startFrame + px * framesPerPx
    fun pxOf(frame: Double): Float = ((frame - startFrame) / framesPerPx).toFloat()
    /** Подходящий уровень пирамиды (−1 — рисовать сырые сэмплы). */
    fun level(): Int = TODO("DSP")
}
