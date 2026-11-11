package com.rassul.audioeditor.core.dsp

/**
 * Параметры полифазного windowed-sinc ресемплера (dsp.md «FR-C6. Ресемплер», эталон tools/ref/resampler_ref.py).
 *  L/M = fs_out/fs_in после сокращения на НОД; ρ = min(1, L/M);
 *  срез fcn = 0,475·ρ (циклов на входной сэмпл), переход Δ = 0,05·ρ;
 *  окно Кайзера: A = 100 дБ, β = 0,1102·(A − 8,7) (Oppenheim & Schafer, 3-е изд., ф-ла 7.62),
 *  Nt = чётное ⌈(A − 7,95)/(2,285·2π·Δ)⌉ (там же, ф-ла 7.63), T = Nt/2.
 */
class ResamplerDesign private constructor(
    val L: Int, val M: Int, val rho: Double, val fcn: Double, val beta: Double, val Nt: Int,
) {
    val T: Int get() = Nt / 2
    private val i0Beta = besselI0(beta)

    /**
     * Ядро h(τ) = 2fcn·sinc(2fcn·τ)·I0(β·√(1−(τ/T)²))/I0(β) при |τ| ≤ T, иначе 0 (dsp.md FR-C6).
     */
    fun kernel(tau: Double): Double {
        val t = T.toDouble()
        if (Math.abs(tau) > t) return 0.0
        val q = tau / t
        val r = maxOf(0.0, 1.0 - q * q)
        val w = besselI0(beta * Math.sqrt(r)) / i0Beta
        return 2.0 * fcn * sinc(2.0 * fcn * tau) * w
    }

    companion object {
        const val A_DESIGN = 100.0

        fun of(inRate: Int, outRate: Int): ResamplerDesign {
            require(inRate > 0 && outRate > 0) { "rates $inRate→$outRate" }
            val g = gcd(inRate, outRate)
            val L = outRate / g
            val M = inRate / g
            val rho = minOf(1.0, L.toDouble() / M.toDouble())
            val fcn = 0.475 * rho
            val dfn = 0.05 * rho
            val beta = 0.1102 * (A_DESIGN - 8.7)
            // Порядок операций как в эталоне: (A − 7,95)/(2,285·2·π·Δ).
            val span = (A_DESIGN - 7.95) / (2.285 * 2 * Math.PI * dfn)
            var nt = Math.ceil(span).toInt()
            nt += nt % 2
            return ResamplerDesign(L, M, rho, fcn, beta, nt)
        }

        private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

        /** sinc(x) = sin(πx)/(πx), sinc(0) = 1 (dsp.md ловушка K-9). */
        fun sinc(x: Double): Double {
            if (Math.abs(x) < 1e-12) return 1.0
            val px = Math.PI * x
            return Math.sin(px) / px
        }

        /** Модифицированная функция Бесселя I0: ряд Σ ((x/2)^k / k!)² до члена < 1e-17·суммы (dsp.md ловушка K-8). */
        fun besselI0(x: Double): Double {
            val h = x / 2.0
            var sum = 1.0
            var term = 1.0
            var k = 1
            while (true) {
                val f = h / k
                term *= f * f
                sum += term
                if (term < 1e-17 * sum) break
                k++
                if (k > 500) break
            }
            return sum
        }
    }
}

/**
 * Потоковая реализация FR-C6 (dsp.md): нуль-фазовая схема без задержки.
 *  Выход m: P = m·M, n0 = P div L, p = P mod L, μ = p/L;
 *  y[m] = Σ_{k=−(T−1)}^{T} x[n0+k]·h(μ − k); x = 0 вне [0, N_in).
 * Таблица фаз L × Nt (double → float). Если L·Nt > 2^20 — таблица на P = 4096 фаз с линейной
 * интерполяцией между соседними фазами (dsp.md П-5). fs_in = fs_out — копирование бит в бит (ловушка K-10).
 * Накопление — в double.
 */
class ResamplerCore(val inRate: Int, val outRate: Int, val channels: Int) {
    init { require(channels >= 1) { "channels=$channels" } }

    val bypass = inRate == outRate
    val design: ResamplerDesign = ResamplerDesign.of(inRate, outRate)
    private val L = design.L
    private val M = design.M
    private val T = design.T
    private val Nt = design.Nt

    /** true — таблица точная (L фаз), false — 4096 фаз с интерполяцией (П-5). */
    val exactTable: Boolean = L.toLong() * Nt <= (1L shl 20)
    private val phases: Int = if (exactTable) L else INTERP_PHASES
    private val table: FloatArray

    init {
        if (bypass) {
            table = FloatArray(0)
        } else {
            val rows = if (exactTable) L else INTERP_PHASES + 1
            table = FloatArray(rows * Nt)
            for (r in 0 until rows) {
                val mu = r.toDouble() / phases.toDouble()
                for (j in 0 until Nt) {
                    val k = j - (T - 1)               // k = −(T−1) … T
                    table[r * Nt + j] = design.kernel(mu - k).toFloat()
                }
            }
        }
    }

    // История входа по каналам: hist[c][i] — сэмпл с абсолютным индексом histStart + i.
    private var hist = Array(channels) { FloatArray(maxOf(4096, 4 * Nt)) }
    private var histStart = -(T - 1).toLong()
    private var histLen = if (bypass) 0 else T - 1        // x[−(T−1) … −1] = 0
    private var nIn = 0L                                  // принято входных кадров
    private var m = 0L                                    // следующий выходной кадр
    private var n0 = 0L                                   // n0 для m
    private var p = 0                                     // фаза для m
    private var flushed = false

    fun maxOutput(frames: Int): Int {
        if (bypass) return frames
        return ((frames.toLong() + T + 2) * L / M + 3).toInt()
    }

    fun process(input: FloatArray, frames: Int, out: FloatArray): Int {
        check(!flushed) { "flush() уже вызван" }
        if (frames <= 0) return 0
        if (bypass) {
            System.arraycopy(input, 0, out, 0, frames * channels)
            nIn += frames
            return frames
        }
        append(input, frames)
        nIn += frames
        // Выход m готов, когда пришли все нужные входы: n0 + T < nIn; и m не превысил текущую длину.
        val limit = outLen(nIn)
        return produce(out, availableEnd = nIn, mLimit = limit)
    }

    fun flush(out: FloatArray): Int {
        check(!flushed) { "flush() уже вызван" }
        flushed = true
        if (bypass) return 0
        // На последнем блоке дополнять нулями (dsp.md FR-C6): T+1 нулей покрывают n0 + T для всех m < N_out.
        appendZeros(T + 1)
        return produce(out, availableEnd = Long.MAX_VALUE, mLimit = outLen(nIn))
    }

    /** N_out = (2·N_in·L + M) div (2·M) (dsp.md FR-C6). */
    private fun outLen(n: Long): Long = (2L * n * L + M) / (2L * M)

    private fun produce(out: FloatArray, availableEnd: Long, mLimit: Long): Int {
        var written = 0
        val ch = channels
        while (m < mLimit && n0 + T < availableEnd && n0 + T < histStart + histLen) {
            val base = (n0 - (T - 1) - histStart).toInt()
            if (exactTable) {
                val off = p * Nt
                for (c in 0 until ch) {
                    out[written * ch + c] = dot(hist[c], base, table, off).toFloat()
                }
            } else {
                // П-5: позиция фазы μ·P = p·P/L; линейная интерполяция между строками i и i+1.
                val pp = p.toLong() * INTERP_PHASES
                val i = (pp / L).toInt()
                val f = (pp % L).toDouble() / L
                val off0 = i * Nt
                val off1 = off0 + Nt
                for (c in 0 until ch) {
                    val a = dot(hist[c], base, table, off0)
                    val b = dot(hist[c], base, table, off1)
                    out[written * ch + c] = ((1.0 - f) * a + f * b).toFloat()
                }
            }
            written++
            m++
            p += M
            n0 += p / L
            p %= L
        }
        // Отбросить вход, который больше не понадобится: нужен x[n0 − (T−1) …].
        val keepFrom = n0 - (T - 1)
        val drop = (keepFrom - histStart).coerceIn(0L, histLen.toLong()).toInt()
        if (drop > 0) {
            for (c in 0 until ch) System.arraycopy(hist[c], drop, hist[c], 0, histLen - drop)
            histLen -= drop
            histStart += drop
        }
        return written
    }

    private fun dot(x: FloatArray, base: Int, h: FloatArray, off: Int): Double {
        var a0 = 0.0
        var a1 = 0.0
        var j = 0
        val n = Nt
        while (j + 1 < n) {
            a0 += x[base + j] * h[off + j]
            a1 += x[base + j + 1] * h[off + j + 1]
            j += 2
        }
        if (j < n) a0 += x[base + j] * h[off + j]
        return a0 + a1
    }

    private fun ensureCapacity(extra: Int) {
        val need = histLen + extra
        if (need <= hist[0].size) return
        var cap = hist[0].size
        while (cap < need) cap *= 2
        hist = Array(channels) { hist[it].copyOf(cap) }
    }

    private fun append(input: FloatArray, frames: Int) {
        ensureCapacity(frames)
        val ch = channels
        for (c in 0 until ch) {
            val h = hist[c]
            var si = c
            var di = histLen
            for (f in 0 until frames) { h[di++] = input[si]; si += ch }
        }
        histLen += frames
    }

    private fun appendZeros(n: Int) {
        ensureCapacity(n)
        for (c in 0 until channels) java.util.Arrays.fill(hist[c], histLen, histLen + n, 0f)
        histLen += n
    }

    companion object {
        const val INTERP_PHASES = 4096
    }
}
