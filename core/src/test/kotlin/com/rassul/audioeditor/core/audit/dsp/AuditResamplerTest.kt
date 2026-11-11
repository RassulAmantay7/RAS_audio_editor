package com.rassul.audioeditor.core.audit.dsp

import com.rassul.audioeditor.core.dsp.Resampler
import com.rassul.audioeditor.core.dsp.ResamplerDesign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит FR-C6 (docs/math/dsp.md «FR-C6», П-5, П-13, K-3, K-8–K-10). */
class AuditResamplerTest {
    companion object {
        /** Ресемплинг целиком кусками случайной длины; проверка, что вызов не пишет больше maxOutput(). */
        fun run(x: FloatArray, ch: Int, fin: Int, fout: Int, rnd: java.util.Random? = null, maxChunk: Int = 8192): FloatArray {
            val r = Resampler(fin, fout, ch)
            val n = x.size / ch
            val out = ArrayList<FloatArray>()
            var i = 0
            while (i < n) {
                val k = minOf(n - i, if (rnd == null) maxChunk else 1 + rnd.nextInt(maxChunk))
                val cap = r.maxOutput(k)
                val buf = FloatArray((cap + 64) * ch) { Float.NaN }
                val got = r.process(x.copyOfRange(i * ch, (i + k) * ch), k, buf)
                assertTrue("process: $got > maxOutput $cap", got <= cap)
                out.add(buf.copyOf(got * ch)); i += k
            }
            val cap = r.maxOutput(0)
            val buf = FloatArray((cap + 64) * ch)
            val got = r.flush(buf)
            assertTrue("flush: $got > maxOutput(0) $cap", got <= cap)
            out.add(buf.copyOf(got * ch))
            val y = FloatArray(out.sumOf { it.size }); var o = 0
            for (a in out) { System.arraycopy(a, 0, y, o, a.size); o += a.size }
            return y
        }

        /** THD+N, дБ: остаток после МНК-подгонки синуса f (a·sin + b·cos + c) на [from, to). */
        fun thdn(y: FloatArray, fs: Int, f: Double, from: Int, to: Int): Pair<Double, Double> {
            var ss = 0.0; var sc = 0.0; var cc = 0.0; var ys = 0.0; var yc = 0.0
            for (i in from until to) {
                val w = 2 * Math.PI * f * i / fs; val s = Math.sin(w); val c = Math.cos(w)
                ss += s * s; sc += s * c; cc += c * c; ys += y[i] * s; yc += y[i] * c
            }
            val det = ss * cc - sc * sc
            val a = (ys * cc - yc * sc) / det; val b = (yc * ss - ys * sc) / det
            var res = 0.0; var sig = 0.0
            for (i in from until to) {
                val w = 2 * Math.PI * f * i / fs
                val fit = a * Math.sin(w) + b * Math.cos(w)
                res += (y[i] - fit).let { it * it }; sig += fit * fit
            }
            return 10 * Math.log10(res / sig) to Math.hypot(a, b)
        }
    }

    /** R-1 и аналоги: синус 1 кГц −1 dBFS, края по T отброшены; амплитуда ±0,01 дБ, THD+N ≤ −90 дБ (FR-C6). */
    @Test fun thdnManyPairs() {
        val pairs = listOf(44_100 to 48_000, 48_000 to 44_100, 8_000 to 48_000, 48_000 to 8_000, 96_000 to 44_100,
            192_000 to 48_000, 11_025 to 48_000, 22_050 to 16_000, 44_099 to 48_000)
        val amp = Math.pow(10.0, -1 / 20.0)
        for ((fin, fout) in pairs) {
            val n = fin // 1 с
            val x = FloatArray(n) { (amp * Math.sin(2 * Math.PI * 1000.0 * it / fin)).toFloat() }
            val y = run(x, 1, fin, fout)
            assertEquals("$fin→$fout длина", Resampler.outputLength(n.toLong(), fin, fout), y.size.toLong())
            val d = ResamplerDesign.of(fin, fout)
            val edge = (d.T.toLong() * fout / fin + 2).toInt()
            val (db, a) = thdn(y, fout, 1000.0, edge, y.size - edge)
            assertTrue("$fin→$fout THD+N=$db", db <= -90.0)
            assertEquals("$fin→$fout амплитуда", 0.0, 20 * Math.log10(a / amp), 0.01)
        }
    }

    /** Нуль-фазовость: y[m] ≈ x(t = m/fs_out) без задержки — сравнение с идеальным синусом в тех же точках. */
    @Test fun zeroPhaseAgainstAnalyticSine() {
        for ((fin, fout) in listOf(44_100 to 48_000, 48_000 to 44_100, 48_000 to 8_000)) {
            val f = 997.0
            val x = FloatArray(fin) { (0.5 * Math.sin(2 * Math.PI * f * it / fin)).toFloat() }
            val y = run(x, 1, fin, fout)
            val edge = (ResamplerDesign.of(fin, fout).T.toLong() * fout / fin + 2).toInt()
            var mx = 0.0
            for (m in edge until y.size - edge) mx = maxOf(mx, Math.abs(y[m] - 0.5 * Math.sin(2 * Math.PI * f * m / fout)))
            assertTrue("$fin→$fout max ошибка $mx", mx < 0.5 * 3.2e-5) // ≤ −90 дБ отн. амплитуды
        }
    }

    /** DC-усиление каждой фазы ≈ 1 (неравномерность ≤ 1,6·10⁻⁴ дБ ⇒ |y−c| ≤ 2·10⁻⁵·c); накопление double, отводы float. */
    @Test fun dcGain() {
        for ((fin, fout) in listOf(44_100 to 48_000, 48_000 to 44_100, 192_000 to 8_000, 44_099 to 48_000)) {
            val x = FloatArray(fin / 2) { 0.75f }
            val y = run(x, 1, fin, fout)
            val edge = (ResamplerDesign.of(fin, fout).T.toLong() * fout / fin + 2).toInt()
            for (m in edge until y.size - edge) assertEquals("$fin→$fout m=$m", 0.75, y[m].toDouble(), 0.75 * 2e-5)
        }
    }

    /** Живая сверка с tools/ref/resampler_ref.resample (float64, точная таблица) — шум, 3 пары включая П-5 (44 099 Гц). */
    @Test fun matchesResamplerRefLive() {
        val rnd = java.util.Random(13)
        for ((fin, fout) in listOf(44_100 to 48_000, 48_000 to 44_100, 44_099 to 48_000, 32_000 to 11_025)) {
            val n = 6000
            val x = FloatArray(n) { (rnd.nextGaussian() * 0.25).toFloat() }
            val ours = run(x, 1, fin, fout, rnd, 700)
            AuditPy.run(
                "import numpy as np\nfrom resampler_ref import resample\n" +
                    "x=np.fromfile(W+'/x.f32',dtype='<f4').astype(np.float64)\n" +
                    "resample(x,$fin,$fout).astype('<f8').tofile(W+'/y')\nprint('ok')",
                prepare = { AuditPy.writeF32(java.io.File(it, "x.f32"), x) },
                after = { dir, _ ->
                    val ref = AuditPy.readF64(java.io.File(dir, "y"))
                    assertEquals("$fin→$fout длина", ref.size, ours.size)
                    var mx = 0.0
                    for (i in ref.indices) mx = maxOf(mx, Math.abs(ref[i] - ours[i]))
                    assertTrue("$fin→$fout max|Δ| = $mx", mx <= 2e-6) // R-6: float32-таблица
                },
            )
        }
    }

    /** Многоканальность и порезка на куски: результат бит в бит равен обработке одним куском; каналы независимы. */
    @Test fun chunkingAndChannels() {
        val rnd = java.util.Random(21)
        val n = 20_000
        val l = FloatArray(n) { rnd.nextFloat() - 0.5f }; val r = FloatArray(n) { rnd.nextFloat() - 0.5f }
        val st = FloatArray(2 * n) { if (it % 2 == 0) l[it / 2] else r[it / 2] }
        for ((fin, fout) in listOf(44_100 to 48_000, 192_000 to 8_000, 8_000 to 192_000, 44_099 to 48_000)) {
            val whole = run(st, 2, fin, fout, null, Int.MAX_VALUE)
            val pieces = run(st, 2, fin, fout, rnd, 37)
            assertTrue("$fin→$fout", whole.contentEquals(pieces))
            val yl = run(l, 1, fin, fout)
            for (i in yl.indices) assertEquals(yl[i], whole[2 * i], 0f)
        }
    }

    /** Длины (R-3, R-4), в том числе огромные — без переполнения Int (K-3); пустой и единичный вход. */
    @Test fun lengthsAndEdges() {
        assertEquals(86_400_000L, Resampler.outputLength(79_380_000L, 44_100, 48_000))
        assertEquals(53_747L, Resampler.outputLength(12_345L, 11_025, 48_000))
        // 24 ч при 192 кГц → 11 025: 2·N·L = 2·1,66·10¹⁰·147 ≈ 4,9·10¹² — Long.
        val n = 16_588_800_000L
        assertEquals(java.math.BigInteger.valueOf(n).multiply(java.math.BigInteger.valueOf(147)).multiply(java.math.BigInteger.TWO)
            .add(java.math.BigInteger.valueOf(2560)).divide(java.math.BigInteger.valueOf(5120)).toLong(),
            Resampler.outputLength(n, 192_000, 11_025))
        for (len in intArrayOf(0, 1, 2, 3, 7)) for ((fin, fout) in listOf(44_100 to 48_000, 48_000 to 8_000, 8_000 to 48_000)) {
            val y = run(FloatArray(len) { 0.5f }, 1, fin, fout)
            assertEquals("len=$len $fin→$fout", Resampler.outputLength(len.toLong(), fin, fout), y.size.toLong())
            assertTrue(y.all { it.isFinite() })
        }
        // R-8: fs_in = fs_out — бит в бит, включая NaN и −0,0.
        val x = floatArrayOf(0.1f, -0.0f, Float.MIN_VALUE, 1e-40f, 3.5f)
        val y = run(x, 1, 48_000, 48_000)
        assertEquals(x.size, y.size)
        for (i in x.indices) assertEquals(java.lang.Float.floatToRawIntBits(x[i]), java.lang.Float.floatToRawIntBits(y[i]))
    }

    /** K-8: I0 по ряду против известных значений (Abramowitz & Stegun, табл. 9.8); β = 10,0613; ядро: h(0) = 2fcn. */
    @Test fun besselAndKernel() {
        assertEquals(1.0, ResamplerDesign.besselI0(0.0), 0.0)
        assertEquals(1.266065877752008, ResamplerDesign.besselI0(1.0), 1e-15)
        assertEquals(27.23987182360445, ResamplerDesign.besselI0(5.0), 1e-12)
        assertEquals(2815.716628466254, ResamplerDesign.besselI0(10.0), 1e-9)
        val d = ResamplerDesign.of(44_100, 48_000)
        assertEquals(0.1102 * (100 - 8.7), d.beta, 1e-12)   // 10,06126 (в dsp.md округлено до 10,0613)
        assertEquals(2 * d.fcn, d.kernel(0.0), 1e-15)
        assertEquals(0.0, d.kernel(d.T + 1e-9), 0.0)
        assertEquals(d.kernel(3.3), d.kernel(-3.3), 1e-16)
        assertEquals(1.0, ResamplerDesign.sinc(0.0), 0.0)
        assertEquals(0.0, ResamplerDesign.sinc(3.0), 1e-16)
        // Nt по Кайзеру (R-7) и дальние пары.
        assertEquals(130, d.Nt); assertEquals(140, ResamplerDesign.of(48_000, 44_100).Nt)
        assertEquals(514, ResamplerDesign.of(192_000, 48_000).Nt); assertEquals(3078, ResamplerDesign.of(192_000, 8_000).Nt)
    }
}
