package com.rassul.audioeditor.core.format.raw

import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.io.RandomAccessSource

/** Окно буферизованного чтения для последовательного сканирования больших файлов. */
class SrcWindow(private val src: RandomAccessSource, private val block: Int = 1 shl 18) {
    val size: Long get() = src.size
    private var bufPos = -1L
    private var buf = ByteArray(0)

    private fun load(pos: Long) {
        bufPos = pos
        buf = Src.readUpTo(src, pos, block)
    }
    /** Байт по смещению или −1 за концом. */
    fun u8(pos: Long): Int {
        if (pos < 0 || pos >= src.size) return -1
        if (pos < bufPos || pos >= bufPos + buf.size) load(pos)
        return buf[(pos - bufPos).toInt()].toInt() and 0xFF
    }
    /** n байт с pos (null, если не хватает). */
    fun bytes(pos: Long, n: Int): ByteArray? {
        if (pos < 0 || pos + n > src.size) return null
        if (pos >= bufPos && pos + n <= bufPos + buf.size) return buf.copyOfRange((pos - bufPos).toInt(), (pos - bufPos).toInt() + n)
        if (n <= block / 2) { load(pos); return buf.copyOfRange(0, n) }
        return Src.readFully(src, pos, n)
    }
}

/** Кадр сжатого потока: смещение и размер в файле, длительность в сэмплах. */
open class CodedFrame(val offset: Long, val size: Int, val samples: Int, val sampleRate: Int, val channels: Int)
