package com.rassul.audioeditor.core.peaks

import com.rassul.audioeditor.core.dsp.RefData
import com.rassul.audioeditor.core.dsp.l
import com.rassul.audioeditor.core.dsp.list
import com.rassul.audioeditor.core.dsp.obj
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-C8, FR-12: пирамида = точный min/max по сэмплам (timeline.md, векторы fr_c8_*). */
class PeakPyramidTest {
    private val ref = RefData.json("timeline_vectors.json").obj()

    private fun build(x: FloatArray, ch: Int, chunk: Int): PeakPyramid {
        val p = PeakPyramid(ch)
        val n = x.size / ch
        var i = 0
        while (i < n) { val k = minOf(chunk, n - i); p.append(x.copyOfRange(i * ch, (i + k) * ch), k); i += k }
        p.finish()
        return p
    }

    /** Сверка всех уровней с прямым min/max по блокам B_k = 256·4^k (последний блок неполный). */
    private fun checkExact(p: PeakPyramid, x: FloatArray, ch: Int) {
        val n = x.size / ch
        assertEquals(n.toLong(), p.lengthFrames)
        for (k in 0 until p.levelCount) {
            val b = p.blockFrames(k).toInt()
            assertEquals("уровень $k", (n + b - 1) / b, p.size(k))
            for (i in 0 until p.size(k)) for (c in 0 until ch) {
                var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY; var ab = 0f
                for (f in i * b until minOf(n, (i + 1) * b)) { val v = x[f * ch + c]; mn = minOf(mn, v); mx = maxOf(mx, v); ab = maxOf(ab, Math.abs(v)) }
                assertEquals(mn, p.min(k, c, i), 0f)
                assertEquals(mx, p.max(k, c, i), 0f)
                // FR-12: пик точки = max|x| по её сэмплам.
                assertEquals(ab, maxOf(Math.abs(p.min(k, c, i)), Math.abs(p.max(k, c, i))), 0f)
            }
        }
        assertEquals(1, p.size(p.levelCount - 1))
    }

    private fun signal(n: Int, ch: Int, seed: Long): FloatArray {
        val r = java.util.Random(seed)
        return FloatArray(n * ch) { (r.nextGaussian() * 0.3).toFloat() }
    }

    @Test fun levelsVector() {
        val v = ref["fr_c8_levels"].obj()
        val n = v["L"].l().toInt()
        val x = signal(n, 2, 1)
        for (chunk in intArrayOf(1, 7, 256, 1000, n)) {
            val p = build(x, 2, chunk)
            assertEquals(v["n_levels"].l().toInt(), p.levelCount)
            val nb = v["n_blocks"].list().map { it.l().toInt() }
            for (k in nb.indices) assertEquals(nb[k], p.size(k))
            checkExact(p, x, 2)
        }
    }

    @Test fun levelCounts() {
        for (c in ref["fr_c8_n_levels"].list().map { it.obj() }) {
            val n = c["L"].l()
            val p = PeakPyramid(1)
            val buf = FloatArray(1 shl 20)
            var left = n
            while (left > 0) { val k = minOf(left, buf.size.toLong()).toInt(); p.append(buf, k); left -= k }
            p.finish()
            assertEquals("L=$n", c["n_levels"].l().toInt(), p.levelCount)
        }
        assertEquals(0, PeakPyramid(1).also { it.finish() }.levelCount)
    }

    @Test fun chooseLevelVector() {
        for (c in ref["fr_c8_choose_level"].list().map { it.obj() }) {
            val (a, b) = (c["spp"] as String).split('/').map { it.toDouble() }
            assertEquals(c.toString(), c["level"].l().toInt(), WaveViewport(0.0, a / b, 1080).level())
        }
    }

    @Test fun nanIgnored() {
        val x = FloatArray(600) { if (it == 10) Float.NaN else it / 1000f }
        val p = build(x, 1, 100)
        assertEquals(0f, p.min(0, 0, 0), 0f); assertEquals(0.255f, p.max(0, 0, 0), 0f)
    }

    @Test fun rebuildFromEqualsFreshBuild() {
        val ch = 2
        val x = signal(20_000, ch, 2)
        for ((p0, newLen) in listOf(0 to 20_000, 5000 to 20_000, 4096 to 15_000, 19_999 to 30_000, 12_345 to 12_400, 300 to 300)) {
            val y = signal(newLen, ch, 3)
            System.arraycopy(x, 0, y, 0, minOf(p0, newLen) * ch)   // префикс до p0 не изменён
            val p = build(x, ch, 4096)
            p.rebuildFrom(p0.toLong(), ArrayPcmSource(PcmSpec(48_000, ch), y))
            val fresh = build(y, ch, 777)
            assertEquals(fresh.levelCount, p.levelCount)
            checkExact(p, y, ch)
        }
    }
}
