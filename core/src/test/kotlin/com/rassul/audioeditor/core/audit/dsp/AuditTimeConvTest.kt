package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.time.TimeConv
import com.rassul.audioeditor.core.time.TimeParse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Аудит FR-C1 (docs/math/timeline.md ф-лы 1–2, решение «м:сс.ммм»): независимая проверка через BigDecimal HALF_UP
 * и свойства кругового пути. Ловушки K-1 (half-even) и K-3 (переполнение Int).
 */
class AuditTimeConvTest {
    private val rates = intArrayOf(8000, 11025, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 44099, 7)

    private fun ok(t: String, fs: Int) = (TimeConv.parse(t, fs) as TimeParse.Ok).samples

    /** Эталон: n = round_half_up(ms·fs/1000) точно (BigDecimal), без double. */
    private fun refSamples(ms: Long, fs: Int): Long =
        BigDecimal.valueOf(ms).multiply(BigDecimal.valueOf(fs.toLong())).divide(BigDecimal.valueOf(1000))
            .setScale(0, RoundingMode.HALF_UP).toLong()

    /** Эталон показа: ms = round_half_up(n·1000/fs). */
    private fun refMs(n: Long, fs: Int): Long =
        BigDecimal.valueOf(n).multiply(BigDecimal.valueOf(1000)).divide(BigDecimal.valueOf(fs.toLong()), 30, RoundingMode.DOWN)
            .setScale(0, RoundingMode.HALF_UP).toLong()

    private fun text(ms: Long): String {
        val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val f = ms % 1000
        return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, f) else "%d:%02d.%03d".format(m, s, f)
    }

    @Test fun parseEqualsExactHalfUp() {
        val rnd = java.util.Random(1)
        for (fs in rates) for (k in 0 until 3000) {
            val ms = when (k % 3) { 0 -> rnd.nextInt(10_000).toLong(); 1 -> (rnd.nextLong() ushr 1) % 86_400_000L; else -> (rnd.nextLong() ushr 1) % 1_000_000_000_000L }
            assertEquals("ms=$ms fs=$fs", refSamples(ms, fs), ok(text(ms), fs))
        }
    }

    /** Точные половины (ловушка double, timeline.md ф-ла 1): 7717,5 → 7718 и т. п. */
    @Test fun exactHalvesRoundUp() {
        assertEquals(7718L, ok("0:00.175", 44_100))
        assertEquals(7718L, ok("0:00.700", 11_025))
        assertEquals(7718L, ok("0:00.350", 22_050))
        for (fs in intArrayOf(44_100, 11_025, 22_050, 88_200)) for (ms in 1L..2000L) {
            val num = ms * fs
            if (num % 1000 == 500L) assertEquals("ms=$ms fs=$fs", num / 1000 + 1, ok(text(ms), fs))
        }
    }

    @Test fun formatEqualsExactHalfUp() {
        val rnd = java.util.Random(2)
        for (fs in rates) for (k in 0 until 3000) {
            val n = when (k % 3) { 0 -> rnd.nextInt(100_000).toLong(); 1 -> (rnd.nextLong() ushr 1) % (86_400L * fs); else -> (rnd.nextLong() ushr 1) % 16_588_799_808L }
            assertEquals("n=$n fs=$fs", text(refMs(n, fs)), TimeConv.format(n, fs))
        }
        // 24 ч при 192 кГц: 2·n·1000 = 3,3·10¹³ — в Long (K-3).
        assertEquals("23:59:59.999", TimeConv.format(16_588_799_808L, 192_000))
        assertEquals("24:00:00.000", TimeConv.format(16_588_800_000L, 192_000))
        // Половина миллисекунды вверх: 24 сэмпла @48 кГц = 0,5 мс → 1 мс.
        assertEquals("0:00.001", TimeConv.format(24, 48_000))
        assertEquals("0:00.000", TimeConv.format(23, 48_000))
    }

    /** Свойство: format(parse(s)) = s для канонической строки при fs > 1000 (≥ 1 сэмпла на мс). */
    @Test fun formatOfParseIsIdentity() {
        val rnd = java.util.Random(3)
        for (fs in rates.filter { it > 1000 }) for (k in 0 until 2000) {
            val ms = (rnd.nextLong() ushr 1) % 90_000_000L
            val s = text(ms)
            assertEquals("fs=$fs", s, TimeConv.format(ok(s, fs), fs))
        }
    }

    /**
     * Свойство parse(format(n)) = n — выполняется только для n, лежащих на сетке мс (n = N(ms));
     * для прочих круговой путь теряет не больше ⌈fs/2000⌉ сэмплов (timeline.md, «Ввод конца…»).
     */
    @Test fun parseOfFormatRoundTrip() {
        val rnd = java.util.Random(4)
        for (fs in rates.filter { it > 1000 }) {
            val slack = (fs + 1999L) / 2000L
            for (k in 0 until 3000) {
                val ms = (rnd.nextLong() ushr 1) % 90_000_000L
                val n = TimeConv.msToSamplesExact(ms, fs)
                assertEquals("на сетке: ms=$ms fs=$fs", n, ok(TimeConv.format(n, fs), fs))
                val m = (rnd.nextLong() ushr 1) % (90_000L * fs)
                val back = ok(TimeConv.format(m, fs), fs)
                assertTrue("m=$m back=$back fs=$fs", Math.abs(back - m) <= slack)
            }
        }
    }

    @Test fun inputVariantsAndErrors() {
        assertEquals(ok("0:05.000", 48_000), ok("5", 48_000))
        assertEquals(ok("0:05.000", 48_000), ok("00:05,000", 48_000))
        assertEquals(ok("1:00:00.000", 48_000), ok("60:00", 48_000))       // ведущее поле не ограничено
        assertEquals(ok("0:01.500", 48_000), ok("1.5", 48_000))             // «.5» = 500 мс
        assertEquals(ok("0:01.050", 48_000), ok("1.05", 48_000))
        for (bad in listOf("1:60.000", "1:00:60", "1:23.4567", "-1.0", "−1.0", "1::2", "abc", "1.2.3", " : ", "", "1:2:3:4", "1e3", "+1", "1:-1"))
            assertTrue("«$bad»", TimeConv.parse(bad, 48_000) is TimeParse.Error)
        // Переполнение: огромные поля не роняют разбор (Math.*Exact / MAX_MS).
        assertTrue(TimeConv.parse("999999999999:59:59.999", 192_000) is TimeParse.Error)
        assertTrue(TimeConv.parse("9223372036854775807", 192_000) is TimeParse.Error)
    }

    @Test fun parseWithinClamp() {
        val fs = 44_100; val len = 44_099L         // показ «0:01.000» → 44 100 > L на 1 ≤ ⌈44100/2000⌉ = 23
        assertEquals(len, (TimeConv.parseWithin(TimeConv.format(len, fs), fs, len) as TimeParse.Ok).samples)
        assertTrue(TimeConv.parseWithin("0:01.100", fs, len) is TimeParse.Error)
    }

    @Test fun msToSamplesDouble() {
        assertEquals(221L, TimeConv.msToSamples(5.0, 44_100))
        assertEquals(7718L, TimeConv.msToSamples(175.0, 44_100))
        assertEquals(11L, TimeConv.msToSamples(0.25, 44_100))   // 11,025 → 11
        assertEquals(12L, TimeConv.msToSamples(0.5, 23_000))    // ровно 11,5 → 12 (half-up, не к чётному)
        assertEquals(10L, TimeConv.msToSamples(0.5, 19_000))    // 9,5 → 10
    }
}
