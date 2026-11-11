package com.rassul.audioeditor.core.dsp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-C6: контрольные примеры R-1…R-8 docs/math/dsp.md (resampler_vectors.json). */
class ResamplerTest {
    private val ref = RefData.json("resampler_vectors.json").obj()

    /** Прогон целиком: process кусками chunk + flush. Вход и выход — чередование. */
    private fun run(x: FloatArray, ch: Int, a: Int, b: Int, chunk: Int = 4096): FloatArray {
        val r = Resampler(a, b, ch)
        val nIn = x.size / ch
        val total = Resampler.outputLength(nIn.toLong(), a, b).toInt()
        val y = FloatArray(total * ch)
        var o = 0
        var i = 0
        val tmp = FloatArray(r.maxOutput(chunk) * ch)
        while (i < nIn) {
            val n = minOf(chunk, nIn - i)
            val got = r.process(x.copyOfRange(i * ch, (i + n) * ch), n, tmp)
            assertTrue(got <= r.maxOutput(n))
            System.arraycopy(tmp, 0, y, o * ch, got * ch); o += got; i += n
        }
        val tail = FloatArray(r.maxOutput(0) * ch)
        val got = r.flush(tail)
        assertEquals("длина $a→$b N=$nIn", total, o + got)
        System.arraycopy(tail, 0, y, o * ch, got * ch)
        return y
    }

    @Test fun lengthCases() {       // R-4, R-3
        for (c in ref["length_cases"].list().map { it.obj() }) {
            assertEquals(c.toString(), c["n_out"].l(), Resampler.outputLength(c["n_in"].l(), c["fs_in"].l().toInt(), c["fs_out"].l().toInt()))
        }
        assertEquals(86_400_000L, Resampler.outputLength(30L * 60 * 44_100, 44_100, 48_000))
        assertEquals(1234L, Resampler.outputLength(1234, 48_000, 48_000))
        // Фактический выход потоковой реализации совпадает с формулой на коротких длинах.
        for (n in intArrayOf(0, 1, 2, 3, 7, 100, 147, 441, 1000))
            for ((a, b) in listOf(44_100 to 48_000, 48_000 to 44_100, 48_000 to 8_000, 8_000 to 48_000, 11_025 to 48_000))
                run(FloatArray(n), 1, a, b, 64)
    }

    @Test fun designParameters() {  // R-7, П-5
        ResamplerDesign.of(44_100, 48_000).let { assertEquals(160, it.L); assertEquals(147, it.M); assertEquals(130, it.Nt) }
        ResamplerDesign.of(48_000, 44_100).let { assertEquals(147, it.L); assertEquals(160, it.M); assertEquals(140, it.Nt) }
        assertEquals(514, ResamplerDesign.of(192_000, 48_000).Nt)
        assertEquals(3078, ResamplerDesign.of(192_000, 8_000).Nt)
        assertEquals(10.0613, ResamplerDesign.of(44_100, 48_000).beta, 1e-4)
        for (v in ref["vectors"].list().map { it.obj() }) {
            val d = ResamplerDesign.of(v["fs_in"].l().toInt(), v["fs_out"].l().toInt())
            assertEquals(v["L"].l().toInt(), d.L); assertEquals(v["M"].l().toInt(), d.M); assertEquals(v["Nt"].l().toInt(), d.Nt)
            assertEquals(v["beta"].d(), d.beta, 1e-12); assertEquals(v["fcn"].d(), d.fcn, 1e-15)
        }
        assertTrue(ResamplerCore(11_025, 192_000, 1).exactTable)     // L = 2560: точная таблица
        assertFalse(ResamplerCore(44_100, 44_099, 1).exactTable)     // L·Nt > 2^20 → 4096 фаз
        assertEquals(1.0, ResamplerDesign.sinc(0.0), 0.0)
        assertEquals(1.0, ResamplerDesign.besselI0(0.0), 0.0)
        assertEquals(2.279585302336067, ResamplerDesign.besselI0(2.0), 1e-14) // I0(2)
    }

    @Test fun referenceVectors() {  // R-6
        for (v in ref["vectors"].list().map { it.obj() }) {
            val a = v["fs_in"].l().toInt(); val b = v["fs_out"].l().toInt()
            val x = v["x"].list().map { it.d().toFloat() }.toFloatArray()
            val yRef = v["y"].doubles()
            for (chunk in intArrayOf(300, 1, 37)) {
                val y = run(x, 1, a, b, chunk)
                assertEquals(yRef.size, y.size)
                var mx = 0.0
                for (i in y.indices) mx = maxOf(mx, Math.abs(y[i] - yRef[i]))
                assertTrue("$a→$b chunk=$chunk max|dy|=$mx", mx <= 2e-6)
            }
        }
    }

    @Test fun bypassBitExact() {    // R-8, K-10
        val rnd = java.util.Random(1)
        val x = FloatArray(2 * 5000) { rnd.nextFloat() * 2.2f - 1.1f }
        val y = run(x, 2, 48_000, 48_000, 333)
        assertArrayEquals(x, y, 0f)
        for (i in x.indices) assertEquals(java.lang.Float.floatToRawIntBits(x[i]), java.lang.Float.floatToRawIntBits(y[i]))
    }

    @Test fun chunkingInvariantStereo() {
        val rnd = java.util.Random(7)
        val x = FloatArray(2 * 10_000) { rnd.nextFloat() - 0.5f }
        for ((a, b) in listOf(44_100 to 48_000, 48_000 to 44_100, 192_000 to 8_000)) {
            val ref1 = run(x, 2, a, b, 10_000)
            for (c in intArrayOf(1, 7, 4096)) assertArrayEquals("$a→$b chunk $c", ref1, run(x, 2, a, b, c), 0f)
            // Каналы независимы: левый канал = моно-прогон левого.
            val left = FloatArray(10_000) { x[2 * it] }
            val yl = run(left, 1, a, b, 999)
            for (i in yl.indices) assertEquals(yl[i], ref1[2 * i], 0f)
        }
    }

    /** Подгонка y ≈ a·sin + b·cos + c (МНК); возвращает (амплитуда, RMS остатка). */
    private fun fitSine(y: FloatArray, from: Int, to: Int, fs: Int, f: Double): Pair<Double, Double> {
        val ata = Array(3) { DoubleArray(3) }; val atb = DoubleArray(3)
        for (i in from until to) {
            val w = 2 * Math.PI * f * i / fs
            val r = doubleArrayOf(Math.sin(w), Math.cos(w), 1.0)
            for (p in 0..2) { for (q in 0..2) ata[p][q] += r[p] * r[q]; atb[p] += r[p] * y[i] }
        }
        val c = solve3(ata, atb)
        var s2 = 0.0
        for (i in from until to) {
            val w = 2 * Math.PI * f * i / fs
            val e = y[i] - (c[0] * Math.sin(w) + c[1] * Math.cos(w) + c[2]); s2 += e * e
        }
        return Math.hypot(c[0], c[1]) to Math.sqrt(s2 / (to - from))
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val m = Array(3) { i -> DoubleArray(4) { j -> if (j < 3) a[i][j] else b[i] } }
        for (c in 0..2) {
            val p = (c..2).maxBy { Math.abs(m[it][c]) }; val t = m[c]; m[c] = m[p]; m[p] = t
            for (r in 0..2) if (r != c) { val f = m[r][c] / m[c][c]; for (k in c..3) m[r][k] -= f * m[c][k] }
        }
        return DoubleArray(3) { m[it][3] / m[it][it] }
    }

    @Test fun sine1kHz441to48() {   // R-1
        val a = 44_100; val b = 48_000
        val amp = Math.pow(10.0, -1 / 20.0)
        val x = FloatArray(2 * a) { (amp * Math.sin(2 * Math.PI * 1000 * it / a)).toFloat() }
        val y = run(x, 1, a, b)
        val cut = ResamplerDesign.of(a, b).T * b / a + 2
        val (A, res) = fitSine(y, cut, y.size - cut, b, 1000.0)
        val ampErr = 20 * Math.log10(A / amp)
        val thdn = 20 * Math.log10(res / (A / Math.sqrt(2.0)))
        assertTrue("ошибка амплитуды $ampErr дБ", Math.abs(ampErr) <= 0.01)
        assertTrue("THD+N $thdn дБ", thdn <= -90.0)
        assertTrue("THD+N $thdn дБ (эталон −126,9, float32-выход)", thdn <= -115.0)
    }

    @Test fun sine23kHz48to441() {  // R-2
        val a = 48_000; val b = 44_100
        val x = FloatArray(2 * a) { Math.sin(2 * Math.PI * 23_000 * it / a).toFloat() }
        val y = run(x, 1, a, b)
        val cut = ResamplerDesign.of(a, b).T * b / a + 2
        var pk = 0.0; var s2 = 0.0
        for (i in cut until y.size - cut) { pk = maxOf(pk, Math.abs(y[i].toDouble())); s2 += y[i].toDouble() * y[i] }
        val pkDb = 20 * Math.log10(pk)
        val rmsDb = 20 * Math.log10(Math.sqrt(s2 / (y.size - 2 * cut)))
        assertTrue("остаток пик $pkDb dBFS", pkDb <= -90.0)
        assertTrue("остаток RMS $rmsDb dBFS (эталон −107,4)", rmsDb <= -100.0)
    }

    @Test fun zeroDelayCentroid() { // R-5
        for ((a, b) in listOf(44_100 to 48_000, 48_000 to 44_100, 8_000 to 48_000, 192_000 to 48_000)) {
            val n0 = 1234
            val sig = 0.2 * minOf(1.0, b.toDouble() / a)
            val x = FloatArray(4000) { val t = (it - n0) * sig; Math.exp(-0.5 * t * t).toFloat() }
            val y = run(x, 1, a, b)
            var s = 0.0; var sw = 0.0
            for (i in y.indices) { val e = y[i].toDouble() * y[i]; s += i * e; sw += e }
            val err = s / sw - n0.toDouble() * b / a
            assertTrue("$a→$b задержка $err", Math.abs(err) <= 1.0)
            assertTrue("$a→$b задержка $err (эталон 1e-11, float32)", Math.abs(err) <= 1e-3)
        }
    }

    /** АЧХ прототипа: |Σ h(j/L)/L · e^{−2πi f j/L}|, f — циклы на входной сэмпл (как resampler_ref.proto_response). */
    private fun response(d: ResamplerDesign, f: Double): Double {
        val L = d.L; val T = d.T
        var re = 0.0; var im = 0.0
        for (j in -T * L..T * L) {
            val h = d.kernel(j.toDouble() / L) / L
            val w = -2 * Math.PI * f * j / L
            re += h * Math.cos(w); im += h * Math.sin(w)
        }
        return Math.hypot(re, im)
    }

    @Test fun passbandRippleAndStopband() { // FR-C6: ≤ 0,1 дБ в 0…0,45·fs_min, ≥ 90 дБ выше 0,5·fs_min
        for ((a, b) in listOf(44_100 to 48_000, 48_000 to 44_100, 48_000 to 8_000)) {
            val d = ResamplerDesign.of(a, b)
            val fmin = minOf(a, b).toDouble()
            var hi = -1e9; var lo = 1e9
            for (k in 0..60) {
                val db = 20 * Math.log10(response(d, 0.45 * fmin * k / 60 / a))
                hi = maxOf(hi, db); lo = minOf(lo, db)
            }
            assertTrue("$a→$b пульсации ${hi - lo} дБ", hi - lo <= 0.1)
            assertTrue("$a→$b пульсации ${hi - lo} дБ (эталон 1,6e-4)", hi - lo <= 1e-3)
            val top = minOf(0.5 * d.L * a, 4.0 * maxOf(a, b))
            var stop = -1e9
            for (k in 0..80) {
                val f = 0.5 * fmin + (top - 0.5 * fmin) * k / 80
                stop = maxOf(stop, 20 * Math.log10(response(d, f / a) + 1e-300))
            }
            assertTrue("$a→$b подавление $stop дБ", stop <= -90.0)
        }
    }

    @Test fun interpolatedTableForOddRates() { // П-5: 4096 фаз с интерполяцией, сверка с прямой формулой в double
        val a = 44_100; val b = 44_099
        val rnd = java.util.Random(3)
        val x = FloatArray(3000) { (0.5 * Math.sin(it * 0.05) + 0.1 * (rnd.nextDouble() - 0.5)).toFloat() }
        val y = run(x, 1, a, b, 500)
        val d = ResamplerDesign.of(a, b)
        var mx = 0.0
        for (m in intArrayOf(0, 1, 100, 1500, 2000, y.size - 1)) {
            val P = m.toLong() * d.M; val n0 = P / d.L; val mu = (P % d.L).toDouble() / d.L
            var s = 0.0
            for (k in -(d.T - 1)..d.T) { val n = n0 + k; if (n in 0 until x.size) s += x[n.toInt()] * d.kernel(mu - k) }
            mx = maxOf(mx, Math.abs(s - y[m]))
        }
        assertTrue("интерполяция фаз: max|dy| = $mx", mx <= 2e-6)
    }
}
