package com.rassul.audioeditor.diag

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/*
 * Зона MEDIA (T17/T18/T19-заготовка). Профиль устройства: выбор путей записи и калибровки задержек
 * (timeline.md «FR-26: риск двойной обрезки», «Задержку энкодера Android … измерить на устройстве»).
 * Значения пишет самопроверка (T19, агент INTEGRATION); по умолчанию — «доверять формату» и свои писатели.
 */
class DeviceProfile private constructor(ctx: Context) {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("device_profile", Context.MODE_PRIVATE)

    /** FR-25: писать .m4a через MediaMuxer вместо своего Mp4AacWriter (по умолчанию false — свой писатель, точный elst). */
    var useMediaMuxerForAac: Boolean
        get() = sp.getBoolean(K_MUX_AAC, false)
        set(v) { sp.edit().putBoolean(K_MUX_AAC, v).apply() }

    /** FR-25: писать .ogg через MediaMuxer вместо OggOpusWriter (по умолчанию false). */
    var useMediaMuxerForOpus: Boolean
        get() = sp.getBoolean(K_MUX_OPUS, false)
        set(v) { sp.edit().putBoolean(K_MUX_OPUS, v).apply() }

    /** Калиброванная задержка энкодера AAC в кадрах; null — брать из выходного формата энкодера (KEY_ENCODER_DELAY). */
    var encoderDelayAac: Int?
        get() = if (sp.contains(K_DELAY_AAC)) sp.getInt(K_DELAY_AAC, 0) else null
        set(v) { sp.edit().apply { if (v == null) remove(K_DELAY_AAC) else putInt(K_DELAY_AAC, v) }.apply() }

    /** Калиброванная задержка энкодера Opus (кадры 48 кГц); null — pre-skip из OpusHead энкодера. */
    var encoderDelayOpus: Int?
        get() = if (sp.contains(K_DELAY_OPUS)) sp.getInt(K_DELAY_OPUS, 0) else null
        set(v) { sp.edit().apply { if (v == null) remove(K_DELAY_OPUS) else putInt(K_DELAY_OPUS, v) }.apply() }

    /** Предпочитать float-вход энкодеров (если энкодер его принимает); по умолчанию 16 бит + TPDF (FR-C7). */
    var preferFloatEncoderInput: Boolean
        get() = sp.getBoolean(K_FLOAT_ENC, false)
        set(v) { sp.edit().putBoolean(K_FLOAT_ENC, v).apply() }

    /**
     * Сколько кадров разгона системный декодер [mime] отбрасывает сам, даже без KEY_ENCODER_DELAY в формате
     * (калибровка на assets/selftest; timeline.md FR-26). По умолчанию 0.
     */
    fun decoderDropsFrames(mime: String): Long = sp.getLong(K_DROPS + mime.lowercase(), 0L)

    /** То же, но null — не калибровалось (тогда DecodePipeline берёт допущение по кодеку, см. MediaDecoder). */
    fun decoderDropsFramesOrNull(mime: String): Long? =
        if (sp.contains(K_DROPS + mime.lowercase())) sp.getLong(K_DROPS + mime.lowercase(), 0L) else null

    fun setDecoderDropsFrames(mime: String, frames: Long?) {
        sp.edit().apply { if (frames == null) remove(K_DROPS + mime.lowercase()) else putLong(K_DROPS + mime.lowercase(), frames) }.apply()
    }

    /**
     * Изменение хвоста системным декодером [mime] (кадры выхода; tail из DecoderDropCalibration, аудит T21 п. 3):
     * > 0 — сам обрезает в конце, < 0 — дописывает (MP3). null — не калибровалось (без поправки).
     */
    fun decoderTailFramesOrNull(mime: String): Long? =
        if (sp.contains(K_TAIL + mime.lowercase())) sp.getLong(K_TAIL + mime.lowercase(), 0L) else null

    fun setDecoderTailFrames(mime: String, frames: Long?) {
        sp.edit().apply { if (frames == null) remove(K_TAIL + mime.lowercase()) else putLong(K_TAIL + mime.lowercase(), frames) }.apply()
    }

    /** Все калибровки — для отчёта самопроверки. */
    fun describe(): String = buildString {
        append("MediaMuxer для AAC: ").append(if (useMediaMuxerForAac) "да" else "нет").append('\n')
        append("MediaMuxer для Opus: ").append(if (useMediaMuxerForOpus) "да" else "нет").append('\n')
        append("Задержка энкодера AAC: ").append(encoderDelayAac?.toString() ?: "из формата").append('\n')
        append("Задержка энкодера Opus: ").append(encoderDelayOpus?.toString() ?: "из OpusHead").append('\n')
        for ((k, v) in sp.all) if (k.startsWith(K_DROPS)) append("Декодер ${k.removePrefix(K_DROPS)} сам отбрасывает: $v\n")
        for ((k, v) in sp.all) if (k.startsWith(K_TAIL)) append("Декодер ${k.removePrefix(K_TAIL)} меняет хвост на: ${-(v as Long)}\n")
    }

    // ---------------------------------------------------------------- журнал
    private val journal = ArrayDeque<String>()

    /** Запись в журнал (память + logcat). Потокобезопасно. */
    fun log(msg: String) {
        Log.i(TAG, msg)
        synchronized(journal) {
            journal.addLast("${System.currentTimeMillis()}: $msg")
            while (journal.size > JOURNAL_MAX) journal.removeFirst()
        }
    }

    fun journal(): List<String> = synchronized(journal) { journal.toList() }

    companion object {
        private const val TAG = "RasAudio"
        private const val JOURNAL_MAX = 500
        private const val K_MUX_AAC = "mux_aac"
        private const val K_MUX_OPUS = "mux_opus"
        private const val K_DELAY_AAC = "delay_aac"
        private const val K_DELAY_OPUS = "delay_opus"
        private const val K_FLOAT_ENC = "float_enc"
        private const val K_DROPS = "drops:"
        private const val K_TAIL = "tail:"

        @Volatile private var inst: DeviceProfile? = null
        fun get(ctx: Context): DeviceProfile = inst ?: synchronized(this) { inst ?: DeviceProfile(ctx).also { inst = it } }
    }
}
