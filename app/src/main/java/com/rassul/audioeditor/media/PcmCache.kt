package com.rassul.audioeditor.media

import android.content.Context
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSource
import com.rassul.audioeditor.core.model.PcmSpec
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/*
 * Зона MEDIA, T17. FR-10: дисковый кэш PCM — float32 LE, чередование каналов, исходная частота,
 * каналы после сведения (FR-11). Размер = кадры·C·4 байт (SpaceEstimator.pcmCacheBytes).
 * Запись — одним потоком (декодер) блоками по ~1 МБ; чтение — позиционное и synchronized
 * (плеер, экспорт и UI читают одновременно).
 */
class PcmCache private constructor(val file: File, override val spec: PcmSpec) : PcmSource, Closeable {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
    private val channel: FileChannel = raf.channel
    private val frameBytes = 4 * spec.channels
    private val wbuf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 20).order(ByteOrder.LITTLE_ENDIAN)
    private var rbuf: ByteBuffer = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var frames = 0L
    @Volatile private var writing = true
    @Volatile private var closed = false

    /** Длина в кадрах (во время записи — уже сброшенное на диск). */
    override val length: Long get() = frames
    val isComplete: Boolean get() = !writing

    /** Дописать кадры (только поток декодера). */
    fun append(interleaved: FloatArray, n: Int) {
        check(writing) { "кэш уже завершён" }
        var i = 0
        val total = n * spec.channels
        while (i < total) {
            if (wbuf.remaining() < 4) flushWrite()
            val k = minOf(total - i, wbuf.remaining() / 4)
            wbuf.asFloatBuffer().put(interleaved, i, k)
            wbuf.position(wbuf.position() + k * 4)
            i += k
        }
        if (wbuf.remaining() < frameBytes) flushWrite()
    }

    private fun flushWrite() {
        wbuf.flip()
        // Пишем только целые кадры; остаток (неполный кадр) переносим в начало буфера.
        val whole = wbuf.remaining() / frameBytes * frameBytes
        val lim = wbuf.limit()
        wbuf.limit(whole)
        try {
            synchronized(this) {
                var pos = frames * frameBytes
                while (wbuf.hasRemaining()) pos += channel.write(wbuf, pos)
                frames += whole / frameBytes
            }
        } catch (e: java.io.IOException) {
            val m = e.message ?: ""
            throw CoreException(if (m.contains("ENOSPC") || m.contains("No space")) "Недостаточно места для временного файла." else "Ошибка записи временного файла.", e)
        }
        wbuf.limit(lim)
        wbuf.compact()
    }

    /** Завершить запись (сбросить буфер). */
    fun finishWriting() {
        if (!writing) return
        flushWrite()
        writing = false
    }

    /** Отбросить последние [n] кадров (хвост, FR-26) — только во время записи или сразу после. */
    @Synchronized
    fun truncateTail(n: Long) {
        if (writing) flushWrite()
        frames = maxOf(0L, frames - n)
        channel.truncate(frames * frameBytes)
    }

    @Synchronized
    override fun read(from: Long, dst: FloatArray, frames: Int): Int {
        if (closed || frames <= 0 || from < 0 || from >= this.frames) return 0
        val n = minOf(frames.toLong(), this.frames - from).toInt()
        val bytes = n * frameBytes
        if (rbuf.capacity() < bytes) rbuf = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
        rbuf.clear(); rbuf.limit(bytes)
        var pos = from * frameBytes
        while (rbuf.hasRemaining()) {
            val r = channel.read(rbuf, pos)
            if (r <= 0) break
            pos += r
        }
        rbuf.flip()
        val got = rbuf.remaining() / frameBytes
        rbuf.asFloatBuffer().get(dst, 0, got * spec.channels)
        return got
    }

    /** Закрыть и удалить файл кэша (FR-10). */
    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            try { channel.close() } catch (_: Exception) {}
            try { raf.close() } catch (_: Exception) {}
        }
        file.delete()
    }

    companion object {
        private const val DIR = "pcm"

        fun dir(ctx: Context): File = File(ctx.cacheDir, DIR).apply { mkdirs() }

        fun create(ctx: Context, spec: PcmSpec): PcmCache = create(dir(ctx), spec)

        fun create(dir: File, spec: PcmSpec): PcmCache {
            dir.mkdirs()
            return PcmCache(File.createTempFile("pcm", ".f32", dir), spec)
        }

        /** Удалить остатки кэшей после сбоя (FR-10). Вызывать при запуске приложения, до открытия файлов. */
        fun cleanupStale(ctx: Context) {
            val t0 = startedAt; File(ctx.cacheDir, DIR).listFiles()?.forEach { if (it.lastModified() < t0) it.delete() }
        }
    }
}
