package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.dsp.Downmix
import com.rassul.audioeditor.core.dsp.RefData
import com.rassul.audioeditor.core.dsp.d
import com.rassul.audioeditor.core.dsp.l
import com.rassul.audioeditor.core.dsp.list
import com.rassul.audioeditor.core.dsp.obj
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Аудит FR-C5: сведение 5.1 / 7.1 / прочих раскладок в стерео (ITU-R BS.775-4 табл. 2; dsp.md П-12, K-14).
 * Эталон записан независимо — по именам каналов, а не по таблице кода.
 */
class AuditDownmixTest {
    private val k = Math.sqrt(0.5)

    private fun mix(mask: Int, frame: FloatArray): FloatArray {
        val m = Downmix.matrix(mask, frame.size)
        val out = FloatArray(m.size)
        Downmix.apply(m, frame, 1, out)
        return out
    }

    /** 5.1 (0xFC): порядок FL FR FC LFE BL BR; L' = L + 0,7071·C + 0,7071·Ls, R' аналогично, LFE отбрасывается. */
    @Test fun fivePointOneRandom() {
        val rnd = java.util.Random(51)
        repeat(2000) {
            val f = FloatArray(6) { (rnd.nextFloat() * 2 - 1) * 1.5f }
            val fl = f[0]; val fr = f[1]; val fc = f[2]; val bl = f[4]; val br = f[5] // f[3] = LFE
            val o = mix(0xFC, f)
            assertEquals((fl + k * fc + k * bl), o[0].toDouble(), 2e-7)
            assertEquals((fr + k * fc + k * br), o[1].toDouble(), 2e-7)
        }
    }

    /** 7.1 (0x18FC): FL FR FC LFE BL BR SL SR (по возрастанию бита); тыл и бок → 0,7071 на свою сторону. */
    @Test fun sevenPointOneRandom() {
        val rnd = java.util.Random(71)
        repeat(2000) {
            val f = FloatArray(8) { (rnd.nextFloat() * 2 - 1) * 1.5f }
            val o = mix(0x18FC, f)
            assertEquals(f[0] + k * f[2] + k * f[4] + k * f[6], o[0].toDouble(), 2e-7)
            assertEquals(f[1] + k * f[2] + k * f[5] + k * f[7], o[1].toDouble(), 2e-7)
        }
        // D-5 и перегрузка без обрезки (FR-14): все = 1 → 1 + 3·0,7071 = 3,1213.
        assertEquals(1.0 + 3 * k, mix(0x18FC, FloatArray(8) { 1f })[0].toDouble(), 5e-7)
        assertEquals(1.0 + 2 * k, mix(0xFC, FloatArray(6) { 1f })[1].toDouble(), 5e-7)
    }

    /** Без маски — раскладка по числу каналов (DEFAULT_MASK basic_ref); неверная маска (число битов ≠ C) — тоже. */
    @Test fun defaultsAndInvalidMask() {
        val vec = RefData.json("basic_vectors.json").obj()["default_mask_by_count"].obj()
        for ((c, m) in vec) assertEquals("C=$c", m.l().toInt(), Downmix.defaultMask(c.toInt()))
        val f = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f)
        assertEquals(mix(0xFC, f).toList(), mix(0, f).toList())
        assertEquals(mix(0xFC, f).toList(), mix(0x1C, f).toList())   // 3 бита при 6 каналах → по умолчанию
        // LCRS 0x41C: BC → 0,5 на обе стороны (П-12).
        val lcrs = mix(0x41C, floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(0.5f, lcrs[0], 0f); assertEquals(0.5f, lcrs[1], 0f)
        // Моно и стерео — без изменений, бит в бит.
        assertEquals(listOf(0.3f), mix(0x10, floatArrayOf(0.3f)).toList())
        assertEquals(listOf(0.3f, -0.7f), mix(0xC, floatArrayOf(0.3f, -0.7f)).toList())
        // LFE2 и верхние каналы отбрасываются и попадают в журнал как «неизвестные» (кроме LFE2).
        assertEquals(0, Downmix.unknownBits(0x18FC or 0x2000000))
        assertEquals(0x4000, Downmix.unknownBits(0xFC or 0x4000))
    }

    @Test fun vectorsFromBasicRef() {
        for (c in RefData.json("basic_vectors.json").obj()["downmix"].list().map { it.obj() }) {
            val fr = c["frame"].list().map { it.d().toFloat() }.toFloatArray()
            val o = mix(c["mask"].l().toInt(), fr)
            assertEquals(c["name"] as String, c["L"].d(), o[0].toDouble(), 5e-7)
            assertEquals(c["name"] as String, c["R"].d(), o[1].toDouble(), 5e-7)
        }
    }

    /** Блочное применение на многих кадрах совпадает с покадровым (индексы чередования). */
    @Test fun blockEqualsFrameByFrame() {
        val rnd = java.util.Random(5)
        val frames = 1000
        val src = FloatArray(frames * 8) { rnd.nextFloat() - 0.5f }
        val m = Downmix.matrix(0x18FC, 8)
        val dst = FloatArray(frames * 2)
        Downmix.apply(m, src, frames, dst)
        for (f in 0 until frames) {
            val one = mix(0x18FC, src.copyOfRange(f * 8, f * 8 + 8))
            assertEquals(one[0], dst[2 * f], 0f); assertEquals(one[1], dst[2 * f + 1], 0f)
        }
    }
}
