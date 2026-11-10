package com.rassul.audioeditor.core.io

/*
 * КОНТРАКТ (главная сессия). Ввод-вывод байтов для форматов. В :app реализуется через
 * FileChannel/ParcelFileDescriptor, в тестах — через RandomAccessFile/массив.
 */

/** Чтение файла по смещению. */
interface RandomAccessSource {
    val size: Long
    /** Прочитать до len байт с позиции pos; вернуть число прочитанных (−1 — конец). */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int
}

/** Запись с возможностью вернуться назад (для заголовков WAV/FLAC/MP4, размеры которых известны в конце). */
interface SeekableSink {
    val position: Long
    fun write(b: ByteArray, off: Int = 0, len: Int = b.size)
    fun seek(pos: Long)
    fun flush() {}
}

/** Реализация в памяти (тесты). */
class ByteArraySink : SeekableSink {
    private var buf = ByteArray(1 shl 16)
    private var size = 0
    private var pos = 0
    override val position: Long get() = pos.toLong()
    override fun write(b: ByteArray, off: Int, len: Int) {
        val end = pos + len
        if (end > buf.size) buf = buf.copyOf(maxOf(end, buf.size * 2))
        System.arraycopy(b, off, buf, pos, len); pos = end; if (end > size) size = end
    }
    override fun seek(pos: Long) { require(pos in 0..size.toLong()); this.pos = pos.toInt() }
    fun toByteArray(): ByteArray = buf.copyOf(size)
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), dst, off, n); return n
    }
}

/** Файл на диске (JVM и Android: java.io доступен в обоих). */
class FileSink(file: java.io.File) : SeekableSink, java.io.Closeable {
    private val raf = java.io.RandomAccessFile(file, "rw").also { it.setLength(0) }
    override val position: Long get() = raf.filePointer
    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
    override fun seek(pos: Long) = raf.seek(pos)
    override fun close() = raf.close()
}

class FileSource(file: java.io.File) : RandomAccessSource, java.io.Closeable {
    private val raf = java.io.RandomAccessFile(file, "r")
    override val size: Long get() = raf.length()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int { raf.seek(pos); return raf.read(dst, off, len) }
    override fun close() = raf.close()
}
