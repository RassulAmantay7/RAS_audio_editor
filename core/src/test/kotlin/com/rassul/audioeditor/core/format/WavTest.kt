package com.rassul.audioeditor.core.format

import com.rassul.audioeditor.core.format.wav.WAVE_FORMAT_EXTENSIBLE
import com.rassul.audioeditor.core.format.wav.WAVE_FORMAT_IEEE_FLOAT
import com.rassul.audioeditor.core.format.wav.WAVE_FORMAT_PCM
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest

class WavTest {
    private fun ints(n: Int, ch: Int, bits: Int): IntArray {
        val max = (1 shl (bits - 1)) - 1
        var x = 12345L
        return IntArray(n * ch) { i ->
            when (i) { 0 -> max; 1 -> -max - 1; else -> { x = (x * 6364136223846793005L + 1442695040888963407L); ((x ushr 20).toInt() % (max + 1)) } }
        }
    }

    private fun write(spec: PcmSpec, fmt: WavSampleFormat, s: IntArray?, f: FloatArray?, frames: Int): Pair<ByteArray, WavWriter> {
        val sink = ByteArraySink()
        val w = WavWriter(sink, spec, fmt)
        if (s != null) { w.writeInt(s.copyOfRange(0, 7 * spec.channels), 7); w.writeInt(s.copyOfRange(7 * spec.channels, s.size), frames - 7) }
        if (f != null) w.writeFloat(f, frames)
        w.finish()
        return sink.toByteArray() to w
    }

    @Test fun roundTripAllFormats() {
        for ((spec, fmt) in listOf(PcmSpec(44100, 2) to WavSampleFormat.PCM16, PcmSpec(48000, 1) to WavSampleFormat.PCM24,
                PcmSpec(48000, 6) to WavSampleFormat.PCM16, PcmSpec(96000, 2) to WavSampleFormat.FLOAT32, PcmSpec(48000, 8) to WavSampleFormat.FLOAT32)) {
            val n = 1001
            val s = if (!fmt.isFloat) ints(n, spec.channels, fmt.bits) else null
            val f = if (fmt.isFloat) FloatArray(n * spec.channels) { ((it * 37) % 2001 - 1000) / 1024f } else null
            val (bytes, w) = write(spec, fmt, s, f, n)
            val r = WavReader(ByteArraySource(bytes))
            assertEquals(spec, r.spec); assertEquals(fmt.bits, r.bitsPerSample); assertEquals(fmt.isFloat, r.isFloat)
            assertEquals(n.toLong(), r.lengthFrames)
            val expTag = if (spec.channels > 2 || (!fmt.isFloat && fmt.bits > 16)) WAVE_FORMAT_EXTENSIBLE else if (fmt.isFloat) WAVE_FORMAT_IEEE_FLOAT else WAVE_FORMAT_PCM
            assertEquals(expTag, r.info.formatTag)
            assertEquals(bytes.size.toLong(), r.info.dataOffset + r.info.declaredDataSize + (r.info.declaredDataSize and 1))
            // MD5 области data = MD5 байтов, отданных писателю (FR-33).
            val md = MessageDigest.getInstance("MD5").also { it.update(bytes, r.info.dataOffset.toInt(), r.info.declaredDataSize.toInt()) }.digest()
            assertArrayEquals(md, w.dataMd5)
            val dst = FloatArray(n * spec.channels)
            assertEquals(n, r.read(0, dst, n))
            if (s != null) {
                val scale = (1 shl (fmt.bits - 1)).toFloat()
                for (i in s.indices) assertEquals(s[i] / scale, dst[i], 0f)
                val di = IntArray(n * spec.channels); r.readInt(0, di, n); assertArrayEquals(s, di)
            } else assertArrayEquals(f, dst, 0f)
        }
    }

    @Test fun oddMono24PadByte() {
        val (bytes, _) = write(PcmSpec(8000, 1), WavSampleFormat.PCM24, ints(9, 1, 24), null, 9)
        assertEquals(0, bytes.size % 2)
        assertEquals(9L, WavReader(ByteArraySource(bytes)).lengthFrames)
    }

    @Test fun fixturesAllBitDepthsSameValues() {
        // s24/s32/f32/f64 сделаны ffmpeg из одного int16-сигнала → после перевода во float значения совпадают точно (FR-3).
        val ref = WavReader(T.src("wav_s24_48000_2ch_4800.wav"))
        assertEquals(24, ref.bitsPerSample); assertEquals(WAVE_FORMAT_EXTENSIBLE, ref.info.formatTag); assertEquals(4800L, ref.lengthFrames)
        val a = FloatArray(9600); ref.read(0, a, 4800)
        for ((name, bits, isF) in listOf(Triple("wav_s32_48000_2ch_4800.wav", 32, false), Triple("wav_f32_48000_2ch_4800.wav", 32, true), Triple("wav_f64_48000_2ch_4800.wav", 64, true))) {
            val r = WavReader(T.src(name))
            assertEquals(name, bits, r.bitsPerSample); assertEquals(isF, r.isFloat); assertEquals(PcmSpec(48000, 2), r.spec)
            val b = FloatArray(9600); assertEquals(4800, r.read(0, b, 4800))
            assertArrayEquals(name, a, b, 0f)
        }
        val e = WavReader(T.src("wav_ext_s16_48000_6ch_4800.wav"))
        assertEquals(6, e.spec.channels); assertEquals(0x3F, e.channelMask); assertEquals(4800L, e.lengthFrames)
        val u8 = WavReader(T.src("wav_u8_8000_1ch_801.wav")); assertEquals(8, u8.bitsPerSample); assertEquals(801L, u8.lengthFrames)
        val odd = WavReader(T.src("wav_oddchunks_s16_8000_1ch_801.wav")); assertEquals(801L, odd.lengthFrames)
        val x = FloatArray(801); val y = FloatArray(801); u8.read(0, x, 801); odd.read(0, y, 801)
        for (i in 0 until 801) assertEquals(y[i], x[i], 1f / 128)   // тот же сигнал в 8 бит
        val s16 = WavReader(T.src("wav_s16_44100_2ch_45100.wav")); assertEquals(45100L, s16.lengthFrames)
    }

    @Test fun truncatedFixture() {
        val i = FileFacts.wav(T.src("wav_truncated_s16_44100_2ch_declared4410_3969.wav"))
        assertTrue(i.truncated); assertEquals(4410L, i.declaredFrames); assertEquals(3969L, i.frames)
    }

    @Test fun notWav() {
        assertTrue(!WavReader.isWav(T.src("aac_44100_2ch_45100.m4a")))
        try { WavReader(T.src("container_avi_44100.avi")); fail() } catch (e: CoreException) { assertTrue(e.message!!.contains("WAV")) }
    }

    @Test fun ffmpegDecodesBitExact() {
        T.need("ffmpeg", "ffprobe")
        for ((spec, fmt) in listOf(PcmSpec(44100, 2) to WavSampleFormat.PCM16, PcmSpec(48000, 2) to WavSampleFormat.PCM24,
                PcmSpec(48000, 6) to WavSampleFormat.PCM24, PcmSpec(22050, 1) to WavSampleFormat.FLOAT32)) {
            val n = 3001
            val s = if (!fmt.isFloat) ints(n, spec.channels, fmt.bits) else null
            val f = if (fmt.isFloat) FloatArray(n) { ((it * 37) % 2001 - 1000) / 1024f } else null
            val file = T.tmp(".wav")
            FileSink(file).use { val w = WavWriter(it, spec, fmt); if (s != null) w.writeInt(s, n) else w.writeFloat(f!!, n); w.finish() }
            val codec = when (fmt) { WavSampleFormat.PCM16 -> "s16le"; WavSampleFormat.PCM24 -> "s24le"; else -> "f32le" }
            val raw = T.ffmpeg("-i", file.path, "-f", codec, "-c:a", "pcm_$codec", "-").stdout
            val data = java.io.File(file.path).readBytes().let { it.copyOfRange(it.size - raw.size - (raw.size and 1), it.size - (raw.size and 1)) }
            assertArrayEquals("$spec $fmt", data, raw)
            val p = T.probe(file, "stream=sample_rate,channels,duration_ts,bits_per_sample")
            assertEquals("${spec.sampleRate}", p["sample_rate"]); assertEquals("${spec.channels}", p["channels"])
            assertEquals("$n", p["duration_ts"]); assertEquals("${fmt.bits}", p["bits_per_sample"])
        }
    }
}
