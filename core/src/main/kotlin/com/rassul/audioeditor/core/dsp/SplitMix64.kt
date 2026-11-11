package com.rassul.audioeditor.core.dsp

/**
 * ГСЧ SplitMix64 — контракт бит в бит с tools/ref/dither_ref.py (dsp.md FR-C7).
 * Источник: G. Steele, D. Lea, C. Flood, «Fast Splittable Pseudorandom Number Generators», OOPSLA 2014
 * (тот же смеситель, что в java.util.SplittableRandom).
 * Константы > Long.MAX_VALUE заданы через ULong.toLong() (dsp.md ловушка K-2); сдвиги — ushr.
 */
class SplitMix64(seed: Long) {
    private var state: Long = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** u = (z >>> 11)·2^−53 ∈ [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * TWO_POW_M53

    companion object {
        private val GOLDEN: Long = 0x9E3779B97F4A7C15uL.toLong()
        private val MIX1: Long = 0xBF58476D1CE4E5B9uL.toLong()
        private val MIX2: Long = 0x94D049BB133111EBuL.toLong()
        private const val TWO_POW_M53 = 1.0 / (1L shl 53)
    }
}
