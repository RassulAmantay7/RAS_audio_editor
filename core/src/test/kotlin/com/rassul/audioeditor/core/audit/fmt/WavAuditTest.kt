package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.format.bits.ByteBuf
import com.rassul.audioeditor.core.io.ByteArraySource
import com.rassul.audioeditor.core.io.FileSource
import com.rassul.audioeditor.core.model.CoreException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Аудит WavReader: EXTENSIBLE (PCM/float, valid < container), RF64/ds64 (EBU Tech 3306), 8 бит беззнаковые, float64. */
class WavAuditTest {
    private val guidTail = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71)

    private fun wav(tag: Int, ch: Int, fs: Int, bits: Int, data: ByteArray, sub: Int = 1, valid: Int = bits, mask: Int = 3,
                    rf64: Boolean = false, extraChunk: Boolean = false): ByteArray {
        val h = ByteBuf(128)
        h.ascii(if (rf64) "RF64" else "RIFF").u32le(if (rf64) -1 else 0).ascii("WAVE")
        if (rf64) { h.ascii("ds64").u32le(28).u64le(0).u64le(data.size.toLong()).u64le(data.size.toLong() / (ch * bits / 8)).u32le(0) }
        if (extraChunk) h.ascii("LIST").u32le(3).bytes(byteArrayOf(1, 2, 3)).u8(0)       // нечётный чанк + pad
        val ba = ch * bits / 8
        if (tag == 0xFFFE) {
            h.ascii("fmt ").u32le(40).u16le(tag).u16le(ch).u32le(fs).u32le(fs * ba).u16le(ba).u16le(bits)
            h.u16le(22).u16le(valid).u32le(mask).u16le(sub).bytes(guidTail)
        } else h.ascii("fmt ").u32le(16).u16le(tag).u16le(ch).u32le(fs).u32le(fs * ba).u16le(ba).u16le(bits)
        h.ascii("data").u32le(if (rf64) -1 else data.size).bytes(data)
        return h.toByteArray()
    }

    @Test fun u8Unsigned() {
        val r = WavReader(ByteArraySource(wav(1, 1, 8000, 8, byteArrayOf(0, 1, 127, 128.toByte(), 129.toByte(), 255.toByte()))))
        val f = FloatArray(6); assertEquals(6, r.read(0, f, 6))
        assertArrayEquals(floatArrayOf(-1f, -127f / 128, -1f / 128, 0f, 1f / 128, 127f / 128), f, 0f)
        val i = IntArray(6); r.readInt(0, i, 6); assertArrayEquals(intArrayOf(-128, -127, -1, 0, 1, 127), i)
    }

    @Test fun extensiblePcm24In32AndFloat() {
        // 24 значащих бита в 32-битном контейнере (выравнивание влево): масштаб 2^31 — та же величина, что у 24 бит.
        val v = intArrayOf(8388607 shl 8, -8388608 shl 8, 1 shl 8, -(1 shl 8))
        val d = ByteArray(16).also { for (k in v.indices) for (b in 0 until 4) it[k * 4 + b] = (v[k] shr (8 * b)).toByte() }
        val r = WavReader(ByteArraySource(wav(0xFFFE, 2, 96000, 32, d, sub = 1, valid = 24, extraChunk = true)))
        assertEquals(24, r.bitsPerSample); assertEquals(2L, r.lengthFrames); assertEquals(3, r.channelMask); assertTrue(!r.isFloat)
        val f = FloatArray(4); r.read(0, f, 2)
        assertArrayEquals(floatArrayOf(8388607f / 8388608, -1f, 1f / 8388608, -1f / 8388608), f, 0f)
        // EXTENSIBLE + IEEE_FLOAT, 6 каналов.
        val fl = floatArrayOf(0.5f, -0.25f, 1e-7f, -1f, 0.999f, 3f)
        val fd = ByteArray(24).also { for (k in fl.indices) { val b = java.lang.Float.floatToRawIntBits(fl[k]); for (j in 0 until 4) it[k * 4 + j] = (b shr (8 * j)).toByte() } }
        val rf = WavReader(ByteArraySource(wav(0xFFFE, 6, 48000, 32, fd, sub = 3, mask = 0x3F)))
        assertTrue(rf.isFloat); assertEquals(0x3F, rf.channelMask)
        val o = FloatArray(6); rf.read(0, o, 1); assertArrayEquals(fl, o, 0f)
    }

    @Test fun float64() {
        val dv = doubleArrayOf(0.1, -0.5, 1.0 / 3, -1.0)
        val d = ByteArray(32).also { for (k in dv.indices) { val b = java.lang.Double.doubleToRawLongBits(dv[k]); for (j in 0 until 8) it[k * 8 + j] = (b shr (8 * j)).toByte() } }
        val r = WavReader(ByteArraySource(wav(3, 2, 44100, 64, d)))
        val f = FloatArray(4); r.read(0, f, 2)
        assertArrayEquals(FloatArray(4) { dv[it].toFloat() }, f, 0f)
    }

    @Test fun rf64Synthetic() {
        val s = ShortArray(1000) { ((it * 37) % 2001 - 1000).toShort() }
        val d = ByteArray(2000).also { for (k in s.indices) { it[2 * k] = s[k].toByte(); it[2 * k + 1] = (s[k].toInt() shr 8).toByte() } }
        val r = WavReader(ByteArraySource(wav(1, 2, 48000, 16, d, rf64 = true)))
        assertTrue(r.info.isRf64); assertEquals(500L, r.lengthFrames); assertTrue(!r.info.truncated)
        val i = IntArray(1000); r.readInt(0, i, 500); assertArrayEquals(IntArray(1000) { s[it].toInt() }, i)
    }

    /** RF64, записанный ffmpeg (-rf64 always), читается так же, как исходный WAV (чужой писатель). */
    @Test fun rf64FromFfmpeg() {
        T.need("ffmpeg")
        val srcF = T.fixture("wav_s24_48000_2ch_4800.wav")
        val out = T.tmp(".wav")
        T.ffmpeg("-i", srcF.path, "-c:a", "pcm_s24le", "-rf64", "always", out.path)
        val a = WavReader(FileSource(srcF)); val b = WavReader(FileSource(out))
        assertTrue(b.info.isRf64); assertEquals(a.lengthFrames, b.lengthFrames)
        val x = IntArray(9600); val y = IntArray(9600); a.readInt(0, x, 4800); b.readInt(0, y, 4800)
        assertArrayEquals(x, y)
    }

    @Test fun invalidInput() {
        for (bad in listOf(wav(1, 2, 48000, 12, ByteArray(6)), wav(2, 2, 48000, 16, ByteArray(4)), wav(3, 1, 48000, 16, ByteArray(4)),
                wav(1, 9, 48000, 16, ByteArray(18)), ByteArray(5)))
            try { WavReader(ByteArraySource(bad)); throw AssertionError("ожидалась CoreException") } catch (e: CoreException) { assertTrue(e.message!!.isNotBlank()) }
    }
}
