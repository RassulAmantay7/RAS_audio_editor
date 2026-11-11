package com.rassul.audioeditor.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import com.rassul.audioeditor.core.dsp.Downmix
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.model.CancelledException
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.model.PcmSpec
import com.rassul.audioeditor.core.model.Progress
import com.rassul.audioeditor.core.peaks.PeakPyramid
import com.rassul.audioeditor.core.pipeline.SpaceEstimator
import com.rassul.audioeditor.diag.DeviceProfile

/*
 * Зона MEDIA, T17. Декодирование дорожки в дисковый кэш PCM (FR-10) с построением пирамиды пиков (FR-12, FR-C8).
 * Цепочка: MediaDecoder (FR-7 → FR-26 → NaN→0) → Downmix по маске (FR-11, FR-C5) → PcmCache + PeakPyramid.
 * WAV читается своим кодом :core (FR-3), без MediaCodec.
 */

/** Итог декодирования. Владелец закрывает [cache] (удаляет файл) при закрытии документа. */
class DecodeResult(
    val cache: PcmCache,
    /** Пирамида пиков исходника (до правок). Писалась под synchronized(peaks). */
    val peaks: PeakPyramid,
    val spec: PcmSpec,
    /** Фактическая разрядность выхода декодера: 32 — float, 16 — 16 бит (FR-4). */
    val decodedBits: Int,
    /** Разрядность исходника (0 — сжатый/неизвестна) — для ExportPlanner.plan(srcBits). */
    val sourceBits: Int,
    /** Предупреждения для пользователя (FR-4 и т.п.). */
    val warnings: List<String>,
    val stats: DecodeStats?,
)

class DecodePipeline(
    private val ctx: Context,
    private val src: OpenedSource,
    private val track: AudioTrackInfo,
    private val probe: ProbeResult,
    private val profile: DeviceProfile = DeviceProfile.get(ctx),
) {
    interface Listener {
        /** Кэш и пирамида созданы (можно начинать рисовать волну, читая peaks под synchronized(peaks)). */
        fun onStarted(cache: PcmCache, peaks: PeakPyramid) {}
        fun onProgress(fraction: Double) {}
        fun onDone(result: DecodeResult)
        /** Понятный текст ошибки (FR-2, FR-9, FR-10). */
        fun onError(message: String)
        fun onCancelled() {}
    }

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /** Запуск на отдельном потоке; колбэки — в главном потоке. */
    fun start(listener: Listener): Thread {
        val main = Handler(Looper.getMainLooper())
        var lastPost = 0L
        val progress = object : Progress {
            override val isCancelled: Boolean get() = cancelled
            override fun report(fraction: Double) {
                val now = System.nanoTime()
                if (now - lastPost > 100_000_000L || fraction >= 1.0) { lastPost = now; main.post { listener.onProgress(fraction) } }
            }
        }
        val t = Thread({
            try {
                val r = run(progress) { c, p -> main.post { listener.onStarted(c, p) } }
                main.post { listener.onDone(r) }
            } catch (e: CancelledException) {
                main.post { listener.onCancelled() }
            } catch (e: CoreException) {
                main.post { listener.onError(e.message ?: "Ошибка декодирования.") }
            } catch (e: Throwable) {
                profile.log("Декодирование: ${e.javaClass.simpleName}: ${e.message}")
                main.post { listener.onError("Не удалось раскодировать звук: файл, возможно, повреждён.") }
            }
        }, "decode")
        t.start()
        return t
    }

    /** Блокирующее декодирование (не из UI-потока). При ошибке/отмене кэш удаляется. */
    fun run(progress: Progress, onStarted: (PcmCache, PeakPyramid) -> Unit = { _, _ -> }): DecodeResult {
        if (!track.decodable) throw CoreException(track.reason ?: "Нет декодера.")
        checkSpace(track.durationUs, track.sampleRate, Downmix.outputChannels(maxOf(1, track.channels)))
        return if (probe.isWav) decodeWav(progress, onStarted) else decodeCodec(progress, onStarted)
    }

    private fun checkSpace(durUs: Long, rate: Int, ch: Int) {
        if (durUs <= 0 || rate <= 0) return
        val need = SpaceEstimator.requiredBytes(durUs, rate, ch)
        val free = StatFs(PcmCache.dir(ctx).path).availableBytes
        SpaceEstimator.check(need, free)?.let { throw CoreException(it) }
    }

    private fun decodeWav(progress: Progress, onStarted: (PcmCache, PeakPyramid) -> Unit): DecodeResult {
        val w = WavReader(src.source)
        val inCh = w.spec.channels
        val outSpec = PcmSpec(w.spec.sampleRate, Downmix.outputChannels(inCh))
        val m = Downmix.matrix(w.channelMask, inCh)
        val cache = PcmCache.create(ctx, outSpec)
        val peaks = PeakPyramid(outSpec.channels)
        try {
            onStarted(cache, peaks)
            val block = 8192
            val inb = FloatArray(block * inCh)
            val outb = FloatArray(block * outSpec.channels)
            var pos = 0L
            val total = w.lengthFrames
            while (pos < total) {
                if (progress.isCancelled) throw CancelledException()
                val n = w.read(pos, inb, minOf(block.toLong(), total - pos).toInt())
                if (n <= 0) break
                for (i in 0 until n * inCh) { val v = inb[i]; if (v.isNaN() || v.isInfinite()) inb[i] = 0f }
                Downmix.apply(m, inb, n, outb)
                cache.append(outb, n)
                synchronized(peaks) { peaks.append(outb, n) }
                pos += n
                progress.report(pos.toDouble() / maxOf(1L, total))
            }
            cache.finishWriting()
            synchronized(peaks) { peaks.finish() }
            profile.log("WAV: ${w.bitsPerSample} бит${if (w.isFloat) " float" else ""}, ${w.lengthFrames} кадров")
            return DecodeResult(cache, peaks, outSpec, if (w.isFloat) 32 else w.bitsPerSample, w.bitsPerSample, emptyList(), null)
        } catch (e: Throwable) {
            cache.close(); throw e
        }
    }

    private fun decodeCodec(progress: Progress, onStarted: (PcmCache, PeakPyramid) -> Unit): DecodeResult {
        val g = GaplessResolver.resolve(src.source, track, probe.tracks.size, profile::log)
        val dec = MediaDecoder(src.fileDescriptor, track.extractorIndex, track.format, g, profile, durationUs = track.durationUs)
        var cache: PcmCache? = null
        var peaks: PeakPyramid? = null
        var matrix: Array<DoubleArray>? = null
        var outb = FloatArray(0)
        var outCh = 0
        val warnings = ArrayList<String>()
        try {
            val stats = dec.run(progress, { f ->
                if (track.channels > 2 && f.channels == 2) profile.log("FR-11: декодер сам свёл ${track.channels} кан. в стерео — принято")
                outCh = Downmix.outputChannels(f.channels)
                val mask = if (f.channelMask != 0) f.channelMask else track.channelMask
                if (f.channels > 2 && Downmix.unknownBits(mask) != 0) profile.log("FR-11: нестандартные каналы маски 0x${Integer.toHexString(mask)} отброшены")
                matrix = Downmix.matrix(mask, f.channels)
                val spec = PcmSpec(f.sampleRate, outCh)
                if (f.sampleRate != track.sampleRate) checkSpace(track.durationUs, f.sampleRate, outCh)
                val c = PcmCache.create(ctx, spec)
                cache = c
                peaks = PeakPyramid(outCh).also { onStarted(c, it) }
                // FR-4: 16 бит вместо 24.
                if (!f.isFloat && f.bits < 24 && track.sourceBits >= 24)
                    warnings += "Телефон раскодировал файл с точностью 16 бит вместо 24. Для максимальной точности используйте WAV."
                profile.log("FR-4: исходник ${track.sourceBits} бит, выход декодера ${if (f.isFloat) "float" else "${f.bits} бит"}")
            }) { buf, n ->
                if (outb.size < n * outCh) outb = FloatArray(n * outCh)
                Downmix.apply(matrix!!, buf, n, outb)
                cache!!.append(outb, n)
                val p = peaks!!
                synchronized(p) { p.append(outb, n) }
            }
            val c = cache ?: throw CoreException("Декодер не выдал ни одного сэмпла: файл, возможно, повреждён.")
            c.finishWriting()
            val p = peaks!!
            synchronized(p) { p.finish() }
            if (c.length == 0L) throw CoreException("В дорожке нет звука (0 сэмплов).")
            val f = stats.format!!
            profile.log("Декодировано ${c.length} кадров; разгон −${stats.headTrimmed}, хвост −${stats.tailTrimmed}, тишина +${stats.silenceInserted}")
            return DecodeResult(c, p, c.spec, if (f.isFloat) 32 else f.bits, track.sourceBits, warnings, stats)
        } catch (e: Throwable) {
            cache?.close(); throw e
        }
    }
}
