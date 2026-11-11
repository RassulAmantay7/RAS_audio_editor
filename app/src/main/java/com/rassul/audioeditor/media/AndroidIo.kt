package com.rassul.audioeditor.media

import android.os.ParcelFileDescriptor
import com.rassul.audioeditor.core.io.RandomAccessSource
import com.rassul.audioeditor.core.io.SeekableSink
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/*
 * Зона MEDIA. Адаптеры ввода-вывода :core (RandomAccessSource/SeekableSink) поверх FileChannel.
 * Чтение — позиционное (FileChannel.read(buf, pos) = pread): потокобезопасно, не двигает общий указатель,
 * поэтому один дескриптор можно делить с MediaExtractor (он тоже читает через pread).
 */

/** RandomAccessSource поверх файлового дескриптора (контент-URI, открытый «r»). Не закрывает pfd сам. */
class FdSource(fd: FileDescriptor) : RandomAccessSource, Closeable {
    private val stream = FileInputStream(fd)
    val channel: FileChannel = stream.channel
    override val size: Long get() = channel.size()

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= size) return -1
        val bb = ByteBuffer.wrap(dst, off, len)
        var total = 0
        while (bb.hasRemaining()) {
            val r = channel.read(bb, pos + total)
            if (r <= 0) break
            total += r
        }
        return if (total == 0) -1 else total
    }

    /** Закрывает только поток-обёртку (сам pfd закрывает владелец). */
    override fun close() { /* FileInputStream(fd) не владеет fd — закрывать нельзя, иначе закроется pfd */ }

    companion object {
        /** Можно ли читать дескриптор по смещению (обычный файл, а не канал/сокет). */
        fun isSeekable(pfd: ParcelFileDescriptor): Boolean = try {
            if (pfd.statSize < 0) false else {
                val ch = FileInputStream(pfd.fileDescriptor).channel
                ch.size(); ch.read(ByteBuffer.allocate(1), 0); true
            }
        } catch (e: Exception) { false }
    }
}

/**
 * SeekableSink поверх FileChannel (позиционная запись; заголовки WAV/FLAC/MP4 дописываются после seek).
 * Для MediaStore: дескриптор, открытый «rw».
 */
class FileChannelSink(fd: FileDescriptor) : SeekableSink, Closeable {
    private val stream = FileOutputStream(fd)
    private val channel: FileChannel = stream.channel
    private var pos = 0L
    /** Сколько байт реально в файле (максимум записанного). */
    var size = 0L; private set
    /** Счётчик для прогресса/отладки. */
    var bytesWritten = 0L; private set

    override val position: Long get() = pos

    override fun write(b: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(b, off, len)
        var p = pos
        while (bb.hasRemaining()) p += channel.write(bb, p)
        pos = p
        if (pos > size) size = pos
        bytesWritten += len
    }

    override fun seek(pos: Long) { require(pos in 0..size) { "seek $pos за концом $size" }; this.pos = pos }

    override fun flush() { channel.force(false) }

    /** Обрезать файл по фактическому размеру (если дескриптор указывал на непустой файл). */
    fun truncateToSize() { try { if (channel.size() > size) channel.truncate(size) } catch (_: Exception) {} }

    override fun close() { /* fd закрывает владелец (ParcelFileDescriptor) */ }
}

/** Куда писать результат: seekable-приёмник для своих писателей и fd для MediaMuxer. */
interface OutputTarget {
    val sink: SeekableSink
    val fd: FileDescriptor
}

/** Время старта процесса: очистка кэшей при запуске удаляет только файлы старше (не трогает только что созданные). */
internal val startedAt: Long = System.currentTimeMillis() - 1000
