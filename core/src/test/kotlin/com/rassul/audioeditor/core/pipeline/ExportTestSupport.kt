package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.T
import com.rassul.audioeditor.core.format.ogg.OggReader
import com.rassul.audioeditor.core.format.ogg.OpusHead
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.format.raw.AdtsScanner
import com.rassul.audioeditor.core.io.FileSink
import com.rassul.audioeditor.core.io.FileSource
import java.io.File

/**
 * Тестовый «внешний энкодер» (вместо MediaCodec): копит PCM, кодирует ffmpeg (aac → ADTS, libopus → Ogg),
 * разбирает пакеты своими сканерами и подаёт их в адаптер контейнера (Mp4PacketSink / OggOpusPacketSink).
 */
class FfmpegEncoderSink(private val fs: Int, private val ch: Int, private val opus: Boolean, private val out: File) : PcmEncoderSink {
    private val raw = T.tmp(".f32")
    private val os = java.io.BufferedOutputStream(java.io.FileOutputStream(raw))
    private val bb = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    var delay = -1; private set

    override fun writePcm(pcm: FloatArray, frames: Int) {
        for (i in 0 until frames * ch) { bb.clear(); bb.putFloat(pcm[i]); os.write(bb.array()) }
    }

    override fun finish(totalFrames: Long) {
        os.close()
        val inArgs = arrayOf("-f", "f32le", "-ar", "$fs", "-ac", "$ch", "-i", raw.path)
        if (!opus) {
            val adts = T.tmp(".aac")
            T.ffmpeg(*inArgs, "-c:a", "aac", "-b:a", "192k", "-fflags", "+bitexact", "-flags:a", "+bitexact", "-f", "adts", adts.path)
            val frames = AdtsScanner.scan(FileSource(adts))
            val all = adts.readBytes()
            FileSink(out).use { s ->
                val sink = Mp4PacketSink(Mp4AacWriter(s, fs, ch, AdtsScanner.audioSpecificConfig(frames[0])))
                for (f in frames) sink.writePacket(all, (f.offset + f.headerSize).toInt(), f.size - f.headerSize, 1024)
                delay = 1024                       // задержка ffmpeg aac (timeline.md, измерено)
                sink.finish(delay, totalFrames)
            }
        } else {
            val ogg = T.tmp(".ogg")
            T.ffmpeg(*inArgs, "-c:a", "libopus", "-b:a", "128k", ogg.path)
            val s = OggReader.read(FileSource(ogg))
            val head = OpusHead.parse(s.packets[0].data)!!
            delay = head.preSkip
            FileSink(out).use { fsink ->
                val sink = OggOpusPacketSink(OggOpusWriter(fsink, ch, fs, head.preSkip))
                for (p in s.packets.drop(2)) if (p.data.isNotEmpty()) sink.writePacket(p.data, 0, p.data.size, OpusToc.samples48(p.data))
                sink.finish(delay, totalFrames)
            }
        }
    }
}

object Ebur128 {
    /** Интегральная громкость и true peak по ffmpeg ebur128 (независимый измеритель). */
    fun measure(f: File): Pair<Double, Double> {
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-nostats", "-i", f.path, "-af", "ebur128=peak=true:framelog=quiet", "-f", "null", "-")
            .redirectErrorStream(true).start()
        val text = String(p.inputStream.readBytes())
        p.waitFor()
        val i = Regex("""I:\s+(-?[\d.]+) LUFS""").findAll(text).last().groupValues[1].toDouble()
        val tp = Regex("""Peak:\s+(-?[\d.]+|-inf) dBFS""").findAll(text).last().groupValues[1].toDouble()
        return i to tp
    }
}
