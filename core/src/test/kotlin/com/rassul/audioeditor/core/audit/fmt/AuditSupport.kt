package com.rassul.audioeditor.core.audit.fmt

import com.rassul.audioeditor.core.format.Json
import com.rassul.audioeditor.core.format.T
import java.io.File

/** Помощники аудита форматов (code-formula-auditor, T14/T15). */
object A {
    /** Векторы tools/ref/audit_fmt_gen.py (ожидания посчитаны функциями tools/ref/timeline_ref.py). */
    @Suppress("UNCHECKED_CAST")
    val vec: Map<String, Any?> by lazy { Json.parse(T.res("ref/audit_fmt_vectors.json").readText()) as Map<String, Any?> }

    /** Детерминированный ГСЧ (тесты воспроизводимы, seed в сообщении об ошибке). */
    class Rng(seed: Long) {
        private val r = java.util.SplittableRandom(seed)
        fun int(n: Int): Int = r.nextInt(n)
        fun range(lo: Int, hiIncl: Int): Int = lo + r.nextInt(hiIncl - lo + 1)
        fun long(lo: Long, hiIncl: Long): Long = lo + r.nextLong(hiIncl - lo + 1)
        fun bool(): Boolean = r.nextBoolean()
        fun <X> pick(xs: List<X>): X = xs[int(xs.size)]
        fun bytes(n: Int): ByteArray = ByteArray(n).also { for (i in it.indices) it[i] = r.nextInt(256).toByte() }
    }

    /** Побитовые CRC «по определению» (без таблиц) — независимая проверка Crc.* (RFC 9639 §9.1.8/§9.3, RFC 3533 §6). */
    fun crcBitwise(b: ByteArray, width: Int, poly: Long, init: Long = 0, reflect: Boolean = false): Long {
        val top = 1L shl (width - 1); val mask = (1L shl width) - 1
        var c = init
        if (!reflect) {
            for (x in b) {
                c = c xor ((x.toLong() and 0xFF) shl (width - 8))
                repeat(8) { c = if (c and top != 0L) ((c shl 1) xor poly) and mask else (c shl 1) and mask }
            }
        } else {
            val rp = java.lang.Long.reverse(poly) ushr (64 - width)
            for (x in b) {
                c = c xor (x.toLong() and 0xFF)
                repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor rp else c ushr 1 }
            }
        }
        return c and mask
    }

    /** Чередующиеся целые → байты LE шириной bytes (знаковые) — раскладка RFC 9639 §8.2 (MD5) и pcm_s16le/s24le. */
    fun pcmLe(s: IntArray, bytes: Int): ByteArray {
        val o = ByteArray(s.size * bytes)
        for (i in s.indices) for (k in 0 until bytes) o[i * bytes + k] = (s[i] shr (8 * k)).toByte()
        return o
    }

    fun md5(b: ByteArray): ByteArray = java.security.MessageDigest.getInstance("MD5").digest(b)

    fun write(bytes: ByteArray, suffix: String): File = T.tmp(suffix).also { it.writeBytes(bytes) }

    /** ffmpeg с -v error: (stdout, stderr). */
    fun ffmpegRaw(input: File, vararg outArgs: String): T.Out =
        T.run("ffmpeg", "-hide_banner", "-v", "error", "-threads", "1", "-i", input.path, *outArgs, "-")
}
