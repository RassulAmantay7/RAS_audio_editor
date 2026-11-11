package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.dsp.Quantizer
import com.rassul.audioeditor.core.dsp.Resampler
import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.EditOp
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.edit.noise
import com.rassul.audioeditor.core.edit.renderAll
import com.rassul.audioeditor.core.edit.src
import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.loudness.LoudnessMeter
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.model.ArrayPcmSource
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.verify.FlacDecoder
import com.rassul.audioeditor.core.verify.VerifyExpect
import com.rassul.audioeditor.core.verify.Verifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** T12: ExportPipeline — WAV/FLAC бит в бит с рендером, ресемплинг, срез, нормализация, M4A/Opus через адаптеры. */
class ExportPipelineTest {
    private val settings = RenderSettings()
    private val edits = EditList(listOf(
        EditOp.Delete(Range(10_000, 20_000)),
        EditOp.Volume(Range(1000, 30_000), -3.0),
        EditOp.Fade(Range(0, 4800), true, FadeCurve.EQUAL_POWER),
    ))

    private fun renderer(fs: Int = 48_000, frames: Int = 96_000) = Renderer(src(noise(frames, 2, 17, 0.5f), 2, fs), edits, settings)

    private fun quantize(x: FloatArray, bits: Int, dither: Boolean, seed: Long): IntArray {
        val out = IntArray(x.size); Quantizer(bits, dither, seed).quantize(x, x.size, out); return out
    }

    private fun export(r: Renderer, req: ExportRequest, srcBits: Int = 24, seed: Long = 1234L): Triple<ByteArray, ExportPlan, ExportResult> {
        val plan = ExportPlanner.plan(r.spec, srcBits, req)
        val sink = ByteArraySink()
        val res = ExportPipeline(r, plan, seed, blockFrames = 3001).writeTo(sink)
        return Triple(sink.toByteArray(), plan, res)
    }

    @Test fun wav24BitExactWithRender() {
        val r = renderer()
        val (bytes, plan, res) = export(r, ExportRequest(ExportFormat.WAV))
        assertEquals(24, plan.bits)
        assertEquals(r.length, res.frames)
        val w = WavReader(ByteArraySource(bytes))
        val got = IntArray((r.length * 2).toInt()); w.readInt(0, got, r.length.toInt())
        assertArrayEquals(quantize(renderAll(r), 24, false, 0), got)
        assertTrue(Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res)).ok)
    }

    @Test fun noDitherAt24Bits() {   // FR-C7: дизеринг только для 16 бит (замечание аудитора T13)
        val r = renderer()
        val exp = quantize(renderAll(r), 24, false, 0)
        for (fmt in listOf(ExportFormat.WAV, ExportFormat.FLAC)) {
            val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(fmt, bits = 24))
            assertEquals(false, plan.dither)
            // Даже если план «просит» дизеринг для 24 бит — его нет.
            val sink = ByteArraySink()
            val res = ExportPipeline(r, plan.copy(dither = true), 99L).writeTo(sink)
            assertEquals(null, res.ditherSeed)
            val got = if (fmt == ExportFormat.WAV) IntArray(exp.size).also { WavReader(ByteArraySource(sink.toByteArray())).readInt(0, it, r.length.toInt()) }
                else ArrayList<Int>().also { l -> FlacDecoder(ByteArraySource(sink.toByteArray())).decode { s, n -> for (i in 0 until n * 2) l += s[i] } }.toIntArray()
            assertArrayEquals(exp, got)
        }
        // 16 бит — дизеринг по плану.
        assertEquals(true, ExportPlanner.plan(r.spec, 16, ExportRequest(ExportFormat.FLAC)).dither)
    }

    @Test fun wavFloatBitExact() {
        val r = renderer()
        val (bytes, plan, res) = export(r, ExportRequest(ExportFormat.WAV, bits = 32))
        val w = WavReader(ByteArraySource(bytes))
        val got = FloatArray((r.length * 2).toInt()); w.read(0, got, r.length.toInt())
        val exp = renderAll(r)
        for (i in exp.indices) assertEquals(exp[i].toRawBits(), got[i].toRawBits())
        assertTrue(Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res)).message, Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res)).ok)
    }

    @Test fun flac16DitherReproducibleAndMd5() {
        val r = renderer()
        val (bytes, plan, res) = export(r, ExportRequest(ExportFormat.FLAC, bits = 16), srcBits = 16, seed = 777L)
        assertTrue(plan.dither)
        assertEquals(777L, res.ditherSeed)
        val exp = quantize(renderAll(r), 16, true, 777L)
        val dec = ArrayList<Int>()
        val d = FlacDecoder(ByteArraySource(bytes)).decode { s, n -> for (i in 0 until n * 2) dec += s[i] }
        assertEquals(null, d.error)
        assertArrayEquals(exp, dec.toIntArray())
        assertArrayEquals(res.pcmMd5, FileFacts.flacStreamInfo(ByteArraySource(bytes)).md5)
        assertArrayEquals(res.pcmMd5, d.md5)
        assertTrue(Verifier.verify(ByteArraySource(bytes), VerifyExpect.of(plan, res), flacFullDecode = true).ok)
    }

    @Test fun flac24DecodedByFfmpegBitExact() {
        T.need("ffmpeg")
        val r = renderer()
        val (bytes, _, _) = export(r, ExportRequest(ExportFormat.FLAC, bits = 24))
        val f = T.tmp(".flac").also { it.writeBytes(bytes) }
        val b = T.ffmpeg("-i", f.path, "-f", "s32le", "-c:a", "pcm_s32le", "-").stdout
        val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val got = IntArray(b.size / 4) { bb.getInt() shr 8 }
        assertArrayEquals(quantize(renderAll(r), 24, false, 0), got)
    }

    @Test fun resample44to48() {
        val r = renderer(fs = 44_100, frames = 44_100)
        val (bytes, plan, res) = export(r, ExportRequest(ExportFormat.WAV, sampleRate = 48_000))
        assertEquals(48_000, plan.outSpec.sampleRate)
        assertEquals(Resampler.outputLength(r.length, 44_100, 48_000), res.frames)
        // То же, что ресемплер на целом рендере.
        val x = renderAll(r)
        val rs = Resampler(44_100, 48_000, 2)
        val y = FloatArray((res.frames * 2).toInt() + rs.maxOutput(x.size / 2) * 2)
        var n = rs.process(x, x.size / 2, y)
        val tail = FloatArray(rs.maxOutput(0) * 2); val m = rs.flush(tail)
        System.arraycopy(tail, 0, y, n * 2, m * 2); n += m
        assertEquals(res.frames, n.toLong())
        val w = WavReader(ByteArraySource(bytes))
        val got = IntArray(n * 2); w.readInt(0, got, n)
        assertArrayEquals(quantize(y.copyOf(n * 2), 24, false, 0), got)
        assertEquals(48_000, FileFacts.wav(ByteArraySource(bytes)).sampleRate)
    }

    @Test fun clippedSamplesCounted() {   // FR-14 / FR-C7: +12 дБ на пике −6 dBFS
        val amp = Math.pow(10.0, -6.0 / 20)
        val x = FloatArray(48_000 * 2) { (amp * Math.sin(2 * Math.PI * 440 * (it / 2) / 48_000.0)).toFloat() }
        val r = Renderer(ArrayPcmSource(PcmSpec(48_000, 2), x), EditList(listOf(EditOp.Volume(null, 12.0))), settings)
        val exp = renderAll(r).count { Math.abs(it) > 1f }.toLong()
        assertTrue(exp > 0)
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.WAV))
        val pipe = ExportPipeline(r, plan, 1L)
        assertEquals(exp, pipe.countClipped())
        assertEquals(exp, pipe.writeTo(ByteArraySink()).clippedSamples)
    }

    @Test fun cancel() {
        val r = renderer()
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.FLAC))
        val p = object : Progress { var k = 0; override fun report(fraction: Double) { k++ }; override val isCancelled get() = k > 2 }
        try { ExportPipeline(r, plan, 1L, 1000).writeTo(ByteArraySink(), p); fail() } catch (_: CancelledException) {}
    }

    private fun sine(frames: Int, amp: Double) = FloatArray(frames * 2) { (amp * Math.sin(2 * Math.PI * 997 * (it / 2) / 48_000.0)).toFloat() }

    @Test fun normalizeMinus14ByFfmpeg() {   // FR-22: −14 ± 0,1 LU, TP ≤ −1,0 + 1e-3
        T.need("ffmpeg")
        val s = ArrayPcmSource(PcmSpec(48_000, 2), noise(48_000 * 12, 2, 5, 0.05f))
        val base = EditList(listOf(EditOp.Fade(Range(0, 48_000), true, FadeCurve.EQUAL_POWER)))
        val n = Renderer(s, base, settings).resolveNormalize(EditOp.Normalize(NormalizeTarget.Lufs(-14.0)))
        val r = Renderer(s, base.plus(n), settings)
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(ExportFormat.FLAC))
        val f = T.tmp(".flac")
        FileSink(f).use { ExportPipeline(r, plan, 1L).writeTo(it) }
        val (i, _) = Ebur128.measure(f)
        assertEquals(-14.0, i, 0.1)
        val dec = T.decodeF32(f)
        val m = LoudnessMeter(48_000, 2).also { it.process(dec, dec.size / 2) }.result()
        assertEquals(-14.0, m.integratedLufs, 0.1)
        assertTrue("TP ${m.truePeakDbtp}", m.truePeakDbtp <= -1.0 + 1e-3)
    }

    @Test fun m4aThroughAdapter() {   // FR-25: elst = задержка, длина = N
        T.need("ffmpeg", "ffprobe")
        val r = renderer(fs = 44_100, frames = 60_000)
        val plan = ExportPlanner.plan(r.spec, 16, ExportRequest(ExportFormat.M4A))
        val f = T.tmp(".m4a")
        val enc = FfmpegEncoderSink(44_100, 2, false, f)
        val res = ExportPipeline(r, plan).encodeTo(enc)
        assertEquals(r.length, res.frames)
        val rep = FileSource(f).use { Verifier.verify(it, VerifyExpect.of(plan, res, enc.delay)) }
        assertTrue(rep.message, rep.ok)
        assertEquals("${res.frames}", T.probe(f, "stream=duration_ts")["duration_ts"])
    }

    @Test fun opusThroughAdapter() {   // FR-25: гранула − pre_skip = N₄₈; ffmpeg декодирует N ± 1
        T.need("ffmpeg")
        val r = renderer()
        val plan = ExportPlanner.plan(r.spec, 16, ExportRequest(ExportFormat.OPUS))
        val f = T.tmp(".ogg")
        val enc = FfmpegEncoderSink(48_000, 2, true, f)
        val res = ExportPipeline(r, plan).encodeTo(enc)
        val rep = FileSource(f).use { Verifier.verify(it, VerifyExpect.of(plan, res, enc.delay)) }
        assertTrue(rep.message, rep.ok)
        val dec = T.decodeF32(f)
        assertTrue("длина ${dec.size / 2} vs ${res.frames}", Math.abs(dec.size / 2 - res.frames) <= 1)
    }
}
