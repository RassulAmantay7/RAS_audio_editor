package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.pipeline.DecoderTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * FR-26 (timeline.md «риск двойной обрезки»): параметры GaplessTrimmer в MediaDecoder.makeTrimmer и сквозная модель
 * «декодер → GaplessTrimmer → Holdback». Контроль — длина ± 0 и позиция импульса ± 0. Аудит T21.
 */
class AuditDecoderTrimTest {
    @Test fun planBasics() {
        // AAC-LC ffmpeg: delay 1024, хвост 980 (46 кадров·1024 − 1024 − 45 100), 44,1 кГц.
        val a = DecoderTrim.plan(1024, 980, 44_100, 44_100, null, false)
        assertEquals(1024L, a.delay); assertEquals(980L, a.padding); assertEquals(0L, a.decoderDrops)
        // HE-AAC: mdhd 22 050, выход 44 100 → ×2.
        val he = DecoderTrim.plan(1024, 100, 22_050, 44_100, null, false)
        assertEquals(2048L, he.delay); assertEquals(200L, he.padding); assertTrue(he.notes.any { "×2" in it })
        // Opus без калибровки: декодер сам отбрасывает весь pre-skip → GaplessTrimmer режет 0 в начале.
        val o = DecoderTrim.plan(312, 588, 48_000, 48_000, null, true)
        assertEquals(312L, o.decoderDrops)
        // Калибровка «0» перекрывает допущение.
        assertEquals(0L, DecoderTrim.plan(312, 588, 48_000, 48_000, 0L, true).decoderDrops)
        // Неправдоподобное (> 1 с или < 0) → 0 с записью в журнал. MP3: padding = enc_pad − 529 < 0 при enc_pad < 529.
        val bad = DecoderTrim.plan(60_000, -100, 48_000, 48_000, null, false)
        assertEquals(0L, bad.delay); assertEquals(0L, bad.padding); assertEquals(2, bad.notes.size)
        // Нет метаданных: metaRate = 0 — без пересчёта и без записей.
        val none = DecoderTrim.plan(0, 0, 0, 44_100, null, false)
        assertEquals(0L, none.delay); assertTrue(none.notes.isEmpty())
        // Не кратно (48 000 / 44 100) — не пересчитывать.
        assertEquals(1024L, DecoderTrim.plan(1024, 0, 44_100, 48_000, null, false).delay)
    }

    @Test fun endToEndNoDoubleTrim() {
        // timeline.md контрольные: AAC L = 45 100, delay 1024, 46 кадров → хвост 980; Opus 48 100, pre-skip 312.
        val aac = AppSim.decode(1024, 980, 45_100, 11_000, headDrop = 0, tailDrop = 0, calibrated = null, wholeDelayByDefault = false, metaRate = 44_100, outRate = 44_100)
        assertEquals(45_100L, aac.length); assertEquals(11_000L, aac.impulse)
        // Opus: декодер (C2SoftOpusDec) сам отбрасывает pre-skip 312 — допущение по умолчанию → без двойной обрезки.
        val op = AppSim.decode(312, 588, 48_100, 24_000, headDrop = 312, tailDrop = 0, calibrated = null, wholeDelayByDefault = true)
        assertEquals(48_100L, op.length); assertEquals(24_000L, op.impulse)
        // Если бы декодер отбросил сам, а мы ещё раз — минус 312 (это и есть «двойная обрезка»).
        val dbl = AppSim.decode(312, 588, 48_100, 24_000, headDrop = 312, tailDrop = 0, calibrated = 0L, wholeDelayByDefault = true)
        assertEquals(48_100L - 312, dbl.length); assertEquals(24_000L - 312, dbl.impulse)
        // MP3 (LAME 576 + 529 = 1105, pad 1556 − 529), декодер ничего не отбрасывает: длина 45 100 (ф. 8).
        val mp3 = AppSim.decode(1105, 1027, 45_100, 5_000, 0, 0, null, false, 44_100, 44_100, 1152)
        assertEquals(45_100L, mp3.length); assertEquals(5_000L, mp3.impulse)
    }

    @Test fun randomCalibratedHeadAlwaysExact() {
        val r = Random(26)
        repeat(3000) {
            val delay = r.nextInt(4000).toLong(); val pad = r.nextInt(2000).toLong()
            val l = 1000L + r.nextInt(200_000); val imp = r.nextInt(l.toInt()).toLong()
            val x = r.nextInt(delay.toInt() + 1).toLong()                    // декодер отбрасывает X ≤ D
            val block = 1 + r.nextInt(5000)
            val o = AppSim.decode(delay, pad, l, imp, x, 0, calibrated = x, wholeDelayByDefault = false, block = block)
            assertEquals("D=$delay X=$x", l, o.length); assertEquals(imp, o.impulse)
        }
    }
}
