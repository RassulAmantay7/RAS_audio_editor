package com.rassul.audioeditor.core.loudness

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * L-12: живая сверка с pyloudnorm.Meter(fs, filter_class="DeMan") (dsp.md П-1) на «музыкоподобном» сигнале.
 * Нет python3/pyloudnorm: пропуск, а при REQUIRE_TOOLS=1 — падение (docs/contracts.md).
 */
class PyloudnormTest {
    private fun pyloudnorm(x: FloatArray, fs: Int, ch: Int): Double? {
        val dir = java.nio.file.Files.createTempDirectory("pyln")
        try {
            val f = dir.resolve("x.f32").toFile()
            val bb = java.nio.ByteBuffer.allocate(x.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (v in x) bb.putFloat(v)
            f.writeBytes(bb.array())
            val code = "import sys,numpy as np,pyloudnorm as pl\n" +
                "x=np.fromfile(sys.argv[1],dtype='<f4').astype(np.float64).reshape(-1,$ch)\n" +
                "print(float(pl.Meter($fs,filter_class='DeMan').integrated_loudness(x)))"
            val p = ProcessBuilder("python3", "-I", "-c", code, f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() != 0) return null
            return out.lines().last().toDoubleOrNull()
        } catch (e: java.io.IOException) {
            return null
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test fun matchesPyloudnormDeMan() {
        val rnd = java.util.Random(11)
        for ((fs, ch) in listOf(44_100 to 2, 8_000 to 1, 96_000 to 2)) {
            val n = 7 * fs
            var lp = 0.0
            val x = FloatArray(n * ch)
            for (i in 0 until n) {
                lp = 0.97 * lp + 0.03 * rnd.nextGaussian()
                val env = 0.2 + 0.8 * Math.abs(Math.sin(i * 2.0 / fs))
                for (c in 0 until ch) x[i * ch + c] = (env * (0.3 * Math.sin(2 * Math.PI * (220.0 + 110 * c) * i / fs) + lp)).toFloat()
            }
            val ref = pyloudnorm(x, fs, ch)
            if (ref == null) {
                assumeTrue("python3 + pyloudnorm недоступны", System.getenv("REQUIRE_TOOLS") == "1")
                throw AssertionError("REQUIRE_TOOLS=1, но pyloudnorm недоступен")
            }
            val ours = LoudnessTest.measure(x, fs, ch).integratedLufs
            assertEquals("fs=$fs", ref, ours, 0.05)
        }
    }
}
