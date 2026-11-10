package com.rassul.audioeditor.core.loudness

import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.Progress

/* КОНТРАКТ (реализация — агент DSP). FR-C4, FR-22. */

/** Результат измерения. NEGATIVE_INFINITY для тишины. */
data class LoudnessResult(
    val integratedLufs: Double,
    val truePeakDbtp: Double,
    val samplePeakDbfs: Double,
    val durationFrames: Long,
)

/** Потоковый измеритель громкости по ITU-R BS.1770-5. */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    fun process(interleaved: FloatArray, frames: Int): Unit = TODO("DSP")
    fun result(): LoudnessResult = TODO("DSP")
    companion object {
        fun measure(src: PcmSource, progress: Progress = Progress.NONE): LoudnessResult = TODO("DSP")
    }
}

sealed class NormalizeTarget {
    /** «На слух». */
    data class Lufs(val targetLufs: Double = -14.0, val truePeakCeilingDbtp: Double = -1.0) : NormalizeTarget()
    /** «По пику». */
    data class Peak(val ceilingDbfs: Double = -1.0) : NormalizeTarget()
}

/**
 * Решение нормализации: gainDb применяется ко всему сигналу.
 * available=false — режим недоступен (короче 400 мс / тише −70 LUFS), message — пояснение.
 * limited=true — сработало ограничение пика, message — текст из FR-22.
 */
data class NormalizeDecision(val available: Boolean, val gainDb: Double, val limited: Boolean, val message: String?)

object Normalizer {
    fun decide(measured: LoudnessResult, target: NormalizeTarget): NormalizeDecision = TODO("DSP")
}
