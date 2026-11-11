package com.rassul.audioeditor.core.time

import com.rassul.audioeditor.core.dsp.RefData
import com.rassul.audioeditor.core.dsp.l
import com.rassul.audioeditor.core.dsp.list
import com.rassul.audioeditor.core.dsp.obj
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-C1, FR-15: контрольные примеры docs/math/timeline.md (векторы timeline_vectors.json). */
class TimeConvTest {
    private val ref = RefData.json("timeline_vectors.json").obj()

    private fun ok(text: String, fs: Int): Long {
        val r = TimeConv.parse(text, fs)
        assertTrue("$text → $r", r is TimeParse.Ok)
        return (r as TimeParse.Ok).samples
    }

    @Test fun parseVectors() {
        val cases = ref["fr_c1_parse"].list()
        assertTrue(cases.size >= 12)
        for (c in cases.map { it.obj() }) {
            val text = c["text"] as String
            val fs = c["fs"].l().toInt()
            assertEquals("ms $text", c["ms"].l(), (TimeConv.parseMs(text) as TimeConv.MsParse.Ok).ms)
            assertEquals("n $text @$fs", c["n"].l(), ok(text, fs))
        }
    }

    @Test fun parseControlExamples() {
        assertEquals(3_680_410L, ok("1:23.456", 44_100))       // FR-C1
        assertEquals(4_005_888L, ok("1:23.456", 48_000))       // FR-C1
        assertEquals(7718L, ok("0:00.175", 44_100))            // наивный double дал бы 7717
        assertEquals(16_588_799_808L, ok("23:59:59.999", 192_000))
        assertEquals(500L, ok("0.5", 1000))                    // дробь 1 цифра = 500 мс
        assertEquals(178_723_200L, ok("1:02:03,4", 48_000))    // запятая допустима
    }

    @Test fun parseInvalidVectors() {
        for (t in ref["fr_c1_parse_invalid"].list().map { it as String }) {
            val r = TimeConv.parse(t, 48_000)
            assertTrue("«$t» должно быть ошибкой: $r", r is TimeParse.Error)
            assertTrue((r as TimeParse.Error).message.isNotBlank())
        }
    }

    @Test fun errorMessagesAreSpecific() {
        assertEquals(TimeConv.MSG_SECONDS, (TimeConv.parse("1:60.000", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_MINUTES, (TimeConv.parse("1:60:00.000", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_FRACTION, (TimeConv.parse("1:23.4567", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_NEGATIVE, (TimeConv.parse("-1.0", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_EMPTY, (TimeConv.parse("  ", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_FORMAT, (TimeConv.parse("1::2", 48_000) as TimeParse.Error).message)
        assertEquals(TimeConv.MSG_TOO_BIG, (TimeConv.parse("99999999999999:00", 48_000) as TimeParse.Error).message)
    }

    /**
     * Вектор fr_c1_display записан в формате «мм:сс.ммм»; по решению главной сессии (timeline.md, конец)
     * до часа показывается «м:сс.ммм» — ведущий ноль минут убирается.
     */
    @Test fun displayVectors() {
        for (c in ref["fr_c1_display"].list().map { it.obj() }) {
            val n = c["n"].l(); val fs = c["fs"].l().toInt()
            var expect = c["text"] as String
            if (Regex("^0[0-9]:[0-9]{2}\\.[0-9]{3}$").matches(expect)) expect = expect.substring(1)
            assertEquals("n=$n fs=$fs", expect, TimeConv.format(n, fs))
        }
    }

    @Test fun displayControlExamples() {
        assertEquals("0:01.000", TimeConv.format(44_099, 44_100))          // округление ДО разбиения на поля
        assertEquals("1:00:00.000", TimeConv.format(158_759_999, 44_100))
        assertEquals("1:23.456", TimeConv.format(3_680_410, 44_100))
        assertEquals("12:34.567", TimeConv.formatMs(754_567))
        assertEquals("0:00.981", TimeConv.formatMs(981))
        assertEquals("10:00:00.000", TimeConv.formatMs(36_000_000))
    }

    @Test fun msToSamples() {
        assertEquals(240L, TimeConv.msToSamples(5.0, 48_000))      // FR-C3
        assertEquals(221L, TimeConv.msToSamples(5.0, 44_100))      // 220,5 → 221
        assertEquals(11L, TimeConv.msToSamples(1.0, 11_025))
        assertEquals(9600L, TimeConv.msToSamples(50.0, 192_000))
        assertEquals(7718L, TimeConv.msToSamples(175.0, 44_100))
        assertEquals(132_300L, TimeConv.msToSamples(3000.0, 44_100))
        assertEquals(2L, TimeConv.msToSamples(1.5, 1000))          // 1,5 → 2 (half-up)
        assertEquals(7718L, TimeConv.msToSamples(700.0, 11_025))   // 7717,5 → 7718
        assertEquals(22L, TimeConv.msToSamples(0.5, 44_100))       // 22,05
        assertEquals(8L, TimeConv.msToSamples(0.35, 22_050))       // 7,7175
    }

    @Test fun parseWithinClampsRoundedDuration() {
        val fs = 44_100
        val len = 44_099L                               // показывается как 0:01.000
        val shown = TimeConv.format(len, fs)
        assertEquals(TimeParse.Ok(len), TimeConv.parseWithin(shown, fs, len))
        assertTrue(TimeConv.parseWithin("0:01.100", fs, len) is TimeParse.Error)
        assertEquals(TimeParse.Ok(22_050), TimeConv.parseWithin("0.5", fs, len))
        assertEquals("Конец должен быть позже начала", TimeConv.rangeError(10, 10, 100, fs))
        assertEquals(null, TimeConv.rangeError(10, 11, 100, fs))
        assertTrue(TimeConv.rangeError(0, 101, 100, fs)!!.startsWith("Больше длительности"))
    }

    @Test fun roundTripErrorBounded() {
        // n → «м:сс.ммм» → n теряет не более ⌈fs/2000⌉ сэмплов (timeline.md).
        for (fs in intArrayOf(8000, 11_025, 44_100, 48_000, 192_000)) {
            var n = 0L
            while (n < 10L * fs) {
                val back = ok(TimeConv.format(n, fs), fs)
                assertTrue("fs=$fs n=$n back=$back", Math.abs(back - n) <= (fs + 1999) / 2000)
                n += 997
            }
        }
    }
}
