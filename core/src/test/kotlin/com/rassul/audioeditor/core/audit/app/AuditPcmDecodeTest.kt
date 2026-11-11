package com.rassul.audioeditor.core.audit.app

import com.rassul.audioeditor.core.pipeline.PcmDecode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PCM выхода MediaCodec → float (dsp.md, 16 бит ×1/32768) и NaN → 0 (timeline.md). Аудит T21. */
class AuditPcmDecodeTest {
    private fun buf(n: Int, order: ByteOrder = ByteOrder.LITTLE_ENDIAN) = ByteBuffer.allocate(n).order(order)

    @Test fun pcm16ScaleAndExactRoundTrip() {
        val b = buf(65536 * 2 + 6)
        for (v in -32768..32767) b.putShort(6 + 2 * (v + 32768), v.toShort())
        val dst = FloatArray(65536)
        PcmDecode.toFloat(b, 6, 16, false, 65536, dst)
        assertEquals(-1.0f, dst[0], 0f)
        assertEquals(32767f / 32768f, dst[65535], 0f)
        assertEquals(0f, dst[32768], 0f)
        // FR-33 FLAC MD5: f·2¹⁵ должно вернуть исходное целое для ВСЕХ 16-битных значений.
        for (i in 0 until 65536) assertEquals((i - 32768).toDouble(), dst[i] * 32768.0, 0.0)
    }

    @Test fun pcm16RespectsBufferOrder() {
        val be = buf(4, ByteOrder.BIG_ENDIAN); be.putShort(0, 0x1234); be.putShort(2, -2)
        val d = FloatArray(2)
        PcmDecode.toFloat(be, 0, 16, false, 2, d)
        assertEquals(0x1234 / 32768f, d[0], 0f); assertEquals(-2 / 32768f, d[1], 0f)
    }

    @Test fun pcm24PackedLe() {
        val vals = intArrayOf(-8388608, -1, 0, 1, 8388607, 0x123456, -0x123456)
        val b = buf(3 * vals.size + 1)
        for ((i, v) in vals.withIndex()) { val p = 1 + 3 * i; b.put(p, v.toByte()); b.put(p + 1, (v shr 8).toByte()); b.put(p + 2, (v shr 16).toByte()) }
        val d = FloatArray(vals.size)
        PcmDecode.toFloat(b, 1, 24, false, vals.size, d)
        for ((i, v) in vals.withIndex()) { assertEquals(v / 8388608.0, d[i].toDouble(), 0.0); assertEquals(v.toDouble(), d[i] * 8388608.0, 0.0) }
    }

    @Test fun pcm32AndFloatAnd8() {
        val b = buf(16); b.putInt(0, Int.MIN_VALUE); b.putInt(4, 1 shl 30); b.putInt(8, Int.MAX_VALUE); b.putInt(12, 0)
        val d = FloatArray(4)
        PcmDecode.toFloat(b, 0, 32, false, 4, d)
        assertEquals(-1f, d[0], 0f); assertEquals(0.5f, d[1], 0f); assertEquals(1f, d[2], 1e-7f); assertEquals(0f, d[3], 0f)

        val f = buf(12); f.putFloat(0, 0.25f); f.putFloat(4, -1.5f); f.putFloat(8, Float.NaN)
        val g = FloatArray(3); PcmDecode.toFloat(f, 0, 32, true, 3, g)
        assertEquals(0.25f, g[0], 0f); assertEquals(-1.5f, g[1], 0f); assertEquals(true, g[2].isNaN())

        val u = buf(3); u.put(0, 0); u.put(1, 128.toByte()); u.put(2, 255.toByte())
        val h = FloatArray(3); PcmDecode.toFloat(u, 0, 8, false, 3, h)
        assertEquals(-1f, h[0], 0f); assertEquals(0f, h[1], 0f); assertEquals(127f / 128f, h[2], 0f)
    }

    @Test fun bytesPerSample() {
        assertEquals(4, PcmDecode.bytesPerSample(32, true)); assertEquals(4, PcmDecode.bytesPerSample(16, true))
        assertEquals(4, PcmDecode.bytesPerSample(32, false)); assertEquals(3, PcmDecode.bytesPerSample(24, false))
        assertEquals(2, PcmDecode.bytesPerSample(16, false)); assertEquals(1, PcmDecode.bytesPerSample(8, false))
    }

    @Test fun sanitizeNanInf() {
        val a = floatArrayOf(1f, Float.NaN, Float.POSITIVE_INFINITY, -0.5f, Float.NEGATIVE_INFINITY, Float.NaN)
        assertEquals(3, PcmDecode.sanitize(a, 1, 4))   // [1, 5): NaN, +Inf, −0,5, −Inf
        assertEquals(listOf(1f, 0f, 0f, -0.5f, 0f), a.take(5)); assertEquals(true, a[5].isNaN())
        assertEquals(0, PcmDecode.sanitize(a, 0, 0))
    }
}
