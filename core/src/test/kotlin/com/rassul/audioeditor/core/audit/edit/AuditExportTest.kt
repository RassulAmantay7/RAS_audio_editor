package com.rassul.audioeditor.core.audit.edit

import com.rassul.audioeditor.core.cli.runCli
import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.Resampler
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.format.WavSampleFormat
import com.rassul.audioeditor.core.format.WavWriter
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPipeline
import com.rassul.audioeditor.core.pipeline.ExportPlan
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.PcmEncoderSink
import com.rassul.audioeditor.core.verify.VerifyExpect
import com.rassul.audioeditor.core.verify.Verifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ExportPipeline (FR-24/25, FR-C6/C7, FR-14), Verifier (FR-33), CLI (T12) — независимые ожидания. */
class AuditExportTest {
    private val ops = listOf(EditOp.Delete(Range(5000, 9000)), EditOp.Volume(Range(100, 20_000), 7.5),
        EditOp.Fade(Range(0, 2400), true, FadeCurve.LOGARITHMIC))

    private fun rend(fs: Int, frames: Int, amp: Double = 0.6) =
        Renderer(ArrayPcmSource(PcmSpec(fs, 2), noiseA(frames, 2, 31, amp)), EditList(ops), RenderSettings())

    private fun all(r: Renderer): FloatArray = FloatArray((r.length * 2).toInt()).also { r.render(0, it, r.length.toInt()) }

    /** Независимое квантование FR-C7: TPDF на java.util.SplittableRandom (тот же SplitMix64, реализация JDK). */
    private fun quant(x: FloatArray, bits: Int, seed: Long?): IntArray {
        val rnd = seed?.let { java.util.SplittableRandom(it) }
        val sc = Math.scalb(1.0, bits - 1)
        return IntArray(x.size) {
            val d = if (rnd != null) rnd.nextDouble() - rnd.nextDouble() else 0.0
            Math.floor(x[it] * sc + d + 0.5).coerceIn(-sc, sc - 1).toInt()
        }
    }
    private fun le(s: IntArray, nb: Int) = ByteArray(s.size * nb).also { o -> for (i in s.indices) for (k in 0 until nb) o[i * nb + k] = (s[i] shr (8 * k)).toByte() }
    private fun md5(b: ByteArray) = java.security.MessageDigest.getInstance("MD5").digest(b)

    private fun export(r: Renderer, plan: ExportPlan, seed: Long, block: Int = 4096): Pair<ByteArray, com.rassul.audioeditor.core.pipeline.ExportResult> {
        val s = ByteArraySink(); val res = ExportPipeline(r, plan, seed, block).writeTo(s); return s.toByteArray() to res
    }

    /** Дизеринг только 16 бит; MD5 PCM = MD5 STREAMINFO = MD5 независимого квантования; Verifier OK. */
    @Test fun flacMd5AndDither() {
        val r = rend(48_000, 30_000, 0.3)
        val x = all(r)
        for (bits in intArrayOf(16, 24)) {
            val plan = ExportPlanner.plan(r.spec, bits, ExportRequest(ExportFormat.FLAC, bits = bits)).copy(dither = true)
            val (bytes, res) = export(r, plan, 4242L)
            val exp = quant(x, bits, if (bits == 16) 4242L else null)
            assertEquals(if (bits == 16) 4242L else null, res.ditherSeed)
            val m = md5(le(exp, bits / 8))
            assertArrayEquals("bits=$bits: pcmMd5", m, res.pcmMd5)
            assertArrayEquals("bits=$bits: STREAMINFO", m, FileFacts.flacStreamInfo(ByteArraySource(bytes)).md5)
            val v = Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res), flacFullDecode = true)
            assertTrue(v.message, v.ok)
            assertEquals("Проверено: длина совпала (0:00.542), FLAC $bits бит 48 кГц стерео", v.message)
        }
    }

    /** Ресемплер — только при смене частоты; длина = Resampler.outputLength; результат не зависит от блока. */
    @Test fun resamplerOnlyOnRateChangeAndLength() {
        val r = rend(44_100, 25_457)
        val same = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.WAV, bits = 32))
        val (b0, res0) = export(r, same, 1)
        assertEquals(r.length, res0.frames)
        val got0 = FloatArray((r.length * 2).toInt()); WavReader(ByteArraySource(b0)).read(0, got0, r.length.toInt())
        assertEquals(-1, firstBitDiff(all(r), got0))
        for (out in intArrayOf(48_000, 8000, 22_050)) {
            val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.WAV, sampleRate = out, bits = 32))
            if (plan.outSpec.sampleRate != out) continue
            val (b1, res1) = export(r, plan, 1, 4096)
            val (b2, res2) = export(r, plan, 1, 7)
            assertEquals(Resampler.outputLength(r.length, 44_100, out), res1.frames)
            assertEquals(res1.frames, res2.frames)
            assertArrayEquals("fs=$out: зависит от размера блока", b1, b2)
        }
    }

    /** Подсчёт срезанных: |x| > 1 по рендеру (WAV 16/24/float, encodeTo) — одно и то же число. */
    @Test fun clippedCount() {
        val r = Renderer(ArrayPcmSource(PcmSpec(48_000, 2), noiseA(20_000, 2, 8, 0.6)), EditList(listOf(EditOp.Volume(Range(1000, 9000), 12.0))), RenderSettings())
        val exp = all(r).count { it > 1f || it < -1f }.toLong()
        assertTrue(exp > 1000)
        for (bits in intArrayOf(16, 24, 32)) {
            val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.WAV, bits = bits))
            assertEquals("bits=$bits", exp, export(r, plan, 5).second.clippedSamples)
        }
        val pipe = ExportPipeline(r, ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.FLAC)), 5)
        assertEquals(exp, pipe.countClipped())
        // M4A: срез [−1, 1] перед энкодером.
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.M4A))
        var maxAbs = 0f; var total = 0L
        val res = ExportPipeline(r, plan, 5).encodeTo(object : PcmEncoderSink {
            override fun writePcm(pcm: FloatArray, frames: Int) { for (i in 0 until frames * 2) maxAbs = maxOf(maxAbs, Math.abs(pcm[i])); total += frames }
            override fun finish(totalFrames: Long) { assertEquals(total, totalFrames) }
        })
        assertEquals(exp, res.clippedSamples); assertTrue(maxAbs <= 1f); assertEquals(r.length, res.frames)
    }

    /** Экспорт → ffmpeg: FLAC 16 (дизер) и WAV 24 декодируются в те же целые, что независимое квантование. */
    @Test fun ffmpegDecodesSameIntegers() {
        T.need("ffmpeg")
        val r = rend(48_000, 40_000, 0.4)
        val x = all(r)
        for ((fmt, bits) in listOf(ExportFormat.FLAC to 16, ExportFormat.WAV to 24, ExportFormat.FLAC to 24)) {
            val plan = ExportPlanner.plan(r.spec, bits, ExportRequest(fmt, bits = bits))
            val (bytes, _) = export(r, plan, 99L)
            val f = T.tmp("." + fmt.ext).also { it.writeBytes(bytes) }
            val raw = T.ffmpeg("-i", f.path, "-f", "s32le", "-c:a", "pcm_s32le", "-").stdout
            val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val got = IntArray(raw.size / 4) { bb.getInt() shr (32 - bits) }
            assertArrayEquals("$fmt $bits", quant(x, bits, if (bits == 16) 99L else null), got)
        }
    }

    /** Verifier FR-33: усечённый WAV — точный текст; порча байта FLAC — ошибка; длина ±0 для WAV/FLAC. */
    @Test fun verifierTexts() {
        val r = rend(48_000, 48_000)
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.WAV, bits = 16))
        val (bytes, res) = export(r, plan, 3)
        val cut = bytes.copyOf(bytes.size - 4 * 4400)
        val n = FileFacts.wav(ByteArraySource(cut)).frames
        assertEquals(res.frames - 4400, n)
        val v = Verifier.verify(ByteArraySource(cut), VerifyExpect.of(plan, res))
        assertFalse(v.ok)
        val t = com.rassul.audioeditor.core.time.TimeConv
        assertEquals("Файл записан с ошибкой: длина ${t.format(n, 48_000)} ($n сэмпл.) вместо ${t.format(res.frames, 48_000)} (${res.frames} сэмпл.). Попробуйте сохранить ещё раз.", v.message)
        // Длина на 1 кадр больше ожидаемой — тоже ошибка (допуск 0).
        assertFalse(Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res).copy(frames = res.frames + 1)).ok)
        val fplan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.FLAC, bits = 16))
        val (fb, fres) = export(r, fplan, 3)
        val bad = fb.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x10).toByte() }
        val fv = Verifier.verify(ByteArraySource(bad), VerifyExpect.of(fplan, fres), flacFullDecode = true)
        assertFalse(fv.message, fv.ok)
        assertTrue(fv.message, fv.message.startsWith("Файл записан с ошибкой: ") && fv.message.endsWith("Попробуйте сохранить ещё раз или выберите WAV."))
    }

    /** CLI: правки применяются в порядке аргументов, время — в координатах результата предыдущих правок. */
    @Test fun cliOrder() {
        val fs = 48_000
        val x = noiseA(48_000, 2, 12, 0.3)
        val inp = T.tmp(".wav")
        FileSink(inp).use { s -> WavWriter(s, PcmSpec(fs, 2), WavSampleFormat.FLOAT32).apply { writeFloat(x, 48_000); finish() } }
        val outF = T.tmp(".wav")
        val bo = java.io.ByteArrayOutputStream(); val be = java.io.ByteArrayOutputStream()
        val code = runCli(arrayOf(inp.path, "--delete", "0.100", "0.200", "--gain", "-6", "0.050", "0.150",
            "--fade-curve", "linear", "--fade-out", "0.05", "--bits", "32", "--out", outF.path),
            java.io.PrintStream(bo, true, "UTF-8"), java.io.PrintStream(be, true, "UTF-8"))
        assertEquals(be.toString("UTF-8"), 0, code)
        assertTrue(bo.toString("UTF-8").contains("Проверено: длина совпала"))
        val l1 = 48_000L - 4800
        val exp = Naive.render(x, 2, listOf(EditOp.Delete(Range(4800, 9600)), EditOp.Volume(Range(2400, 7200), -6.0),
            EditOp.Fade(Range(l1 - 2400, l1), false, FadeCurve.LINEAR)), 240)
        val w = WavReader(com.rassul.audioeditor.core.io.FileSource(outF))
        val got = FloatArray(exp.size); w.read(0, got, exp.size / 2)
        assertEquals(exp.size / 2L, w.lengthFrames)
        assertEquals(-1, firstBitDiff(exp, got))
        // Ошибка ввода — код 2, текст на русском.
        val be2 = java.io.ByteArrayOutputStream()
        assertEquals(2, runCli(arrayOf(inp.path, "--delete", "0", "1.000", "--out", outF.path), java.io.PrintStream(java.io.ByteArrayOutputStream()), java.io.PrintStream(be2, true, "UTF-8")))
        assertTrue(be2.toString("UTF-8"), be2.toString("UTF-8").contains("Нельзя удалить весь файл"))
    }
}
