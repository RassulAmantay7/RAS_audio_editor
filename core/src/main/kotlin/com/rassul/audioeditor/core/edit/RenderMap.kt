package com.rassul.audioeditor.core.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.FadeCurves
import com.rassul.audioeditor.core.dsp.Gain
import com.rassul.audioeditor.core.dsp.JointSmoother
import com.rassul.audioeditor.core.model.PcmSource

/**
 * Множитель одной операции на участке ИСХОДНИКА [srcStart, srcEnd).
 * Индекс внутри кривой: i = s − origin (s — кадр исходника), см. [RenderMap.build].
 */
internal class Piece(
    val step: Int,
    val srcStart: Long,
    val srcEnd: Long,
    private val kind: Int,
    private val g: Float,
    private val n: Long,
    private val origin: Long,
    private val flag: Boolean,
    private val curve: FadeCurve?,
) {
    /** Множитель float32 для кадра исходника s. Коэффициенты — в double, затем округление во float32 (timeline.md). */
    fun gain(s: Long): Float = when (kind) {
        CONST -> g
        // FR-C2: x_i = i/(N−1) (FadeCurves.gainAt, dsp.md ф-лы 1–4).
        FADE -> FadeCurves.gainAt(curve!!, s - origin, n, flag).toFloat()
        // FR-C3: сетка (i+0,5)/N, g = 0,5(1 − cos πx) (JointSmoother.gain, dsp.md П-3).
        else -> JointSmoother.gain(s - origin, n, flag).toFloat()
    }

    val isConst: Boolean get() = kind == CONST
    val constGain: Float get() = g

    companion object {
        const val CONST = 0
        const val FADE = 1
        const val RAMP = 2
    }
}

/** Сегмент результата: кадры результата [out, out+len) = кадры исходника [src, src+len). */
internal class Seg(val out: Long, val src: Long, val len: Long, val pieces: Array<Piece>)

/**
 * «Карта» рендера: сегменты исходника в порядке результата + для каждого — множители в порядке операций.
 * Неизменяема после построения; render() потокобезопасен (чтение исходника — под его монитором).
 */
internal class RenderMap private constructor(private val segs: Array<Seg>, val length: Long, private val channels: Int) {

    fun render(source: PcmSource, from: Long, dst: FloatArray, dstOff: Int, frames: Int): Int {
        if (from >= length || frames == 0) return 0
        val total = minOf(frames.toLong(), length - from).toInt()
        val ch = channels
        require(dst.size >= (dstOff + total) * ch) { "dst мал: ${dst.size} < ${(dstOff + total) * ch}" }
        var si = findSeg(from)
        var pos = from
        var done = 0
        while (done < total) {
            val seg = segs[si]
            val n = minOf((total - done).toLong(), seg.out + seg.len - pos).toInt()
            val s0 = seg.src + (pos - seg.out)
            val base = (dstOff + done) * ch
            readSource(source, s0, dst, base, n)
            val s1 = s0 + n
            // Множители строго по порядку операций (pieces отсортированы по step); внутри одного шага
            // участки не пересекаются, поэтому каждый кадр умножается в том же порядке, что при
            // последовательном применении правок.
            for (p in seg.pieces) {
                val a = maxOf(p.srcStart, s0)
                val b = minOf(p.srcEnd, s1)
                if (a >= b) continue
                var idx = base + ((a - s0).toInt()) * ch
                if (p.isConst) {
                    val g = p.constGain
                    val end = base + ((b - s0).toInt()) * ch
                    while (idx < end) { dst[idx] = dst[idx] * g; idx++ }
                } else {
                    var s = a
                    while (s < b) {
                        val g = p.gain(s)
                        for (c in 0 until ch) { dst[idx] = dst[idx] * g; idx++ }
                        s++
                    }
                }
            }
            done += n
            pos += n
            si++
        }
        return total
    }

    private fun readSource(source: PcmSource, from: Long, dst: FloatArray, base: Int, n: Int) {
        val ch = channels
        var got = 0
        synchronized(source) {
            if (base == 0) {
                got = source.read(from, dst, n)
            } else {
                val tmp = scratch(n * ch)
                got = source.read(from, tmp, n)
                System.arraycopy(tmp, 0, dst, base, maxOf(got, 0) * ch)
            }
        }
        // Источник короче заявленного (не должно быть) — недостающее заполняется нулями.
        if (got < n) java.util.Arrays.fill(dst, base + maxOf(got, 0) * ch, base + n * ch, 0f)
    }

    private fun findSeg(pos: Long): Int {
        var lo = 0
        var hi = segs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (segs[mid].out <= pos) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        private val tls = ThreadLocal<FloatArray>()
        private fun scratch(size: Int): FloatArray {
            val a = tls.get()
            if (a != null && a.size >= size) return a
            val b = FloatArray(maxOf(size, 4096))
            tls.set(b)
            return b
        }

        /** Множитель громкости FR-20: g32 = float32(10^(десятые/200)) (timeline.md «gain [a,b)»). */
        fun volumeGain(op: EditOp.Volume): Float = Gain.fromTenthsDb(Math.round(op.db * 10.0).toInt())

        /** Множитель нормализации FR-22: G32 = float32(10^(G/20)) из замороженного G. */
        fun normalizeGain(op: EditOp.Normalize): Float = Gain.dbToLinear(op.resolvedGainDb!!).toFloat()

        private class Cur(val src: Long, val len: Long)

        /**
         * Построение по списку (все Normalize разрешены, правки проверены EditRules).
         * Семантика правок — timeline.md «Семантика списка правок»; стыки — FR-19, dsp.md П-3.
         */
        fun build(sourceLength: Long, ops: List<EditOp>, jointN: Long, channels: Int): RenderMap {
            var segs: List<Cur> = if (sourceLength > 0) listOf(Cur(0, sourceLength)) else emptyList()
            var len = sourceLength
            val pieces = ArrayList<Piece>()

            // Участок [ra, rb) в координатах ТЕКУЩЕГО шага → куски в координатах исходника.
            // origin = src_seg − out_seg + ra: тогда i = s − origin = (позиция в шаге) − ra.
            fun emit(step: Int, ra: Long, rb: Long, make: (srcA: Long, srcB: Long, origin: Long) -> Piece) {
                if (ra >= rb) return
                var out = 0L
                for (sg in segs) {
                    val a = maxOf(ra, out)
                    val b = minOf(rb, out + sg.len)
                    if (a < b) pieces += make(sg.src + (a - out), sg.src + (b - out), sg.src - out + ra)
                    out += sg.len
                    if (out >= rb) break
                }
            }
            fun const(step: Int, ra: Long, rb: Long, g: Float) {
                if (g == 1.0f) return // x·1 = x точно (IEEE 754)
                emit(step, ra, rb) { a, b, o -> Piece(step, a, b, Piece.CONST, g, 0, o, false, null) }
            }
            fun ramp(step: Int, ra: Long, n: Long, rising: Boolean) {
                if (n <= 0) return
                emit(step, ra, ra + n) { a, b, o -> Piece(step, a, b, Piece.RAMP, 1f, n, o, rising, null) }
            }
            fun slice(a: Long, b: Long): List<Cur> {
                val r = ArrayList<Cur>()
                var out = 0L
                for (sg in segs) {
                    val x = maxOf(a, out)
                    val y = minOf(b, out + sg.len)
                    if (x < y) r += Cur(sg.src + (x - out), y - x)
                    out += sg.len
                }
                return r
            }

            ops.forEachIndexed { k, op ->
                when (op) {
                    is EditOp.Keep -> {
                        // trim [a,b): x[a:b]; нарастание N_k в начале при a > 0, затухание в конце при b < L.
                        val a = op.range.start; val b = op.range.end
                        segs = slice(a, b)
                        val nl = len
                        len = b - a
                        val nk = JointSmoother.pieceRamp(jointN, len)
                        if (a > 0) ramp(k, 0, nk, true)
                        if (b < nl) ramp(k, len - nk, nk, false)
                    }
                    is EditOp.Delete -> {
                        // delete [a,b): x[:a] ++ x[b:]; затухание N_L до границы при a > 0, нарастание N_R после при b < L.
                        val a = op.range.start; val b = op.range.end
                        val nl = len
                        segs = slice(0, a) + slice(b, nl)
                        len = nl - (b - a)
                        if (a > 0) { val n = JointSmoother.pieceRamp(jointN, a); ramp(k, a - n, n, false) }
                        if (b < nl) ramp(k, a, JointSmoother.pieceRamp(jointN, nl - b), true)
                    }
                    is EditOp.Volume -> {
                        val r = op.range ?: Range(0, len)
                        const(k, r.start, r.end, volumeGain(op))
                    }
                    is EditOp.Fade -> {
                        val a = op.range.start; val n = op.range.length
                        if (n > 1) { // N ≤ 1 — ничего не делать (dsp.md П-7)
                            emit(k, a, op.range.end) { sa, sb, o -> Piece(k, sa, sb, Piece.FADE, 1f, n, o, op.fadeIn, op.curve) }
                        }
                    }
                    is EditOp.Normalize -> const(k, 0, len, normalizeGain(op))
                }
            }

            // Финальные сегменты + относящиеся к ним множители (в порядке шагов — pieces уже упорядочены).
            val res = ArrayList<Seg>(segs.size)
            var out = 0L
            for (sg in segs) {
                val s0 = sg.src; val s1 = sg.src + sg.len
                val mine = pieces.filter { it.srcStart < s1 && it.srcEnd > s0 }.toTypedArray()
                res += Seg(out, sg.src, sg.len, mine)
                out += sg.len
            }
            check(out == len) { "карта: $out != $len" }
            return RenderMap(res.toTypedArray(), len, channels)
        }
    }
}
