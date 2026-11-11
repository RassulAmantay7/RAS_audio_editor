package com.rassul.audioeditor.core.audit.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.fail

/**
 * Независимая «наивная» реализация списка правок (code-formula-auditor, аудит T12/T14):
 * каждая операция применяется к ЦЕЛОМУ массиву float32 результата предыдущих операций — прямо по
 * docs/math/timeline.md «Семантика списка правок» и dsp.md (FR-C2, FR-C3 П-3, П-7, решения главной сессии).
 * Код :core (JointSmoother, FadeCurves, Gain, RenderMap) здесь НЕ используется.
 */
object Naive {
    /** FR-C3 (dsp.md П-3): g_up[i] = 0,5(1 − cos(π(i+0,5)/N)). */
    fun up(i: Long, n: Long): Double = 0.5 * (1.0 - Math.cos(Math.PI * ((i.toDouble() + 0.5) / n.toDouble())))

    /** FR-C2: x_i = i/(N−1); fade out = g(1 − x_i) (dsp.md ф-лы 1–4, принято в аудите T13). */
    fun fade(curve: FadeCurve, i: Long, n: Long, fadeIn: Boolean): Double {
        val x0 = i.toDouble() / (n - 1).toDouble()
        val t = if (fadeIn) x0 else 1.0 - x0
        return when (curve) {
            FadeCurve.LINEAR -> t
            FadeCurve.EQUAL_POWER -> Math.sin(Math.PI * t / 2.0)
            FadeCurve.LOGARITHMIC -> if (t <= 0.0) 0.0 else Math.pow(10.0, -60.0 * (1.0 - t) / 20.0)
        }
    }

    /** N_k = min(N, ⌊len/2⌋) (dsp.md П-3). */
    fun nk(n: Long, len: Long) = minOf(n, len / 2)

    fun volume(db: Double): Float = Math.pow(10.0, Math.round(db * 10.0) / 200.0).toFloat()
    fun normGain(g: Double): Float = Math.pow(10.0, g / 20.0).toFloat()

    fun apply(x: FloatArray, ch: Int, op: EditOp, n: Long): FloatArray {
        val l = (x.size / ch).toLong()
        fun mul(y: FloatArray, f: Long, g: Float) { for (c in 0 until ch) { val k = (f * ch + c).toInt(); y[k] = y[k] * g } }
        fun cut(a: Long, b: Long) = x.copyOfRange((a * ch).toInt(), (b * ch).toInt())
        return when (op) {
            is EditOp.Keep -> {
                val a = op.range.start; val b = op.range.end
                val y = cut(a, b); val len = b - a; val k = nk(n, len)
                if (a > 0) for (i in 0 until k) mul(y, i, up(i, k).toFloat())
                if (b < l) for (j in 0 until k) mul(y, len - k + j, up(k - 1 - j, k).toFloat())
                y
            }
            is EditOp.Delete -> {
                val a = op.range.start; val b = op.range.end
                val y = cut(0, a) + cut(b, l)
                if (a > 0) { val k = nk(n, a); for (j in 0 until k) mul(y, a - k + j, up(k - 1 - j, k).toFloat()) }
                if (b < l) { val k = nk(n, l - b); for (i in 0 until k) mul(y, a + i, up(i, k).toFloat()) }
                y
            }
            is EditOp.Volume -> {
                val y = x.copyOf(); val r = op.range ?: Range(0, l); val g = volume(op.db)
                for (f in r.start until r.end) mul(y, f, g); y
            }
            is EditOp.Fade -> {
                val y = x.copyOf(); val len = op.range.length
                if (len >= 2) for (i in 0 until len) mul(y, op.range.start + i, fade(op.curve, i, len, op.fadeIn).toFloat())
                y
            }
            is EditOp.Normalize -> {
                val y = x.copyOf(); val g = normGain(op.resolvedGainDb!!)
                for (f in 0 until l) mul(y, f, g); y
            }
        }
    }

    fun render(x: FloatArray, ch: Int, ops: List<EditOp>, n: Long): FloatArray =
        ops.fold(x) { acc, op -> apply(acc, ch, op, n) }
}

/** Детерминированный ГСЧ для аудита. */
class ARng(seed: Long) {
    private val r = java.util.SplittableRandom(seed)
    fun int(n: Int) = r.nextInt(n)
    fun long(lo: Long, hiIncl: Long) = lo + r.nextLong(hiIncl - lo + 1)
    fun dbl() = r.nextDouble()
    fun bool(p: Double = 0.5) = r.nextDouble() < p

    /** Точка в [0, L], с перекосом к краям и к «старым стыкам» (anchors). */
    fun point(l: Long, anchors: List<Long>): Long = when {
        bool(0.15) -> 0L
        bool(0.15) -> l
        anchors.isNotEmpty() && bool(0.35) -> (anchors[int(anchors.size)] + long(-6, 6)).coerceIn(0, l)
        else -> long(0, l)
    }

    fun range(l: Long, anchors: List<Long>, nonEmpty: Boolean): Range {
        while (true) {
            var a = point(l, anchors); var b = point(l, anchors)
            if (a > b) { val t = a; a = b; b = t }
            if (!nonEmpty || b > a) return Range(a, b)
        }
    }

    /**
     * Случайная допустимая правка для длины l. anchors — позиции стыков в координатах текущего шага
     * (обновляются вызывающим), чтобы чаще проверять «повторный Delete рядом со старым стыком».
     */
    fun op(l: Long, anchors: MutableList<Long>): EditOp {
        while (true) {
            when (int(6)) {
                0 -> if (l >= 2) { val r = range(l, anchors, true); return EditOp.Keep(r) }
                1, 2 -> if (l >= 2) { val r = range(l, anchors, true); if (!(r.start == 0L && r.end == l)) return EditOp.Delete(r) }
                3 -> return EditOp.Volume(if (bool(0.3)) null else range(l, anchors, false), long(-400, 200) / 10.0)
                4 -> return EditOp.Fade(range(l, anchors, false), bool(), FadeCurve.values()[int(3)])
                5 -> return EditOp.Normalize(com.rassul.audioeditor.core.loudness.NormalizeTarget.Lufs(), resolvedGainDb = (dbl() - 0.5) * 30.0)
            }
        }
    }

    /** Список из k случайных правок; возвращает (список, длины по шагам). */
    fun list(l0: Long, k: Int): List<EditOp> {
        val ops = ArrayList<EditOp>(); var l = l0; val anchors = ArrayList<Long>()
        repeat(k) {
            val op = op(l, anchors)
            ops += op
            when (op) {
                is EditOp.Keep -> { anchors.replaceAll { it - op.range.start }; anchors.removeIf { it < 0 || it > op.range.length }; l = op.range.length; anchors += 0L; anchors += l }
                is EditOp.Delete -> { val a = op.range.start; val d = op.range.length; anchors.replaceAll { if (it >= op.range.end) it - d else if (it > a) a else it }; l -= d; anchors += a }
                else -> {}
            }
        }
        return ops
    }
}

/** Источник, который НЕ потокобезопасен (общий буфер) — ловит одновременное чтение без синхронизации. */
class FragileSource(override val spec: PcmSpec, private val data: FloatArray) : PcmSource {
    private val shared = FloatArray(1 shl 16)
    var reads = 0; private set
    override val length: Long get() = (data.size / spec.channels).toLong()
    override fun read(from: Long, dst: FloatArray, frames: Int): Int {
        reads++
        if (from >= length || frames <= 0) return 0
        val n = minOf(frames.toLong(), length - from, (shared.size / spec.channels).toLong()).toInt()
        val m = n * spec.channels
        System.arraycopy(data, (from * spec.channels).toInt(), shared, 0, m)
        Thread.yield()
        System.arraycopy(shared, 0, dst, 0, m)
        return n
    }
}

fun noiseA(frames: Int, ch: Int, seed: Long, amp: Double = 0.6): FloatArray {
    val r = java.util.SplittableRandom(seed)
    return FloatArray(frames * ch) { ((r.nextDouble() * 2 - 1) * amp).toFloat() }
}

fun arraySrc(data: FloatArray, ch: Int, fs: Int = 48_000) = ArrayPcmSource(PcmSpec(fs, ch), data)

/** Рендер целиком случайными кусками (размер 1…maxBlock), запись по случайным dstOffset. */
fun renderChunks(r: Renderer, rng: ARng, maxBlock: Int = 997): FloatArray {
    val ch = r.spec.channels; val total = r.length.toInt()
    val out = FloatArray(total * ch)
    var pos = 0
    while (pos < total) {
        val n = minOf(1 + rng.int(maxBlock), total - pos)
        val off = rng.int(5)
        val tmp = FloatArray((off + n) * ch)
        val got = r.render(pos.toLong(), tmp, off, n)
        if (got != n) fail("render вернул $got вместо $n с позиции $pos")
        System.arraycopy(tmp, off * ch, out, pos * ch, n * ch)
        pos += n
    }
    return out
}

fun firstBitDiff(a: FloatArray, b: FloatArray): Int {
    for (i in 0 until minOf(a.size, b.size)) if (a[i].toRawBits() != b[i].toRawBits()) return i
    return if (a.size == b.size) -1 else minOf(a.size, b.size)
}

fun settingsFor(n: Long, fs: Int): RenderSettings =
    if (n == 0L) RenderSettings(smoothJoints = false) else RenderSettings(true, n * 1000.0 / fs)

fun renderer(src: PcmSource, ops: List<EditOp>, s: RenderSettings) = Renderer(src, EditList(ops), s)
