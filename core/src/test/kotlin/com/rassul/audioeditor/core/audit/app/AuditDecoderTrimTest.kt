package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.pipeline.DecoderDropCalibration
import com.rassul.audioeditor.core.pipeline.DecoderTrim
import com.rassul.audioeditor.core.pipeline.LengthTolerance
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

    /**
     * Аудит T21 п. 3: поправка хвоста декодера. MP3 (C2SoftMp3Dec, [предположение по AOSP]): декодер отбрасывает 529
     * в начале и дописывает 529 нулей в конце. Калибровка (проход 1 SelfTest без поправок) → head 529, tail −529;
     * проход 2 с обеими поправками → длина и импульс ± 0. Без хвоста поведение прежнее.
     */
    @Test fun decoderTailCorrection() {
        // plan: P' = P − X_t; null и 0 — без изменений; отрицательное прижимается к 0.
        assertEquals(1027L + 529, DecoderTrim.plan(1105, 1027, 44_100, 44_100, 529, false, -529).padding)
        assertEquals(1027L, DecoderTrim.plan(1105, 1027, 44_100, 44_100, 529, false, null).padding)
        val z = DecoderTrim.plan(1105, 1027, 44_100, 44_100, 529, false, 0)
        assertEquals(1027L, z.padding); assertTrue(z.notes.isEmpty())
        assertEquals(0L, DecoderTrim.plan(1105, 100, 44_100, 44_100, 0, false, 300).padding)
        // Сквозная модель: калибровка → профиль → декодирование.
        val samples = listOf(Triple(1027L, 88_200L, 22_050L), Triple(600L, 96_000L, 24_000L)).map { (pad, l, i) ->
            val o = AppSim.decode(1105, pad, l, i, 529, -529, calibrated = 0L, wholeDelayByDefault = false, calibratedTail = 0L)
            DecoderDropCalibration.Sample(i - o.impulse, l - o.length, 1105)
        }
        val r = DecoderDropCalibration.infer(samples) as DecoderDropCalibration.Result.Absolute
        assertEquals(529L, r.head); assertEquals(-529L, r.tail)
        val ok = AppSim.decode(1105, 1027, 88_200, 22_050, 529, -529, calibrated = r.head, wholeDelayByDefault = false, calibratedTail = r.tail)
        assertEquals(88_200L, ok.length); assertEquals(22_050L, ok.impulse)
        // Без поправки хвоста — прежнее +529.
        val old = AppSim.decode(1105, 1027, 88_200, 22_050, 529, -529, calibrated = r.head, wholeDelayByDefault = false)
        assertEquals(88_200L + 529, old.length)
        // Декодер без изменения хвоста (tail = 0) — длина точная, как раньше.
        val aac = AppSim.decode(1024, 980, 45_100, 11_000, 0, 0, calibrated = 0L, wholeDelayByDefault = false, calibratedTail = 0L)
        assertEquals(45_100L, aac.length); assertEquals(11_000L, aac.impulse)
    }

    @Test fun randomTailCorrectionExact() {
        val r = Random(21)
        repeat(3000) {
            val delay = r.nextInt(4000).toLong(); val pad = r.nextInt(2000).toLong()
            val l = 1000L + r.nextInt(200_000); val imp = r.nextInt(l.toInt()).toLong()
            val xh = r.nextInt(delay.toInt() + 1).toLong()
            val xt = (r.nextInt(pad.toInt() + 1001) - 1000).toLong()     // −1000 (дописывает) … pad (обрезает ≤ хвоста)
            val o = AppSim.decode(delay, pad, l, imp, xh, xt, calibrated = xh, wholeDelayByDefault = false, block = 1 + r.nextInt(5000), calibratedTail = xt)
            assertEquals("D=$delay P=$pad Xh=$xh Xt=$xt", l, o.length); assertEquals(imp, o.impulse)
        }
    }

    /** Аудит T21 п. 9: запасной допуск Opus — длительность последнего пакета по TOC (RFC 6716 §3.1). */
    @Test fun opusToleranceByLastPacket() {
        assertEquals(960, LengthTolerance.opusFrame(byteArrayOf((19 shl 3).toByte())))            // CELT 20 мс, c=0
        assertEquals(2880, LengthTolerance.opusFrame(byteArrayOf((3 shl 3).toByte())))            // SILK 60 мс
        assertEquals(1920, LengthTolerance.opusFrame(byteArrayOf(((19 shl 3) or 1).toByte())))    // 2 × 20 мс
        assertEquals(960, LengthTolerance.opusFrame(null as ByteArray?)); assertEquals(960, LengthTolerance.opusFrame(ByteArray(0)))
        assertEquals(2880, LengthTolerance.opusFrame(2880)); assertEquals(960, LengthTolerance.opusFrame(0))
        // Расхождение 2000 при пакетах 60 мс — предупреждение, а не ошибка.
        assertEquals(LengthTolerance.Verdict.WARN, LengthTolerance.lossy(50_000, 48_000, LengthTolerance.opusFrame(2880)))
    }

    /**
     * Отчёт с устройства (c2.android.mp3.decoder): LAME delay 1105, padding 551, L = 88 200 (44,1 кГц) и padding 815,
     * L = 96 000 (48 кГц). Проход 1 (drops = 0, без поправки хвоста): длина 88 200 точно, импульс 21 521 вместо 22 050.
     * С drops = 529 без поправки хвоста: длина 88 729 (+529). ⇒ декодер отбрасывает 529 в начале и дописывает 529 в конце.
     * Знак: tail = X_t = −529 («изменение хвоста»; < 0 — дописывает), тример режет хвост P − X_t = P + 529.
     */
    @Test fun mp3DeviceNumbers() {
        val p1 = AppSim.decode(1105, 551, 88_200, 22_050, 529, -529, calibrated = 0L, wholeDelayByDefault = false,
            metaRate = 44_100, outRate = 44_100, block = 1152, calibratedTail = 0L)
        assertEquals(88_200L, p1.length); assertEquals(21_521L, p1.impulse)                       // как на телефоне
        val p1b = AppSim.decode(1105, 815, 96_000, 24_000, 529, -529, calibrated = 0L, wholeDelayByDefault = false, block = 1152, calibratedTail = 0L)
        val r = DecoderDropCalibration.infer(listOf(
            DecoderDropCalibration.Sample(22_050 - p1.impulse, 88_200 - p1.length, 1105),
            DecoderDropCalibration.Sample(24_000 - p1b.impulse, 96_000 - p1b.length, 1105)))
        assertEquals(DecoderDropCalibration.Result.Absolute(529, -529), r)
        val noTail = AppSim.decode(1105, 551, 88_200, 22_050, 529, -529, calibrated = 529, wholeDelayByDefault = false,
            metaRate = 44_100, outRate = 44_100, block = 1152)
        assertEquals(88_729L, noTail.length); assertEquals(22_050L, noTail.impulse)               // как на телефоне
        assertEquals(551L + 529, DecoderTrim.plan(1105, 551, 44_100, 44_100, 529, false, -529).padding)
        for ((pad, l, i, fs) in listOf(listOf(551L, 88_200L, 22_050L, 44_100L), listOf(815L, 96_000L, 24_000L, 48_000L))) {
            val o = AppSim.decode(1105, pad, l, i, 529, -529, calibrated = 529, wholeDelayByDefault = false,
                metaRate = fs.toInt(), outRate = fs.toInt(), block = 1152, calibratedTail = -529)
            assertEquals(l, o.length); assertEquals(i, o.impulse)
        }
    }

    /**
     * AAC (наблюдение с устройства): декодер с elst «сам отбрасывает 1024» (FR-7: перекрытие PTS 1024). Профиль drops = 1024:
     * head = max(0, D − drops). Файл без elst (D = 0, MKV/ADTS): если декодер ничего не отбрасывает — head 0, потерь нет;
     * Apple D = 2112, декодер отбрасывает 1024 → head 1088, итог точный.
     */
    @Test fun aacProfileDrops1024() {
        val withElst = AppSim.decode(1024, 980, 88_200, 22_050, 1024, 0, calibrated = 1024, wholeDelayByDefault = false)
        assertEquals(88_200L, withElst.length); assertEquals(22_050L, withElst.impulse)
        val noElst = AppSim.decode(0, 0, 88_200 + 1024 + 980, 22_050, 0, 0, calibrated = 1024, wholeDelayByDefault = false)
        assertEquals(88_200L + 1024 + 980, noElst.length); assertEquals(22_050L, noElst.impulse)
        val apple = AppSim.decode(2112, 1000, 88_200, 22_050, 1024, 0, calibrated = 1024, wholeDelayByDefault = false)
        assertEquals(88_200L, apple.length); assertEquals(22_050L, apple.impulse)
    }
}
