package com.rassul.audioeditor.core.model

/*
 * КОНТРАКТ (главная сессия). Общие типы :core. Чистый Kotlin, без Android.
 * ЕДИНИЦЫ: позиции и длины — в сэмплах на канал («кадрах»), Long; частота — Int, Гц;
 * PCM внутри — Float, чередование каналов, номинальный диапазон [−1, 1].
 */

/** Формат несжатого PCM: частота (Гц) и число каналов. */
data class PcmSpec(val sampleRate: Int, val channels: Int) {
    init {
        require(sampleRate in 1..768_000) { "sampleRate=$sampleRate" }
        require(channels in 1..8) { "channels=$channels" }
    }
}

/** Ошибка, которую можно показать пользователю как есть (message — на русском). */
open class CoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Операция отменена пользователем. */
class CancelledException : CoreException("Отменено")

/** Флаг отмены и прогресс для долгих операций. */
interface Progress {
    /** Доля 0..1. */
    fun report(fraction: Double) {}
    val isCancelled: Boolean get() = false
    companion object { val NONE: Progress = object : Progress {} }
}

/** Источник PCM с произвольным доступом (кэш на диске в :app, массив в тестах). Потокобезопасность не гарантируется. */
interface PcmSource {
    val spec: PcmSpec
    /** Длина в кадрах. */
    val length: Long
    /**
     * Прочитать до [frames] кадров начиная с кадра [from] в dst (чередование, dst.size ≥ frames*channels).
     * Возвращает число прочитанных кадров (меньше только у конца).
     */
    fun read(from: Long, dst: FloatArray, frames: Int): Int
}

/** Простой источник из массива (тесты, короткие сигналы). */
class ArrayPcmSource(override val spec: PcmSpec, private val data: FloatArray) : PcmSource {
    override val length: Long get() = (data.size / spec.channels).toLong()
    override fun read(from: Long, dst: FloatArray, frames: Int): Int {
        if (from >= length || frames <= 0) return 0
        val n = minOf(frames.toLong(), length - from).toInt()
        System.arraycopy(data, (from * spec.channels).toInt(), dst, 0, n * spec.channels)
        return n
    }
}
