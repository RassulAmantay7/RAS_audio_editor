package com.rassul.audioeditor.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.rassul.audioeditor.core.dsp.Quantizer
import com.rassul.audioeditor.core.format.Mp4AacWriter
import com.rassul.audioeditor.core.format.OggOpusWriter
import com.rassul.audioeditor.core.format.bits.u16le
import com.rassul.audioeditor.core.format.bits.u64le
import com.rassul.audioeditor.core.format.bits.u8
import com.rassul.audioeditor.core.format.ogg.OpusToc
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.pipeline.EncodedPacketSink
import com.rassul.audioeditor.core.pipeline.EncoderDelay
import com.rassul.audioeditor.core.pipeline.MediaMath
import com.rassul.audioeditor.core.pipeline.Mp4PacketSink
import com.rassul.audioeditor.core.pipeline.OggOpusPacketSink
import com.rassul.audioeditor.core.pipeline.PcmEncoderSink
import com.rassul.audioeditor.diag.DeviceProfile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * Зона MEDIA, T18. Энкодеры AAC-LC и Opus на MediaCodec — реализации PcmEncoderSink ядра (ExportPipeline.encodeTo).
 * Вход: float [−1, 1] (срез уже сделан ExportPipeline); в энкодер — 16 бит с TPDF-дизерингом (Quantizer, FR-C7)
 * или float, если энкодер его принимает и это включено в DeviceProfile.
 * Задержка энкодера (FR-25, timeline.md «Задержку энкодера Android … измерить»), приоритет:
 *   явный параметр (калибровка) → DeviceProfile (калибровано) → формат энкодера (KEY_ENCODER_DELAY / OpusHead pre-skip)
 *   → допущение (AAC 1024 — FDK/ffmpeg, Opus 312 — libopus) с записью в журнал.
 * Контейнер создаётся лениво — после первого вывода энкодера, когда известны csd-0 и задержка.
 */

/** Фабрика контейнера: формат выхода энкодера, csd (AudioSpecificConfig / OpusHead), задержка энкодера. */
typealias PacketSinkFactory = (outFormat: MediaFormat, csd: ByteArray, encoderDelay: Int) -> EncodedPacketSink

abstract class MediaCodecEncoderSink(
    protected val mime: String,
    protected val spec: PcmSpec,
    protected val bitrateKbps: Int,
    protected val profile: DeviceProfile?,
    private val makeSink: PacketSinkFactory,
    /** Явная задержка (калибровка самопроверкой); null — по приоритету выше. */
    protected val delayOverride: Int?,
    preferFloat: Boolean,
    ditherSeed: Long,
) : PcmEncoderSink {
    private val codec: MediaCodec
    private val floatInput: Boolean
    private val quantizer = Quantizer(16, true, ditherSeed)
    private val info = MediaCodec.BufferInfo()
    private var ints = IntArray(0)
    private var bytes = ByteArray(0)
    private var fed = 0L
    private var sink: EncodedPacketSink? = null
    private var outFormat: MediaFormat? = null
    private var config: ByteArray? = null
    private var eos = false
    /** Задержка, записанная в контейнер (после первого пакета). */
    var encoderDelay: Int = -1; private set
    var packets = 0L; private set
    val codecName: String get() = codec.name

    init {
        val f = MediaFormat.createAudioFormat(mime, spec.sampleRate, spec.channels)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1000)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        configureExtra(f)
        if (preferFloat) f.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
        codec = try { MediaCodec.createEncoderByType(mime) } catch (e: Exception) {
            throw CoreException("На этом телефоне нет энкодера ${name()}.", e)
        }
        try {
            try { codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) } catch (e: Exception) {
                if (!preferFloat) throw e
                f.removeKey(MediaFormat.KEY_PCM_ENCODING)
                codec.reset()
                codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            floatInput = preferFloat && codec.inputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
            codec.start()
        } catch (e: CoreException) { codec.release(); throw e
        } catch (e: Exception) {
            codec.release()
            throw CoreException("Энкодер ${name()} не принял параметры (${spec.sampleRate} Гц, ${spec.channels} кан., $bitrateKbps кбит/с).", e)
        }
        profile?.log("Энкодер ${codec.name}: ${spec.sampleRate} Гц, ${spec.channels} кан., $bitrateKbps кбит/с, вход ${if (floatInput) "float" else "16 бит + TPDF"}")
    }

    protected abstract fun name(): String
    protected open fun configureExtra(f: MediaFormat) {}
    /** Длительность пакета в кадрах шкалы контейнера. */
    protected abstract fun packetFrames(b: ByteArray, len: Int): Int
    /** csd для контейнера из буфера CODEC_CONFIG и/или выходного формата. */
    protected abstract fun csd(config: ByteArray?, fmt: MediaFormat?): ByteArray
    protected abstract fun delayFromEncoder(config: ByteArray?, fmt: MediaFormat?): Int?
    protected abstract val defaultDelay: Int
    protected abstract val delayFromProfile: Int?

    override fun writePcm(pcm: FloatArray, frames: Int) {
        val ch = spec.channels
        val n = frames * ch
        val bps = if (floatInput) 4 else 2
        if (bytes.size < n * bps) bytes = ByteArray(n * bps)
        if (floatInput) {
            ByteBuffer.wrap(bytes, 0, n * 4).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(pcm, 0, n)
        } else {
            if (ints.size < n) ints = IntArray(n)
            quantizer.quantize(pcm, n, ints)   // FR-C7: TPDF ±1 LSB, half-up
            var k = 0
            for (i in 0 until n) { val v = ints[i]; bytes[k++] = v.toByte(); bytes[k++] = (v shr 8).toByte() }
        }
        feed(bytes, n * bps, frames, bps * ch)
    }

    private fun feed(b: ByteArray, len: Int, frames: Int, frameBytes: Int) {
        var off = 0
        while (off < len) {
            val ii = codec.dequeueInputBuffer(10_000)
            if (ii >= 0) {
                val ib = codec.getInputBuffer(ii)!!
                ib.clear()
                val m = minOf(len - off, ib.remaining() / frameBytes * frameBytes)
                ib.put(b, off, m)
                val pts = MediaMath.framesToUs(fed, spec.sampleRate)
                codec.queueInputBuffer(ii, 0, m, pts, 0)
                fed += m / frameBytes
                off += m
            }
            drain(0)
        }
    }

    override fun finish(totalFrames: Long) {
        try {
            while (true) {
                val ii = codec.dequeueInputBuffer(10_000)
                if (ii >= 0) { codec.queueInputBuffer(ii, 0, 0, MediaMath.framesToUs(fed, spec.sampleRate), MediaCodec.BUFFER_FLAG_END_OF_STREAM); break }
                drain(0)
            }
            var guard = 0
            while (!eos && guard++ < 3000) drain(10_000)
            if (!eos) throw CoreException("Энкодер ${name()} не завершил работу.")
            val s = sink ?: throw CoreException("Энкодер ${name()} не выдал ни одного пакета.")
            // FR-25: m4a — elst(media_time = delay, segment = N); Ogg — pre_skip = delay, гранула = delay + N.
            s.finish(encoderDelay, totalFrames)
        } finally {
            release()
        }
    }

    /** Освободить энкодер (при отмене — вызвать вместо finish). */
    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
    }

    private fun drain(timeoutUs: Long) {
        while (true) {
            val oi = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                oi == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outFormat = codec.outputFormat
                oi >= 0 -> {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also { ob.get(it) }
                    val flags = info.flags
                    codec.releaseOutputBuffer(oi, false)
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = data
                    else if (data.isNotEmpty()) {
                        val s = sink ?: openSink()
                        s.writePacket(data, 0, data.size, packetFrames(data, data.size))
                        packets++
                    }
                    if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; return }
                }
            }
        }
    }

    private fun openSink(): EncodedPacketSink {
        val fmt = outFormat ?: codec.outputFormat
        val fromEnc = delayFromEncoder(config, fmt)
        // Ровно один источник (без сложения формата и профиля) — EncoderDelay.choose (:core, аудит T21).
        val (d, src) = EncoderDelay.choose(delayOverride, delayFromProfile, fromEnc, defaultDelay)
        profile?.log("Задержка энкодера ${name()}: $d кадров (${src.text})")
        encoderDelay = d
        return makeSink(fmt, csd(config, fmt), d).also { sink = it }
    }
}

/** AAC-LC (ISO/IEC 14496-3), 1024 кадра на пакет. */
class AacEncoderSink(
    spec: PcmSpec, bitrateKbps: Int, profile: DeviceProfile?, makeSink: PacketSinkFactory,
    delayOverride: Int? = null, preferFloat: Boolean = profile?.preferFloatEncoderInput ?: false, ditherSeed: Long = System.nanoTime(),
) : MediaCodecEncoderSink("audio/mp4a-latm", spec, bitrateKbps, profile, makeSink, delayOverride, preferFloat, ditherSeed) {
    override fun name() = "AAC"
    override fun configureExtra(f: MediaFormat) { f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC) }
    override fun packetFrames(b: ByteArray, len: Int) = 1024
    override val defaultDelay = 1024
    override val delayFromProfile: Int? get() = profile?.encoderDelayAac
    override fun delayFromEncoder(config: ByteArray?, fmt: MediaFormat?): Int? =
        fmt?.takeIf { it.containsKey(MediaFormat.KEY_ENCODER_DELAY) }?.getInteger(MediaFormat.KEY_ENCODER_DELAY, 0)?.takeIf { it > 0 }
    /** csd-0 = AudioSpecificConfig (ISO/IEC 14496-3 §1.6.2.1). */
    override fun csd(config: ByteArray?, fmt: MediaFormat?): ByteArray =
        config ?: fmt?.let { GaplessResolver.csd(it, "csd-0") } ?: throw CoreException("Энкодер AAC не выдал AudioSpecificConfig.")
}

/** Opus (RFC 6716/7845), всегда 48 кГц (ExportPlanner). */
class OpusEncoderSink(
    spec: PcmSpec, bitrateKbps: Int, profile: DeviceProfile?, makeSink: PacketSinkFactory,
    delayOverride: Int? = null, preferFloat: Boolean = profile?.preferFloatEncoderInput ?: false, ditherSeed: Long = System.nanoTime(),
) : MediaCodecEncoderSink("audio/opus", spec, bitrateKbps, profile, makeSink, delayOverride, preferFloat, ditherSeed) {
    override fun name() = "Opus"
    override fun packetFrames(b: ByteArray, len: Int) = OpusToc.samples48(b, 0, len)
    override val defaultDelay = 312
    override val delayFromProfile: Int? get() = profile?.encoderDelayOpus

    override fun delayFromEncoder(config: ByteArray?, fmt: MediaFormat?): Int? {
        val head = opusHead(config, fmt)
        if (head != null && head.size >= 12) return head.u16le(10)          // pre_skip (RFC 7845 §5.1)
        fmt?.let { GaplessResolver.csd(it, "csd-1") }?.takeIf { it.size >= 8 }?.let { return MediaMath.nsToFrames(it.u64le(0), 48000).toInt() }
        return null
    }

    override fun csd(config: ByteArray?, fmt: MediaFormat?): ByteArray = opusHead(config, fmt) ?: ByteArray(0)

    companion object {
        /** OpusHead из буфера конфигурации (Android пакует его как «AOPUSHDR»+длина+OpusHead+…) или из csd-0. */
        fun opusHead(config: ByteArray?, fmt: MediaFormat?): ByteArray? {
            for (b in listOfNotNull(config, fmt?.let { GaplessResolver.csd(it, "csd-0") })) {
                val i = GaplessResolver.indexOf(b, "OpusHead")
                if (i >= 0 && b.size >= i + 19) {
                    val family = b.u8(i + 18)
                    val len = if (family == 0) 19 else 21 + b.u8(i + 9)
                    return b.copyOfRange(i, minOf(b.size, i + len))
                }
            }
            return null
        }
    }
}

/** Запасной путь FR-25 (DeviceProfile.useMediaMuxer*): MediaMuxer. elst/гранулу конца он пишет сам — длина не гарантируется. */
class MuxerPacketSink(fd: java.io.FileDescriptor, outputFormat: Int, private val format: MediaFormat, private val rate: Int,
                      private val profile: DeviceProfile?) : EncodedPacketSink {
    private val muxer = MediaMuxer(fd, outputFormat)
    private val track = muxer.addTrack(format)
    private val bi = MediaCodec.BufferInfo()
    private var frames = 0L
    private var buf = ByteBuffer.allocate(1 shl 14)
    init { muxer.start() }

    override fun writePacket(data: ByteArray, off: Int, len: Int, frames: Int) {
        if (buf.capacity() < len) buf = ByteBuffer.allocate(len)
        buf.clear(); buf.put(data, off, len); buf.flip()
        bi.set(0, len, MediaMath.framesToUs(this.frames, rate), MediaCodec.BUFFER_FLAG_KEY_FRAME)
        muxer.writeSampleData(track, buf, bi)
        this.frames += frames
    }

    override fun finish(encoderDelay: Int, validFrames: Long) {
        profile?.log("MediaMuxer: задержка $encoderDelay и длина $validFrames не записываются точно (запасной режим FR-25)")
        try { muxer.stop() } finally { muxer.release() }
    }

    fun abort() { try { muxer.release() } catch (_: Exception) {} }
}

/** Фабрики «энкодер → контейнер» с выбором пути по DeviceProfile. */
object Encoders {
    fun aacToM4a(target: OutputTarget, spec: PcmSpec, bitrateKbps: Int, profile: DeviceProfile, delayOverride: Int? = null, ditherSeed: Long = System.nanoTime()): AacEncoderSink =
        AacEncoderSink(spec, bitrateKbps, profile, { fmt, asc, _ ->
            if (profile.useMediaMuxerForAac) MuxerPacketSink(target.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, fmt, spec.sampleRate, profile)
            else Mp4PacketSink(Mp4AacWriter(target.sink, spec.sampleRate, spec.channels, asc, 1024))
        }, delayOverride, ditherSeed = ditherSeed)

    fun opusToOgg(target: OutputTarget, spec: PcmSpec, inputSampleRate: Int, bitrateKbps: Int, profile: DeviceProfile, delayOverride: Int? = null, ditherSeed: Long = System.nanoTime()): OpusEncoderSink =
        OpusEncoderSink(spec, bitrateKbps, profile, { fmt, _, delay ->
            if (profile.useMediaMuxerForOpus) MuxerPacketSink(target.fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, fmt, 48000, profile)
            else OggOpusPacketSink(OggOpusWriter(target.sink, spec.channels, inputSampleRate, delay))
        }, delayOverride, ditherSeed = ditherSeed)
}
