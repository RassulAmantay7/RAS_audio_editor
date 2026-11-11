package com.rassul.audioeditor.core.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-32: контрольные примеры fr32_loop (docs/math/timeline.md). Источник: сэмпл = его номер. */
class LoopCursorTest {
    private fun cursor(len: Int): LoopCursor {
        val s = src(FloatArray(len) { it.toFloat() }, 1)
        return LoopCursor(Renderer(s, EditList(), RenderSettings())).apply { repeat = true }
    }

    private fun stream(c: LoopCursor, total: Int, block: Int): FloatArray {
        val out = FloatArray(total)
        val buf = FloatArray(block)
        var w = 0
        while (w < total) {
            val n = c.fill(buf, minOf(block, total - w))
            System.arraycopy(buf, 0, out, w, n)
            w += n
        }
        return out
    }

    @Test fun mainLoop() {   // [48 000; 96 000), 5 проходов → 240 000 кадров, out[i] = 48 000 + i mod 48 000
        for (block in intArrayOf(1, 7, 4096, 48_000, 100_000)) {
            val c = cursor(200_000)
            c.selection = Range(48_000, 96_000)
            val out = stream(c, 240_000, block)
            for (i in out.indices) if (out[i] != (48_000 + i % 48_000).toFloat()) throw AssertionError("block=$block i=$i ${out[i]}")
            assertEquals(240_000L, c.framesWritten)
        }
    }

    @Test fun smallLoops() {
        val c1 = cursor(100); c1.selection = Range(10, 11)
        assertEquals(listOf(10f, 10f, 10f, 10f, 10f), stream(c1, 5, 3).toList())
        val c2 = cursor(100); c2.selection = Range(0, 3)
        assertEquals(listOf(0f, 1f, 2f, 0f, 1f, 2f, 0f, 1f), stream(c2, 8, 5).toList())
    }

    @Test fun selectionChanges() {
        val c = cursor(5000); c.selection = Range(0, 2000)
        stream(c, 1000, 500)
        c.selection = Range(0, 600)                        // конец := 600 после 1000 кадров
        val o = stream(c, 600, 500)
        for (i in 0 until 600) assertEquals(i.toFloat(), o[i], 0f)
        val d = cursor(5000); d.selection = Range(0, 2000)
        stream(d, 500, 500)
        d.selection = Range(800, 1000)
        val p = stream(d, 1000, 500)
        for (i in 0 until 1000) assertEquals((800 + i % 200).toFloat(), p[i], 0f)
    }

    @Test fun cursorOutsideAndNoSelection() {
        val c = cursor(1000); c.seek(900); c.selection = Range(100, 200)
        assertEquals(100f, stream(c, 1, 1)[0], 0f)         // курсор вне выделения → начало
        val w = cursor(10)                                  // без выделения — весь файл по кругу
        assertEquals((0 until 25).map { (it % 10).toFloat() }, stream(w, 25, 4).toList())
        val lin = cursor(10).apply { repeat = false }       // без повтора — до конца
        assertEquals(10, lin.fill(FloatArray(64), 64))
        assertEquals(0, lin.fill(FloatArray(64), 64))
    }

    @Test fun cursorFromTimestamp() {   // 60-й проход, середина: 72 000
        val c = cursor(200_000); c.selection = Range(48_000, 96_000)
        stream(c, 61 * 48_000, 960)
        val pos = c.cursorAt(59L * 48_000, 1_000_000_000L, 1_500_000_000L)
        assertEquals(72_000L, pos)
        assertEquals(48_000L, c.renderPositionAt(60L * 48_000))
        assertEquals(95_999L, c.renderPositionAt(61L * 48_000 - 1))
        assertNull(c.renderPositionAt(61L * 48_000))
    }
}
