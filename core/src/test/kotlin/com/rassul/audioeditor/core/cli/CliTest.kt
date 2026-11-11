package com.rassul.audioeditor.core.cli

import com.rassul.audioeditor.core.edit.noise
import com.rassul.audioeditor.core.format.FileFacts
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.WavSampleFormat
import com.rassul.audioeditor.core.format.WavWriter
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.Ebur128
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** CLI: wav → правки → flac/wav, автопроверка; ошибки аргументов — код 2 и понятный текст. */
class CliTest {
    private fun wav(frames: Int): java.io.File {
        val f = T.tmp(".wav")
        val x = noise(frames, 2, 3, 0.05f)
        val ints = IntArray(x.size) { Math.round(x[it] * 32767f) }
        FileSink(f).use { WavWriter(it, PcmSpec(48_000, 2), WavSampleFormat.PCM16).apply { writeInt(ints, frames); finish() } }
        return f
    }

    private fun run(vararg a: String): Triple<Int, String, String> {
        val o = ByteArrayOutputStream(); val e = ByteArrayOutputStream()
        val code = runCli(arrayOf(*a), PrintStream(o, true, "UTF-8"), PrintStream(e, true, "UTF-8"))
        return Triple(code, o.toString("UTF-8"), e.toString("UTF-8"))
    }

    @Test fun normalizeFadeToFlac() {
        val inp = wav(48_000 * 6)
        val out = T.tmp(".flac")
        val (code, text, err) = run(inp.path, "--delete", "1.000", "2.000", "--fade-in", "3", "--norm", "-14", "--out", out.path)
        assertEquals(err + text, 0, code)
        assertTrue(text, text.contains("Проверено: длина совпала (0:05.000), FLAC 16 бит 48 кГц стерео"))
        assertEquals(48_000L * 5, FileSource(out).use { FileFacts.flacStreamInfo(it).totalSamples })
        if (T.has("ffmpeg")) assertEquals(-14.0, Ebur128.measure(out).first, 0.1)
        // Вход FLAC → WAV 24 бит.
        val out2 = T.tmp(".wav")
        val (c2, t2, _) = run(out.path, "--keep", "0:01", "0:02.5", "--gain", "-6", "--bits", "24", "--out", out2.path)
        assertEquals(t2, 0, c2)
        assertEquals(72_000L, FileSource(out2).use { FileFacts.wav(it).frames })
    }

    @Test fun argumentErrors() {
        val inp = wav(4800)
        assertEquals(2, run(inp.path, "--delete", "0", "0.1", "--out", "/tmp/x.mp3").first)
        val (c, _, e) = run(inp.path, "--delete", "0", "0.1", "--out", T.tmp(".wav").path)
        assertEquals(2, c)
        assertTrue(e, e.contains("Нельзя удалить весь файл"))
        val (c3, _, e3) = run(inp.path, "--keep", "1:60", "2", "--out", T.tmp(".wav").path)
        assertEquals(2, c3); assertTrue(e3.startsWith("Ошибка: «1:60»"))
        assertEquals(2, run().first)
    }
}
