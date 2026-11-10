package com.rassul.audioeditor.core.dsp

import com.rassul.audioeditor.core.model.PcmSpec

/* КОНТРАКТ (реализация — агент DSP). Формулы — docs/math/dsp.md (physics-math-reviewer). */

object Gain {
    /** g = 10^(dB/20) (FR-20). */
    fun dbToLinear(db: Double): Double = TODO("DSP")
    fun linearToDb(g: Double): Double = TODO("DSP")
}

enum class FadeCurve { LINEAR, EQUAL_POWER, LOGARITHMIC }

object FadeCurves {
    /**
     * Множитель нарастания (fade in) в относительной позиции x ∈ [0,1] (0 — тишина, 1 — исходный уровень).
     * Fade out = gainIn(1 − x). FR-C2.
     */
    fun gainIn(curve: FadeCurve, x: Double): Double = TODO("DSP")
}

object JointSmoother {
    /**
     * Множитель сглаживания стыка (FR-C3) для сэмпла с индексом i ∈ [0, n) внутри рампы длиной n,
     * нарастание (rising=true, после стыка) или затухание (до стыка).
     */
    fun gain(i: Long, n: Long, rising: Boolean): Double = TODO("DSP")
}

/** Сведение каналов (FR-11, FR-C5). */
object Downmix {
    /** Число выходных каналов: 1 для моно, 2 для всего остального. */
    fun outputChannels(inChannels: Int): Int = if (inChannels == 1) 1 else 2
    /**
     * Матрица [out][in] для маски каналов Android (AudioFormat.CHANNEL_OUT_*; 0 — неизвестна → порядок по умолчанию).
     * Для 1 и 2 каналов — единичная.
     */
    fun matrix(channelMask: Int, inChannels: Int): Array<DoubleArray> = TODO("DSP")
    /** Применить матрицу: src (inCh, чередование) → dst (outCh). */
    fun apply(matrix: Array<DoubleArray>, src: FloatArray, frames: Int, dst: FloatArray): Unit = TODO("DSP")
}

/** Потоковый ресемплер (FR-C6). */
class Resampler(val inRate: Int, val outRate: Int, val channels: Int) {
    /** Подать frames кадров; записать готовые в out; вернуть число записанных кадров. out должен вмещать maxOutput(frames). */
    fun process(input: FloatArray, frames: Int, out: FloatArray): Int = TODO("DSP")
    /** Дописать хвост в конце потока; вернуть число кадров. */
    fun flush(out: FloatArray): Int = TODO("DSP")
    fun maxOutput(frames: Int): Int = TODO("DSP")
    companion object {
        /** Точная длина выхода для входа inFrames (формула из docs/math/dsp.md). */
        fun outputLength(inFrames: Long, inRate: Int, outRate: Int): Long = TODO("DSP")
    }
}

/** Квантование float → целые с TPDF-дизерингом (FR-C7). */
class Quantizer(val bits: Int, val dither: Boolean, seed: Long = System.nanoTime()) {
    /** src (float) → dst (целые со знаком в диапазоне bits). Возвращает число срезанных (clipped) сэмплов. */
    fun quantize(src: FloatArray, n: Int, dst: IntArray): Int = TODO("DSP")
}

/** Утилита: спецификация после сведения. */
fun PcmSpec.afterDownmix(): PcmSpec = PcmSpec(sampleRate, Downmix.outputChannels(channels))
