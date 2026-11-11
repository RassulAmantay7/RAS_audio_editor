package com.rassul.audioeditor.core.audit.edit

import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.LoopCursor
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.edit.UndoStack
import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.loudness.Normalizer
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** FR-22 в списке правок (timeline.md «Нормализация … замораживается») и FR-32 (LoopCursor, журнал курсора). */
class AuditNormalizeLoopTest {
    private val fs = 48_000
    private fun sine(frames: Int, amp: Double, f: Double = 997.0) =
        FloatArray(frames * 2) { (amp * Math.sin(2 * Math.PI * f * (it / 2) / fs)).toFloat() }

    private val prefix = listOf(EditOp.Delete(Range(10_000, 30_000)), EditOp.Volume(Range(0, 40_000), -9.0),
        EditOp.Fade(Range(0, 4800), true, com.rassul.audioeditor.core.dsp.FadeCurve.EQUAL_POWER))

    /** Усиление измеряется по результату ПРЕДШЕСТВУЮЩИХ правок (независимо: наивный рендер → тот же измеритель). */
    @Test fun resolveMeasuresPrefixResult() {
        val x = sine(96_000, 0.3)
        val src = ArrayPcmSource(PcmSpec(fs, 2), x)
        val s = RenderSettings()
        for (target in listOf(NormalizeTarget.Lufs(-14.0), NormalizeTarget.Lufs(-30.0), NormalizeTarget.Peak(-1.0))) {
            val op = Renderer(src, EditList(prefix), s).resolveNormalize(EditOp.Normalize(target))
            val naive = Naive.render(x, 2, prefix, 240)
            val d = Normalizer.decide(LoudnessMeter.measure(ArrayPcmSource(PcmSpec(fs, 2), naive)), target)
            assertEquals("$target", if (d.available) d.gainDb else 0.0, op.resolvedGainDb!!, 0.0)
            assertEquals(d.message, op.notice)
            // Применение: G32 = float32(10^(G/20)) поверх префикса, бит в бит.
            val r = Renderer(src, EditList(prefix + op), s)
            assertEquals(-1, firstBitDiff(Naive.render(x, 2, prefix + op, 240), renderChunks(r, ARng(9), 5000)))
        }
    }

    /** Разрешённая операция не пересчитывается: рендер готов сразу, источник при построении не читается; redo — тот же объект. */
    @Test fun frozenNoRecompute() {
        val x = sine(48_000, 0.2)
        val src = FragileSource(PcmSpec(fs, 2), x)
        val op = Renderer(src, EditList(prefix.take(1)), RenderSettings()).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs()))
        val before = src.reads
        val list = EditList(prefix.take(1) + op)
        val r = Renderer(src, list, RenderSettings())
        assertTrue(r.isPrepared)
        r.prepare()
        assertEquals("источник читался при построении/prepare разрешённого списка", before, src.reads)
        val u = UndoStack(EditList(prefix.take(1)))
        u.push(list); u.undo()
        assertSame(list, u.redo())
        assertSame(op, u.current.ops.last())
    }

    /** Неразрешённая нормализация в середине списка: render до prepare — ошибка; prepare = resolve по префиксу. */
    @Test fun unresolvedInMiddle() {
        val x = sine(96_000, 0.25)
        val src = ArrayPcmSource(PcmSpec(fs, 2), x)
        val s = RenderSettings()
        val list = EditList(prefix + EditOp.Normalize(NormalizeTarget.Lufs(-16.0)) + EditOp.Volume(null, 2.0))
        val r = Renderer(src, list, s)
        assertTrue(!r.isPrepared)
        try { r.render(0, FloatArray(20), 10); fail("render без prepare") } catch (e: IllegalStateException) {}
        r.prepare()
        val exp = Renderer(src, EditList(prefix), s).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs(-16.0)))
        assertEquals(exp, r.resolvedEdits.ops[3])
    }

    /** Тишина / < 0,4 с: усиление 0 дБ и пояснение (FR-22), а не NaN/∞ в множителе. */
    @Test fun unavailableGivesZeroGain() {
        for (frames in intArrayOf(48_000, 10_000)) {
            val x = if (frames == 48_000) FloatArray(frames * 2) else sine(frames, 0.5)
            val op = Renderer(ArrayPcmSource(PcmSpec(fs, 2), x), EditList(), RenderSettings()).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs()))
            assertEquals(0.0, op.resolvedGainDb!!, 0.0)
            assertNotNull(op.notice)
        }
    }

    // ---------- FR-32 ----------
    private fun indexRenderer(len: Int) = Renderer(ArrayPcmSource(PcmSpec(fs, 1), FloatArray(len) { it.toFloat() }), EditList(), RenderSettings())

    private fun play(c: LoopCursor, frames: Int, block: Int): IntArray {
        val out = IntArray(frames); var k = 0; val buf = FloatArray(block)
        while (k < frames) { val n = c.fill(buf, minOf(block, frames - k)); if (n <= 0) break; for (i in 0 until n) out[k + i] = buf[i].toInt(); k += n }
        assertEquals(frames, k)
        return out
    }

    @Test fun loopVectors() {
        val r = indexRenderer(200_000)
        for (block in intArrayOf(4096, 1, 7, 48_000, 100_000)) {
            val c = LoopCursor(r).apply { repeat = true; selection = Range(48_000, 96_000) }
            val o = play(c, 240_000, block)
            for (i in o.indices) if (o[i] != 48_000 + i % 48_000) fail("блок $block: кадр $i = ${o[i]}")
        }
        assertEquals(listOf(10, 10, 10, 10, 10), play(LoopCursor(r).apply { repeat = true; selection = Range(10, 11) }, 5, 3).toList())
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1), play(LoopCursor(r).apply { repeat = true; selection = Range(0, 3) }, 8, 5).toList())
        // Смена конца после 1000 кадров: 1000…1599 = 0…599.
        val c1 = LoopCursor(r).apply { repeat = true; selection = Range(0, 2000) }
        play(c1, 1000, 500); c1.selection = Range(0, 600)
        assertEquals((0 until 600).toList(), play(c1, 600, 500).toList())
        // Смена выделения после 500 кадров на [800, 1000).
        val c2 = LoopCursor(r).apply { repeat = true; selection = Range(0, 2000) }
        play(c2, 500, 500); c2.selection = Range(800, 1000)
        val o2 = play(c2, 1000, 500)
        for (i in o2.indices) assertEquals(800 + i % 200, o2[i])
        // Начало сдвинуто правее pos → переход к началу (timeline.md FR-32 п. 1).
        val c3 = LoopCursor(r).apply { repeat = true; selection = Range(0, 2000) }
        play(c3, 100, 50); c3.selection = Range(500, 700)
        assertEquals(500, play(c3, 1, 1)[0])
    }

    /** Курсор: framePosition = 59·48 000, now − nanoTime = 0,5 с → позиция 72 000 (timeline.md). */
    @Test fun cursorVector() {
        val c = LoopCursor(indexRenderer(200_000)).apply { repeat = true; selection = Range(48_000, 96_000) }
        play(c, 61 * 48_000, 960)
        assertEquals(72_000L, c.cursorAt(59L * 48_000, 1_000_000_000L, 1_500_000_000L))
        // Округление half-up: 0,5 кадра (10 416,67 нс при 48 кГц → 10 417 нс ≥ ½ кадра) → следующий кадр.
        assertEquals(72_001L, c.cursorAt(59L * 48_000, 0L, 500_000_000L + 10_417L))
        assertEquals(72_000L, c.cursorAt(59L * 48_000, 0L, 500_000_000L + 10_416L))
        // Прошлое забыто, будущее (не записанное) — null.
        assertEquals(null, c.renderPositionAt(1000L))
        assertEquals(null, c.renderPositionAt(62L * 48_000))
        // Без повтора: позиция = номер кадра вывода (после seek — со сдвигом).
        val d = LoopCursor(indexRenderer(10_000))
        play(d, 3000, 700); d.seek(5000); play(d, 100, 64)
        assertEquals(2999L, d.renderPositionAt(2999)); assertEquals(5000L, d.renderPositionAt(3000)); assertEquals(5099L, d.renderPositionAt(3099))
    }
}
