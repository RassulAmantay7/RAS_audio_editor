package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.FadeCurves
import com.rassul.audioeditor.core.dsp.Gain
import com.rassul.audioeditor.core.dsp.JointSmoother
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит FR-C2, FR-C3, FR-20 (docs/math/dsp.md ф-лы 1–5, 17; П-3, П-7; K-11). Property-тесты. */
class AuditFadeGainTest {
    private val rnd = java.util.Random(7)
    private val sizes = intArrayOf(2, 3, 4, 5, 17, 240, 441, 4800, 48_000)

    @Test fun endpointsExact() {
        for (c in FadeCurve.values()) for (n in sizes) {
            val inT = FadeCurves.table(c, n, true); val outT = FadeCurves.table(c, n, false)
            assertEquals("$c in[0]", 0.0, inT[0], 0.0); assertEquals("$c in[N−1]", 1.0, inT[n - 1], 0.0)
            assertEquals("$c out[0]", 1.0, outT[0], 0.0); assertEquals("$c out[N−1]", 0.0, outT[n - 1], 0.0)
        }
    }

    /** fade out = зеркало fade in (g_out[i] = g_in[N−1−i]); монотонность; диапазон [0, 1]. */
    @Test fun mirrorMonotoneBounded() {
        for (c in FadeCurve.values()) for (n in sizes) {
            val inT = FadeCurves.table(c, n, true); val outT = FadeCurves.table(c, n, false)
            for (i in 0 until n) {
                assertEquals("$c n=$n i=$i", inT[n - 1 - i], outT[i], 1e-15)
                assertTrue(inT[i] in 0.0..1.0)
                if (i > 0) assertTrue("$c монотонность n=$n i=$i", inT[i] >= inT[i - 1])
            }
        }
    }

    /** Кроссфейд fade in ∘ fade out на одном участке: линейная g_in + g_out = 1; равной мощности g_in² + g_out² = 1. */
    @Test fun crossfadeInvariants() {
        for (n in sizes) {
            val li = FadeCurves.table(FadeCurve.LINEAR, n, true); val lo = FadeCurves.table(FadeCurve.LINEAR, n, false)
            val pi = FadeCurves.table(FadeCurve.EQUAL_POWER, n, true); val po = FadeCurves.table(FadeCurve.EQUAL_POWER, n, false)
            for (i in 0 until n) {
                assertEquals(1.0, li[i] + lo[i], 1e-15)
                assertEquals("n=$n i=$i", 1.0, pi[i] * pi[i] + po[i] * po[i], 1e-15)
            }
        }
        for (k in 0 until 10_000) {
            val x = rnd.nextDouble()
            val gi = FadeCurves.gainIn(FadeCurve.EQUAL_POWER, x); val go = FadeCurves.gainIn(FadeCurve.EQUAL_POWER, 1 - x)
            assertEquals(1.0, gi * gi + go * go, 1e-15)
            // «Лог»: ровно −60·(1−x) дБ при x > 0 (dsp.md ф-ла 3).
            if (x > 0) assertEquals(-60.0 * (1 - x), 20 * Math.log10(FadeCurves.gainIn(FadeCurve.LOGARITHMIC, x)), 1e-9)
        }
    }

    /** Контрольные C2-1, C2-5 и устойчивость к выходу за диапазон / NaN. */
    @Test fun controlAndEdges() {
        assertEquals(0.70710678, FadeCurves.gainIn(FadeCurve.EQUAL_POWER, 0.5), 1e-8)
        assertEquals(0.031622777, FadeCurves.gainIn(FadeCurve.LOGARITHMIC, 0.5), 1e-9)
        assertEquals(0.0, FadeCurves.gainIn(FadeCurve.LOGARITHMIC, 0.0), 0.0)
        assertEquals(1e-3, FadeCurves.gainIn(FadeCurve.LOGARITHMIC, 1e-300), 1e-12) // скачок −60 дБ у x → 0⁺ (допустим)
        for (c in FadeCurve.values()) {
            assertEquals(0.0, FadeCurves.gainIn(c, Double.NaN), 0.0)
            assertEquals(0.0, FadeCurves.gainIn(c, -5.0), 0.0)
            assertEquals(1.0, FadeCurves.gainIn(c, 5.0), 0.0)
            assertEquals(1.0, FadeCurves.gainAt(c, 0, 1, true), 0.0)   // П-7
            assertEquals(1.0, FadeCurves.gainAt(c, 0, 1, false), 0.0)
            assertEquals(0, FadeCurves.table(c, 0, true).size)
        }
    }

    /** FR-C3 (П-3): сетка (i+0,5)/N — нет нулей; g_up[i] + g_up[N−1−i] = 1; спад — зеркало подъёма; N_k (FR-19). */
    @Test fun jointSmootherProperties() {
        for (n in longArrayOf(1, 2, 3, 4, 11, 221, 240, 9600)) {
            for (i in 0 until n) {
                val up = JointSmoother.gain(i, n, true)
                assertTrue("n=$n i=$i: $up", up > 0.0 && up < 1.0)
                assertEquals(1.0, up + JointSmoother.gain(n - 1 - i, n, true), 1e-15)
                assertEquals(up, JointSmoother.gain(n - 1 - i, n, false), 0.0)
                if (i > 0) assertTrue(up > JointSmoother.gain(i - 1, n, true))
            }
        }
        assertEquals(1.070916794e-5, JointSmoother.gain(0, 240, true), 1e-13)
        assertEquals(0.4967275310, JointSmoother.gain(119, 240, true), 1e-10)
        assertEquals(240L, JointSmoother.rampLength(5, 48_000)); assertEquals(221L, JointSmoother.rampLength(5, 44_100))
        assertEquals(11L, JointSmoother.rampLength(1, 11_025)); assertEquals(9600L, JointSmoother.rampLength(50, 192_000))
        // K-3: d·fs в Long — 10⁴ с при 768 кГц = 7,68·10¹² мс·Гц.
        assertEquals(7_680_000_000L, JointSmoother.rampLength(10_000_000, 768_000))
        assertEquals(3L, JointSmoother.pieceRamp(240, 7)); assertEquals(0L, JointSmoother.pieceRamp(240, 1))
        assertEquals(0L, JointSmoother.pieceRamp(240, -5)); assertEquals(240L, JointSmoother.pieceRamp(240, 10_000))
        assertEquals(1.0, JointSmoother.gain(0, 0, true), 0.0)
    }

    /** FR-20: g = 10^(dB/20), из десятых дБ — double → float (K-11); обратимость. */
    @Test fun gainProperties() {
        assertEquals(1.995262315, Gain.dbToLinear(6.0), 1e-9)
        assertEquals(1.9952623f, Gain.fromTenthsDb(60), 0f)
        assertEquals(0.988553095, Gain.fromTenthsDb(-1).toDouble(), 1e-7)
        assertEquals(Double.NEGATIVE_INFINITY, Gain.linearToDb(0.0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, Gain.linearToDb(-1.0), 0.0)
        for (t in -600..600) {
            val exact = Math.pow(10.0, t / 200.0)
            val g = Gain.fromTenthsDb(t)
            assertEquals("t=$t", exact.toFloat(), g, 0f)                 // округление один раз, из double
            assertTrue(Math.abs(g / exact - 1) <= 6e-8)
            assertEquals(t / 10.0, Gain.linearToDb(Gain.dbToLinear(t / 10.0)), 1e-12)
            if (t > -600) assertTrue(Gain.fromTenthsDb(t) > Gain.fromTenthsDb(t - 1))
        }
        assertEquals(1.0f, Gain.fromTenthsDb(0), 0f)
    }
}
