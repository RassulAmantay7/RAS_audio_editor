package com.rassul.audioeditor.core.audit.edit

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Renderer (карта сегментов, RenderMap) против независимой последовательной реализации на массиве [Naive]:
 * бит в бит (timeline.md «Бит-в-бит рендера», «Семантика списка правок»; dsp.md П-3).
 * fs = 1000 Гц, чтобы N стыка = jointMs ровно (любое N, включая 1…3).
 */
class AuditRenderTest {
    private val fs = 1000

    private fun check(tag: String, x: FloatArray, ch: Int, ops: List<EditOp>, n: Long, rng: ARng) {
        val exp = Naive.render(x, ch, ops, n)
        val r = renderer(arraySrc(x, ch, fs), ops, settingsFor(n, fs))
        assertEquals("$tag: длина", (exp.size / ch).toLong(), r.length)
        val got = renderChunks(r, rng)
        val d = firstBitDiff(exp, got)
        if (d >= 0) fail("$tag: расхождение в сэмпле $d (кадр ${d / ch}): ожидалось ${exp[d]}, получено ${got[d]}; ops=$ops")
    }

    /** 500 случайных списков (seed 20251111): Keep/Delete/Volume/Fade/Normalize, стыки, N ∈ {0,1,2,3,7,24,240}. */
    @Test fun random500BitExact() {
        val rng = ARng(20251111L)
        val ns = longArrayOf(0, 1, 2, 3, 7, 24, 240)
        repeat(500) { t ->
            val ch = 1 + rng.int(3)
            val l0 = rng.long(1, 3000).toInt()
            val x = noiseA(l0, ch, t.toLong())
            val ops = rng.list(l0.toLong(), 1 + rng.int(9))
            check("список $t (L=$l0, ch=$ch)", x, ch, ops, ns[rng.int(ns.size)], rng)
        }
    }

    /** Множитель пересекает кусок, удалённый ПОЗЖЕ; Fade частично удалён позже (индекс кривой сохраняется). */
    @Test fun multipliersAcrossLaterDeletes() {
        val rng = ARng(1)
        val x = noiseA(2000, 2, 5)
        val cases = listOf(
            listOf(EditOp.Volume(Range(100, 1500), -6.0), EditOp.Delete(Range(300, 900))),
            listOf(EditOp.Fade(Range(200, 1200), true, FadeCurve.LOGARITHMIC), EditOp.Delete(Range(150, 700))),
            listOf(EditOp.Fade(Range(200, 1200), false, FadeCurve.EQUAL_POWER), EditOp.Keep(Range(600, 1900))),
            listOf(EditOp.Fade(Range(0, 2000), true, FadeCurve.LINEAR), EditOp.Delete(Range(500, 600)), EditOp.Delete(Range(450, 520)),
                EditOp.Keep(Range(10, 1700)), EditOp.Volume(null, 3.3)),
            // Volume ПОСЛЕ Delete на диапазоне, пересекающем стык (в исходнике — два разнесённых куска).
            listOf(EditOp.Delete(Range(500, 1000)), EditOp.Volume(Range(400, 700), 6.0), EditOp.Fade(Range(450, 600), true, FadeCurve.EQUAL_POWER)),
        )
        for ((i, ops) in cases.withIndex()) for (n in longArrayOf(0, 3, 240)) check("случай $i N=$n", x, 2, ops, n, rng)
    }

    /** Повторный Delete рядом со старым стыком: рампы перемножаются (timeline.md «Наложение … перемножаются»). */
    @Test fun deleteNearOldJoint() {
        val rng = ARng(2)
        val x = noiseA(3000, 1, 9)
        for (d in -300L..300L step 7) {
            val a = (1000 + d).coerceAtLeast(1)
            val ops = listOf(EditOp.Delete(Range(1000, 1500)), EditOp.Delete(Range(a, a + 37)))
            check("d=$d", x, 1, ops, 240, rng)
        }
        // Короткие куски: N_k = min(N, ⌊len/2⌋), включая N_k = 1 (множитель 0,5 по сетке (i+0,5)/N).
        check("короткие", x, 1, listOf(EditOp.Delete(Range(1, 2998)), EditOp.Delete(Range(1, 2))), 240, rng)
        check("L=1000 [300,900)", x.copyOf(1000), 1, listOf(EditOp.Delete(Range(300, 900))), 240, rng)
    }

    /** Контрольный пример timeline.md: L = 1000, delete [300,900), N = 240 → N_L = 150, N_R = 50; r[350:] = x[950:]. */
    @Test fun shortPieceVector() {
        val x = noiseA(1000, 1, 3)
        val r = renderer(arraySrc(x, 1, 48_000), listOf(EditOp.Delete(Range(300, 900))), com.rassul.audioeditor.core.edit.RenderSettings())
        assertEquals(400, r.length)
        val y = FloatArray(400); r.render(0, y, 400)
        for (i in 0 until 150) assertEquals(x[i].toRawBits(), y[i].toRawBits())          // до зоны N_L
        assertTrue(y[150] != x[150] && y[299] != x[299])                                 // зона N_L = 150
        assertTrue(y[349] != x[949])                                                     // зона N_R = 50
        for (i in 350 until 400) assertEquals(x[i + 600].toRawBits(), y[i].toRawBits())
        // N = 0: x[:a] ++ x[b:] бит в бит.
        val r0 = renderer(arraySrc(x, 1, 48_000), listOf(EditOp.Delete(Range(300, 900))), com.rassul.audioeditor.core.edit.RenderSettings(smoothJoints = false))
        val y0 = FloatArray(400); r0.render(0, y0, 400)
        for (i in 0 until 400) assertEquals(x[if (i < 300) i else i + 600].toRawBits(), y0[i].toRawBits())
    }

    /** Порядок умножения во float32: ((x·g1)·f2)·G ≠ x·(g1·f2·G) — Renderer обязан совпасть с первым. */
    @Test fun multiplicationOrderMatters() {
        val x = noiseA(4000, 1, 77)
        val ops = listOf(EditOp.Volume(null, 3.7), EditOp.Fade(Range(0, 4000), true, FadeCurve.EQUAL_POWER),
            EditOp.Normalize(com.rassul.audioeditor.core.loudness.NormalizeTarget.Lufs(), resolvedGainDb = 1.2345))
        val r = renderer(arraySrc(x, 1, fs), ops, settingsFor(0, fs))
        val got = renderChunks(r, ARng(3))
        val seq = Naive.render(x, 1, ops, 0)
        assertEquals(-1, firstBitDiff(seq, got))
        // Предварительное перемножение даёт другие биты хотя бы где-то — тест чувствителен к порядку.
        val g1 = Naive.volume(3.7); val g3 = Naive.normGain(1.2345)
        var differs = 0
        for (i in 0 until 4000) { val pre = x[i] * (g1 * Naive.fade(FadeCurve.EQUAL_POWER, i.toLong(), 4000, true).toFloat() * g3); if (pre.toRawBits() != got[i].toRawBits()) differs++ }
        assertTrue("перемножение коэффициентов неотличимо — тест не чувствителен", differs > 0)
    }

    /** Несколько читателей одновременно над НЕпотокобезопасным источником: результат = последовательный. */
    @Test fun concurrentReaders() {
        val x = noiseA(20_000, 2, 11)
        val src = FragileSource(PcmSpec(fs, 2), x)
        val ops = listOf(EditOp.Delete(Range(3000, 5000)), EditOp.Volume(Range(1000, 9000), -4.0), EditOp.Keep(Range(500, 17_000)))
        val r = renderer(src, ops, settingsFor(24, fs))
        val exp = Naive.render(x, 2, ops, 24)
        val errors = java.util.concurrent.atomic.AtomicInteger()
        val threads = (0 until 4).map { t ->
            Thread {
                val rng = ARng(100L + t)
                repeat(40) { if (firstBitDiff(exp, renderChunks(r, rng, 4000)) >= 0) errors.incrementAndGet() }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertEquals("рендеры при параллельном чтении расходятся с эталоном", 0, errors.get())
    }

    /** Выход за длину: render с from ≥ L → 0; хвост короче запроса. */
    @Test fun edges() {
        val x = noiseA(100, 1, 1)
        val r = renderer(arraySrc(x, 1, fs), listOf(EditOp.Delete(Range(0, 10))), settingsFor(3, fs))
        assertEquals(0, r.render(90, FloatArray(10), 10))
        assertEquals(5, r.render(85, FloatArray(10), 10))
        try { renderer(arraySrc(x, 1, fs), listOf(EditOp.Delete(Range(0, 100))), settingsFor(3, fs)); fail("удаление всего файла принято") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Нельзя удалить весь файл")) }
        try { renderer(arraySrc(x, 1, fs), listOf(EditOp.Keep(Range(10, 101))), settingsFor(3, fs)); fail("выход за конец принят") }
        catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("за конец")) }
    }
}
