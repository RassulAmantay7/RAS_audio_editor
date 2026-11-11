package com.rassul.audioeditor.core.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.JointSmoother
import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** FR-13, FR-16…FR-22, FR-14: Renderer против последовательного эталона и контрольных примеров. */
class RendererTest {
    private val on = RenderSettings(smoothJoints = true, jointMs = 5.0)   // N = 240 @48 кГц
    private val off = RenderSettings(smoothJoints = false)

    /** Строит список из [count] случайных правок через UndoStack (нормализация разрешается при добавлении). */
    private fun buildRandom(source: com.rassul.audioeditor.core.model.PcmSource, settings: RenderSettings, seed: Long, count: Int): UndoStack {
        val rnd = java.util.Random(seed)
        val st = UndoStack()
        repeat(count) {
            val r = Renderer(source, st.current, settings)
            var op = randomOp(rnd, r.length)
            if (op is EditOp.Normalize) op = r.resolveNormalize(op)
            st.push(st.current.plus(op))
        }
        return st
    }

    @Test fun fr16RandomUndoRedoBitExact() {
        val ch = 2
        val x = noise(144_000, ch, 42)
        val s = src(x, ch)
        for ((settings, n) in listOf(on to 240L, off to 0L)) {
            val st = buildRandom(s, settings, 20261111L, 50)
            val list = st.current
            assertEquals(50, list.ops.size)
            val r1 = Renderer(s, list, settings)
            val a = renderAll(r1)
            // Эталон: последовательное применение правок.
            assertBitEqual("рендер = последовательный эталон", SeqRef.render(x, ch, list.ops, n), a)
            repeat(50) { assertNotNull(st.undo()) }
            assertFalse(st.canUndo)
            assertEquals(EditList(), st.current)
            assertBitEqual("после отмены всех = исходник", x, renderAll(Renderer(s, st.current, settings)))
            repeat(50) { assertNotNull(st.redo()) }
            assertFalse(st.canRedo)
            assertEquals(list, st.current)
            assertBitEqual("отмена всех → повтор всех", a, renderAll(Renderer(s, st.current, settings)))
            // Нормализация не пересчитывается заново: префиксы разрешены, рендер готов сразу.
            assertTrue(Renderer(s, st.current, settings).isPrepared)
            assertEquals(list.ops.count { it is EditOp.Normalize }, r1.notices.size)
        }
    }

    @Test fun blocksAndFromAreBitExact() {
        val ch = 2
        val x = noise(60_000, ch, 7)
        val s = src(x, ch)
        val list = buildRandom(s, on, 99L, 30).current
        val r = Renderer(s, list, on)
        val whole = renderAll(r, 1 shl 20)
        for (b in intArrayOf(7, 4096, 1000)) assertBitEqual("блок $b", whole, renderAll(r, b))
        // Блок 1 на участке и произвольные from.
        val rnd = java.util.Random(5)
        repeat(200) {
            val from = (rnd.nextDouble() * r.length).toLong()
            val n = 1 + rnd.nextInt(3000)
            val buf = FloatArray(n * ch)
            val got = r.render(from, buf, n)
            assertEquals(minOf(n.toLong(), r.length - from).toInt(), got)
            for (i in 0 until got * ch) if (buf[i].toRawBits() != whole[(from * ch).toInt() + i].toRawBits()) fail("from=$from i=$i")
        }
        val one = FloatArray(ch)
        for (p in 0L until minOf(r.length, 3000L)) {
            r.render(p, one, 1)
            for (c in 0 until ch) assertEquals(whole[(p * ch).toInt() + c].toRawBits(), one[c].toRawBits())
        }
        // Смещение в dst.
        val buf = FloatArray(10 * ch)
        r.render(100, buf, 3, 5)
        for (i in 0 until 5 * ch) assertEquals(whole[100 * ch + i].toRawBits(), buf[3 * ch + i].toRawBits())
        assertEquals(0, r.render(r.length, buf, 5))
    }

    @Test fun keepDeleteLengthsAndUntouchedSamples() {   // FR-17/18/19
        val ch = 2
        val x = noise(10_000, ch, 3)
        val s = src(x, ch)
        // Удаление: длина L − (b − a); вне зон N сэмплы бит в бит.
        val del = Renderer(s, EditList(listOf(EditOp.Delete(Range(3000, 5000)))), on)
        assertEquals(8000L, del.length)
        val y = renderAll(del)
        for (f in 0 until 8000) {
            val inZone = f in 2760 until 3240
            val sf = if (f < 3000) f else f + 2000
            for (c in 0 until ch) {
                val e = x[sf * ch + c]; val a = y[f * ch + c]
                if (!inZone) assertEquals("f=$f", e.toRawBits(), a.toRawBits())
                else {
                    val g = if (f < 3000) JointSmoother.gain((f - 2760).toLong(), 240, false) else JointSmoother.gain((f - 3000).toLong(), 240, true)
                    assertEquals((e * g.toFloat()).toRawBits(), a.toRawBits())
                }
            }
        }
        // Обрезка: длина b − a.
        val keep = Renderer(s, EditList(listOf(EditOp.Keep(Range(1234, 7777)))), on)
        assertEquals(7777L - 1234L, keep.length)
        val k = renderAll(keep)
        for (f in 240 until 6543 - 240) for (c in 0 until ch) assertEquals(x[(f + 1234) * ch + c].toRawBits(), k[f * ch + c].toRawBits())
        // Сглаживание выключено: стык бит в бит x[:a] ++ x[b:].
        val d0 = renderAll(Renderer(s, EditList(listOf(EditOp.Delete(Range(3000, 5000)))), off))
        assertBitEqual("стык без сглаживания", x.copyOfRange(0, 6000) + x.copyOfRange(10_000, 20_000), d0)
        val k0 = renderAll(Renderer(s, EditList(listOf(EditOp.Keep(Range(1234, 7777)))), off))
        assertBitEqual("обрезка без сглаживания", x.copyOfRange(1234 * ch, 7777 * ch), k0)
    }

    @Test fun shortPiece() {   // timeline.md short_piece: L = 1000, delete [300, 900), N = 240 → 150 / 50
        val x = FloatArray(1000) { 1f }
        val r = renderAll(Renderer(src(x, 1), EditList(listOf(EditOp.Delete(Range(300, 900)))), on))
        assertEquals(400, r.size)
        for (i in 0 until 150) assertEquals(1f, r[i], 0f)
        for (j in 0 until 150) assertEquals(JointSmoother.gain(j.toLong(), 150, false).toFloat(), r[150 + j], 0f)
        for (i in 0 until 50) assertEquals(JointSmoother.gain(i.toLong(), 50, true).toFloat(), r[300 + i], 0f)
        for (i in 350 until 400) assertEquals(1f, r[i], 0f)
        // C3-4: кусок 7 сэмплов → N_k = 3.
        val r2 = renderAll(Renderer(src(FloatArray(20) { 1f }, 1), EditList(listOf(EditOp.Delete(Range(7, 20)))), on))
        assertEquals(7, r2.size)
        for (i in 0 until 4) assertEquals(1f, r2[i], 0f)
        for (j in 0 until 3) assertEquals(JointSmoother.gain(j.toLong(), 3, false).toFloat(), r2[4 + j], 0f)
    }

    @Test fun deleteWholeFileRejected() {
        assertEquals(EditRules.MSG_DELETE_ALL, EditRules.check(EditOp.Delete(Range(0, 100)), 100))
        try { Renderer(src(FloatArray(100), 1), EditList(listOf(EditOp.Delete(Range(0, 100)))), on); fail() }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains(EditRules.MSG_DELETE_ALL)) }
        assertEquals(EditRules.MSG_OUT_OF_RANGE, EditRules.check(EditOp.Keep(Range(0, 101)), 100))
    }

    @Test fun volumePlus6dB() {   // FR-20 / G-1: +6,0 дБ → ×1,995262 (отн. 1e-6)
        val x = noise(5000, 2, 11)
        val y = renderAll(Renderer(src(x, 2), EditList(listOf(EditOp.Volume(null, 6.0))), on))
        for (i in x.indices) if (x[i] != 0f) assertEquals(1.995262315, y[i].toDouble() / x[i], 1.995262315 * 1e-6)
        // На выделении: вне диапазона — без изменений.
        val z = renderAll(Renderer(src(x, 2), EditList(listOf(EditOp.Volume(Range(100, 200), -40.0))), on))
        for (f in 0 until 5000) for (c in 0..1) {
            val i = f * 2 + c
            if (f in 100 until 200) assertEquals(x[i] * 0.01, z[i].toDouble(), 1e-6 * Math.abs(x[i]) + 1e-12)
            else assertEquals(x[i].toRawBits(), z[i].toRawBits())
        }
    }

    @Test fun fadeControlExamples() {   // C2-3, C2-4, C2-5 (dsp.md)
        val ones = FloatArray(10) { 1f }
        val exp = mapOf(
            FadeCurve.LINEAR to doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0),
            FadeCurve.EQUAL_POWER to doubleArrayOf(0.0, 0.382683432, 0.707106781, 0.923879533, 1.0),
            FadeCurve.LOGARITHMIC to doubleArrayOf(0.0, 0.005623413, 0.031622777, 0.177827941, 1.0),
        )
        for ((curve, e) in exp) {
            val fin = renderAll(Renderer(src(ones, 1), EditList(listOf(EditOp.Fade(Range(2, 7), true, curve))), on))
            for (i in 0 until 5) assertEquals("$curve in $i", e[i], fin[2 + i].toDouble(), 1e-6)
            assertEquals(1f, fin[0], 0f); assertEquals(1f, fin[9], 0f)
            val fout = renderAll(Renderer(src(ones, 1), EditList(listOf(EditOp.Fade(Range(2, 7), false, curve))), on))
            for (i in 0 until 5) assertEquals("$curve out $i", e[4 - i], fout[2 + i].toDouble(), 1e-6)
        }
        val n1 = renderAll(Renderer(src(ones, 1), EditList(listOf(EditOp.Fade(Range(3, 4), true, FadeCurve.LINEAR))), on))
        assertBitEqual("N = 1 — ничего", ones, n1)
        // Fade без выделения: 3,0 с при 48 кГц, прижатие к L.
        assertEquals(Range(0, 144_000), EditRules.fadeRangeWithoutSelection(3000.0, 48_000, 1_000_000, true))
        assertEquals(Range(0, 500), EditRules.fadeRangeWithoutSelection(3000.0, 48_000, 500, false))
    }

    private fun sine(frames: Int, ch: Int, amp: Double, fs: Int = 48_000) =
        FloatArray(frames * ch) { (amp * Math.sin(2 * Math.PI * 1000.0 * (it / ch) / fs)).toFloat() }

    @Test fun normalizeReachesTarget() {   // FR-22: измеренная громкость результата = цель ± 0,1 LU
        val ch = 2
        val s = src(sine(48_000 * 5, ch, 0.1), ch)
        val base = EditList(listOf(EditOp.Volume(null, -3.0), EditOp.Delete(Range(10_000, 20_000))))
        val op = Renderer(s, base, on).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs(-14.0)))
        assertNotNull(op.resolvedGainDb)
        assertNull(op.notice)
        val r = Renderer(s, base.plus(op), on)
        val m = LoudnessMeter.measure(r.asSource())
        assertEquals(-14.0, m.integratedLufs, 0.1)
        assertTrue(m.truePeakDbtp <= -1.0 + 1e-3)
        // То же через prepare() с неразрешённой операцией; render до prepare — ошибка.
        val lazy = Renderer(s, base.plus(EditOp.Normalize(NormalizeTarget.Lufs(-14.0))), on)
        assertFalse(lazy.isPrepared)
        try { lazy.render(0, FloatArray(2), 1); fail() } catch (_: IllegalStateException) {}
        lazy.prepare()
        assertEquals(op, lazy.resolvedEdits.ops.last())
        assertBitEqual("prepare = resolveNormalize", renderAll(r), renderAll(lazy))
        // По пику: max|x| → −1,0 dBFS.
        val pk = Renderer(s, base, on).resolveNormalize(EditOp.Normalize(NormalizeTarget.Peak(-1.0)))
        val mp = LoudnessMeter.measure(Renderer(s, base.plus(pk), on).asSource())
        assertEquals(-1.0, mp.samplePeakDbfs, 0.01)
    }

    @Test fun normalizePeakPreventsGain() {   // пик у предела при G > 0 → 0 дБ и пояснение
        val ch = 2
        val x = sine(48_000 * 5, ch, 0.1)
        x[2 * 100_000] = 0.97f
        val s = src(x, ch)
        val op = Renderer(s, EditList(), on).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs(-14.0)))
        assertEquals(0.0, op.resolvedGainDb!!, 0.0)
        assertTrue(op.notice!!.startsWith("Нормализация не применена: в записи есть пик у предела"))
        val r = Renderer(s, EditList(listOf(op)), on)
        assertEquals(listOf(op.notice), r.notices)
        assertBitEqual("0 дБ — без изменений", x, renderAll(r))
        // Короткий файл: «На слух» недоступна → 0 дБ и текст.
        val short = Renderer(src(sine(1000, 1, 0.1), 1), EditList(), on).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs()))
        assertEquals(0.0, short.resolvedGainDb!!, 0.0)
        assertEquals(com.rassul.audioeditor.core.loudness.Normalizer.MSG_TOO_SHORT, short.notice)
    }

    @Test fun overload() {   // FR-14: +12 дБ на сигнале с пиком −6 dBFS → перегрузка
        val s = src(sine(48_000, 2, Math.pow(10.0, -6.0 / 20)), 2)
        assertTrue(Renderer(s, EditList(listOf(EditOp.Volume(null, 12.0))), on).hasOverload())
        assertFalse(Renderer(s, EditList(listOf(EditOp.Volume(null, 5.0))), on).hasOverload())
        assertFalse(Renderer(s, EditList(), on).hasOverload())
        // Перегружены ровно сэмплы выше −12 dBFS исходника.
        val x = sine(48_000, 2, Math.pow(10.0, -6.0 / 20))
        val y = renderAll(Renderer(s, EditList(listOf(EditOp.Volume(null, 12.0))), on))
        val thr = Math.pow(10.0, -12.0 / 20)
        for (i in x.indices) {
            val a = Math.abs(x[i].toDouble())
            if (Math.abs(a - thr) > 1e-4) assertEquals("i=$i", a > thr, Math.abs(y[i]) > 1f)
        }
    }

    @Test fun firstDifference() {
        val ch = 1
        val s = src(noise(10_000, ch, 1), ch)
        val base = EditList(listOf(EditOp.Volume(Range(5000, 6000), 3.0)))
        val r0 = Renderer(s, base, on)
        fun fd(op: EditOp) = Renderer(s, base.plus(op), on).firstDifference(r0)
        // timeline.md «Первый изменённый сэмпл p».
        assertEquals(3000L - 240, fd(EditOp.Delete(Range(3000, 4000))))
        assertEquals(0L, fd(EditOp.Delete(Range(0, 4000))))
        assertEquals(0L, fd(EditOp.Keep(Range(10, 4000))))
        assertEquals(4000L - 240, fd(EditOp.Keep(Range(0, 4000))))
        assertEquals(700L, fd(EditOp.Volume(Range(700, 900), -2.0)))
        assertEquals(800L, fd(EditOp.Fade(Range(800, 1900), true, FadeCurve.LINEAR)))
        assertEquals(0L, fd(EditOp.Normalize(NormalizeTarget.Peak(), 2.0)))
        assertEquals(r0.length, r0.firstDifference(Renderer(s, base, on)))
        assertEquals(0L, r0.firstDifference(null))
        assertEquals(0L, r0.firstDifference(Renderer(s, base, off)))
        // Свойство: до firstDifference рендеры совпадают бит в бит (случайные пары префиксов).
        val list = buildRandom(s, on, 77L, 25).current
        val renders = (0..25).map { Renderer(s, EditList(list.ops.subList(0, it)), on) }
        val outs = renders.map { renderAll(it) }
        val rnd = java.util.Random(3)
        repeat(40) {
            val i = rnd.nextInt(26); val j = rnd.nextInt(26)
            val p = renders[i].firstDifference(renders[j])
            assertEquals(p, renders[j].firstDifference(renders[i]))
            assertTrue(p <= minOf(renders[i].length, renders[j].length))
            for (k in 0 until (p * ch).toInt()) assertEquals("i=$i j=$j k=$k p=$p", outs[i][k].toRawBits(), outs[j][k].toRawBits())
        }
    }
}
