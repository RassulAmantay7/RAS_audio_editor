package com.rassul.audioeditor.core.verify

import com.rassul.audioeditor.core.format.FlacWriter
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.io.ByteArraySink
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.PcmSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Свой FLAC-декодер: на файлах ffmpeg (LPC, разные блоки) = декодирование ffmpeg; MD5 по векторам fr33_flac_md5. */
class FlacDecoderTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** edit_signal: x[n,c] = float32(((n·7919 + 13·c) mod 2001) − 1000)/1024, L = 4800, C = 2. */
    private fun editSignal() = FloatArray(4800 * 2) { i -> val n = i / 2; val c = i % 2; (((n * 7919 + 13 * c) % 2001) - 1000).toFloat() / 1024f }

    private fun decodeInts(bytes: ByteArray): Pair<IntArray, FlacDecoder.Result> {
        val l = ArrayList<Int>()
        val r = FlacDecoder(ByteArraySource(bytes)).decode { s, n -> for (i in 0 until n * 2) l += s[i] }
        return l.toIntArray() to r
    }

    @Test fun md5Vectors() {
        val x = editSignal()
        val i16 = IntArray(x.size) { (x[it] * 32767f).toInt() }
        val i24 = IntArray(x.size) { Math.rint(x[it].toDouble() * 8388607).toInt() }
        for ((ints, bits, md5) in listOf(Triple(i16, 16, "01bd03cc62bc6d6482dc96a42a6db498"), Triple(i24, 24, "7ef2647fbfcb8e08b25b8c1a494c5d45"))) {
            val sink = ByteArraySink()
            FlacWriter(sink, PcmSpec(48_000, 2), bits, 1152).apply { writeInt(ints, 4800); finish() }
            val (dec, r) = decodeInts(sink.toByteArray())
            assertNull(r.error)
            assertEquals(0, r.crcErrors)
            assertArrayEquals(ints, dec)
            assertEquals(md5, hex(r.md5))
        }
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(java.security.MessageDigest.getInstance("MD5").digest()))
    }

    @Test fun ffmpegEncodedFiles() {
        T.need("ffmpeg")
        for ((fixture, fmt) in listOf("wav_s16_44100_2ch_45100.wav" to "s16", "wav_s24_48000_2ch_4800.wav" to "s32")) {
            for (level in listOf("0", "8")) {
                val f = T.tmp(".flac")
                T.ffmpeg("-i", T.fixture(fixture).path, "-c:a", "flac", "-compression_level", level,
                    *(if (fmt == "s32") arrayOf("-sample_fmt", "s32") else emptyArray()), f.path)
                val raw = T.ffmpeg("-i", f.path, "-f", "${fmt}le", "-c:a", "pcm_${fmt}le", "-").stdout
                val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val exp = if (fmt == "s16") IntArray(raw.size / 2) { bb.getShort().toInt() } else IntArray(raw.size / 4) { bb.getInt() shr 8 }
                val (dec, r) = decodeInts(f.readBytes())
                assertNull(r.error)
                assertEquals("$fixture/$level", 0, r.crcErrors)
                assertArrayEquals("$fixture/$level", exp, dec)
                assertEquals(hex(FlacDecoder(FileSource(f)).streamInfo.md5), hex(r.md5))
            }
        }
    }

    @Test fun truncatedReportsError() {
        val sink = ByteArraySink()
        val ints = IntArray(48_000 * 2) { ((it * 7919) % 30000) - 15000 }
        FlacWriter(sink, PcmSpec(48_000, 2), 16).apply { writeInt(ints, 48_000); finish() }
        val b = sink.toByteArray()
        val r = FlacDecoder(ByteArraySource(b.copyOf(b.size * 9 / 10))).decode()
        assert(r.error != null || r.crcErrors > 0)
        assert(r.frames < 48_000)
    }
}
