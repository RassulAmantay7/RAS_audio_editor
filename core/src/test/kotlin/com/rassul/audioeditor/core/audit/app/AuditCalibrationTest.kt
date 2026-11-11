package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.pipeline.DecoderDropCalibration
import com.rassul.audioeditor.core.pipeline.DecoderDropCalibration.Result
import com.rassul.audioeditor.core.pipeline.DecoderDropCalibration.Sample
import com.rassul.audioeditor.core.pipeline.EncoderDelay
import com.rassul.audioeditor.core.pipeline.GaplessTrimmer
import com.rassul.audioeditor.core.pipeline.LengthTolerance
import com.rassul.audioeditor.core.pipeline.Nfr7
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * SelfTest (T19): вывод «декодер сам отбрасывает» по двум оценкам, задержка энкодера по сдвигу импульса,
 * допуски FR-33 и экстраполяция NFR-7. Проход 1 моделируется AppSim с decoderDropsOverride = 0. Аудит T21.
 */
class AuditCalibrationTest {
    /** Проход 1 SelfTest: X_imp = I − p₀, X_len = L − L₀ при калибровке 0 (GaplessTrimmer режет всю D и хвост). */
    private fun pass1(delay: Long, pad: Long, l: Long, i: Long, xh: Long, xt: Long, jitter: Long = 0): Sample {
        val o = AppSim.decode(delay, pad, l, i, xh, xt, calibrated = 0L, wholeDelayByDefault = false)
        return Sample(i - (o.impulse + jitter), l - o.length, delay)
    }

    @Test fun signsOfBothEstimates() {
        val s = pass1(1024, 980, 88_200, 22_050, xh = 700, xt = 0)
        assertEquals(700L, s.xImp); assertEquals(700L, s.xLen)
        assertEquals(Result.Absolute(700, 0), DecoderDropCalibration.infer(listOf(s)))
    }

    @Test fun lengthPreferredWithinTolerance() {
        // Импульс сжатого сигнала ±2 → длина (точное целое) выигрывает.
        val a = pass1(1024, 980, 88_200, 22_050, 500, 0, jitter = 2)
        val b = pass1(1024, 980, 96_000, 24_000, 500, 0, jitter = -1)
        assertEquals(Result.Absolute(500, 0), DecoderDropCalibration.infer(listOf(a, b)))
        // +2 / −2: разброс импульсов 4 > tol, но оба в ± 2 от точной длины → принимается (до T21 — «разный сдвиг»).
        val c = pass1(1024, 980, 88_200, 22_050, 500, 0, jitter = -2)
        assertEquals(Result.Absolute(500, 0), DecoderDropCalibration.infer(listOf(a, c)))
    }

    @Test fun inconsistentAndImplausible() {
        assertTrue(DecoderDropCalibration.infer(listOf(Sample(100, 100, 1024), Sample(104, 104, 1024))) is Result.Inconsistent)
        assertEquals(Result.Implausible(-5), DecoderDropCalibration.infer(listOf(Sample(-5, -5, 0))))
        assertEquals(Result.Implausible(9000), DecoderDropCalibration.infer(listOf(Sample(9000, 9000, 9000))))
    }

    /**
     * Opus: декодер отбрасывает весь pre-skip, а pre-skip у файлов разный (312 у libopus, 3840/5760 после быстрой
     * обрезки). До T21 SelfTest сравнивал X с задержкой ПОСЛЕДНЕГО файла и проверял разброс X — здесь вышло бы
     * «сдвиг на разных файлах разный». Теперь — по файлу.
     */
    @Test fun opusWholePreSkipPerFile() {
        val a = pass1(312, 588, 96_000, 24_000, xh = 312, xt = 0, jitter = 1)
        val b = pass1(3840, 100, 96_000, 24_000, xh = 3840, xt = 0)
        assertEquals(Result.WholeDelay(0), DecoderDropCalibration.infer(listOf(a, b), allowWholeDelay = true))
        assertTrue(DecoderDropCalibration.infer(listOf(a, b), allowWholeDelay = false) is Result.Inconsistent)
        // Если Opus-декодер НЕ отбрасывает pre-skip — абсолютное 0.
        val c = pass1(312, 588, 96_000, 24_000, xh = 0, xt = 0)
        assertEquals(Result.Absolute(0, 0), DecoderDropCalibration.infer(listOf(c), allowWholeDelay = true))
        // ±2 от pre-skip (оценка по импульсу) тоже «весь pre-skip» — раньше сравнивалось x == delay строго.
        assertEquals(Result.WholeDelay(0), DecoderDropCalibration.infer(listOf(Sample(310, 312, 312)), allowWholeDelay = true))
    }

    /**
     * Декодер, который отбрасывает 529 в начале и дописывает 529 нулей в конце (модель C2SoftMp3Dec, [предположение по AOSP]):
     * X_imp = 529, X_len = 0 → head = 529, tail = −529. GaplessTrimmer хвост не калибрует → длина на 529 больше
     * (ВАЖНО в отчёте T21); с поправкой padding − tail длина точная.
     */
    @Test fun mp3HeadDropTailAppend() {
        val s1 = pass1(1105, 1027, 88_200, 22_050, xh = 529, xt = -529, jitter = 1)
        val s2 = pass1(1105, 600, 96_000, 24_000, xh = 529, xt = -529)
        val r = DecoderDropCalibration.infer(listOf(s1, s2))
        assertEquals(Result.Absolute(529, -529), r)
        val now = AppSim.decode(1105, 1027, 88_200, 22_050, 529, -529, calibrated = 529, wholeDelayByDefault = false)
        assertEquals(22_050L, now.impulse); assertEquals(88_200L + 529, now.length)
        val fixed = AppSim.decode(1105, 1027, 88_200, 22_050, 529, -529, calibrated = 529, wholeDelayByDefault = false, metaPad = 1027 - (-529))
        assertEquals(88_200L, fixed.length); assertEquals(22_050L, fixed.impulse)
    }

    @Test fun randomCalibrationRecoversHead() {
        val r = Random(19)
        repeat(2000) {
            val xh = r.nextInt(3000).toLong()
            val d1 = xh + r.nextInt(2000); val d2 = xh + r.nextInt(2000)
            val s = listOf(pass1(d1, r.nextInt(900).toLong(), 88_200, 22_050, xh, 0, (r.nextInt(5) - 2).toLong()),
                           pass1(d2, r.nextInt(900).toLong(), 96_000, 24_000, xh, 0, (r.nextInt(5) - 2).toLong()))
            val res = DecoderDropCalibration.infer(s) as Result.Absolute
            assertEquals(xh, res.head); assertEquals(0L, res.tail)
            // Проход 2 с откалиброванным числом: длина ± 0, импульс ± 0.
            val o = AppSim.decode(d1, 300, 88_200, 22_050, xh, 0, calibrated = res.head, wholeDelayByDefault = false)
            assertEquals(88_200L, o.length); assertEquals(22_050L, o.impulse)
        }
    }

    // ---------------------------------------------------------------- энкодер (FR-25)

    @Test fun encoderDelayPriorityNoSummation() {
        assertEquals(2048 to EncoderDelay.Source.OVERRIDE, EncoderDelay.choose(2048, 1600, 1024, 1024))
        assertEquals(1600 to EncoderDelay.Source.PROFILE, EncoderDelay.choose(null, 1600, 1024, 1024))
        assertEquals(2112 to EncoderDelay.Source.ENCODER, EncoderDelay.choose(null, null, 2112, 1024))
        assertEquals(312 to EncoderDelay.Source.ASSUMED, EncoderDelay.choose(null, null, null, 312))
        assertEquals(0 to EncoderDelay.Source.PROFILE, EncoderDelay.choose(null, 0, 2112, 1024))   // 0 из профиля — валидно
    }

    @Test fun encoderTrueDelaySign() {
        // Истинная задержка 2048, в elst записано 1024 → GaplessTrimmer режет 1024, импульс опаздывает на 1024.
        val i = 24_000L; val dTrue = 2048L; val used = 1024
        val t = GaplessTrimmer(used.toLong(), 0, 0, null)
        val p = (dTrue + i) - t.dropAtStart(100_000)
        assertEquals(i + 1024, p)
        assertEquals(dTrue, EncoderDelay.trueDelay(used, p, i))
        // Обратный знак: записано больше истинной → импульс раньше → D меньше.
        assertEquals(1024L, EncoderDelay.trueDelay(2112, i - 1088, i))
    }

    // ---------------------------------------------------------------- FR-33, NFR-7

    @Test fun lossyTolerance() {
        assertEquals(LengthTolerance.Verdict.OK, LengthTolerance.lossy(45_100, 45_100, 1024))
        assertEquals(LengthTolerance.Verdict.OK, LengthTolerance.lossy(45_099, 45_100, 1024))
        assertEquals(LengthTolerance.Verdict.OK, LengthTolerance.lossy(45_101, 45_100, 1024))
        assertEquals(LengthTolerance.Verdict.WARN, LengthTolerance.lossy(45_102, 45_100, 1024))
        assertEquals(LengthTolerance.Verdict.WARN, LengthTolerance.lossy(46_124, 45_100, 1024))   // +1 кадр: MediaMuxer без elst
        assertEquals(LengthTolerance.Verdict.FAIL, LengthTolerance.lossy(46_125, 45_100, 1024))
        assertEquals(LengthTolerance.Verdict.WARN, LengthTolerance.lossy(48_100 - 960, 48_100, 960))
        assertEquals(LengthTolerance.Verdict.FAIL, LengthTolerance.lossy(48_100 - 961, 48_100, 960))
        // 30 мин @48 кГц — Long, без переполнения.
        assertEquals(LengthTolerance.Verdict.OK, LengthTolerance.lossy(86_400_001L, 86_400_000L, 1024))
    }

    @Test fun nfr7Extrapolation() {
        assertEquals(60.0, Nfr7.extrapolate(1.0, 30), 1e-12)
        assertEquals(27.0, Nfr7.extrapolate(0.45, 30), 1e-12)   // 0,45 с за 30 с → 27 с за 30 мин
        assertEquals(1800.0, Nfr7.extrapolate(30.0, 30), 1e-12)
    }
}
