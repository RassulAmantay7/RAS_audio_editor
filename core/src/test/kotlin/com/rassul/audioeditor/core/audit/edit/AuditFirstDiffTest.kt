package com.rassul.audioeditor.core.audit.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * firstDifference (timeline.md «Первый изменённый сэмпл p»): НИКОГДА не больше истинной первой разницы
 * (иначе пирамида/кэш не пересчитаются там, где звук изменился). Свойство на случайных парах списков.
 */
class AuditFirstDiffTest {
    private val fs = 1000

    @Test fun neverAfterTrueDifference() {
        val rng = ARng(424242L)
        val ns = longArrayOf(0, 1, 3, 24, 240)
        var exact = 0
        repeat(600) { t ->
            val ch = 1 + rng.int(2)
            val l0 = rng.long(2, 2500)
            val x = noiseA(l0.toInt(), ch, 1000L + t)
            val n = ns[rng.int(ns.size)]
            val s = settingsFor(n, fs)
            val src = arraySrc(x, ch, fs)
            val prefix = rng.list(l0, rng.int(4))
            val lp = renderer(src, prefix, s).length
            val tailA = rng.list(lp, rng.int(4))
            val tailB = if (rng.bool(0.3)) emptyList() else rng.list(lp, rng.int(4))
            val ra = renderer(src, prefix + tailA, s); val rb = renderer(src, prefix + tailB, s)
            val ya = Naive.render(x, ch, prefix + tailA, n); val yb = Naive.render(x, ch, prefix + tailB, n)
            val d = firstBitDiff(ya, yb)
            val trueFrame = if (d < 0) ra.length else (d / ch).toLong()
            val fd = ra.firstDifference(rb)
            assertTrue("пара $t: firstDifference=$fd > истинной $trueFrame; prefix=$prefix A=$tailA B=$tailB", fd <= trueFrame)
            assertEquals("пара $t: несимметрично", fd, rb.firstDifference(ra))
            if (fd == trueFrame) exact++
        }
        assertTrue("оценка слишком грубая: точных $exact из 600", exact > 200)
    }

    /** Одна добавленная правка → p из timeline.md: delete a − N_L (0 при a = 0); trim 0 при a > 0, иначе b − N; gain/fade a; normalize 0. */
    @Test fun singleAppendedOpFormula() {
        val x = noiseA(1000, 1, 5)
        val src = arraySrc(x, 1, 48_000)
        val s = com.rassul.audioeditor.core.edit.RenderSettings()   // 5 мс → N = 240
        val base = renderer(src, emptyList(), s)
        fun p(op: EditOp) = renderer(src, listOf(op), s).firstDifference(base)
        assertEquals(300 - 150L, p(EditOp.Delete(Range(300, 900))))   // N_L = min(240, 150)
        assertEquals(0L, p(EditOp.Delete(Range(0, 900))))
        assertEquals(800 - 240L, p(EditOp.Delete(Range(800, 1000))))
        assertEquals(0L, p(EditOp.Keep(Range(1, 1000))))
        assertEquals(600 - 240L, p(EditOp.Keep(Range(0, 600))))       // N_k = min(240, 300)
        assertEquals(100 - 50L, p(EditOp.Keep(Range(0, 100))))          // N_k = 50
        assertEquals(1000L, p(EditOp.Keep(Range(0, 1000))))              // тождество
        assertEquals(123L, p(EditOp.Volume(Range(123, 456), -1.0)))
        assertEquals(77L, p(EditOp.Fade(Range(77, 900), true, FadeCurve.LINEAR)))
        assertEquals(0L, p(EditOp.Normalize(com.rassul.audioeditor.core.loudness.NormalizeTarget.Lufs(), 2.0)))
        assertEquals(1000L, p(EditOp.Normalize(com.rassul.audioeditor.core.loudness.NormalizeTarget.Lufs(), 0.0)))  // ×1 — тождество
        assertEquals(0L, base.firstDifference(null))
        assertEquals(0L, base.firstDifference(renderer(arraySrc(x, 1, 48_000), emptyList(), s)))  // другой источник
    }
}
