package com.rassul.audioeditor.media

import android.media.AudioFormat
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import com.rassul.audioeditor.core.format.ContainerSniffer
import com.rassul.audioeditor.core.format.WavReader
import com.rassul.audioeditor.core.format.flac.FlacFrames
import com.rassul.audioeditor.core.format.bits.Src
import com.rassul.audioeditor.core.model.CoreException
import com.rassul.audioeditor.core.pipeline.ExtractPlanner
import java.util.Locale

/*
 * Зона MEDIA, T17. FR-2 (контейнеры, тексты), FR-5 (дорожки), FR-9 (есть ли декодер).
 */

/** Звуковая дорожка источника. */
data class AudioTrackInfo(
    /** Индекс дорожки в MediaExtractor. */
    val extractorIndex: Int,
    /** Порядковый номер среди звуковых (0…), для текста «Дорожка N». */
    val ordinal: Int,
    val mime: String,
    val language: String?,
    val isDefault: Boolean,
    val channels: Int,
    /** AudioFormat.CHANNEL_OUT_* (0 — неизвестна). */
    val channelMask: Int,
    val sampleRate: Int,
    /** бит/с; 0 — неизвестен. */
    val bitrate: Int,
    /** мкс; 0 — неизвестна. */
    val durationUs: Long,
    /** Разрядность исходника: 16/24/32 для PCM/FLAC/WAV, 0 — сжатый или неизвестно. */
    val sourceBits: Int,
    val decodable: Boolean,
    /** Копия формата дорожки (не менять). */
    val format: MediaFormat,
) {
    val codecName: String get() = ExtractPlanner.codecName(mime)

    /** FR-9: пояснение, если декодера нет. */
    val reason: String? get() = if (decodable) null else
        "Этот телефон не умеет раскодировать звук $codecName. Его можно сохранить без изменений, но нельзя редактировать или перекодировать."

    /** «Дорожка 1 · русский · AAC · стерео · 48 кГц · 192 кбит/с» (FR-5). */
    fun title(): String {
        val parts = ArrayList<String>()
        parts += "Дорожка ${ordinal + 1}"
        languageName(language)?.let { parts += it }
        parts += codecName
        parts += when (channels) { 1 -> "моно"; 2 -> "стерео"; 6 -> "5.1"; 8 -> "7.1"; else -> "$channels кан." }
        if (sampleRate > 0) parts += khz(sampleRate)
        if (bitrate > 0) parts += "${(bitrate + 500) / 1000} кбит/с"
        return parts.joinToString(" · ")
    }

    companion object {
        fun khz(r: Int): String = if (r % 1000 == 0) "${r / 1000} кГц"
            else "${r / 1000},${(r % 1000).toString().padStart(3, '0').trimEnd('0')} кГц"

        fun languageName(lang: String?): String? {
            if (lang.isNullOrBlank() || lang == "und") return null
            return try {
                Locale.forLanguageTag(lang).getDisplayLanguage(Locale.forLanguageTag("ru")).takeIf { it.isNotBlank() && it != lang } ?: lang
            } catch (_: Exception) { lang }
        }
    }
}

/** Итог анализа файла. */
data class ProbeResult(
    val tracks: List<AudioTrackInfo>,
    val hasVideo: Boolean,
    /** Индекс в [tracks], выбранный по умолчанию (FR-5: флаг «по умолчанию», иначе первая). */
    val defaultTrack: Int,
    /** Файл — WAV, разбирается своим кодом (FR-3). */
    val isWav: Boolean,
)

object TrackProbe {
    const val MSG_NO_AUDIO = "В этом видео нет звуковой дорожки."
    const val MSG_NO_AUDIO_FILE = "В этом файле нет звука."
    const val MSG_DRM = "Это видео защищено от копирования (DRM). Извлечь из него звук нельзя."
    const val MSG_UNREADABLE = "Не удалось прочитать файл: формат не распознан или файл повреждён. Поддерживаются: MP4, MOV, 3GP, MKV, WebM, TS."

    /** Анализ открытого источника. Долго (чтение файла) — не из UI-потока. Ошибки — CoreException (FR-2). */
    fun probe(src: OpenedSource): ProbeResult {
        ContainerSniffer.unsupportedMessage(src.source)?.let { throw CoreException(it) }
        val isWav = try { WavReader.isWav(src.source) } catch (_: Exception) { false }
        val ex = MediaExtractor()
        try {
            try { ex.setDataSource(src.fileDescriptor) } catch (e: Exception) {
                if (isWav) return wavOnly(src)
                throw CoreException(MSG_UNREADABLE, e)
            }
            val drm = try { ex.drmInitData != null || !ex.psshInfo.isNullOrEmpty() } catch (_: Exception) { false }
            var hasVideo = false
            val tracks = ArrayList<AudioTrackInfo>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) hasVideo = true
                if (!mime.startsWith("audio/")) continue
                tracks += info(i, tracks.size, mime, f, src, isWav)
            }
            if (drm || tracks.any { it.format.containsKey("crypto-mode") }) throw CoreException(MSG_DRM)
            if (tracks.isEmpty()) {
                if (isWav) return wavOnly(src)
                throw CoreException(if (hasVideo || src.mime?.startsWith("video/") == true) MSG_NO_AUDIO else MSG_NO_AUDIO_FILE)
            }
            val def = tracks.indexOfFirst { it.isDefault }.let { if (it < 0) 0 else it }
            return ProbeResult(tracks, hasVideo, def, isWav)
        } finally {
            ex.release()
        }
    }

    private fun wavOnly(src: OpenedSource): ProbeResult {
        val w = try { WavReader(src.source) } catch (e: CoreException) { throw e } catch (e: Exception) { throw CoreException(MSG_UNREADABLE, e) }
        val f = MediaFormat.createAudioFormat("audio/raw", w.spec.sampleRate, w.spec.channels)
        val t = AudioTrackInfo(-1, 0, "audio/raw", null, true, w.spec.channels,
            com.rassul.audioeditor.core.pipeline.MediaMath.waveMaskToAndroid(w.channelMask), w.spec.sampleRate,
            w.spec.sampleRate * w.spec.channels * w.bitsPerSample, w.lengthFrames * 1_000_000L / w.spec.sampleRate,
            w.bitsPerSample, true, f)
        return ProbeResult(listOf(t), false, 0, true)
    }

    private fun info(i: Int, ordinal: Int, extractorMime: String, f: MediaFormat, src: OpenedSource, isWav: Boolean): AudioTrackInfo {
        // Файл .flac: FLACExtractor может отдавать уже раскодированный PCM (audio/raw) — для текстов, «как есть»
        // и быстрой обрезки кодек считаем FLAC; декодирование идёт по исходному формату дорожки.
        val mime = if (extractorMime == "audio/raw" && !isWav && flacBits(src) > 0) "audio/flac" else extractorMime
        val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 0)
        val rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE, 0)
        val mask = f.getInteger(MediaFormat.KEY_CHANNEL_MASK, 0)
        val br = f.getInteger(MediaFormat.KEY_BIT_RATE, 0)
        val dur = f.getLong(MediaFormat.KEY_DURATION, 0L)
        val lang: String? = if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else null
        val def = f.getInteger(MediaFormat.KEY_IS_DEFAULT, 0) != 0
        var bits = 0
        if (mime == "audio/raw") bits = when (f.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
            AudioFormat.ENCODING_PCM_8BIT -> 8; AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 32; else -> 16
        }
        if (mime == "audio/flac") bits = f.getInteger("bits-per-sample", 0).let { if (it > 0) it else flacBits(src) }
        if (isWav) try { bits = WavReader(src.source).bitsPerSample } catch (_: Exception) {}
        return AudioTrackInfo(i, ordinal, mime, lang, def, ch, mask, rate, br, dur, bits,
            isWav || CapabilityProbe.hasDecoder(f), MediaFormat(f))
    }

    private fun flacBits(src: OpenedSource): Int = try {
        val h = Src.readUpTo(src.source, 0, 4)
        if (h.size == 4 && String(h, Charsets.ISO_8859_1) == "fLaC") FlacFrames.readHeader(src.source).first.bitsPerSample else 0
    } catch (_: Exception) { 0 }
}

/** FR-9: возможности кодеков телефона (MediaCodecList). */
object CapabilityProbe {
    private val list by lazy { MediaCodecList(MediaCodecList.REGULAR_CODECS) }

    /** Есть ли системный декодер для формата дорожки. */
    fun hasDecoder(format: MediaFormat): Boolean {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
        if (mime == "audio/raw") return true
        return decoderName(format) != null || hasDecoder(mime)
    }

    fun hasDecoder(mime: String): Boolean = list.codecInfos.any { ci ->
        !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }

    fun hasEncoder(mime: String): Boolean = list.codecInfos.any { ci ->
        ci.isEncoder && ci.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }

    /** Имя подходящего декодера (без ключей задержки в формате — они мешают подбору на некоторых устройствах). */
    fun decoderName(format: MediaFormat): String? = try {
        val f = MediaFormat(format)
        f.removeKey(MediaFormat.KEY_ENCODER_DELAY); f.removeKey(MediaFormat.KEY_ENCODER_PADDING)
        f.removeKey(MediaFormat.KEY_DURATION); f.removeKey(MediaFormat.KEY_BIT_RATE)
        list.findDecoderForFormat(f)
    } catch (_: Exception) { null }

    /** Текст для самопроверки: какие декодеры/энкодеры есть. */
    fun describe(): String = buildString {
        for (m in listOf("audio/mp4a-latm", "audio/opus", "audio/vorbis", "audio/mpeg", "audio/flac", "audio/ac3", "audio/eac3", "audio/vnd.dts", "audio/3gpp", "audio/amr-wb"))
            append(ExtractPlanner.codecName(m)).append(": декодер ").append(if (hasDecoder(m)) "есть" else "нет").append('\n')
        append("Энкодер AAC: ").append(if (hasEncoder("audio/mp4a-latm")) "есть" else "нет").append('\n')
        append("Энкодер Opus: ").append(if (hasEncoder("audio/opus")) "есть" else "нет").append('\n')
    }
}
