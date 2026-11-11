package com.rassul.audioeditor.core.loudness

import com.rassul.audioeditor.core.dsp.Gain
import com.rassul.audioeditor.core.dsp.RefData
import com.rassul.audioeditor.core.dsp.d
import com.rassul.audioeditor.core.dsp.list
import com.rassul.audioeditor.core.dsp.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-22: N-1…N-7 docs/math/dsp.md + решение пользователя о G′ < 0 при G > 0. */
class NormalizerTest {
    private fun res(i: Double, tp: Double, sp: Double = tp) = LoudnessResult(i, tp, sp, 480_000)

    @Test fun lufsVectors() {   // N-1…N-4 (+ случай −16/−30)
        val ref = RefData.json("basic_vectors.json").obj()
        for (c in ref["normalize_lufs"].list().map { it.obj() }) {
            val tgt = c["target"].d(); val i = c["I"].d(); val tp = c["TP"].d()
            val dec = Normalizer.decide(res(i, tp), NormalizeTarget.Lufs(tgt))
            assertTrue(dec.available)
            if (tgt - i > 0 && c["G_applied_dB"].d() < 0) {
                // Решение пользователя (dsp.md «Решения главной сессии»): громкость не меняется.
                assertEquals(0.0, dec.gainDb, 0.0)
                assertTrue(dec.limited)
                assertEquals("Нормализация не применена: в записи есть пик у предела (TP = −0,5 dBTP), поднять громкость без искажений нельзя", dec.message)
                continue
            }
            assertEquals(c.toString(), c["G_applied_dB"].d(), dec.gainDb, 1e-6)
            assertEquals(c["gain_lin"].d(), Gain.dbToLinear(dec.gainDb), 1e-6)
            assertEquals(c["limited"] as Boolean, dec.limited)
            assertEquals(c["message"] as String?, dec.message)
        }
    }

    @Test fun controlTexts() {  // N-2 строка точно
        val d = Normalizer.decide(res(-20.0, -5.7), NormalizeTarget.Lufs(-14.0))
        assertEquals(4.7, d.gainDb, 1e-9)
        assertEquals(1.7179084, Gain.dbToLinear(d.gainDb), 1e-6)
        assertEquals("Громкость −14 LUFS недостижима без перегрузки. Достигнуто −15,3 LUFS (пик −1,0 dBTP).", d.message)
        val n1 = Normalizer.decide(res(-20.0, -8.0), NormalizeTarget.Lufs())
        assertEquals(6.0, n1.gainDb, 1e-12); assertFalse(n1.limited); assertNull(n1.message)
        val n4 = Normalizer.decide(res(-14.0, -0.2), NormalizeTarget.Lufs(-23.0))
        assertEquals(-9.0, n4.gainDb, 1e-12); assertEquals(0.3548134, Gain.dbToLinear(n4.gainDb), 1e-6)
        // G ≤ 0 и пик всё равно выше потолка: min(G, G′).
        val n5 = Normalizer.decide(res(-13.0, 0.5), NormalizeTarget.Lufs(-14.0))
        assertEquals(-1.5, n5.gainDb, 1e-12); assertTrue(n5.limited)
        assertEquals("Громкость −14 LUFS недостижима без перегрузки. Достигнуто −14,5 LUFS (пик −1,0 dBTP).", n5.message)
        // N-3: G > 0, G′ < 0 → 0 дБ с пояснением.
        val n3 = Normalizer.decide(res(-16.0, -0.5), NormalizeTarget.Lufs(-14.0))
        assertEquals(0.0, n3.gainDb, 0.0); assertTrue(n3.available)
    }

    @Test fun unavailable() {
        Normalizer.decide(res(Double.NaN, -10.0), NormalizeTarget.Lufs()).let {
            assertFalse(it.available); assertEquals(Normalizer.MSG_TOO_SHORT, it.message)
        }
        Normalizer.decide(res(Double.NEGATIVE_INFINITY, -80.0), NormalizeTarget.Lufs()).let {
            assertFalse(it.available); assertEquals(Normalizer.MSG_TOO_QUIET, it.message)
        }
        Normalizer.decide(res(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY), NormalizeTarget.Peak()).let {
            assertFalse(it.available); assertEquals(Normalizer.MSG_SILENT, it.message)
        }
        // Сквозь измеритель: 19 199 сэмплов → «короче 0,4 с»; тишина → «тише −70».
        val short = LoudnessTest.sine(48_000, 1000.0, -23.0, 19_199.0 / 48_000)
        assertEquals(Normalizer.MSG_TOO_SHORT, Normalizer.decide(LoudnessTest.measure(short, 48_000, 2), NormalizeTarget.Lufs()).message)
        assertEquals(Normalizer.MSG_TOO_QUIET, Normalizer.decide(LoudnessTest.measure(FloatArray(96_000), 48_000, 2), NormalizeTarget.Lufs()).message)
        // По пику для короткого файла режим доступен.
        assertTrue(Normalizer.decide(LoudnessTest.measure(short, 48_000, 2), NormalizeTarget.Peak()).available)
    }

    @Test fun peakMode() {      // N-5
        val sp = 20 * Math.log10(0.5)
        val d = Normalizer.decide(res(-20.0, -5.0, sp), NormalizeTarget.Peak())
        assertEquals(1.7825018763, Gain.dbToLinear(d.gainDb), 1e-6)
        assertFalse(d.limited)
        // Пик результата во float: −1,00 ± 0,01 dBFS.
        val g = Gain.dbToLinear(d.gainDb).toFloat()
        assertEquals(-1.0, 20 * Math.log10((0.5f * g).toDouble()), 0.01)
    }

    @Test fun formatting() {
        assertEquals("−15,3", Normalizer.fmtDb(-15.25))
        assertEquals("−15,4", Normalizer.fmtDb(-15.35))
        assertEquals("0,0", Normalizer.fmtDb(-0.04))
        assertEquals("0,1", Normalizer.fmtDb(0.05))
        assertEquals("−14,0", Normalizer.fmtDb(-14.0))
        assertEquals("−14", Normalizer.fmtTarget(-14.0))
        assertEquals("−23", Normalizer.fmtTarget(-23.0))
        assertEquals("−14,5", Normalizer.fmtTarget(-14.5))
    }

    @Test fun truePeakAfterLimitedNormalization() { // N-7, N-6: сквозной прогон через измеритель
        val fs = 48_000
        val x = LoudnessTest.sine(fs, 1000.0, -30.0, 10.0)
        for (k in 0 until 3) { val p = 100_000 * (k + 1) + 3; x[2 * p] = 0.3f; x[2 * p + 1] = -0.3f } // щелчки −10,5 dBFS
        val r0 = LoudnessTest.measure(x, fs, 2)
        val dec = Normalizer.decide(r0, NormalizeTarget.Lufs(-14.0))
        assertTrue("должно сработать ограничение: $dec", dec.limited && dec.gainDb > 0)
        val g = Gain.dbToLinear(dec.gainDb).toFloat()
        val r1 = LoudnessTest.measure(FloatArray(x.size) { x[it] * g }, fs, 2)
        assertTrue("TP после нормализации ${r1.truePeakDbtp}", r1.truePeakDbtp <= -1.0 + 1e-3)
        assertEquals(r0.integratedLufs + dec.gainDb, r1.integratedLufs, 0.001)
        // Без ограничения: громкость = цель ± 0,1 LU (FR-22).
        val y = LoudnessTest.sine(fs, 1000.0, -30.0, 10.0)
        val ry = LoudnessTest.measure(y, fs, 2)
        val dy = Normalizer.decide(ry, NormalizeTarget.Lufs(-16.0))
        assertFalse(dy.limited)
        val gy = Gain.dbToLinear(dy.gainDb).toFloat()
        assertEquals(-16.0, LoudnessTest.measure(FloatArray(y.size) { y[it] * gy }, fs, 2).integratedLufs, 0.001)
    }
}
