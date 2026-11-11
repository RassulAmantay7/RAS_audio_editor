package com.rassul.audioeditor.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-20, FR-C2, FR-C3, FR-C5: контрольные примеры docs/math/dsp.md (basic_vectors.json). */
class BasicDspTest {
    private val ref = RefData.json("basic_vectors.json").obj()
    private val curveNames = mapOf("linear" to FadeCurve.LINEAR, "equal_power" to FadeCurve.EQUAL_POWER, "log60" to FadeCurve.LOGARITHMIC)

    // ---------------- FR-20 ----------------
    @Test fun gainVectors() {   // G-1
        for ((k, v) in ref["gain"].obj()) {
            val g = Gain.dbToLinear(k.toDouble())
            assertEquals("dB=$k", v.d(), g, 1e-12 * v.d())
            // float32 (как в правке): относительная ошибка ≤ 1e-6 (FR-20).
            val t = Math.round(k.toDouble() * 10).toInt()
            assertEquals("tenths $k", 1.0, Gain.fromTenthsDb(t) / v.d(), 1e-6)
        }
        assertEquals(1.995262315, Gain.dbToLinear(6.0), 1e-9)
        assertEquals(1.9952623f, Gain.fromTenthsDb(60))
        assertEquals(6.0, Gain.linearToDb(Gain.dbToLinear(6.0)), 1e-12)
        assertEquals(Double.NEGATIVE_INFINITY, Gain.linearToDb(0.0), 0.0)
    }

    // ---------------- FR-C2 ----------------
    @Test fun fadeAtHalfAndEnds() {  // C2-1, C2-2
        assertEquals(0.5, FadeCurves.gainIn(FadeCurve.LINEAR, 0.5), 1e-6)
        assertEquals(0.70710678, FadeCurves.gainIn(FadeCurve.EQUAL_POWER, 0.5), 1e-6)
        assertEquals(0.031622777, FadeCurves.gainIn(FadeCurve.LOGARITHMIC, 0.5), 1e-6)
        for (c in FadeCurve.values()) {
            assertEquals("$c x=1", 1.0, FadeCurves.gainIn(c, 1.0), 0.0)
            assertEquals("$c x=0", 0.0, FadeCurves.gainIn(c, 0.0), 0.0)
        }
        assertEquals(1e-3, FadeCurves.gainIn(FadeCurve.LOGARITHMIC, 1e-12), 1e-12) // разрыв −60 дБ у нуля
    }

    @Test fun fadeTablesN5N2() {  // C2-3, C2-4
        for ((key, fadeIn) in listOf("fade_in_N5" to true, "fade_out_N5" to false, "fade_in_N2" to true)) {
            for ((name, arr) in ref[key].obj()) {
                val exp = arr.doubles()
                val got = FadeCurves.table(curveNames.getValue(name), exp.size, fadeIn)
                assertArrayEquals("$key $name", exp, got, 1e-9)
            }
        }
    }

    @Test fun fadeN1IsNoOp() {    // C2-5, dsp.md П-7
        for (c in FadeCurve.values()) {
            assertEquals(1.0, FadeCurves.gainAt(c, 0, 1, true), 0.0)
            assertEquals(1.0, FadeCurves.gainAt(c, 0, 1, false), 0.0)
            assertEquals(0, FadeCurves.table(c, 0, true).size)
        }
    }

    // ---------------- FR-C3 ----------------
    @Test fun jointRampLength() { // C3-1
        val jn = ref["joint_N"].obj()
        for ((k, v) in jn) {
            val (ms, fs) = Regex("""(\d+)ms@(\d+)""").matchEntire(k)!!.destructured
            assertEquals(k, v.l(), JointSmoother.rampLength(ms.toInt(), fs.toInt()))
        }
        assertEquals(240L, JointSmoother.rampLength(5, 48_000))
        assertEquals(221L, JointSmoother.rampLength(5, 44_100))
        assertEquals(11L, JointSmoother.rampLength(1, 11_025))
        assertEquals(9600L, JointSmoother.rampLength(50, 192_000))
    }

    @Test fun jointRampValues() { // C3-2, C3-3, C3-5
        for ((k, v) in ref["joint_up_N240_idx"].obj()) assertEquals("i=$k", v.d(), JointSmoother.gain(k.toLong(), 240, true), 1e-12)
        assertEquals(1.070916794e-5, JointSmoother.gain(0, 240, true), 1e-7)
        assertEquals(0.4967275310, JointSmoother.gain(119, 240, true), 1e-7)
        assertEquals(0.5032724690, JointSmoother.gain(120, 240, true), 1e-7)
        assertEquals(0.9999892908, JointSmoother.gain(239, 240, true), 1e-7)
        val n4 = ref["joint_up_N4"].doubles()
        for (i in 0 until 4) assertEquals(n4[i], JointSmoother.gain(i.toLong(), 4, true), 1e-12)
        for (n in longArrayOf(1, 2, 3, 4, 11, 221, 240, 9600)) for (i in 0 until n) {
            val up = JointSmoother.gain(i, n, true)
            assertTrue(up > 0.0 && up < 1.0 || n == 1L)
            assertEquals("сумма N=$n i=$i", 1.0, up + JointSmoother.gain(n - 1 - i, n, true), 1e-12)
            // затухание до стыка — зеркало нарастания
            assertEquals(JointSmoother.gain(n - 1 - i, n, true), JointSmoother.gain(i, n, false), 0.0)
        }
    }

    @Test fun jointShortPiece() { // C3-4
        assertEquals(3L, JointSmoother.pieceRamp(240, 7))
        assertEquals(150L, JointSmoother.pieceRamp(240, 300))
        assertEquals(50L, JointSmoother.pieceRamp(240, 100))
        assertEquals(0L, JointSmoother.pieceRamp(240, 1))
        assertEquals(240L, JointSmoother.pieceRamp(240, 10_000))
    }

    // ---------------- FR-C5 ----------------
    @Test fun downmixVectors() {  // D-1…D-9
        for (c in ref["downmix"].list().map { it.obj() }) {
            val mask = c["mask"].l().toInt()
            val frame = c["frame"].list().map { it.d().toFloat() }.toFloatArray()
            val m = Downmix.matrix(mask, frame.size)
            val out = FloatArray(2)
            Downmix.apply(m, frame, 1, out)
            assertEquals("${c["name"]} L", c["L"].d(), out[0].toDouble(), 5e-7)
            assertEquals("${c["name"]} R", c["R"].d(), out[1].toDouble(), 5e-7)
        }
    }

    @Test fun downmixControlExamples() {
        fun mix(mask: Int, vararg v: Float): FloatArray {
            val out = FloatArray(2); Downmix.apply(Downmix.matrix(mask, v.size), v, 1, out); return out
        }
        val s = 0.70710678
        mix(0xFC, 0f, 0f, 1f, 0f, 0f, 0f).let { assertEquals(s, it[0].toDouble(), 5e-5); assertEquals(s, it[1].toDouble(), 5e-5) }     // D-1
        mix(0xFC, 0f, 0f, 0f, 0f, 0.5f, 0f).let { assertEquals(0.35355339, it[0].toDouble(), 5e-5); assertEquals(0.0, it[1].toDouble(), 0.0) } // D-2
        mix(0xFC, 0f, 0f, 0f, 1f, 0f, 0f).let { assertEquals(0f, it[0], 0f); assertEquals(0f, it[1], 0f) }                              // D-3
        mix(0xFC, 1f, 1f, 1f, 1f, 1f, 1f).let { assertEquals(2.41421356, it[0].toDouble(), 5e-5); assertEquals(2.41421356, it[1].toDouble(), 5e-5) } // D-4
        mix(0x18FC, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f).let { assertEquals(1.41421356, it[0].toDouble(), 5e-5); assertEquals(0.0, it[1].toDouble(), 0.0) } // D-5
        mix(0x183C, 0f, 0f, 0f, 0f, 1f, 0f).let { assertEquals(s, it[0].toDouble(), 5e-5) }                                               // D-6
        mix(0xCC, 0.2f, 0f, 0f, 1f).let { assertEquals(0.2, it[0].toDouble(), 5e-7); assertEquals(s, it[1].toDouble(), 5e-5) }           // D-7
        mix(0xDC, 0f, 0f, 1f, 0f, 1f).let { assertEquals(s, it[0].toDouble(), 5e-5); assertEquals(1.41421356, it[1].toDouble(), 5e-5) }  // D-8
        mix(0x41C, 0f, 0f, 0f, 1f).let { assertEquals(0.5, it[0].toDouble(), 5e-5); assertEquals(0.5, it[1].toDouble(), 5e-5) }          // D-9
        // Маска 0 → по числу каналов (6 → 5.1).
        mix(0, 0f, 0f, 1f, 0f, 0f, 0f).let { assertEquals(s, it[0].toDouble(), 5e-5) }
    }

    @Test fun downmixDefaultsAndIdentity() {
        val dm = ref["default_mask_by_count"].obj()
        for ((k, v) in dm) assertEquals("ch=$k", v.l().toInt(), Downmix.defaultMask(k.toInt()))
        assertEquals(1, Downmix.outputChannels(1)); assertEquals(2, Downmix.outputChannels(6))
        // 1 и 2 канала — единичная матрица, бит в бит.
        val src = floatArrayOf(0.1f, -0.3f, 1.7f, -2f, Float.MIN_VALUE, 0.33333334f)
        val dst = FloatArray(6)
        Downmix.apply(Downmix.matrix(0, 2), src, 3, dst)
        assertArrayEquals(src, dst, 0f)
        val m1 = Downmix.matrix(0x10, 1); assertEquals(1, m1.size); assertEquals(1.0, m1[0][0], 0.0)
        assertEquals(0x4000, Downmix.unknownBits(0x4000 or 0xFC))
        // Маска с неверным числом битов → по умолчанию.
        assertEquals(0xFC, Downmix.effectiveMask(0x3C, 6))
    }
}
