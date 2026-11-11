package com.rassul.audioeditor.core.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.FadeCurves
import com.rassul.audioeditor.core.dsp.Gain
import com.rassul.audioeditor.core.dsp.JointSmoother
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.fail

/**
 * Эталон: ПОСЛЕДОВАТЕЛЬНОЕ применение правок к целому массиву (timeline.md «Семантика списка правок»,
 * как apply_op в tools/ref/timeline_ref.py, но с сеткой стыка (i+0,5)/N из dsp.md П-3).
 */
object SeqRef {
    fun apply(x: FloatArray, ch: Int, op: EditOp, jointN: Long): FloatArray {
        val l = x.size / ch
        fun mul(y: FloatArray, f: Int, g: Float) { for (c in 0 until ch) y[f * ch + c] = y[f * ch + c] * g }
        return when (op) {
            is EditOp.Keep -> {
                val a = op.range.start.toInt(); val b = op.range.end.toInt()
                val y = x.copyOfRange(a * ch, b * ch)
                val n = JointSmoother.pieceRamp(jointN, (b - a).toLong()).toInt()
                if (a > 0) for (i in 0 until n) mul(y, i, JointSmoother.gain(i.toLong(), n.toLong(), true).toFloat())
                if (b < l) for (j in 0 until n) mul(y, (b - a) - n + j, JointSmoother.gain(j.toLong(), n.toLong(), false).toFloat())
                y
            }
            is EditOp.Delete -> {
                val a = op.range.start.toInt(); val b = op.range.end.toInt()
                val left = x.copyOfRange(0, a * ch); val right = x.copyOfRange(b * ch, l * ch)
                if (a > 0) {
                    val n = JointSmoother.pieceRamp(jointN, a.toLong()).toInt()
                    for (j in 0 until n) mul(left, a - n + j, JointSmoother.gain(j.toLong(), n.toLong(), false).toFloat())
                }
                if (b < l) {
                    val n = JointSmoother.pieceRamp(jointN, (l - b).toLong()).toInt()
                    for (i in 0 until n) mul(right, i, JointSmoother.gain(i.toLong(), n.toLong(), true).toFloat())
                }
                left + right
            }
            is EditOp.Volume -> {
                val y = x.copyOf()
                val r = op.range ?: Range(0, l.toLong())
                val g = Gain.fromTenthsDb(Math.round(op.db * 10).toInt())
                for (f in r.start.toInt() until r.end.toInt()) mul(y, f, g)
                y
            }
            is EditOp.Fade -> {
                val y = x.copyOf()
                val n = op.range.length
                for (f in op.range.start.toInt() until op.range.end.toInt())
                    mul(y, f, FadeCurves.gainAt(op.curve, f - op.range.start, n, op.fadeIn).toFloat())
                y
            }
            is EditOp.Normalize -> {
                val y = x.copyOf()
                val g = Gain.dbToLinear(op.resolvedGainDb!!).toFloat()
                for (f in 0 until l) mul(y, f, g)
                y
            }
        }
    }

    fun render(x: FloatArray, ch: Int, ops: List<EditOp>, jointN: Long): FloatArray =
        ops.fold(x) { acc, op -> apply(acc, ch, op, jointN) }
}

fun renderAll(r: Renderer, block: Int = 4096): FloatArray {
    r.prepare()
    val ch = r.spec.channels
    val out = FloatArray((r.length * ch).toInt())
    val buf = FloatArray(block * ch)
    var pos = 0L
    while (pos < r.length) {
        val n = r.render(pos, buf, block)
        System.arraycopy(buf, 0, out, (pos * ch).toInt(), n * ch)
        pos += n
    }
    return out
}

fun assertBitEqual(msg: String, exp: FloatArray, act: FloatArray) {
    if (exp.size != act.size) fail("$msg: размер ${act.size} != ${exp.size}")
    for (i in exp.indices) if (java.lang.Float.floatToRawIntBits(exp[i]) != java.lang.Float.floatToRawIntBits(act[i]))
        fail("$msg: отличие в [$i]: ${act[i]} != ${exp[i]}")
}

/** Детерминированный шумоподобный сигнал. */
fun noise(frames: Int, ch: Int, seed: Long, amp: Float = 0.3f): FloatArray {
    val rnd = java.util.Random(seed)
    return FloatArray(frames * ch) { ((rnd.nextDouble() * 2 - 1) * amp).toFloat() }
}

fun src(data: FloatArray, ch: Int, fs: Int = 48_000) = ArrayPcmSource(PcmSpec(fs, ch), data)

/** Случайная правка, допустимая для длины l. */
fun randomOp(rnd: java.util.Random, l: Long): EditOp {
    fun range(maxFrac: Double): Range {
        val len = 1 + (rnd.nextDouble() * maxFrac * l).toLong().coerceAtMost(l - 1)
        val a = (rnd.nextDouble() * (l - len + 1)).toLong()
        return Range(a, a + len)
    }
    val curves = FadeCurve.values()
    while (true) {
        val op = when (rnd.nextInt(10)) {
            0, 1 -> EditOp.Delete(range(0.08))
            2 -> { val r = range(1.0); if (r.length < l * 0.85) continue else EditOp.Keep(r) }
            3, 4 -> EditOp.Volume(range(0.5), (rnd.nextInt(600) - 400) / 10.0)
            5 -> EditOp.Volume(null, (rnd.nextInt(100) - 60) / 10.0)
            6, 7 -> EditOp.Fade(range(0.3), rnd.nextBoolean(), curves[rnd.nextInt(3)])
            8 -> EditOp.Normalize(NormalizeTarget.Peak(-1.0))
            else -> EditOp.Normalize(NormalizeTarget.Lufs(-16.0))
        }
        if (EditRules.check(op, l) == null) return op
    }
}
