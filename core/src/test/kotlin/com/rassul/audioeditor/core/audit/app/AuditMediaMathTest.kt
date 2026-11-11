package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.dsp.Downmix
import com.rassul.audioeditor.core.pipeline.MediaMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random

/** Перевод PTS/времени и маски каналов в склейке :app (timeline.md ф. 1, 7; dsp.md FR-C5). Аудит T21. */
class AuditMediaMathTest {
    /** Эталон: round_half_up(us·fs/10⁶) в BigDecimal (для us ≥ 0). */
    private fun ref(us: Long, fs: Int): Long =
        BigDecimal.valueOf(us).multiply(BigDecimal.valueOf(fs.toLong())).divide(BigDecimal.valueOf(1_000_000L))
            .setScale(0, RoundingMode.HALF_UP).toLong()

    @Test fun usToFramesControlExamples() {
        assertEquals(4410L, MediaMath.usToFrames(100_000, 44_100))          // fr7_gap_fill: 100 мс @44,1 кГц
        assertEquals(593L, MediaMath.usToFrames(12_345, 48_000))           // 592,56 → 593
        assertEquals(7718L, MediaMath.usToFrames(175_000, 44_100))         // 7717,5 → 7718 (ловушка double: 7717)
        assertEquals(48L, MediaMath.usToFrames(1_000, 48_000))
        assertEquals(86_400_000L, MediaMath.usToFrames(1_800_000_000L, 48_000))   // 30 мин @48 кГц — без Int-переполнения
        assertEquals(345_600_000L, MediaMath.usToFrames(1_800_000_000L, 192_000))
        assertEquals(16_588_800_000L, MediaMath.usToFrames(86_400_000_000L, 192_000)) // 24 ч @192 кГц
        // Шаг PTS AAC (RemuxCopier.aacFramesPerPacket): 23 219 мкс @44,1 кГц → 1023,96 → 1024; HE 46 439 → 2048.
        assertEquals(1024L, MediaMath.usToFrames(23_219, 44_100))
        assertEquals(2048L, MediaMath.usToFrames(46_439, 44_100))
    }

    @Test fun usToFramesRandomVsBigDecimal() {
        val r = Random(21)
        val rates = intArrayOf(8000, 11025, 22050, 44100, 48000, 96000, 192000)
        repeat(200_000) {
            val fs = rates[r.nextInt(rates.size)]
            val us = (r.nextLong() ushr 1) % 90_000_000_000L
            assertEquals("us=$us fs=$fs", ref(us, fs), MediaMath.usToFrames(us, fs))
        }
        // Ровно половины: us·fs ≡ 500 000 (mod 10⁶) → вверх.
        for (fs in rates) for (k in 0L until 2000L) {
            val num = k * 1_000_000L + 500_000L
            if (num % fs == 0L) assertEquals(k + 1, MediaMath.usToFrames(num / fs, fs))
        }
    }

    @Test fun usToFramesNegativeIsFloorHalfUp() {
        // Отрицательные PTS (перекрытие, edit list): ⌊x + ½⌋, а не усечение к нулю.
        assertEquals(-48L, MediaMath.usToFrames(-1_000, 48_000))
        assertEquals(0L, MediaMath.usToFrames(-10, 48_000))       // −0,48 → 0
        assertEquals(-1L, MediaMath.usToFrames(-11, 48_000))      // −0,528 → −1
        assertEquals(0L, MediaMath.usToFrames(-500_000, 1))       // ровно −½ → 0 (вверх)
    }

    @Test fun nsToFramesOpusCsd1() {
        // Android csd-1 Opus: codec delay в нс. 312 сэмплов @48 кГц = 6 500 000 нс; 80 мс = 3840.
        assertEquals(312L, MediaMath.nsToFrames(6_500_000L, 48_000))
        assertEquals(3840L, MediaMath.nsToFrames(80_000_000L, 48_000))
        assertEquals(312L, MediaMath.nsToFrames(6_510_416L, 48_000))   // 312,49 → 312
        assertEquals(313L, MediaMath.nsToFrames(6_510_417L, 48_000))   // 312,50002 → 313
    }

    @Test fun framesToUsMonotonicFloor() {
        var prev = -1L
        for (f in 0L..200_000L) { val us = MediaMath.framesToUs(f, 44_100); assertTrue(us >= prev); prev = us
            assertEquals(f * 1_000_000L / 44_100, us) }
        assertEquals(1_800_000_000L, MediaMath.framesToUs(86_400_000L, 48_000))
    }

    @Test fun waveMaskToAndroid() {
        assertEquals(Downmix.CHANNEL_OUT_STEREO, MediaMath.waveMaskToAndroid(0x3))
        assertEquals(Downmix.CHANNEL_OUT_MONO, MediaMath.waveMaskToAndroid(0x4))
        assertEquals(Downmix.CHANNEL_OUT_5POINT1, MediaMath.waveMaskToAndroid(0x3F))           // FL FR FC LFE BL BR
        assertEquals(0x183C, MediaMath.waveMaskToAndroid(0x60F))                                 // 5.1 side
        assertEquals(Downmix.CHANNEL_OUT_7POINT1_SURROUND, MediaMath.waveMaskToAndroid(0x63F))  // 7.1
        assertEquals(Downmix.CHANNEL_OUT_SURROUND, MediaMath.waveMaskToAndroid(0x107))          // LCRS: FL FR FC BC
    }

    /** Ошибка до T21: маска WAV 5.1 (0x3F) шла в Downmix как маска Android → FL/FR терялись, LFE уходил вправо. */
    @Test fun wav51DownmixUsesConvertedMask() {
        val s = Math.sqrt(0.5)
        val good = Downmix.matrix(MediaMath.waveMaskToAndroid(0x3F), 6)
        // Каналы WAV 5.1: FL FR FC LFE BL BR → L' = FL + √½·FC + √½·BL; R' = FR + √½·FC + √½·BR (BS.775, dsp.md П-12).
        assertArrayEquals(doubleArrayOf(1.0, 0.0, s, 0.0, s, 0.0), good[0], 1e-12)
        assertArrayEquals(doubleArrayOf(0.0, 1.0, s, 0.0, 0.0, s), good[1], 1e-12)
        val bad = Downmix.matrix(0x3F, 6)
        assertEquals(0.0, bad[0][0], 0.0)   // FL пропал бы
        assertEquals(1.0, bad[1][3], 0.0)   // LFE ушёл бы в R
    }
}
