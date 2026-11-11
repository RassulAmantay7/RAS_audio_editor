package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.dsp.Quantizer
import com.rassul.audioeditor.core.dsp.SplitMix64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит FR-C7 (docs/math/dsp.md «FR-C7», П-9, K-1, K-2): SplitMix64, TPDF, half-up, статистика дизеринга.
 */
class AuditQuantizerTest {
    /** Независимая реализация JDK: java.util.SplittableRandom(seed) — тот же GOLDEN_GAMMA и mix64 (Steele et al. 2014). */
    @Test fun splitMixEqualsJdkSplittableRandom() {
        for (seed in longArrayOf(0, 1, -1, 123_456_789, 20_251_110, Long.MIN_VALUE, Long.MAX_VALUE, 0x9E3779B97F4A7C15uL.toLong())) {
            val a = SplitMix64(seed); val b = java.util.SplittableRandom(seed)
            repeat(10_000) { assertEquals("seed=$seed", b.nextLong(), a.nextLong()) }
            val c = SplitMix64(seed); val d = java.util.SplittableRandom(seed)
            repeat(10_000) { assertEquals(d.nextDouble(), c.nextDouble(), 0.0) }   // (z>>>11)·2⁻⁵³
        }
        assertEquals("2466975172287755897", java.lang.Long.toUnsignedString(SplitMix64(123_456_789).nextLong())) // Q-1
        // K-2: десятичные литералы констант из dsp.md совпадают с шестнадцатеричными.
        assertEquals(-7046029254386353131L, 0x9E3779B97F4A7C15uL.toLong())
        assertEquals(-4658895280553007687L, 0xBF58476D1CE4E5B9uL.toLong())
        assertEquals(-7723592293110705685L, 0x94D049BB133111EBuL.toLong())
    }

    /** Живая сверка бит в бит с tools/ref/dither_ref.quantize16 (дизер) на 20 000 сэмплов, включая > 1 и половины LSB. */
    @Test fun matchesDitherRefLive() {
        val rnd = java.util.Random(9)
        val n = 20_000
        val x = FloatArray(n) {
            when (it % 5) { 0 -> (rnd.nextInt(65_536) - 32_768 + 0.5f) / 32_768f; 1 -> rnd.nextFloat() * 2.4f - 1.2f; else -> (rnd.nextGaussian() * 0.1).toFloat() }
        }
        val seed = 987_654_321L
        val ours = IntArray(n); Quantizer(16, true, seed).quantize(x, n, ours)
        val ours24 = IntArray(n); Quantizer(24, false, seed).quantize(x, n, ours24)
        AuditPy.run(
            "import numpy as np\nfrom dither_ref import quantize16\n" +
                "x=np.fromfile(W+'/x.f32',dtype='<f4').astype(np.float64)\n" +
                "quantize16(x,$seed,True).astype('<i4').tofile(W+'/y16')\n" +
                "np.clip(np.floor(x*8388608.0+0.5),-8388608,8388607).astype('<i4').tofile(W+'/y24')\nprint('ok')",
            prepare = { AuditPy.writeF32(java.io.File(it, "x.f32"), x) },
            after = { dir, _ ->
                fun ints(name: String): IntArray {
                    val bb = java.nio.ByteBuffer.wrap(java.io.File(dir, name).readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    return IntArray(n) { bb.int }
                }
                assertArrayEquals(ints("y16"), ours)
                assertArrayEquals(ints("y24"), ours24)
            },
        )
    }

    /** Округление — половина вверх (floor(v+0,5)), а не к чётному (K-1); граничные значения и NaN. */
    @Test fun halfUpAndClamp() {
        val q = Quantizer(16, false, 1)
        val lsb = 1.0 / 32_768
        val xs = floatArrayOf(0.5f / 32768, 1.5f / 32768, 2.5f / 32768, -0.5f / 32768, -1.5f / 32768, -2.5f / 32768,
            1f, -1f, 1.5f, -1.5f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1.0000001f)
        val y = IntArray(xs.size)
        val clipped = q.quantize(xs, xs.size, y)
        assertArrayEquals(intArrayOf(1, 2, 3, 0, -1, -2, 32_767, -32_768, 32_767, -32_768, 0, 32_767, -32_768, 32_767), y)
        assertEquals(5, clipped) // 1,5; −1,5; +∞; −∞; 1,0000001 (|x| > 1, ДО дизера); NaN и ровно ±1 — не срез
        assertTrue(lsb > 0)
        val y24 = IntArray(3); Quantizer(24, false, 1).quantize(floatArrayOf(1f, -1f, 0.99999f), 3, y24)
        assertArrayEquals(intArrayOf(8_388_607, -8_388_608, 8_388_524), y24)
        // Дизер у 0 dBFS не даёт ложных срабатываний счётчика.
        val full = FloatArray(10_000) { if (it % 2 == 0) 1f else -1f }
        assertEquals(0, Quantizer(16, true, 3).quantize(full, full.size, IntArray(full.size)))
    }

    /** Статистика: d — треугольное на (−1, 1), E = 0, D = 1/6; соседние d некоррелированы. */
    @Test fun tpdfDistribution() {
        val r = SplitMix64(42)
        val n = 1_000_000
        var s = 0.0; var s2 = 0.0; var prev = 0.0; var cov = 0.0
        val hist = IntArray(20)
        for (i in 0 until n) {
            val d = r.nextDouble() - r.nextDouble()
            assertTrue(d > -1.0 && d < 1.0)
            s += d; s2 += d * d; cov += d * prev; prev = d
            hist[((d + 1.0) * 10).toInt().coerceIn(0, 19)]++
        }
        assertEquals(0.0, s / n, 0.002)
        assertEquals(1.0 / 6, s2 / n, 0.001)
        assertEquals(0.0, cov / n, 0.002)
        for (b in 0 until 20) {
            val lo = -1.0 + b * 0.1; val hi = lo + 0.1
            fun cdf(t: Double) = if (t < 0) 0.5 * (1 + t) * (1 + t) else 1 - 0.5 * (1 - t) * (1 - t)
            val expect = (cdf(hi) - cdf(lo)) * n
            assertEquals("bin $b", expect, hist[b].toDouble(), 5 * Math.sqrt(expect) + 1)
        }
    }

    /**
     * TPDF ±1 LSB: первые два момента ошибки не зависят от сигнала (Lipshitz–Wannamaker–Vanderkooy 1992):
     * E[e] = 0 и E[e²] = 1/4 LSB² при любом постоянном уровне; без дизера ошибка зависит от уровня.
     */
    @Test fun errorMomentsIndependentOfSignal() {
        val n = 200_000
        for (level in doubleArrayOf(0.0, 0.1, 0.25, 0.3, 0.5, 0.75, 0.9, 7.3, -3.6)) {
            val x = FloatArray(n) { (level / 32_768).toFloat() }
            val xd = x[0].toDouble() * 32_768
            val y = IntArray(n); Quantizer(16, true, 1000L + (level * 100).toLong()).quantize(x, n, y)
            var m = 0.0; var m2 = 0.0
            for (v in y) { val e = v - xd; m += e; m2 += e * e }
            assertEquals("E[e] level=$level", 0.0, m / n, 0.005)
            assertEquals("E[e²] level=$level", 0.25, m2 / n, 0.004)
        }
        // Без дизера: 0,3 LSB теряется полностью (Q-7).
        val y0 = IntArray(1000); Quantizer(16, false, 0).quantize(FloatArray(1000) { (0.3 / 32_768).toFloat() }, 1000, y0)
        assertTrue(y0.all { it == 0 })
    }

    /** Q-5/Q-6: RMS ошибки −96,33 дБ отн. 1,0 (± 0,05 дБ, решение главной сессии). */
    @Test fun noiseLevel() {
        val n = 1_000_000
        for (sine in booleanArrayOf(false, true)) {
            val x = FloatArray(n) { if (sine) (0.1 * Math.sin(2 * Math.PI * 997 * it / 48_000.0)).toFloat() else 0f }
            val y = IntArray(n); Quantizer(16, true, 20_251_110).quantize(x, n, y)
            var e2 = 0.0
            for (i in 0 until n) { val e = y[i] / 32_768.0 - x[i]; e2 += e * e }
            assertEquals("sine=$sine", -96.330, 10 * Math.log10(e2 / n), 0.05)
        }
    }
}
