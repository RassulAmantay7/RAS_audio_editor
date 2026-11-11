package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.dsp.Gain
import com.rassul.audioeditor.core.loudness.LoudnessResult
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.loudness.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит FR-22 (docs/math/dsp.md ф-лы 18–19, П-8, «Решения главной сессии и пользователя»). */
class AuditNormalizerTest {
    private fun r(i: Double, tp: Double, sp: Double = tp) = LoudnessResult(i, tp, sp, 48_000)
    private val ear = NormalizeTarget.Lufs()

    @Test fun controlExamples() {
        Normalizer.decide(r(-20.0, -8.0), ear).let { assertTrue(it.available); assertFalse(it.limited); assertEquals(6.0, it.gainDb, 1e-12) } // N-1
        Normalizer.decide(r(-20.0, -5.7), ear).let {                                                                                       // N-2
            assertTrue(it.limited); assertEquals(4.7, it.gainDb, 1e-12)
            assertEquals(1.7179084, Gain.dbToLinear(it.gainDb), 1e-7)
            assertEquals("Громкость −14 LUFS недостижима без перегрузки. Достигнуто −15,3 LUFS (пик −1,0 dBTP).", it.message)
        }
        Normalizer.decide(r(-16.0, -0.5), ear).let {                                                                                       // N-3 + решение пользователя
            assertTrue(it.available); assertTrue(it.limited); assertEquals(0.0, it.gainDb, 0.0)
            assertEquals("Нормализация не применена: в записи есть пик у предела (TP = −0,5 dBTP), поднять громкость без искажений нельзя", it.message)
        }
        Normalizer.decide(r(-14.0, -0.2), NormalizeTarget.Lufs(-23.0)).let { assertFalse(it.limited); assertEquals(-9.0, it.gainDb, 1e-12) } // N-4
        // G ≤ 0, но и после снижения пик выше потолка: применяется min(G, G′) = G′.
        Normalizer.decide(r(-14.0, 9.0), NormalizeTarget.Lufs(-23.0)).let { assertTrue(it.limited); assertEquals(-10.0, it.gainDb, 1e-12) }
        // N-5: по пику max|x| = 0,5 → ×1,7825018763.
        Normalizer.decide(r(-20.0, -5.0, 20 * Math.log10(0.5)), NormalizeTarget.Peak()).let { assertEquals(1.7825018763, Gain.dbToLinear(it.gainDb), 1e-9) }
    }

    /** Ровно на потолке (TP + G = −1,0) ограничение не срабатывает (неравенство строгое, basic_ref.normalize_lufs). */
    @Test fun ceilingBoundary() {
        Normalizer.decide(r(-20.0, -7.0), ear).let { assertFalse(it.limited); assertEquals(6.0, it.gainDb, 0.0) }
    }

    /** Свойство: после нормализации TP + G ≤ потолок + 1e-9 (кроме случая «не применена»), I + G ≤ цель. */
    @Test fun propertyNeverExceedsCeiling() {
        val rnd = java.util.Random(22)
        repeat(100_000) {
            val i = -70 + rnd.nextDouble() * 70; val tp = i + rnd.nextDouble() * 25; val tgt = -30 + rnd.nextDouble() * 25
            val d = Normalizer.decide(r(i, tp), NormalizeTarget.Lufs(tgt))
            assertTrue(d.available)
            if (d.limited && d.gainDb == 0.0) {
                assertTrue("решение пользователя: G > 0 и G' < 0", tgt - i > 0 && -1.0 - tp < 0)   // «не применена»
            } else {
                assertTrue("i=$i tp=$tp tgt=$tgt g=${d.gainDb}", tp + d.gainDb <= -1.0 + 1e-9)
                assertTrue(i + d.gainDb <= tgt + 1e-9)
                assertEquals(d.limited, tp + (tgt - i) > -1.0)
            }
        }
    }

    /** Коды недоступности: NaN — короче 0,4 с; −∞ — тише −70; тишина для «по пику». */
    @Test fun unavailableModes() {
        Normalizer.decide(r(Double.NaN, -10.0), ear).let { assertFalse(it.available); assertEquals(Normalizer.MSG_TOO_SHORT, it.message) }
        Normalizer.decide(r(Double.NEGATIVE_INFINITY, -60.0), ear).let { assertFalse(it.available); assertEquals(Normalizer.MSG_TOO_QUIET, it.message) }
        Normalizer.decide(r(-70.0, -60.0), ear).let { assertFalse(it.available); assertEquals(Normalizer.MSG_TOO_QUIET, it.message) }
        Normalizer.decide(r(-30.0, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY), NormalizeTarget.Peak()).let {
            assertFalse(it.available); assertEquals(Normalizer.MSG_SILENT, it.message)
        }
    }

    /** Формат чисел — как basic_ref.fmt_db (живая сверка на сетке сотых и случайных значениях) и правила П-8 (г). */
    @Test fun fmtDbMatchesBasicRef() {
        assertEquals("−15,3", Normalizer.fmtDb(-15.25)); assertEquals("0,0", Normalizer.fmtDb(-0.04))
        assertEquals("−0,1", Normalizer.fmtDb(-0.05)); assertEquals("0,1", Normalizer.fmtDb(0.05))
        assertEquals("−14", Normalizer.fmtTarget(-14.0)); assertEquals("−14,5", Normalizer.fmtTarget(-14.5))
        assertEquals("0", Normalizer.fmtTarget(-0.0))
        val rnd = java.util.Random(4)
        val vals = DoubleArray(4000) { if (it < 2000) (it - 1000) / 100.0 else (rnd.nextDouble() - 0.5) * 80 } +
            DoubleArray(400) { -20.0 + (it % 100) / 10.0 + 4.7 * (it / 100) / 10.0 }
        val txt = vals.joinToString(",") { it.toString() }
        val out = AuditPy.run("from basic_ref import fmt_db\nprint(' '.join(fmt_db(v) for v in [$txt]))")
        val ref = out.lines().last { it.isNotBlank() }.trim().split(' ')
        for (k in vals.indices) assertEquals("v=${vals[k]}", ref[k], Normalizer.fmtDb(vals[k]))
    }
}
