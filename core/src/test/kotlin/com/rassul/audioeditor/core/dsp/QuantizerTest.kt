package com.rassul.audioeditor.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-C7: контрольные примеры Q-1…Q-7 docs/math/dsp.md (dither_vectors.json). */
class QuantizerTest {
    private val ref = RefData.json("dither_vectors.json").obj()

    @Test fun splitMixBitExact() {  // Q-1
        val seed = ref["seed"].l()
        assertEquals(123456789L, seed)
        assertEquals(ref["first_u64"].list()[0] as String, java.lang.Long.toUnsignedString(SplitMix64(seed).nextLong()))
        assertEquals("2466975172287755897", java.lang.Long.toUnsignedString(SplitMix64(123456789L).nextLong()))
        val g = SplitMix64(seed)
        for (u in ref["first_doubles"].doubles()) assertEquals(u, g.nextDouble(), 0.0)
        // d_i = u[2i] − u[2i+1]
        val g2 = SplitMix64(seed)
        for (d in ref["d"].doubles()) assertEquals(d, g2.nextDouble() - g2.nextDouble(), 0.0)
    }

    @Test fun vectorsBitExact() {   // Q-2
        val x = ref["x"].list().map { it.d().toFloat() }.toFloatArray()
        val seed = ref["seed"].l()
        fun q(bits: Int, dither: Boolean): Pair<IntArray, Int> {
            val out = IntArray(x.size); val c = Quantizer(bits, dither, seed).quantize(x, x.size, out); return out to c
        }
        val (yd, clip) = q(16, true)
        assertArrayEquals(ref["y16_dither"].list().map { it.l().toInt() }.toIntArray(), yd)
        assertEquals(ref["overflow_count_|x|>1"].l().toInt(), clip)
        assertArrayEquals(ref["y16_nodither"].list().map { it.l().toInt() }.toIntArray(), q(16, false).first)
        assertArrayEquals(ref["y24"].list().map { it.l().toInt() }.toIntArray(), q(24, false).first)
        // Потоковость: разбиение на куски не меняет последовательность дизера.
        val qz = Quantizer(16, true, seed)
        val out = IntArray(x.size)
        var o = 0
        for (n in intArrayOf(1, 7, 20, 36)) {
            val part = IntArray(n); qz.quantize(x.copyOfRange(o, o + n), n, part); System.arraycopy(part, 0, out, o, n); o += n
        }
        assertArrayEquals(yd, out)
    }

    @Test fun edgeCases() {         // Q-3, Q-4
        val x16 = floatArrayOf(1.0f, -1.0f, (0.5 / 32768).toFloat(), (-0.5 / 32768).toFloat(), 1.5f, -1.5f)
        val y = IntArray(x16.size)
        val clip = Quantizer(16, false, 0).quantize(x16, x16.size, y)
        assertArrayEquals(intArrayOf(32767, -32768, 1, 0, 32767, -32768), y)
        assertEquals(2, clip)  // 1,0 не считается срезом: |x| > 1,0
        val x24 = floatArrayOf(1.0f, -1.0f, 0.99999f)
        val y24 = IntArray(3)
        Quantizer(24, false, 0).quantize(x24, 3, y24)
        assertArrayEquals(intArrayOf(8388607, -8388608, 8388524), y24)
    }

    private fun stats(x: FloatArray, seed: Long): Pair<Double, Double> {
        val y = IntArray(x.size)
        Quantizer(16, true, seed).quantize(x, x.size, y)
        var s = 0.0; var s2 = 0.0
        for (i in x.indices) { val e = y[i] / 32768.0 - x[i]; s += e; s2 += e * e }
        val rmsDb = 20 * Math.log10(Math.sqrt(s2 / x.size))
        return rmsDb to (s / x.size * 32768.0)
    }

    private val theory = 20 * Math.log10(0.5 / 32768) // −96,330 дБ отн. 1,0 (dsp.md П-9)

    @Test fun ditherStatisticsSilence() {   // Q-5
        val (rms, mean) = stats(FloatArray(1_000_000), 20251110L)
        assertEquals(theory, rms, 0.05)
        assertEquals(-96.331, rms, 0.001)   // эталон dither_ref.py (тот же ГСЧ и сид)
        assertEquals(0.0, mean, 0.01)
    }

    @Test fun ditherStatisticsSine() {      // Q-6
        val x = FloatArray(1_000_000) { (0.1 * Math.sin(2 * Math.PI * 997 * it / 48_000.0)).toFloat() }
        val (rms, mean) = stats(x, 20251110L)
        assertEquals(theory, rms, 0.05)
        assertEquals(0.0, mean, 0.01)
    }

    @Test fun subLsbDcPreservedOnlyWithDither() { // Q-7
        val x = FloatArray(1_000_000) { (0.3 / 32768).toFloat() }
        val y = IntArray(x.size)
        Quantizer(16, false, 1).quantize(x, x.size, y)
        assertEquals(0.0, y.average(), 0.0)
        Quantizer(16, true, 20251110L).quantize(x, x.size, y)
        assertEquals(0.3, y.average(), 0.01)
    }
}
