package com.rassul.audioeditor.core.loudness

import com.rassul.audioeditor.core.dsp.RefData
import com.rassul.audioeditor.core.dsp.d
import com.rassul.audioeditor.core.dsp.l
import com.rassul.audioeditor.core.dsp.list
import com.rassul.audioeditor.core.dsp.obj
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-C4: K-фильтр, Tech 3341, true peak, крайние случаи (docs/math/dsp.md K-1…K-3, L-1…L-12, TP-1…TP-7). */
class LoudnessTest {

    // ---------------- генераторы сигналов (как в tools/ref/loudness_ref.py) ----------------
    companion object {
        fun sine(fs: Int, f: Double, ampDbfs: Double, dur: Double, ch: Int = 2): FloatArray {
            val n = Math.round(dur * fs).toInt()
            val a = Math.pow(10.0, ampDbfs / 20)
            val out = FloatArray(n * ch)
            for (i in 0 until n) {
                val v = (a * Math.sin(2 * Math.PI * f * (i.toDouble() / fs))).toFloat()
                for (c in 0 until ch) out[i * ch + c] = v
            }
            return out
        }

        fun segments(fs: Int, f: Double, spec: List<Pair<Double, Double>>, ch: Int = 2): FloatArray {
            val parts = spec.map { (a, d) -> sine(fs, f, a, d, ch) }
            val out = FloatArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) { System.arraycopy(p, 0, out, o, p.size); o += p.size }
            return out
        }

        /** Tech 3341 тесты 15–19, подъём и спад 10 мс (dsp.md ловушка K-13; loudness_ref.tp_sine). */
        fun tpSine(fs: Int, frac: Double, amp: Double, phaseDeg: Double, dur: Double = 1.0, rampS: Double = 0.01): FloatArray {
            val n = (fs * dur).toInt()
            val s = DoubleArray(n) { amp * Math.sin(2 * Math.PI * frac * it + Math.toRadians(phaseDeg)) }
            val nr = (rampS * fs).toInt()
            if (nr > 0) {
                val r = DoubleArray(nr) { 0.5 * (1 - Math.cos(Math.PI * (it + 0.5) / nr)) }
                for (i in 0 until nr) { s[i] *= r[i]; s[n - nr + i] *= r[nr - 1 - i] }
            }
            return FloatArray(2 * n) { s[it / 2].toFloat() }
        }

        fun measure(x: FloatArray, fs: Int, ch: Int, chunk: Int = 65_536): LoudnessResult {
            val m = LoudnessMeter(fs, ch)
            var i = 0
            val n = x.size / ch
            while (i < n) {
                val k = minOf(chunk, n - i)
                m.process(if (i == 0 && k == n) x else x.copyOfRange(i * ch, (i + k) * ch), k)
                i += k
            }
            return m.result()
        }
    }

    /** Генераторы для случаев loudness_cases.json (имя → (сигнал, каналы)). Тесты 20–23 не воспроизводятся (dsp.md TP-8). */
    private fun generator(name: String): Pair<FloatArray, Int>? {
        val fs48 = 48_000
        return when {
            name.startsWith("T3341-1 sine1k") -> sine(fs48, 1000.0, -23.0, 20.0) to 2
            name.startsWith("T3341-2 ") -> sine(fs48, 1000.0, -33.0, 20.0) to 2
            name.startsWith("T3341-3 ") -> segments(fs48, 1000.0, listOf(-36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0)) to 2
            name.startsWith("T3341-4 ") -> segments(fs48, 1000.0, listOf(-72.0 to 10.0, -36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0, -72.0 to 10.0)) to 2
            name.startsWith("T3341-5 ") -> segments(fs48, 1000.0, listOf(-26.0 to 20.0, -20.0 to 20.1, -26.0 to 20.0)) to 2
            name.startsWith("mono sine") -> {
                val m = Regex("""mono sine (\d+)Hz 0dBFS 10s @(\d+)""").find(name)!!.groupValues
                sine(m[2].toInt(), m[1].toDouble(), 0.0, 10.0, 1) to 1
            }
            name.startsWith("T3341-1 at ") -> {
                val fs = Regex("""at (\d+) Hz""").find(name)!!.groupValues[1].toInt()
                sine(fs, 1000.0, -23.0, 20.0) to 2
            }
            name.startsWith("T3341-15 ") -> tpSine(fs48, 0.25, 0.5, 0.0) to 2
            name.startsWith("T3341-16 ") -> tpSine(fs48, 0.25, 0.5, 45.0) to 2
            name.startsWith("T3341-17 ") -> tpSine(fs48, 1 / 6.0, 0.5, 60.0) to 2
            name.startsWith("T3341-18 ") -> tpSine(fs48, 0.125, 0.5, 67.5) to 2
            name.startsWith("T3341-19 ") -> tpSine(fs48, 0.25, 1.41, 45.0) to 2
            name.startsWith("onset (no ramp)") -> tpSine(fs48, 0.125, 0.5, 67.5, rampS = 0.0) to 2
            name.startsWith("TP fs/4 0.5 45deg @") -> tpSine(name.substringAfter('@').toInt(), 0.25, 0.5, 45.0) to 2
            name.startsWith("399.98 ms") -> sine(fs48, 1000.0, -23.0, 19_199.0 / fs48) to 2
            name.startsWith("exactly 400 ms") -> sine(fs48, 1000.0, -23.0, 0.4) to 2
            name.startsWith("digital silence") -> FloatArray(2 * 5 * fs48) to 2
            name.startsWith("sine -80 dBFS") -> sine(fs48, 1000.0, -80.0, 5.0) to 2
            else -> null
        }
    }

    // ---------------- K-фильтр ----------------
    @Test fun kFilterVsCsv() {          // K-1, K-2, K-3
        val rows = RefData.csv("kfilter_coeffs.csv")
        assertEquals(14, rows.size)
        for (r in rows) {
            val fs = r.getValue("fs").toInt()
            val c = KFilter.coefficients(fs)
            val got = doubleArrayOf(c.s1b0, c.s1b1, c.s1b2, c.s1a1, c.s1a2, c.s2a1, c.s2a2)
            val keys = listOf("s1_b0", "s1_b1", "s1_b2", "s1_a1", "s1_a2", "s2_a1", "s2_a2")
            for (i in keys.indices) assertEquals("fs=$fs ${keys[i]}", r.getValue(keys[i]).toDouble(), got[i], 1e-12)
            assertEquals("fs=$fs H(997)", r.getValue("gain_997Hz_dB").toDouble(), KFilter.responseDb(c, 997.0, fs), 1e-9)
        }
        // 48 кГц: таблицы 1–2 BS.1770-5 (≤ 1e-6).
        val c = KFilter.coefficients(48_000)
        val table = doubleArrayOf(1.53512485958697, -2.69169618940638, 1.19839281085285, -1.69065929318241, 0.73248077421585, -1.99004745483398, 0.99007225036621)
        val got = doubleArrayOf(c.s1b0, c.s1b1, c.s1b2, c.s1a1, c.s1a2, c.s2a1, c.s2a2)
        for (i in table.indices) assertEquals(table[i], got[i], 1e-6)
        // 44,1 кГц (K-2), 1e-12
        val k = KFilter.coefficients(44_100)
        assertEquals(1.53084123005035, k.s1b0, 1e-12); assertEquals(-2.65097999515473, k.s1b1, 1e-12)
        assertEquals(1.16907907992159, k.s1b2, 1e-12); assertEquals(-1.66365511325602, k.s1a1, 1e-12)
        assertEquals(0.71259542807323, k.s1a2, 1e-12); assertEquals(-1.98916967362980, k.s2a1, 1e-12)
        assertEquals(0.98919903578704, k.s2a2, 1e-12)
        assertEquals(0.69101, KFilter.responseDb(c, 997.0, 48_000), 1e-4)
    }

    @Test fun truePeakTapsAreAnnex2() {
        // Все 48 значений кратны 2^-13; симметрия фаз; сумма каждой фазы ≈ 1 (±0,03).
        for (h in listOf(TruePeakFilter.PHASE0, TruePeakFilter.PHASE1)) {
            assertEquals(12, h.size)
            for (v in h) assertEquals(0.0, v * 8192 - Math.rint(v * 8192), 0.0)
            assertEquals(1.0, h.sum(), 0.03) // фаза 0: 0,973 — свойство фильтра Annex 2 (поэтому TP = max(…, |x|), П-6)
        }
        assertEquals(4, TruePeakFilter.phasesFor(48_000).size)
        assertEquals(2, TruePeakFilter.phasesFor(96_000).size)
    }

    // ---------------- случаи loudness_cases.json ----------------
    @Test fun loudnessCasesVsReference() {
        val cases = RefData.json("loudness_cases.json").list().map { it.obj() }
        var checked = 0
        for (c in cases) {
            val name = c["name"] as String
            val (x, ch) = generator(name) ?: continue
            val fs = c["fs"].l().toInt()
            assertEquals(name, c["n"].l(), (x.size / ch).toLong())
            val r = measure(x, fs, ch)
            val iRef = c["I_ref"]
            if (iRef == null) {
                assertTrue("$name: I=${r.integratedLufs} (ожидалось «нет»)", r.integratedLufs.isNaN() || r.integratedLufs == Double.NEGATIVE_INFINITY)
            } else {
                // dsp.md L-1…L-5: ±0,001; L-6…L-10: ±0,002.
                val tol = if (name.startsWith("T3341-") && !name.contains(" at ")) 0.001 else 0.002
                assertEquals(name, iRef.d(), r.integratedLufs, tol)
                // L-12: сверка с pyloudnorm filter_class="DeMan" ≤ 0,05 LU (значения из эталона).
                c["pyln_DeMan"]?.let { assertEquals("$name vs pyloudnorm DeMan", it.d(), r.integratedLufs, 0.05) }
            }
            val tpRef = c["TP_ref_dBTP"].d()
            if (tpRef.isFinite()) assertEquals("$name TP", tpRef, r.truePeakDbtp, 0.005)
            else assertEquals(Double.NEGATIVE_INFINITY, r.truePeakDbtp, 0.0)
            val spRef = c["SP_dBFS"].d()
            if (spRef.isFinite()) assertEquals("$name SP", spRef, r.samplePeakDbfs, 1e-4)
            // Нормы Tech 3341 (+0,2 / −0,4 dB для TP).
            val eTp = c["expect_TP"]
            if (eTp != null) {
                val dlt = r.truePeakDbtp - eTp.d()
                if (!name.startsWith("onset")) assertTrue("$name TP норма: Δ=$dlt", dlt <= 0.2 && dlt >= -0.4)
            }
            checked++
        }
        assertTrue("проверено $checked случаев", checked == 28)
    }

    @Test fun normativeTech3341() {     // L-1, L-2, L-6
        assertEquals(-23.0, measure(sine(48_000, 1000.0, -23.0, 20.0), 48_000, 2).integratedLufs, 0.1)
        assertEquals(-33.0, measure(sine(48_000, 1000.0, -33.0, 20.0), 48_000, 2).integratedLufs, 0.1)
        assertEquals(-3.01, measure(sine(48_000, 997.0, 0.0, 10.0, 1), 48_000, 1).integratedLufs, 0.01)
        assertEquals(-3.0103, measure(sine(48_000, 997.0, 0.0, 10.0, 1), 48_000, 1).integratedLufs, 0.002)
        assertEquals(-3.0075, measure(sine(44_100, 997.0, 0.0, 10.0, 1), 44_100, 1).integratedLufs, 0.002)
    }

    @Test fun edgeCases() {             // L-9, L-10, L-11
        val m1 = LoudnessMeter(48_000, 2); val s1 = sine(48_000, 1000.0, -23.0, 19_199.0 / 48_000); m1.process(s1, s1.size / 2)
        assertEquals(0, m1.blocks); assertTrue(m1.result().integratedLufs.isNaN())
        val m2 = LoudnessMeter(48_000, 2); val s2 = sine(48_000, 1000.0, -23.0, 0.4); m2.process(s2, s2.size / 2)
        assertEquals(1, m2.blocks); assertEquals(-22.9936, m2.result().integratedLufs, 0.002)
        val m3 = LoudnessMeter(48_000, 2); m3.process(FloatArray(2 * 240_000), 240_000)
        assertEquals(47, m3.blocks)
        m3.result().let {
            assertEquals(Double.NEGATIVE_INFINITY, it.integratedLufs, 0.0)
            assertEquals(Double.NEGATIVE_INFINITY, it.truePeakDbtp, 0.0)
            assertEquals(Double.NEGATIVE_INFINITY, it.samplePeakDbfs, 0.0)
            assertEquals(240_000L, it.durationFrames)
        }
        // П-4: fs = 11 025 (шаг 1102,5) — число блоков max(0, ⌊10N/fs⌋ − 3), только полные.
        for (n in intArrayOf(4409, 4410, 5512, 5513, 11_025, 22_051)) {
            val m = LoudnessMeter(11_025, 1); m.process(FloatArray(n) { 0.1f }, n)
            var j = 0; while ((j + 4L) * 11_025 / 10 <= n) j++
            assertEquals("N=$n", j, m.blocks)
            assertEquals(LoudnessMeter.hasCompleteBlock(n.toLong(), 11_025), j > 0)
        }
        assertTrue(LoudnessMeter.hasCompleteBlock(19_200, 48_000)); assertTrue(!LoudnessMeter.hasCompleteBlock(19_199, 48_000))
    }

    @Test fun chunkingAndDenormals() {
        val x = segments(44_100, 440.0, listOf(-6.0 to 3.0, -200.0 to 3.0, -30.0 to 2.0))
        val whole = measure(x, 44_100, 2, Int.MAX_VALUE)
        for (chunk in intArrayOf(1, 7, 1000, 4411)) {
            val r = measure(x, 44_100, 2, chunk)
            assertEquals(whole.integratedLufs, r.integratedLufs, 1e-9)
            assertEquals(whole.truePeakDbtp, r.truePeakDbtp, 0.0)
            assertEquals(whole.samplePeakDbfs, r.samplePeakDbfs, 0.0)
        }
    }

    @Test fun truePeakSkipIsExact() {
        val rnd = java.util.Random(5)
        // «Музыкоподобный» сигнал: шум с огибающей и редкими выбросами.
        val n = 200_000
        val x = FloatArray(2 * n) { val t = it / 2; (0.3 * Math.sin(t * 0.001) * (rnd.nextGaussian() * 0.2) + if (t % 50_000 == 777) 0.8 else 0.0).toFloat() }
        for (fs in intArrayOf(44_100, 96_000)) {
            val a = LoudnessMeter(fs, 2); a.process(x, n)
            val b = LoudnessMeter(fs, 2); b.tpSkipEnabled = false; b.process(x, n)
            assertEquals(b.result().truePeakDbtp, a.result().truePeakDbtp, 0.0)
        }
    }

    @Test fun measureFromSource() {
        val x = sine(48_000, 1000.0, -23.0, 20.0)
        val r = LoudnessMeter.measure(ArrayPcmSource(PcmSpec(48_000, 2), x))
        assertEquals(-22.9933, r.integratedLufs, 0.001)
        assertEquals(960_000L, r.durationFrames)
    }

    @Test fun linearityAfterGain() {    // N-6
        val x = sine(48_000, 1000.0, -23.0, 20.0)
        val r0 = measure(x, 48_000, 2)
        val g = Math.pow(10.0, 6.0 / 20).toFloat()
        val r1 = measure(FloatArray(x.size) { x[it] * g }, 48_000, 2)
        assertEquals(6.0, r1.integratedLufs - r0.integratedLufs, 0.001)
        assertEquals(6.0, r1.truePeakDbtp - r0.truePeakDbtp, 0.001)
    }
}
