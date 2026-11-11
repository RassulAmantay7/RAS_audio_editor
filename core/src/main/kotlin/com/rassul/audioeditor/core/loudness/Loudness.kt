package com.rassul.audioeditor.core.loudness

import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.Progress

/* КОНТРАКТ (реализация — агент DSP). FR-C4, FR-22. */

/**
 * Результат измерения. NEGATIVE_INFINITY для тишины.
 * integratedLufs: NaN — нет ни одного полного блока 400 мс (файл короче 0,4 с);
 * −∞ — ни один блок не прошёл абсолютный гейт −70 LUFS (dsp.md «FR-C4» п. 4, ловушка K-7).
 */
data class LoudnessResult(
    val integratedLufs: Double,
    val truePeakDbtp: Double,
    val samplePeakDbfs: Double,
    val durationFrames: Long,
)

/** Потоковый измеритель громкости по ITU-R BS.1770-5. */
class LoudnessMeter(val sampleRate: Int, val channels: Int) {
    /** Веса каналов G_i (BS.1770-5 табл. 3): L = R = C = 1,0; Ls = Rs = 1,41. По умолчанию все 1,0. */
    var weights: DoubleArray = DoubleArray(channels) { 1.0 }
        private set

    constructor(sampleRate: Int, channels: Int, weights: DoubleArray) : this(sampleRate, channels) {
        require(weights.size == channels) { "weights.size=${weights.size}" }
        this.weights = weights.copyOf()
    }

    init {
        require(sampleRate > 0 && channels >= 1) { "fs=$sampleRate ch=$channels" }
    }

    // ---- K-фильтр (double, Transposed DF II), состояние своё для каждого канала (dsp.md «FR-C4» п. 2, K-4) ----
    private val k = KFilter.coefficients(sampleRate)
    private val st = Array(channels) { DoubleArray(4) } // [s1_z1, s1_z2, s2_z1, s2_z2]

    // ---- Сегменты 100 мс: b(i) = floor(i·fs/10) (dsp.md П-4), энергия по сегменту в double (K-5) ----
    private var pos = 0L
    private var segIndex = 0L
    private var segEnd = segBound(1)
    private val segSum = DoubleArray(channels)
    private val prevSeg = Array(3) { DoubleArray(channels) } // три предыдущих сегмента (кольцо)
    private var blockP = DoubleArray(1024)                     // p_j = Σ G_i·z_ij
    private var blockCount = 0

    // ---- True peak (Annex 2), история 11 сэмплов на канал, нулевое начальное состояние (K-12) ----
    private val phases = TruePeakFilter.phasesFor(sampleRate)
    private val tpBound = phases.maxOf { h -> h.sumOf { Math.abs(it) } }
    private val ext = Array(channels) { DoubleArray(HIST + CHUNK) }
    private var tpMax = 0.0      // max(|y_φ|, |x|) — линейно
    private var samplePeak = 0.0 // max|x|

    /** Отключить пропуск заведомо тихих блоков в true peak (только для тестов: результат обязан совпадать). */
    var tpSkipEnabled = true

    private fun segBound(i: Long): Long = i * sampleRate / 10L // Long: i·fs до 3,5·10⁹ и больше (K-3)

    fun process(interleaved: FloatArray, frames: Int) {
        var off = 0
        var left = frames
        while (left > 0) {
            val n = minOf(left.toLong(), segEnd - pos, CHUNK.toLong()).toInt()
            chunk(interleaved, off, n)
            off += n
            left -= n
            pos += n
            if (pos == segEnd) endSegment()
        }
    }

    private fun chunk(x: FloatArray, frameOff: Int, n: Int) {
        val ch = channels
        val b0 = k.s1b0; val b1 = k.s1b1; val b2 = k.s1b2; val a1 = k.s1a1; val a2 = k.s1a2
        val c1 = k.s2a1; val c2 = k.s2a2
        var chunkMax = 0.0
        for (c in 0 until ch) {
            val s = st[c]
            var z1 = s[0]; var z2 = s[1]; var w1 = s[2]; var w2 = s[3]
            var acc = 0.0
            val e = ext[c]
            var idx = frameOff * ch + c
            var peak = 0.0
            for (i in 0 until n) {
                val v = x[idx].toDouble()
                idx += ch
                e[HIST + i] = v
                val av = Math.abs(v)
                if (av > peak) peak = av
                // Каскад 1 (полка), TDF-II: y = b0·x + z1; z1 = b1·x − a1·y + z2; z2 = b2·x − a2·y (BS.1770-5 Annex 1).
                val y1 = b0 * v + z1
                z1 = b1 * v - a1 * y1 + z2
                z2 = b2 * v - a2 * y1
                // Каскад 2 (RLB ФВЧ), b = [1, −2, 1].
                val y2 = y1 + w1
                w1 = -2.0 * y1 - c1 * y2 + w2
                w2 = y1 - c2 * y2
                acc += y2 * y2 // энергия: Σ y² (BS.1770-5 ф-ла 1)
            }
            s[0] = z1; s[1] = z2; s[2] = w1; s[3] = w2
            segSum[c] += acc
            if (peak > chunkMax) chunkMax = peak
        }
        if (chunkMax > samplePeak) samplePeak = chunkMax
        if (chunkMax > tpMax) tpMax = chunkMax // TP = max(пик фильтра, max|x|) (dsp.md П-6)
        truePeakChunk(n)
    }

    /** True peak по ext[c] = [11 предыдущих сэмплов, n новых]; затем сдвиг истории. */
    private fun truePeakChunk(n: Int) {
        for (c in 0 until channels) {
            val e = ext[c]
            var winMax = 0.0
            for (i in 0 until HIST + n) { val a = Math.abs(e[i]); if (a > winMax) winMax = a }
            // Точное ускорение: |y_φ[n]| ≤ Σ|h_φ|·max|x окна|; если это ≤ уже найденного пика, блок не меняет итог.
            if (!(tpSkipEnabled && winMax * tpBound <= tpMax)) {
                tpMax = maxOf(tpMax, firPeak(e, HIST, n))
            }
            System.arraycopy(e, n, e, 0, HIST)
        }
    }

    /** max_φ max_i |Σ_k h_φ[k]·e[i − k]| для i ∈ [from, from+n) (BS.1770-5 Annex 2, КИХ-интерполятор). */
    private fun firPeak(e: DoubleArray, from: Int, n: Int): Double {
        var mx = 0.0
        for (h in phases) {
            val h0 = h[0]; val h1 = h[1]; val h2 = h[2]; val h3 = h[3]; val h4 = h[4]; val h5 = h[5]
            val h6 = h[6]; val h7 = h[7]; val h8 = h[8]; val h9 = h[9]; val h10 = h[10]; val h11 = h[11]
            for (i in from until from + n) {
                val y = h0 * e[i] + h1 * e[i - 1] + h2 * e[i - 2] + h3 * e[i - 3] + h4 * e[i - 4] +
                    h5 * e[i - 5] + h6 * e[i - 6] + h7 * e[i - 7] + h8 * e[i - 8] + h9 * e[i - 9] +
                    h10 * e[i - 10] + h11 * e[i - 11]
                val a = Math.abs(y)
                if (a > mx) mx = a
            }
        }
        return mx
    }

    private fun endSegment() {
        // Денормалы (dsp.md ловушка K-6): обнулить затухшее состояние IIR раз на сегмент.
        for (s in st) for (j in 0 until 4) if (Math.abs(s[j]) < 1e-30) s[j] = 0.0
        val i = segIndex
        if (i >= 3) {
            // Блок j = i − 3 = сегменты j…j+3: z_jc = Σ y² / (b(j+4) − b(j)) (BS.1770-5 ф-ла 2, dsp.md П-4).
            val j = i - 3
            val len = (segBound(j + 4) - segBound(j)).toDouble()
            var pj = 0.0
            for (c in 0 until channels) {
                val sum = prevSeg[((j) % 3).toInt()][c] + prevSeg[((j + 1) % 3).toInt()][c] +
                    prevSeg[((j + 2) % 3).toInt()][c] + segSum[c]
                pj += weights[c] * (sum / len) // Σ G_i·z_ij (BS.1770-5 ф-ла 4)
            }
            if (blockCount == blockP.size) blockP = blockP.copyOf(blockP.size * 2)
            blockP[blockCount++] = pj
        }
        val slot = prevSeg[(i % 3).toInt()]
        for (c in 0 until channels) { slot[c] = segSum[c]; segSum[c] = 0.0 }
        segIndex++
        segEnd = segBound(segIndex + 1)
    }

    /** Число полных блоков 400 мс. */
    val blocks: Int get() = blockCount

    fun result(): LoudnessResult {
        return LoudnessResult(
            integratedLufs = integrated(),
            truePeakDbtp = toDb(maxOf(tpMax, tailPeak())),
            samplePeakDbfs = toDb(samplePeak),
            durationFrames = pos,
        )
    }

    /** Хвост true peak: после конца подаются 11 нулей (dsp.md П-6, K-12); состояние не меняется. */
    private fun tailPeak(): Double {
        var mx = 0.0
        val tmp = DoubleArray(2 * HIST)
        for (c in 0 until channels) {
            System.arraycopy(ext[c], 0, tmp, 0, HIST)
            java.util.Arrays.fill(tmp, HIST, 2 * HIST, 0.0)
            mx = maxOf(mx, firPeak(tmp, HIST, HIST))
        }
        return mx
    }

    private fun integrated(): Double {
        if (blockCount == 0) return Double.NaN // нет полного блока (< 400 мс)
        // l_j = −0,691 + 10·log10(p_j) (BS.1770-5 ф-ла 4). Гейты строгие (dsp.md табл. ф-ла 9).
        var sumA = 0.0
        var nA = 0
        for (j in 0 until blockCount) {
            val l = lufs(blockP[j])
            if (l > ABS_GATE) { sumA += blockP[j]; nA++ } // NaN и −∞ не проходят (K-7)
        }
        if (nA == 0) return Double.NEGATIVE_INFINITY
        // Γ_r = −0,691 + 10·log10(среднее p по J_a) − 10 (BS.1770-5 ф-лы 6–7).
        val gammaR = lufs(sumA / nA) + REL_GATE
        var sumR = 0.0
        var nR = 0
        for (j in 0 until blockCount) {
            val l = lufs(blockP[j])
            if (l > ABS_GATE && l > gammaR) { sumR += blockP[j]; nR++ }
        }
        if (nR == 0) return Double.NEGATIVE_INFINITY
        return lufs(sumR / nR) // L_K = −0,691 + 10·log10(среднее p по J_g) (BS.1770-5 ф-ла 7)
    }

    companion object {
        const val ABS_GATE = -70.0
        const val REL_GATE = -10.0
        private const val HIST = TruePeakFilter.TAPS - 1
        private const val CHUNK = 8192

        /** −0,691 + 10·log10(p) (BS.1770-5 ф-ла 4; −0,691 компенсирует +0,691 дБ K-фильтра на 997 Гц). */
        fun lufs(p: Double): Double = -0.691 + 10.0 * Math.log10(p)

        /** 20·log10(v), v ≤ 0 → −∞ (dsp.md ф-ла 12). */
        fun toDb(v: Double): Double = if (v > 0.0) 20.0 * Math.log10(v) else Double.NEGATIVE_INFINITY

        /** Есть ли хотя бы один полный блок 400 мс: b(4) = ⌊4·fs/10⌋ ≤ N (dsp.md П-4). */
        fun hasCompleteBlock(frames: Long, sampleRate: Int): Boolean = 4L * sampleRate / 10L <= frames

        fun measure(src: PcmSource, progress: Progress = Progress.NONE): LoudnessResult {
            val ch = src.spec.channels
            val meter = LoudnessMeter(src.spec.sampleRate, ch)
            val step = 65_536
            val buf = FloatArray(step * ch)
            val total = src.length
            var from = 0L
            while (from < total) {
                if (progress.isCancelled) throw CancelledException()
                val want = minOf(step.toLong(), total - from).toInt()
                val got = src.read(from, buf, want)
                if (got <= 0) break
                meter.process(buf, got)
                from += got
                if (total > 0) progress.report(from.toDouble() / total)
            }
            return meter.result()
        }
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
    const val MSG_TOO_SHORT = "Нормализация «На слух» недоступна: файл короче 0,4 с"
    const val MSG_TOO_QUIET = "Нормализация «На слух» недоступна: файл тише −70 LUFS"
    const val MSG_SILENT = "Нормализация по пику недоступна: в файле только тишина"

    fun decide(measured: LoudnessResult, target: NormalizeTarget): NormalizeDecision = when (target) {
        is NormalizeTarget.Lufs -> decideLufs(measured, target)
        is NormalizeTarget.Peak -> decidePeak(measured, target)
    }

    private fun decideLufs(m: LoudnessResult, t: NormalizeTarget.Lufs): NormalizeDecision {
        val i = m.integratedLufs
        if (i.isNaN()) return NormalizeDecision(false, 0.0, false, MSG_TOO_SHORT)
        if (!(i > LoudnessMeter.ABS_GATE)) return NormalizeDecision(false, 0.0, false, MSG_TOO_QUIET)
        // FR-22: G = цель − I (I и TP сдвигаются ровно на G дБ — линейность, dsp.md ф-ла 18).
        val g = t.targetLufs - i
        val tp = m.truePeakDbtp
        if (!(tp + g > t.truePeakCeilingDbtp)) return NormalizeDecision(true, g, false, null)
        // Ограничение пика: G' = потолок − TP_исх (FR-22).
        val gp = t.truePeakCeilingDbtp - tp
        if (g > 0.0 && gp < 0.0) {
            // Решение пользователя (dsp.md, «Решения главной сессии»): громкость не меняется, усиление 0 дБ.
            return NormalizeDecision(true, 0.0, true,
                "Нормализация не применена: в записи есть пик у предела (TP = ${fmtDb(tp)} dBTP), " +
                    "поднять громкость без искажений нельзя")
        }
        val applied = minOf(g, gp)
        val achieved = i + applied
        val msg = "Громкость ${fmtTarget(t.targetLufs)} LUFS недостижима без перегрузки. " +
            "Достигнуто ${fmtDb(achieved)} LUFS (пик ${fmtDb(t.truePeakCeilingDbtp)} dBTP)."
        return NormalizeDecision(true, applied, true, msg)
    }

    private fun decidePeak(m: LoudnessResult, t: NormalizeTarget.Peak): NormalizeDecision {
        val sp = m.samplePeakDbfs
        if (!sp.isFinite()) return NormalizeDecision(false, 0.0, false, MSG_SILENT)
        // По пику: g = 10^(потолок/20)/max|x| ⇔ G = потолок − 20·log10(max|x|) (dsp.md ф-ла 19).
        return NormalizeDecision(true, t.ceilingDbfs - sp, false, null)
    }

    /**
     * Число дБ для текста (dsp.md П-8 (г), basic_ref.fmt_db): одна цифра после запятой, округление
     * половины от нуля, минус U+2212, без «−0,0».
     */
    fun fmtDb(v: Double): String {
        if (v.isNaN()) return "—"
        if (v.isInfinite()) return if (v < 0) "−∞" else "∞"
        val tenths = Math.floor(Math.abs(v) * 10.0 + 0.5 + 1e-9).toLong()
        val s = "${tenths / 10},${tenths % 10}"
        return if (v < 0 && tenths != 0L) "−$s" else s
    }

    /** Цель: целое значение выводится без дробной части («−14»), иначе как fmtDb. */
    fun fmtTarget(v: Double): String {
        if (v == Math.floor(v) && Math.abs(v) < 1e9) {
            val n = v.toLong()
            return if (n < 0) "−${-n}" else "$n"
        }
        return fmtDb(v)
    }
}
