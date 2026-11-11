package com.rassul.audioeditor.core.verify

import com.rassul.audioeditor.core.edit.EditList
import com.rassul.audioeditor.core.edit.RenderSettings
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.edit.noise
import com.rassul.audioeditor.core.edit.src
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportPipeline
import com.rassul.audioeditor.core.pipeline.ExportPlanner
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.core.pipeline.FfmpegEncoderSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-33 (часть :core): корректные файлы → «Проверено», усечённые на 10 % → понятная ошибка, без исключений. */
class VerifierTest {
    private val r = Renderer(src(noise(48_000, 2, 9), 2), EditList(), RenderSettings())

    private fun export(fmt: ExportFormat, bits: Int? = null): Pair<ByteArray, VerifyExpect> {
        val plan = ExportPlanner.plan(r.spec, 24, ExportRequest(fmt, bits = bits))
        val sink = ByteArraySink()
        val res = ExportPipeline(r, plan, 1L).writeTo(sink)
        return sink.toByteArray() to VerifyExpect.of(plan, res)
    }

    private fun cut(b: ByteArray) = b.copyOf(b.size - b.size / 10)

    @Test fun wav() {
        val (b, e) = export(ExportFormat.WAV)
        val ok = Verifier.verify(ByteArraySource(b), e)
        assertTrue(ok.message, ok.ok)
        assertEquals("Проверено: длина совпала (0:01.000), WAV 24 бит 48 кГц стерео", ok.message)
        val bad = Verifier.verify(ByteArraySource(cut(b)), e)
        assertFalse(bad.ok)
        assertTrue(bad.message, bad.message.startsWith("Файл записан с ошибкой: длина 0:00.900"))
        assertTrue(bad.message.contains("вместо 0:01.000 (48000 сэмпл.)"))
        // Порча данных при той же длине → MD5.
        val c = b.copyOf(); c[c.size - 5] = (c[c.size - 5] + 1).toByte()
        assertTrue(Verifier.verify(ByteArraySource(c), e).message.contains("MD5"))
        // Другие параметры.
        assertTrue(Verifier.verify(ByteArraySource(b), e.copy(sampleRate = 44_100)).message.contains("частота 48 кГц вместо 44,1 кГц"))
    }

    @Test fun flac() {
        val (b, e) = export(ExportFormat.FLAC, 16)
        assertTrue(Verifier.verify(ByteArraySource(b), e, flacFullDecode = true).ok)
        val bad = Verifier.verify(ByteArraySource(cut(b)), e, flacFullDecode = true)
        assertFalse(bad.ok)
        assertTrue(bad.message, bad.message.startsWith("Файл записан с ошибкой: длина"))
        val wrongMd5 = Verifier.verify(ByteArraySource(b), e.copy(pcmMd5 = ByteArray(16) { 1 }))
        assertTrue(wrongMd5.message.contains("MD5"))
    }

    @Test fun garbageNeverThrows() {
        for (fmt in ExportFormat.values()) {
            val e = VerifyExpect(fmt, 48_000, 2, if (fmt == ExportFormat.FLAC || fmt == ExportFormat.WAV) 16 else 0, 1000)
            for (bytes in listOf(ByteArray(0), ByteArray(100) { it.toByte() })) {
                val rep = Verifier.verify(ByteArraySource(bytes), e)
                assertFalse(rep.ok)
                assertTrue(rep.message, rep.message.startsWith("Файл записан с ошибкой"))
            }
        }
    }

    @Test fun truncatedCompressed() {
        T.need("ffmpeg")
        for (opus in listOf(false, true)) {
            val fmt = if (opus) ExportFormat.OPUS else ExportFormat.M4A
            val plan = ExportPlanner.plan(r.spec, 16, ExportRequest(fmt))
            val f = T.tmp(if (opus) ".ogg" else ".m4a")
            val enc = FfmpegEncoderSink(48_000, 2, opus, f)
            val res = ExportPipeline(r, plan).encodeTo(enc)
            val e = VerifyExpect.of(plan, res, enc.delay)
            assertTrue(FileSource(f).use { Verifier.verify(it, e) }.ok)
            val bad = Verifier.verify(ByteArraySource(cut(f.readBytes())), e)
            assertFalse(bad.ok)
            assertTrue(bad.message, bad.message.startsWith("Файл записан с ошибкой"))
            // Неверная задержка — ошибка.
            assertFalse(FileSource(f).use { Verifier.verify(it, e.copy(encoderDelay = 7)) }.ok)
        }
    }

    @Test fun bitrateVectors() {   // fr33_bitrate: 45 МБ / 32 МБ за 30 мин при 192 кбит/с
        val e = VerifyExpect(ExportFormat.M4A, 48_000, 2, 0, 86_400_000, bitrateKbps = 192)
        assertNull(Verifier.bitrateWarning(e, 45_000_000, 86_400_000, false))
        assertTrue(Verifier.bitrateWarning(e, 32_000_000, 86_400_000, false)!!.contains("142 кбит/с"))
        assertNull(Verifier.bitrateWarning(e, 32_000_000, 86_400_000, true))       // Opus: только превышение
        assertNull(Verifier.bitrateWarning(e, 1, 48_000 * 5, false))                // < 10 с — не проверять
    }
}
