package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.dsp.Quantizer
import com.rassul.audioeditor.core.dsp.Resampler
import com.rassul.audioeditor.core.edit.Renderer
import com.rassul.audioeditor.core.format.FlacWriter
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.WavSampleFormat
import com.rassul.audioeditor.core.format.WavWriter
import com.rassul.audioeditor.core.io.SeekableSink
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.Progress

/*
 * Экспорт (агент EDIT). FR-24, FR-25, FR-C6, FR-C7, FR-14.
 * Цепочка: Renderer (тот же, что для плеера, FR-13) → Resampler (FR-C6, только если частота меняется)
 * → для WAV/FLAC: Quantizer (FR-C7) → WavWriter/FlacWriter; для M4A/Opus: срез [−1, 1] → внешний энкодер.
 */

/**
 * Внешний энкодер сжатого формата (MediaCodec AAC/Opus в :app). Получает PCM float [−1, 1]
 * (чередование, частота и каналы — ExportPlan.outSpec) и отдаёт пакеты в [EncodedPacketSink].
 */
interface PcmEncoderSink {
    /** Очередной блок PCM. Массив переиспользуется после возврата — копировать при необходимости. */
    fun writePcm(pcm: FloatArray, frames: Int)
    /** Конец потока: [totalFrames] — полная длина поданного PCM (= ожидаемая длина файла, FR-25). */
    fun finish(totalFrames: Long)
}

/** Приёмник пакетов энкодера = контейнер. Длительности — в кадрах шкалы контейнера (Opus: 48 кГц). */
interface EncodedPacketSink {
    fun writePacket(data: ByteArray, off: Int, len: Int, frames: Int)
    /**
     * Завершить файл: [encoderDelay] — задержка (priming) энкодера, [validFrames] — полезная длина
     * (timeline.md: m4a elst.media_time = delay, segment = N; Ogg: гранула = pre_skip + N₄₈).
     */
    fun finish(encoderDelay: Int, validFrames: Long)
}

/**
 * Удержание хвостовых пакетов энкодера (аудит устройства: c2.android.aac.encoder выдаёт на пакет больше, а Opus без
 * дополнительной тишины — меньше; энкодер в :app подаёт перед EOS тишину delay + кадр). Последние ≥ [holdbackFrames]
 * кадров пакетов держатся в памяти; в [finish] записываются только пакеты, пока их сумма не покроет delay + N
 * (K = ⌈(delay + N)/F⌉ для AAC — условие Mp4Timing; Ogg — гранула конца preSkip + N ≤ Σ, RFC 7845 §4.4).
 * Остальное — заполнение после конца сигнала, отбрасывается. Пакетов не хватает — решает писатель (ошибка).
 */
class TailPacketHoldback(private val holdbackFrames: Long, private val out: (ByteArray, Int) -> Unit) {
    private val q = ArrayDeque<Pair<ByteArray, Int>>()
    private var qFrames = 0L
    /** Кадров в уже отданных писателю пакетах. */
    var writtenFrames = 0L; private set

    fun add(data: ByteArray, off: Int, len: Int, frames: Int) {
        q.addLast(data.copyOfRange(off, off + len) to frames); qFrames += frames
        while (q.size > 1 && qFrames - q.first().second >= holdbackFrames) pop()
    }

    private fun pop() { val (d, f) = q.removeFirst(); qFrames -= f; out(d, f); writtenFrames += f }

    /** Записать пакеты, пока Σ < [needed]; вернуть число отброшенных хвостовых пакетов. */
    fun finish(needed: Long): Int {
        while (q.isNotEmpty() && writtenFrames < needed) pop()
        val dropped = q.size
        q.clear(); qFrames = 0
        return dropped
    }

    companion object { const val DEFAULT_HOLDBACK = 96_000L }
}

/**
 * Адаптер к .m4a: задержка → elst.media_time (ISO/IEC 14496-12 §8.6.6), длина → segment_duration.
 * Лишние хвостовые пакеты (сверх ⌈(delay + N)/F⌉) отбрасываются — TailPacketHoldback.
 */
class Mp4PacketSink(val writer: Mp4AacWriter, holdbackFrames: Long = TailPacketHoldback.DEFAULT_HOLDBACK) : EncodedPacketSink {
    private val hold = TailPacketHoldback(holdbackFrames) { d, _ -> writer.writePacket(d, 0, d.size) }
    /** Сколько хвостовых пакетов отброшено при finish (для журнала). */
    var droppedTailPackets = 0; private set
    override fun writePacket(data: ByteArray, off: Int, len: Int, frames: Int) = hold.add(data, off, len, frames)
    override fun finish(encoderDelay: Int, validFrames: Long) {
        droppedTailPackets = hold.finish(encoderDelay + validFrames)
        writer.finish(encoderDelay, validFrames)
    }
}

/**
 * Адаптер к .ogg (Opus): pre_skip задаётся при создании OggOpusWriter (он пишется в OpusHead первым),
 * поэтому [finish] требует, чтобы encoderDelay совпал с writer.preSkip (RFC 7845 §4.2, §4.4).
 * frames — длительность пакета в кадрах 48 кГц (по TOC, RFC 6716 §3.1).
 */
class OggOpusPacketSink(val writer: OggOpusWriter, holdbackFrames: Long = TailPacketHoldback.DEFAULT_HOLDBACK) : EncodedPacketSink {
    private val hold = TailPacketHoldback(holdbackFrames) { d, f -> writer.writePacket(d, 0, d.size, f) }
    /** Сколько пакетов после покрывающего preSkip + N отброшено (для журнала). */
    var droppedTailPackets = 0; private set
    override fun writePacket(data: ByteArray, off: Int, len: Int, frames: Int) = hold.add(data, off, len, frames)
    override fun finish(encoderDelay: Int, validFrames: Long) {
        require(encoderDelay == writer.preSkip) { "задержка энкодера $encoderDelay ≠ pre_skip ${writer.preSkip}" }
        droppedTailPackets = hold.finish(encoderDelay + validFrames)
        writer.finish(validFrames)
    }
}

/** Итог экспорта. */
data class ExportResult(
    /** Записано кадров (после ресемплинга) — ожидаемая длина для FR-33. */
    val frames: Long,
    /** Сэмплов с |x| > 1,0 (срезаны; для WAV float — сохранены как есть, но посчитаны). FR-14, FR-C7. */
    val clippedSamples: Long,
    /** Сид дизеринга (для журнала, dsp.md FR-C7); null — дизеринга не было. */
    val ditherSeed: Long?,
    /**
     * MD5 записанного PCM в представлении RFC 9639 §8.2 (знаковые целые LE по ⌈bits/8⌉ байт; для float —
     * байты float32 LE). Для FLAC = ожидаемый MD5 STREAMINFO, для WAV = MD5 области data. null — сжатый формат.
     */
    val pcmMd5: ByteArray?,
)

/**
 * Экспорт результата правок по плану (ExportPlanner). Каналы рендера должны совпадать с plan.outSpec.channels
 * (сведение FR-11 — при декодировании). Не потокобезопасен; долгий — не из UI-потока.
 */
class ExportPipeline(
    val renderer: Renderer,
    val plan: ExportPlan,
    /** Сид дизеринга TPDF (FR-C7); фиксируется для воспроизводимости и пишется в журнал. */
    val ditherSeed: Long = System.nanoTime(),
    val blockFrames: Int = 8192,
) {
    private val inRate = renderer.spec.sampleRate
    private val outRate = plan.outSpec.sampleRate
    private val ch = renderer.spec.channels

    init {
        require(plan.outSpec.channels == ch) { "каналы рендера $ch ≠ плана ${plan.outSpec.channels}" }
        require(blockFrames > 0)
    }

    /** Ожидаемая длина файла (FR-C6: N_out = round(N_in·fs_out/fs_in), Resampler.outputLength). */
    val expectedFrames: Long get() = Resampler.outputLength(renderer.length, inRate, outRate)

    /**
     * Прогон цепочки до точки среза: Renderer → (Resampler) → consumer(блок, кадры).
     * Прогресс — по доле отрендеренных кадров; отмена → CancelledException.
     */
    private fun run(progress: Progress, consumer: (FloatArray, Int) -> Unit): Long {
        renderer.prepare(progress)
        val total = renderer.length
        val buf = FloatArray(blockFrames * ch)
        val rs = if (inRate != outRate) Resampler(inRate, outRate, ch) else null
        val rbuf = rs?.let { FloatArray(maxOf(it.maxOutput(blockFrames), it.maxOutput(0)) * ch) }
        var pos = 0L
        var out = 0L
        while (pos < total) {
            if (progress.isCancelled) throw CancelledException()
            val n = renderer.render(pos, buf, minOf(blockFrames.toLong(), total - pos).toInt())
            if (n <= 0) break
            pos += n
            if (rs == null) { consumer(buf, n); out += n }
            else { val m = rs.process(buf, n, rbuf!!); if (m > 0) { consumer(rbuf, m); out += m } }
            progress.report(pos.toDouble() / maxOf(1L, total))
        }
        if (rs != null) { val m = rs.flush(rbuf!!); if (m > 0) { consumer(rbuf, m); out += m } }
        check(out == expectedFrames) { "длина после ресемплинга $out ≠ ожидаемой $expectedFrames" }
        return out
    }

    /** Подсчёт сэмплов, которые будут срезаны (|x| > 1,0), — «сообщается перед экспортом» (FR-C7, FR-14). */
    fun countClipped(progress: Progress = Progress.NONE): Long {
        var c = 0L
        run(progress) { b, n -> for (i in 0 until n * ch) if (b[i] > 1f || b[i] < -1f) c++ }
        return c
    }

    /** Экспорт WAV или FLAC в [sink]. */
    fun writeTo(sink: SeekableSink, progress: Progress = Progress.NONE): ExportResult {
        val md5 = java.security.MessageDigest.getInstance("MD5")
        var clipped = 0L
        when (plan.format) {
            ExportFormat.WAV -> if (plan.bits == 32) {
                val w = WavWriter(sink, plan.outSpec, WavSampleFormat.FLOAT32)
                var bytes = ByteArray(0)
                val frames = run(progress) { b, n ->
                    if (bytes.size < n * ch * 4) bytes = ByteArray(n * ch * 4)
                    var k = 0
                    for (i in 0 until n * ch) {
                        val v = b[i]
                        if (v > 1f || v < -1f) clipped++
                        val bits = java.lang.Float.floatToRawIntBits(v)
                        bytes[k++] = bits.toByte(); bytes[k++] = (bits shr 8).toByte(); bytes[k++] = (bits shr 16).toByte(); bytes[k++] = (bits shr 24).toByte()
                    }
                    md5.update(bytes, 0, k)
                    w.writeFloat(b, n)
                }
                w.finish()
                return ExportResult(frames, clipped, null, md5.digest())
            } else {
                val fmt = if (plan.bits == 24) WavSampleFormat.PCM24 else WavSampleFormat.PCM16
                val w = WavWriter(sink, plan.outSpec, fmt)
                val r = quantized(progress, md5) { s, n -> w.writeInt(s, n) }
                w.finish()
                return r
            }
            ExportFormat.FLAC -> {
                val w = FlacWriter(sink, plan.outSpec, plan.bits)
                val r = quantized(progress, md5) { s, n -> w.writeInt(s, n) }
                w.finish()
                return r
            }
            else -> throw IllegalArgumentException("${plan.format}: сжатый формат — используйте encodeTo()")
        }
    }

    private fun quantized(progress: Progress, md5: java.security.MessageDigest, write: (IntArray, Int) -> Unit): ExportResult {
        val bits = plan.bits
        require(bits == 16 || bits == 24) { "bits=$bits" }
        // FR-C7: дизеринг TPDF только для 16 бит; 24 бит — без дизеринга, даже если план его просит.
        val dither = plan.dither && bits == 16
        val q = Quantizer(bits, dither, ditherSeed)
        var ints = IntArray(0)
        val nb = bits / 8
        var bytes = ByteArray(0)
        var clipped = 0L
        val frames = run(progress) { b, n ->
            if (ints.size < n * ch) { ints = IntArray(n * ch); bytes = ByteArray(n * ch * nb) }
            clipped += q.quantize(b, n * ch, ints)
            // MD5 по RFC 9639 §8.2: знаковые целые, чередование, LE, ⌈bits/8⌉ байт.
            var k = 0
            for (i in 0 until n * ch) { var v = ints[i]; repeat(nb) { bytes[k++] = v.toByte(); v = v shr 8 } }
            md5.update(bytes, 0, k)
            write(ints, n)
        }
        return ExportResult(frames, clipped, if (dither) ditherSeed else null, md5.digest())
    }

    /** Экспорт M4A/Opus через внешний энкодер: PCM со срезом [−1, 1] (энкодеры ждут номинальный диапазон). */
    fun encodeTo(encoder: PcmEncoderSink, progress: Progress = Progress.NONE): ExportResult {
        require(plan.format == ExportFormat.M4A || plan.format == ExportFormat.OPUS) { "${plan.format}: используйте writeTo()" }
        var clipped = 0L
        val frames = run(progress) { b, n ->
            for (i in 0 until n * ch) {
                val v = b[i]
                if (v > 1f) { b[i] = 1f; clipped++ } else if (v < -1f) { b[i] = -1f; clipped++ }
            }
            encoder.writePcm(b, n)
        }
        encoder.finish(frames)
        return ExportResult(frames, clipped, null, null)
    }
}
