package com.rassul.audioeditor.core.format.bits

import com.rassul.audioeditor.core.io.RandomAccessSource

/*
 * Общие утилиты форматов: буфер байтов с записью LE/BE, писатель битов (MSB first),
 * читатель битов, контрольные суммы CRC.
 */

/** Растущий буфер байтов с записью little/big-endian. */
class ByteBuf(initial: Int = 256) {
    var data = ByteArray(initial); private set
    var size = 0; private set

    private fun ensure(n: Int) {
        if (size + n > data.size) data = data.copyOf(maxOf(size + n, data.size * 2))
    }
    fun u8(v: Int): ByteBuf { ensure(1); data[size++] = v.toByte(); return this }
    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteBuf {
        ensure(len); System.arraycopy(b, off, data, size, len); size += len; return this
    }
    fun ascii(s: String): ByteBuf { for (c in s) u8(c.code); return this }
    fun zeros(n: Int): ByteBuf { ensure(n); java.util.Arrays.fill(data, size, size + n, 0); size += n; return this }
    fun u16le(v: Int) = u8(v).u8(v ushr 8)
    fun u24le(v: Int) = u8(v).u8(v ushr 8).u8(v ushr 16)
    fun u32le(v: Long) = u16le(v.toInt()).u16le((v ushr 16).toInt())
    fun u32le(v: Int) = u32le(v.toLong() and 0xFFFFFFFFL)
    fun u64le(v: Long) = u32le(v and 0xFFFFFFFFL).u32le(v ushr 32)
    fun u16be(v: Int) = u8(v ushr 8).u8(v)
    fun u24be(v: Int) = u8(v ushr 16).u8(v ushr 8).u8(v)
    fun u32be(v: Long) = u16be((v ushr 16).toInt()).u16be(v.toInt())
    fun u32be(v: Int) = u32be(v.toLong() and 0xFFFFFFFFL)
    fun u64be(v: Long) = u32be(v ushr 32).u32be(v and 0xFFFFFFFFL)
    fun putU32beAt(pos: Int, v: Long) {
        data[pos] = (v ushr 24).toByte(); data[pos + 1] = (v ushr 16).toByte()
        data[pos + 2] = (v ushr 8).toByte(); data[pos + 3] = v.toByte()
    }
    fun toByteArray(): ByteArray = data.copyOf(size)
    fun clear() { size = 0 }
}

/** Писатель битов, старший бит первым (FLAC, RFC 9639 §9: все поля big-endian, MSB first). */
class BitWriter(initial: Int = 1 shl 14) {
    var buf = ByteArray(initial); private set
    private var len = 0            // целых байт
    private var acc = 0L           // накопитель
    private var nacc = 0           // бит в накопителе (< 8 после flushAcc)

    val bitLength: Long get() = len * 8L + nacc
    /** Целых байт, уже сброшенных из накопителя (вызывать после flushedBuffer()/alignByte()). */
    val byteLength: Int get() = len

    private fun ensure(n: Int) { if (len + n > buf.size) buf = buf.copyOf(maxOf(len + n, buf.size * 2)) }

    /** Записать n (0..32) младших бит value. */
    fun write(value: Int, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (value.toLong() and ((1L shl n) - 1))
        nacc += n
        if (nacc >= 32) drain()
    }
    fun writeLong(value: Long, n: Int) {
        if (n > 32) { write((value ushr 32).toInt(), n - 32); write(value.toInt(), 32) } else write(value.toInt(), n)
    }
    /** Знаковое число в n битах (дополнительный код). */
    fun writeSigned(value: Int, n: Int) = write(value, n)
    /** Унарный код: q нулей и единица (RFC 9639 §9.2.7.1, Rice: частное — унарно). */
    fun writeUnary(q: Int) {
        var z = q
        while (z >= 32) { write(0, 32); z -= 32 }
        write(1, z + 1)
    }
    private fun drain() {
        ensure(8)
        while (nacc >= 8) { nacc -= 8; buf[len++] = (acc ushr nacc).toByte() }
        acc = acc and ((1L shl nacc) - 1)
    }
    /** Дополнить нулями до границы байта. */
    fun alignByte() { if (nacc % 8 != 0) write(0, 8 - nacc % 8); drain() }
    fun isAligned(): Boolean = nacc % 8 == 0
    /** Байты (только при выравнивании). */
    fun bytes(): ByteArray { drain(); check(nacc == 0); return buf.copyOf(len) }
    fun reset() { len = 0; acc = 0; nacc = 0 }
    fun writeBytes(b: ByteArray, off: Int, n: Int) { for (i in 0 until n) write(b[off + i].toInt() and 0xFF, 8) }
    /** Внутренний буфер после выравнивания: [0, byteLength). */
    fun flushedBuffer(): ByteArray { drain(); check(nacc == 0); return buf }
}

/** Читатель битов MSB first из массива. */
class BitReader(private val b: ByteArray, private var bytePos: Int = 0, private val end: Int = b.size) {
    private var bit = 0
    val position: Long get() = bytePos * 8L + bit
    fun read(n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            if (bytePos >= end) throw IndexOutOfBoundsException("bit reader past end")
            v = (v shl 1) or ((b[bytePos].toInt() ushr (7 - bit)) and 1)
            if (++bit == 8) { bit = 0; bytePos++ }
        }
        return v
    }
    fun readLong(n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 1) or read(1).toLong(); return v }
    fun skip(n: Int) { val t = position + n; bytePos = (t / 8).toInt(); bit = (t % 8).toInt() }
}

object Crc {
    /** CRC-8, полином x^8+x^2+x+1 (0x07), начальное 0 — заголовок кадра FLAC, RFC 9639 §9.1.8. */
    private val T8 = IntArray(256) { i -> var c = i; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }; c and 0xFF }
    fun crc8(b: ByteArray, off: Int, len: Int, init: Int = 0): Int {
        var c = init
        for (i in off until off + len) c = T8[(c xor b[i].toInt()) and 0xFF]
        return c
    }

    /** CRC-16, полином x^16+x^15+x^2+1 (0x8005), начальное 0, MSB first — конец кадра FLAC, RFC 9639 §9.3. */
    private val T16 = IntArray(256) { i -> var c = i shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }; c and 0xFFFF }
    fun crc16(b: ByteArray, off: Int, len: Int, init: Int = 0): Int {
        var c = init
        for (i in off until off + len) c = ((c shl 8) xor T16[((c ushr 8) xor b[i].toInt()) and 0xFF]) and 0xFFFF
        return c
    }

    /** CRC-16/ARC (отражённый 0x8005 = 0xA001, начальное 0) — «Info Tag CRC» тега LAME (Mp3 Info Tag rev 1, байты 0..189 кадра). */
    private val TARC = IntArray(256) { i -> var c = i; repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }; c }
    fun crc16Arc(b: ByteArray, off: Int, len: Int, init: Int = 0): Int {
        var c = init
        for (i in off until off + len) c = (c ushr 8) xor TARC[(c xor b[i].toInt()) and 0xFF]
        return c and 0xFFFF
    }

    /** CRC-32 Ogg: полином 0x04C11DB7, начальное 0, без отражения и финального XOR (RFC 3533 §6). */
    private val TOGG = IntArray(256) { i -> var c = i shl 24; repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }; c }
    fun crc32Ogg(b: ByteArray, off: Int, len: Int, init: Int = 0): Int {
        var c = init
        for (i in off until off + len) c = (c shl 8) xor TOGG[((c ushr 24) xor b[i].toInt()) and 0xFF]
        return c
    }
}

/** Чтение примитивов из RandomAccessSource. */
object Src {
    fun readFully(src: RandomAccessSource, pos: Long, len: Int): ByteArray? {
        if (pos < 0 || len < 0 || pos + len > src.size) return null
        val b = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = src.read(pos + done, b, done, len - done)
            if (n <= 0) return null
            done += n
        }
        return b
    }
    /** Прочитать до len байт (меньше у конца файла). */
    fun readUpTo(src: RandomAccessSource, pos: Long, len: Int): ByteArray {
        val n = maxOf(0L, minOf(len.toLong(), src.size - pos)).toInt()
        return readFully(src, pos, n) ?: ByteArray(0)
    }
}

fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xFF
fun ByteArray.u16le(i: Int): Int = u8(i) or (u8(i + 1) shl 8)
fun ByteArray.u24le(i: Int): Int = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
fun ByteArray.u32le(i: Int): Long = (u16le(i).toLong() or (u16le(i + 2).toLong() shl 16))
fun ByteArray.u64le(i: Int): Long = u32le(i) or (u32le(i + 4) shl 32)
fun ByteArray.u16be(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
fun ByteArray.u24be(i: Int): Int = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
fun ByteArray.u32be(i: Int): Long = (u16be(i).toLong() shl 16) or u16be(i + 2).toLong()
fun ByteArray.u64be(i: Int): Long = (u32be(i) shl 32) or u32be(i + 4)
fun ByteArray.ascii(i: Int, n: Int): String = String(CharArray(n) { (this[i + it].toInt() and 0xFF).toChar() })
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
