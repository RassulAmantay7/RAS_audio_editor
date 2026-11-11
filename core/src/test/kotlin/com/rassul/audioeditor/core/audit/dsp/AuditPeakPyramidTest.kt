package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.peaks.PeakPyramid
import com.rassul.audioeditor.core.peaks.WaveViewport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Аудит FR-C8 (docs/math/timeline.md ф-лы 4–5): каждая точка уровня k равна точному min/max сэмплов
 * [i·B_k, min((i+1)·B_k, L)) — бит в бит; уровней K = 1 + ⌈log₄⌈L/256⌉⌉; порезка и пересборка не влияют.
 */
class AuditPeakPyramidTest {
    private fun build(x: FloatArray, ch: Int, rnd: java.util.Random?): PeakPyramid {
        val p = PeakPyramid(ch)
        val n = x.size / ch
        var i = 0
        while (i < n) {
            val k = minOf(n - i, if (rnd == null) n else 1 + rnd.nextInt(3000))
            p.append(x.copyOfRange(i * ch, (i + k) * ch), k); i += k
        }
        p.finish()
        return p
    }

    private fun expectedLevels(l: Long): Int {
        if (l == 0L) return 0
        var blocks = (l + 255) / 256
        var k = 1
        while (blocks > 1) { blocks = (blocks + 3) / 4; k++ }
        return k
    }

    private fun checkExact(p: PeakPyramid, x: FloatArray, ch: Int) {
        val n = x.size / ch
        assertEquals("L=$n уровней", expectedLevels(n.toLong()), p.levelCount)
        for (k in 0 until p.levelCount) {
            val b = p.blockFrames(k)
            assertEquals(256L shl (2 * k), b)
            val cnt = ((n + b - 1) / b).toInt()
            assertEquals("L=$n k=$k точек", cnt, p.size(k))
            for (i in 0 until cnt) for (c in 0 until ch) {
                var mn = Float.POSITIVE_INFINITY; var mx = Float.NEGATIVE_INFINITY
                val end = minOf((i + 1) * b, n.toLong()).toInt()
                for (s in (i * b).toInt() until end) { val v = x[s * ch + c]; if (v < mn) mn = v; if (v > mx) mx = v }
                assertEquals("L=$n k=$k i=$i c=$c min", mn, p.min(k, c, i), 0f)
                assertEquals("L=$n k=$k i=$i c=$c max", mx, p.max(k, c, i), 0f)
            }
        }
    }

    @Test fun exactMinMaxAllLevels() {
        val rnd = java.util.Random(31)
        val lengths = intArrayOf(0, 1, 255, 256, 257, 1023, 1024, 1025, 4096, 4097, 16_385, 65_536, 65_537, 300_001) +
            IntArray(10) { 1 + rnd.nextInt(200_000) }
        for (len in lengths) for (ch in intArrayOf(1, 2)) {
            val x = FloatArray(len * ch) { (rnd.nextGaussian() * 0.3).toFloat() }
            if (len > 10) x[rnd.nextInt(x.size)] = 1.5f   // выброс > 0 dBFS
            checkExact(build(x, ch, null), x, ch)
            checkExact(build(x, ch, rnd), x, ch)
        }
        assertEquals(11, expectedLevels(86_400_000L)) // timeline.md: L = 86,4·10⁶ → 11 уровней
    }

    /** Пересборка с кадра p после правки (длина растёт и уменьшается) = построение с нуля. */
    @Test fun rebuildEqualsFresh() {
        val rnd = java.util.Random(41)
        repeat(25) {
            val ch = 1 + rnd.nextInt(2)
            val n0 = 1 + rnd.nextInt(120_000)
            val old = FloatArray(n0 * ch) { rnd.nextFloat() - 0.5f }
            val pyr = build(old, ch, rnd)
            val p = rnd.nextInt(n0 + 1).toLong()
            val n1 = maxOf(1, (p + rnd.nextInt(100_000) - 20_000).toInt().coerceAtLeast(p.toInt()))
            val neu = FloatArray(n1 * ch) { if (it < p * ch) old[it] else rnd.nextFloat() * 2 - 1 }
            pyr.rebuildFrom(p, ArrayPcmSource(PcmSpec(48_000, ch), neu))
            checkExact(pyr, neu, ch)
        }
    }

    /** Выбор уровня: наибольшее k с 256·4^k ≤ spp; spp < 256 → сырые сэмплы (−1). */
    @Test fun viewportLevel() {
        for ((spp, k) in listOf(0.125 to -1, 255.999 to -1, 256.0 to 0, 1023.9 to 0, 1024.0 to 1, 4096.0 to 2, 1e7 to 7, Double.NaN to -1))
            assertEquals("spp=$spp", k, WaveViewport(0.0, spp, 100).level())
    }
}
