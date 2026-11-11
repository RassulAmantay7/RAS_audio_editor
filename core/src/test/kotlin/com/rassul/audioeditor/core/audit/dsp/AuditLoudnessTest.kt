package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.loudness.LoudnessResult
import com.rassul.audioeditor.core.loudness.TruePeakFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит FR-C4 (ITU-R BS.1770-5 Annex 1–2; EBU Tech 3341 v4; docs/math/dsp.md «FR-C4», П-1, П-4, П-6, K-4–K-7, K-12, K-13).
 */
class AuditLoudnessTest {
    companion object {
        fun measure(x: FloatArray, fs: Int, ch: Int, rnd: java.util.Random? = null, chunk: Int = 65_536, skip: Boolean = true): LoudnessResult {
            val m = LoudnessMeter(fs, ch)
            m.tpSkipEnabled = skip
            val n = x.size / ch
            var i = 0
            while (i < n) {
                val k = minOf(n - i, if (rnd == null) chunk else 1 + rnd.nextInt(chunk))
                m.process(x.copyOfRange(i * ch, (i + k) * ch), k)
                i += k
            }
            return m.result()
        }

        fun sine(fs: Int, f: Double, dbfs: Double, n: Int, ch: Int = 2): FloatArray {
            val a = Math.pow(10.0, dbfs / 20)
            return FloatArray(n * ch) { (a * Math.sin(2 * Math.PI * f * (it / ch) / fs)).toFloat() }
        }

        /** Tech 3341 тесты 15–19: синус frac·fs, амплитуда amp, фаза φ, подъём/спад 10 мс (как loudness_ref.tp_sine). */
        fun tpSine(fs: Int, frac: Double, amp: Double, phaseDeg: Double, ramp: Boolean = true): FloatArray {
            val n = fs
            val s = DoubleArray(n) { amp * Math.sin(2 * Math.PI * frac * it + Math.toRadians(phaseDeg)) }
            val nr = if (ramp) (0.01 * fs).toInt() else 0
            for (i in 0 until nr) {
                val r = 0.5 * (1 - Math.cos(Math.PI * (i + 0.5) / nr))
                s[i] *= r; s[n - nr + i] *= 0.5 * (1 - Math.cos(Math.PI * (nr - 1 - i + 0.5) / nr))
            }
            return FloatArray(2 * n) { s[it / 2].toFloat() }
        }

        /** «Музыкоподобный» сигнал: окрашенный шум с огибающей + тон, с паузами (для гейтов). */
        fun music(fs: Int, n: Int, ch: Int, seed: Long): FloatArray {
            val rnd = java.util.Random(seed)
            var lp = 0.0
            val x = FloatArray(n * ch)
            for (i in 0 until n) {
                lp = 0.95 * lp + 0.05 * rnd.nextGaussian()
                val t = i.toDouble() / fs
                val env = if ((t * 1.3).toInt() % 4 == 3) 0.003 else 0.15 + 0.8 * Math.abs(Math.sin(t * 2.1))
                for (c in 0 until ch) x[i * ch + c] = (env * (0.25 * Math.sin(2 * Math.PI * (300.0 + 170 * c) * t) + 2 * lp + 0.05 * rnd.nextGaussian())).toFloat()
            }
            return x
        }
    }

    // ---------------- Annex 2: коэффициенты ----------------

    /**
     * 48 коэффициентов true peak: совпадают с tools/ref/loudness_ref.py (TP_PHASE0/1), кратны 2⁻¹³, 48-отводный
     * фильтр h[4k+φ] симметричен, фазы 2/3 — зеркала 1/0. Суммы: фаза 0 и 3 = 1,0015869140625; фаза 1 и 2 = 0,9730224609375
     * (именно фазы 1–2 дают 0,973, а не фаза 0). Сверка с PDF Annex 2 недоступна из среды (itu.int 403); значения
     * совпали с 8 независимыми открытыми реализациями (GitHub code search, напр. audionuma/libtruepeak COEF_0_48k / COEF_1_48k).
     */
    @Test fun annex2Coefficients() {
        val dir = AuditPy.refDir
        if (dir != null) {
            val src = java.io.File(dir, "loudness_ref.py").readText()
            fun arr(name: String) = Regex("$name = \\[([^\\]]*)\\]").find(src)!!.groupValues[1]
                .split(',').map { it.trim().toDouble() }.toDoubleArray()
            assertTrue(arr("TP_PHASE0").contentEquals(TruePeakFilter.PHASE0))
            assertTrue(arr("TP_PHASE1").contentEquals(TruePeakFilter.PHASE1))
        }
        val ph = arrayOf(TruePeakFilter.PHASE0, TruePeakFilter.PHASE1, TruePeakFilter.PHASE2, TruePeakFilter.PHASE3)
        for (h in ph) { assertEquals(12, h.size); for (v in h) assertEquals(0.0, v * 8192 - Math.rint(v * 8192), 0.0) }
        val full = DoubleArray(48) { ph[it % 4][it / 4] }
        for (i in 0 until 48) assertEquals(full[47 - i], full[i], 0.0)
        assertEquals(1.0015869140625, TruePeakFilter.PHASE0.sum(), 1e-15)
        assertEquals(0.9730224609375, TruePeakFilter.PHASE1.sum(), 1e-15)
        assertEquals(0.9721679687500, TruePeakFilter.PHASE0[6], 0.0)
        assertEquals(4, TruePeakFilter.phasesFor(95_999).size)
        val p2 = TruePeakFilter.phasesFor(96_000)
        assertTrue(p2.size == 2 && p2[0] === TruePeakFilter.PHASE0 && p2[1].contentEquals(TruePeakFilter.PHASE1.reversedArray()))
    }

    /** TP = max(пик фильтра, max|x|) (П-6, K-12): одиночный импульс 1,0 даёт фильтром 0,972, итог — ровно 0 dBTP. */
    @Test fun truePeakNotBelowSamplePeak() {
        for (fs in intArrayOf(44_100, 48_000, 96_000, 192_000)) {
            val x = FloatArray(2 * fs); x[2 * 1000] = 1f; x[2 * 1000 + 1] = -0.5f
            val r = measure(x, fs, 2)
            assertEquals("fs=$fs", 0.0, r.truePeakDbtp, 0.0)
            assertEquals(0.0, r.samplePeakDbfs, 0.0)
            // Импульс в самом последнем сэмпле: хвост из 11 нулей (K-12) — всё равно 0 dBTP, а фаза 1 даёт 0,78.
            val y = FloatArray(2 * 4800); y[y.size - 2] = 1f
            assertEquals(0.0, measure(y, fs, 2).truePeakDbtp, 0.0)
        }
        // Ступень DC 1,0 с нулями до и после: выход фазы — частичные суммы h (начало) и хвостовые суммы (конец, K-12);
        // установившееся значение фазы 0 = 1,0015869. Итог = max(|частичных сумм|, сумм, 1).
        for (fs in intArrayOf(48_000, 96_000)) {
            var expect = 1.0
            for (h in TruePeakFilter.phasesFor(fs)) {
                var pre = 0.0; var suf = 0.0
                for (k in 0 until 12) { pre += h[k]; suf += h[11 - k]; expect = maxOf(expect, Math.abs(pre), Math.abs(suf)) }
            }
            val dc = FloatArray(2 * 4800) { 1f }
            assertEquals("fs=$fs", 20 * Math.log10(expect), measure(dc, fs, 2).truePeakDbtp, 1e-12)
        }
        // Отрицательный пик учитывается по модулю.
        val neg = FloatArray(2 * 4800); neg[100] = -0.25f
        assertEquals(20 * Math.log10(0.25), measure(neg, 48_000, 2).truePeakDbtp, 1e-12)
    }

    /** Tech 3341 v4 тесты 15–19 (48 кГц): эталон dsp.md TP-1…TP-5 ± 0,005 дБ и норма +0,2/−0,4 дБ. */
    @Test fun tech3341TruePeak15to19() {
        data class C(val name: String, val frac: Double, val amp: Double, val ph: Double, val ref: Double, val norm: Double)
        val cases = listOf(
            C("15", 0.25, 0.5, 0.0, -6.021, -6.0), C("16", 0.25, 0.5, 45.0, -5.976, -6.0),
            C("17", 1 / 6.0, 0.5, 60.0, -6.316, -6.0), C("18", 0.125, 0.5, 67.5, -6.028, -6.0),
            C("19", 0.25, 1.41, 45.0, 3.029, 3.0),
        )
        for (c in cases) {
            val r = measure(tpSine(48_000, c.frac, c.amp, c.ph), 48_000, 2)
            assertEquals("тест ${c.name}", c.ref, r.truePeakDbtp, 0.005)
            assertTrue("тест ${c.name} норма", r.truePeakDbtp <= c.norm + 0.2 && r.truePeakDbtp >= c.norm - 0.4)
        }
        assertEquals(-9.031, measure(tpSine(48_000, 0.25, 0.5, 45.0), 48_000, 2).samplePeakDbfs, 0.005)
        // TP-6: тест 16 при 44,1 / 96 (×2) / 192 (×2) кГц; TP-7: резкий старт.
        for (fs in intArrayOf(44_100, 96_000, 192_000)) assertEquals("fs=$fs", -5.976, measure(tpSine(fs, 0.25, 0.5, 45.0), fs, 2).truePeakDbtp, 0.005)
        assertEquals(-5.318, measure(tpSine(48_000, 0.125, 0.5, 67.5, ramp = false), 48_000, 2).truePeakDbtp, 0.005)
    }

    /**
     * Ускорение true peak (пропуск блока при Σ|h_φ|·max|x окна| ≤ найденного пика) не меняет результат — бит в бит,
     * на сигналах, где граница почти достигается: знаковый узор под фазу 1 (|y| = A·Σ|h₁|), пики на стыках кусков.
     */
    @Test fun truePeakSkipIsExact() {
        val rnd = java.util.Random(77)
        val h1 = TruePeakFilter.PHASE1
        val bound1 = h1.sumOf { Math.abs(it) }
        for (fs in intArrayOf(48_000, 96_000)) {
            val n = fs
            val cases = ArrayList<FloatArray>()
            // (а) импульс 0,9, затем узор амплитуды A с A·Σ|h| чуть выше 0,9 — максимум только у межсэмплового пика.
            for (aRel in doubleArrayOf(0.999, 1.0, 1.0005, 1.01, 1.2)) {
                val a = 0.9 / bound1 * aRel
                val x = FloatArray(2 * n)
                x[2 * 10] = 0.9f
                for (rep in 0 until 6) {
                    val at = rnd.nextInt(n - 40) + 20
                    for (k in 0 until 12) x[2 * (at + k) + rep % 2] = (a * Math.signum(h1[11 - k])).toFloat()
                }
                cases.add(x)
            }
            // (б) узор, пересекающий границы сегментов 100 мс (там граница внутренних кусков).
            val y = FloatArray(2 * n)
            for (s in 1 until 9) { val at = s * fs / 10 - 6; for (k in 0 until 12) y[2 * (at + k)] = (0.3 * Math.signum(h1[11 - k]) * (1 + s * 0.01)).toFloat() }
            cases.add(y)
            // (в) музыка с затуханием и всплесками.
            cases.add(music(fs, n, 2, 5))
            for ((ci, x) in cases.withIndex()) {
                val ref = measure(x, fs, 2, skip = false, chunk = Int.MAX_VALUE).truePeakDbtp
                for (rep in 0 until 3) {
                    val got = measure(x, fs, 2, rnd, chunk = 3000, skip = true).truePeakDbtp
                    assertEquals("fs=$fs случай $ci", ref, got, 0.0)
                }
            }
        }
    }

    /** Живая сверка с эталоном tools/ref/loudness_ref (float64): I и TP на шумоподобных сигналах разных fs, порезка кусками. */
    @Test fun matchesLoudnessRefLive() {
        val rnd = java.util.Random(3)
        for ((fs, ch) in listOf(8_000 to 1, 11_025 to 2, 44_100 to 2, 48_000 to 1, 96_000 to 2, 192_000 to 2, 22_050 to 1)) {
            val n = (3.37 * fs).toInt()          // не кратно 100 мс (П-4: только полные блоки)
            val x = music(fs, n, ch, fs.toLong())
            val ours = measure(x, fs, ch, rnd, 5000)
            val out = AuditPy.run(
                "import numpy as np, math\nfrom loudness_ref import integrated_loudness, true_peak_lin, block_bounds\n" +
                    "x=np.fromfile(W+'/x.f32',dtype='<f4').astype(np.float64).reshape(-1,$ch)\n" +
                    "I,J,_=integrated_loudness(x,$fs)\ntp=true_peak_lin(x,$fs)\n" +
                    "print(repr(float('nan') if I is None else I), J, repr(20*math.log10(tp)), repr(20*math.log10(np.max(np.abs(x)))))",
                prepare = { AuditPy.writeF32(java.io.File(it, "x.f32"), x) },
            )
            val v = AuditPy.numbers(out)
            assertEquals("fs=$fs I", v[0], ours.integratedLufs, 1e-6)
            assertEquals("fs=$fs J", v[1].toInt(), maxOf(0, (10L * n / fs - 3).toInt()))
            assertEquals("fs=$fs TP", v[2], ours.truePeakDbtp, 1e-9)
            assertEquals("fs=$fs SP", v[3], ours.samplePeakDbfs, 1e-12)
        }
    }

    /** L-12 / П-1: pyloudnorm.Meter(fs, filter_class="DeMan") на Tech 3341 #1–#5 и музыке; норма ≤ 0,05 LU, факт ≤ 2·10⁻³. */
    @Test fun matchesPyloudnormDeMan() {
        val fs = 48_000
        fun seg(vararg p: Pair<Double, Double>): FloatArray {
            val parts = p.map { (a, d) -> sine(fs, 1000.0, a, Math.round(d * fs).toInt()) }
            val out = FloatArray(parts.sumOf { it.size }); var o = 0
            for (q in parts) { System.arraycopy(q, 0, out, o, q.size); o += q.size }
            return out
        }
        val cases = listOf(
            Triple("3341-1", seg(-23.0 to 20.0), 2), Triple("3341-2", seg(-33.0 to 20.0), 2),
            Triple("3341-3", seg(-36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0), 2),
            Triple("3341-4", seg(-72.0 to 10.0, -36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0, -72.0 to 10.0), 2),
            Triple("3341-5", seg(-26.0 to 20.0, -20.0 to 20.1, -26.0 to 20.0), 2),
            Triple("music 48k", music(fs, 9 * fs, 2, 1), 2),
        )
        for ((name, x, ch) in cases) {
            val ours = measure(x, fs, ch).integratedLufs
            val out = AuditPy.run(
                "import numpy as np, pyloudnorm as pl\n" +
                    "x=np.fromfile(W+'/x.f32',dtype='<f4').astype(np.float64).reshape(-1,$ch)\n" +
                    "print(repr(float(pl.Meter($fs,filter_class='DeMan').integrated_loudness(x))))",
                prepare = { AuditPy.writeF32(java.io.File(it, "x.f32"), x) },
            )
            val ref = AuditPy.numbers(out)[0]
            assertEquals(name, ref, ours, 2e-3)
        }
        // Абсолютные значения Tech 3341 (эталон dsp.md L-1…L-5 ± 0,001; норма −23 ± 0,1).
        assertEquals(-22.9933, measure(seg(-23.0 to 20.0), fs, 2).integratedLufs, 0.001)
        assertEquals(-23.0139, measure(seg(-72.0 to 10.0, -36.0 to 10.0, -23.0 to 60.0, -36.0 to 10.0, -72.0 to 10.0), fs, 2).integratedLufs, 0.001)
        assertEquals(-22.9787, measure(seg(-26.0 to 20.0, -20.0 to 20.1, -26.0 to 20.0), fs, 2).integratedLufs, 0.001)
    }

    /** П-4 и K-7: < 400 мс → NaN; ровно 400 мс → 1 блок; тишина и −80 dBFS → −∞; пустой ввод; число блоков для любых fs. */
    @Test fun gatingAndEdgeCases() {
        val fs = 48_000
        assertTrue(measure(sine(fs, 1000.0, -23.0, 19_199), fs, 2).integratedLufs.isNaN())
        val m = LoudnessMeter(fs, 2); m.process(sine(fs, 1000.0, -23.0, 19_200), 19_200)
        assertEquals(1, m.blocks); assertEquals(-22.9936, m.result().integratedLufs, 0.002)
        assertEquals(Double.NEGATIVE_INFINITY, measure(FloatArray(2 * 5 * fs), fs, 2).integratedLufs, 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, measure(sine(fs, 1000.0, -80.0, 5 * fs), fs, 2).integratedLufs, 0.0)
        val e = LoudnessMeter(fs, 2).result()
        assertTrue(e.integratedLufs.isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, e.truePeakDbtp, 0.0); assertEquals(Double.NEGATIVE_INFINITY, e.samplePeakDbfs, 0.0)
        assertEquals(0L, e.durationFrames)
        for (rate in intArrayOf(8_000, 11_025, 22_050, 44_100, 88_200)) for (n in intArrayOf(0, 1, rate * 4 / 10 - 1, rate * 4 / 10, rate * 4 / 10 + 1, (rate * 1.05).toInt(), 3 * rate + 7)) {
            val mm = LoudnessMeter(rate, 1)
            if (n > 0) mm.process(FloatArray(n) { 0.1f }, n)
            var j = 0
            while ((j + 4L) * rate / 10 <= n) j++   // цикл, а не формула (dsp.md П-4)
            assertEquals("fs=$rate n=$n", j, mm.blocks)
            assertEquals(j > 0, LoudnessMeter.hasCompleteBlock(n.toLong(), rate))
        }
    }

    /** Порезка на куски (1, 7, случайные) не меняет I (≤ 1e-9 LU) и TP (бит в бит). */
    @Test fun chunkingInvariance() {
        val fs = 44_100
        val x = music(fs, 2 * fs, 2, 8)
        val whole = measure(x, fs, 2, chunk = Int.MAX_VALUE)
        val rnd = java.util.Random(1)
        for (r in listOf(measure(x, fs, 2, chunk = 1), measure(x, fs, 2, chunk = 7), measure(x, fs, 2, rnd, 4410))) {
            assertEquals(whole.integratedLufs, r.integratedLufs, 1e-9)
            assertEquals(whole.truePeakDbtp, r.truePeakDbtp, 0.0)
            assertEquals(whole.samplePeakDbfs, r.samplePeakDbfs, 0.0)
        }
    }

    /** Веса каналов (BS.1770-5 табл. 3): моно vs двойное моно = +3,0103 дБ; канал с G = 1,41 → +10·log10(1,41). */
    @Test fun channelWeights() {
        val fs = 48_000
        val mono = measure(sine(fs, 997.0, -20.0, 5 * fs, 1), fs, 1).integratedLufs
        val dual = measure(sine(fs, 997.0, -20.0, 5 * fs, 2), fs, 2).integratedLufs
        assertEquals(10 * Math.log10(2.0), dual - mono, 1e-9)
        val x = sine(fs, 997.0, -20.0, 5 * fs, 2)
        for (i in x.indices step 2) x[i] = 0f
        val m = LoudnessMeter(fs, 2, doubleArrayOf(1.0, 1.41)); m.process(x, 5 * fs)
        assertEquals(mono + 10 * Math.log10(1.41), m.result().integratedLufs, 1e-9)
        assertEquals(-3.0103, measure(sine(fs, 997.0, 0.0, 10 * fs, 1), fs, 1).integratedLufs, 0.002)
        assertEquals(-3.0075, measure(sine(44_100, 997.0, 0.0, 10 * 44_100, 1), 44_100, 1).integratedLufs, 0.002)
    }

    /**
     * K-6: громкий участок, затем длинная тишина при 192 кГц — состояние IIR обнуляется, тишина обрабатывается не медленнее
     * чистой тишины (с запасом ×4 + 0,5 с), громкость определяется громким участком.
     */
    @Test fun denormalsAfterLoudPassage() {
        val fs = 192_000
        val loud = sine(fs, 40.0, -3.0, fs, 1)
        val silence = FloatArray(20 * fs)
        val a = LoudnessMeter(fs, 1); a.process(loud, fs)
        val t0 = System.nanoTime(); a.process(silence, silence.size); val tLoudThenSilence = System.nanoTime() - t0
        val b = LoudnessMeter(fs, 1)
        val t1 = System.nanoTime(); b.process(silence, silence.size); val tSilence = System.nanoTime() - t1
        assertTrue("тишина после громкого: ${tLoudThenSilence / 1e6} мс vs ${tSilence / 1e6} мс", tLoudThenSilence <= 4 * tSilence + 500_000_000L)
        // Блоки на стыке «громко → тихо» законно проходят гейт; после 1 с тишины хвост IIR = 0, остальные блоки ниже −70.
        val ref = LoudnessMeter(fs, 1); ref.process(loud, fs); ref.process(FloatArray(fs), fs)
        assertEquals(ref.result().integratedLufs, a.result().integratedLufs, 1e-9)
        assertEquals(ref.result().truePeakDbtp, a.result().truePeakDbtp, 0.0)
    }
}
