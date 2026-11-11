package com.rassul.audioeditor.core.dsp

import com.rassul.audioeditor.core.model.PcmSpec

/* КОНТРАКТ (реализация — агент DSP). Формулы — docs/math/dsp.md (physics-math-reviewer). */

object Gain {
    /** g = 10^(dB/20) (FR-20; IEC 60027-3, дБ по амплитуде; dsp.md табл. ф-ла 17). */
    fun dbToLinear(db: Double): Double = Math.pow(10.0, db / 20.0)

    /** dB = 20·log10(g); g ≤ 0 → −∞ (dsp.md ф-ла 12). */
    fun linearToDb(g: Double): Double = if (g > 0.0) 20.0 * Math.log10(g) else Double.NEGATIVE_INFINITY

    /**
     * Усиление из целых десятых дБ (шаг FR-20 = 0,1 дБ): g = 10^(t/200), посчитано в double и
     * округлено во float32 (dsp.md ловушка K-11; timeline.md «gain [a,b)»).
     */
    fun fromTenthsDb(tenths: Int): Float = Math.pow(10.0, tenths / 200.0).toFloat()
}

enum class FadeCurve { LINEAR, EQUAL_POWER, LOGARITHMIC }

object FadeCurves {
    /**
     * Множитель нарастания (fade in) в относительной позиции x ∈ [0,1] (0 — тишина, 1 — исходный уровень).
     * Fade out = gainIn(1 − x). FR-C2.
     */
    fun gainIn(curve: FadeCurve, x: Double): Double {
        val t = if (x.isNaN()) 0.0 else x.coerceIn(0.0, 1.0)
        return when (curve) {
            // FR-C2: линейная g = x.
            FadeCurve.LINEAR -> t
            // FR-C2: равной мощности g = sin(π·x/2) (Zölzer, DAFX, 2-е изд., гл. 2; dsp.md ф-ла 2).
            FadeCurve.EQUAL_POWER -> Math.sin(Math.PI * t / 2.0)
            // FR-C2: «лог» g = 10^(−60·(1−x)/20) при x > 0, g(0) = 0 (dsp.md ф-ла 3).
            FadeCurve.LOGARITHMIC -> if (t <= 0.0) 0.0 else Math.pow(10.0, -60.0 * (1.0 - t) / 20.0)
        }
    }

    /**
     * Множитель i-го сэмпла fade длиной n: x_i = i/(n−1) (FR-C2, dsp.md ф-ла 4).
     * n ≤ 1 → 1 (операция ничего не делает, dsp.md П-7). fadeIn=false — fade out: g(1 − x_i).
     */
    fun gainAt(curve: FadeCurve, i: Long, n: Long, fadeIn: Boolean): Double {
        if (n <= 1) return 1.0
        require(i in 0 until n) { "i=$i n=$n" }
        val x = i.toDouble() / (n - 1).toDouble()
        return gainIn(curve, if (fadeIn) x else 1.0 - x)
    }

    /** Таблица множителей fade длиной n (n ≤ 1 → все 1). */
    fun table(curve: FadeCurve, n: Int, fadeIn: Boolean): DoubleArray =
        DoubleArray(maxOf(n, 0)) { gainAt(curve, it.toLong(), n.toLong(), fadeIn) }
}

object JointSmoother {
    /**
     * Множитель сглаживания стыка (FR-C3) для сэмпла с индексом i ∈ [0, n) внутри рампы длиной n,
     * нарастание (rising=true, после стыка) или затухание (до стыка).
     */
    fun gain(i: Long, n: Long, rising: Boolean): Double {
        if (n <= 0) return 1.0
        require(i in 0 until n) { "i=$i n=$n" }
        // dsp.md П-3 (ПРИНЯТО): сетка x_i = (i + 0,5)/N; g_up = 0,5·(1 − cos(π·x_i)) (окно Ханна, Harris 1978).
        // Затухание — зеркально: g_down[j] = g_up[N−1−j].
        val k = if (rising) i else n - 1 - i
        val x = (k.toDouble() + 0.5) / n.toDouble()
        return 0.5 * (1.0 - Math.cos(Math.PI * x))
    }

    /** N = round_half_up(d·fs) из целых мс: ⌊(d_ms·fs + 500)/1000⌋ (FR-C3, timeline.md ф-ла 3). */
    fun rampLength(durationMs: Int, sampleRate: Int): Long {
        require(durationMs >= 0 && sampleRate > 0)
        return (durationMs.toLong() * sampleRate + 500L) / 1000L
    }

    /** Длина рампы для куска длиной pieceLen: N_k = min(N, ⌊len_k/2⌋) (dsp.md П-3, FR-19). 0 — кусок не трогать. */
    fun pieceRamp(n: Long, pieceLen: Long): Long = minOf(n, maxOf(pieceLen, 0L) / 2L).coerceAtLeast(0L)
}

/** Сведение каналов (FR-11, FR-C5). */
object Downmix {
    // Значения битов Android AudioFormat.CHANNEL_OUT_* (без импорта Android; те же числа).
    const val CHANNEL_OUT_FRONT_LEFT = 0x4
    const val CHANNEL_OUT_FRONT_RIGHT = 0x8
    const val CHANNEL_OUT_FRONT_CENTER = 0x10
    const val CHANNEL_OUT_LOW_FREQUENCY = 0x20
    const val CHANNEL_OUT_BACK_LEFT = 0x40
    const val CHANNEL_OUT_BACK_RIGHT = 0x80
    const val CHANNEL_OUT_FRONT_LEFT_OF_CENTER = 0x100
    const val CHANNEL_OUT_FRONT_RIGHT_OF_CENTER = 0x200
    const val CHANNEL_OUT_BACK_CENTER = 0x400
    const val CHANNEL_OUT_SIDE_LEFT = 0x800
    const val CHANNEL_OUT_SIDE_RIGHT = 0x1000
    const val CHANNEL_OUT_LOW_FREQUENCY_2 = 0x2000000

    const val CHANNEL_OUT_MONO = CHANNEL_OUT_FRONT_CENTER
    const val CHANNEL_OUT_STEREO = CHANNEL_OUT_FRONT_LEFT or CHANNEL_OUT_FRONT_RIGHT
    const val CHANNEL_OUT_QUAD = 0xCC
    const val CHANNEL_OUT_SURROUND = 0x41C
    const val CHANNEL_OUT_5POINT1 = 0xFC
    const val CHANNEL_OUT_7POINT1_SURROUND = 0x18FC

    private const val SQRT1_2 = 0.7071067811865476 // √0,5; BS.775 печатает 0,7071

    /**
     * Вклад бита маски в (L', R'). ITU-R BS.775-4 табл. 2 (3/2→2/0, 2/2→2/0) и решения dsp.md П-12:
     * Lb/Rb и Ls/Rs → 0,7071 на свою сторону; BC → 0,5 на обе; FLC/FRC → 1,0; LFE/LFE2 и прочие → 0.
     */
    fun coefficients(bit: Int): DoubleArray = when (bit) {
        CHANNEL_OUT_FRONT_LEFT -> doubleArrayOf(1.0, 0.0)
        CHANNEL_OUT_FRONT_RIGHT -> doubleArrayOf(0.0, 1.0)
        CHANNEL_OUT_FRONT_CENTER -> doubleArrayOf(SQRT1_2, SQRT1_2)
        CHANNEL_OUT_BACK_LEFT, CHANNEL_OUT_SIDE_LEFT -> doubleArrayOf(SQRT1_2, 0.0)
        CHANNEL_OUT_BACK_RIGHT, CHANNEL_OUT_SIDE_RIGHT -> doubleArrayOf(0.0, SQRT1_2)
        CHANNEL_OUT_FRONT_LEFT_OF_CENTER -> doubleArrayOf(1.0, 0.0)
        CHANNEL_OUT_FRONT_RIGHT_OF_CENTER -> doubleArrayOf(0.0, 1.0)
        CHANNEL_OUT_BACK_CENTER -> doubleArrayOf(0.5, 0.5)
        else -> doubleArrayOf(0.0, 0.0) // LFE, LFE2, TOP_* и прочие (dsp.md П-12)
    }

    /** Маска по умолчанию по числу каналов (dsp.md П-12, basic_ref.DEFAULT_MASK); 0 — нет. */
    fun defaultMask(channels: Int): Int = when (channels) {
        1 -> 0x10
        2 -> 0xC
        3 -> 0x1C
        4 -> 0xCC
        5 -> 0xDC
        6 -> 0xFC
        7 -> 0x4FC
        8 -> 0x18FC
        else -> 0
    }

    /** Маска, по которой реально строится матрица: заданная, если число её битов = числу каналов, иначе по умолчанию. */
    fun effectiveMask(channelMask: Int, inChannels: Int): Int =
        if (channelMask != 0 && Integer.bitCount(channelMask) == inChannels) channelMask else defaultMask(inChannels)

    /** Биты маски, которые при сведении отбрасываются без стандартного правила (для записи в журнал). LFE не считается. */
    fun unknownBits(channelMask: Int): Int {
        val known = CHANNEL_OUT_FRONT_LEFT or CHANNEL_OUT_FRONT_RIGHT or CHANNEL_OUT_FRONT_CENTER or
            CHANNEL_OUT_LOW_FREQUENCY or CHANNEL_OUT_BACK_LEFT or CHANNEL_OUT_BACK_RIGHT or
            CHANNEL_OUT_FRONT_LEFT_OF_CENTER or CHANNEL_OUT_FRONT_RIGHT_OF_CENTER or CHANNEL_OUT_BACK_CENTER or
            CHANNEL_OUT_SIDE_LEFT or CHANNEL_OUT_SIDE_RIGHT or CHANNEL_OUT_LOW_FREQUENCY_2
        return channelMask and known.inv()
    }

    /** Число выходных каналов: 1 для моно, 2 для всего остального. */
    fun outputChannels(inChannels: Int): Int = if (inChannels == 1) 1 else 2
    /**
     * Матрица [out][in] для маски каналов Android (AudioFormat.CHANNEL_OUT_*; 0 — неизвестна → порядок по умолчанию).
     * Для 1 и 2 каналов — единичная.
     */
    fun matrix(channelMask: Int, inChannels: Int): Array<DoubleArray> {
        require(inChannels >= 1) { "inChannels=$inChannels" }
        if (inChannels <= 2) return Array(inChannels) { o -> DoubleArray(inChannels) { i -> if (i == o) 1.0 else 0.0 } }
        val mask = effectiveMask(channelMask, inChannels)
        val m = Array(2) { DoubleArray(inChannels) }
        // Порядок каналов в чередовании — по возрастанию номера бита маски (dsp.md П-12, ловушка K-14).
        var ch = 0
        var rest = mask
        while (rest != 0 && ch < inChannels) {
            val bit = Integer.lowestOneBit(rest)
            rest = rest and bit.inv()
            val c = coefficients(bit)
            m[0][ch] = c[0]
            m[1][ch] = c[1]
            ch++
        }
        return m
    }

    /** Применить матрицу: src (inCh, чередование) → dst (outCh). */
    fun apply(matrix: Array<DoubleArray>, src: FloatArray, frames: Int, dst: FloatArray) {
        val outCh = matrix.size
        val inCh = matrix[0].size
        if (outCh == inCh && isIdentity(matrix)) {
            if (src !== dst) System.arraycopy(src, 0, dst, 0, frames * inCh)
            return
        }
        require(outCh <= inCh || src !== dst) { "in-place upmix" }
        // FR-C5: L' = Σ c_L,i·x_i, R' = Σ c_R,i·x_i — накопление в double, без нормировки и без обрезки (FR-14).
        for (f in 0 until frames) {
            val si = f * inCh
            val di = f * outCh
            for (o in 0 until outCh) {
                val row = matrix[o]
                var acc = 0.0
                for (i in 0 until inCh) acc += row[i] * src[si + i]
                dst[di + o] = acc.toFloat()
            }
        }
    }

    private fun isIdentity(m: Array<DoubleArray>): Boolean {
        for (o in m.indices) for (i in m[o].indices) if (m[o][i] != (if (i == o) 1.0 else 0.0)) return false
        return true
    }
}

/** Потоковый ресемплер (FR-C6). Реализация — [ResamplerCore] (Resampler.kt), формулы — dsp.md «FR-C6». */
class Resampler(val inRate: Int, val outRate: Int, val channels: Int) {
    private val core = ResamplerCore(inRate, outRate, channels)

    /** Параметры расчёта (L, M, Nt, β, срез). */
    val design: ResamplerDesign get() = core.design

    /** Подать frames кадров; записать готовые в out; вернуть число записанных кадров. out должен вмещать maxOutput(frames). */
    fun process(input: FloatArray, frames: Int, out: FloatArray): Int = core.process(input, frames, out)
    /** Дописать хвост в конце потока; вернуть число кадров. out должен вмещать maxOutput(0). */
    fun flush(out: FloatArray): Int = core.flush(out)
    /** Верхняя граница числа кадров одного вызова process(frames); maxOutput(0) — граница для flush(). */
    fun maxOutput(frames: Int): Int = core.maxOutput(frames)
    companion object {
        /** Точная длина выхода для входа inFrames (формула из docs/math/dsp.md). */
        fun outputLength(inFrames: Long, inRate: Int, outRate: Int): Long {
            if (inRate == outRate) return inFrames
            val d = ResamplerDesign.of(inRate, outRate)
            // dsp.md FR-C6: N_out = (2·N_in·L + M) div (2·M) — round half up в целых (Long, ловушка K-3).
            return (2L * inFrames * d.L + d.M) / (2L * d.M)
        }
    }
}

/** Квантование float → целые с TPDF-дизерингом (FR-C7). */
class Quantizer(val bits: Int, val dither: Boolean, seed: Long = System.nanoTime()) {
    init { require(bits in 8..32) { "bits=$bits" } }
    /** Сид ГСЧ (записывать в журнал, dsp.md FR-C7). */
    val seed: Long = seed
    private val rng = SplitMix64(seed)
    private val scale = Math.scalb(1.0, bits - 1)          // 2^(bits−1): 32768 для 16 бит, 8388608 для 24
    private val maxV = Math.scalb(1.0, bits - 1) - 1.0
    private val minV = -Math.scalb(1.0, bits - 1)

    /** src (float) → dst (целые со знаком в диапазоне bits). Возвращает число срезанных (clipped) сэмплов. */
    fun quantize(src: FloatArray, n: Int, dst: IntArray): Int {
        var clipped = 0
        for (i in 0 until n) {
            val x = src[i].toDouble()
            // Счётчик среза — по |x| > 1,0 ДО дизеринга (dsp.md FR-C7).
            if (x > 1.0 || x < -1.0) clipped++
            // TPDF ±1 LSB: d = u1 − u2, два числа SplitMix64 на сэмпл, порядок «кадр, затем канал» —
            // это порядок чередования (dsp.md FR-C7; Lipshitz, Wannamaker, Vanderkooy, JAES 40(5), 1992).
            val d = if (dither) rng.nextDouble() - rng.nextDouble() else 0.0
            // y = clamp(floor(x·2^(bits−1) + d + 0,5)) — half-up в double (FR-C7, dsp.md ловушка K-1).
            var v = Math.floor(x * scale + d + 0.5)
            if (v.isNaN()) v = 0.0
            if (v > maxV) v = maxV
            if (v < minV) v = minV
            dst[i] = v.toLong().toInt()
        }
        return clipped
    }
}

/** Утилита: спецификация после сведения. */
fun PcmSpec.afterDownmix(): PcmSpec = PcmSpec(sampleRate, Downmix.outputChannels(channels))
