package com.rassul.audioeditor.core.pipeline

import com.rassul.audioeditor.core.format.ogg.OpusToc
import java.nio.ByteBuffer

/*
 * Чистая арифметика «склейки» :app (MediaDecoder, Encoders, SelfTest, DeviceVerifier, RemuxCopier), вынесенная
 * в :core для тестов (аудит T21, code-formula-auditor). Источники — docs/math/timeline.md (FR-7, FR-25, FR-26,
 * FR-33), docs/math/dsp.md (FR-C5, FR-C7). Без зависимостей от Android.
 */

/** Перевод времени и масок; все величины — целые (Long), округление half-up как в timeline.md ф. 1 и 7. */
object MediaMath {
    /**
     * PTS (мкс) → кадры: ⌊(us·fs + 5·10⁵)/10⁶⌋ (timeline.md ф. 7, round_half_up). floorDiv — чтобы и при us < 0
     * правило было «половина вверх», а не к нулю. Переполнения нет до |us| ≈ 4,8·10¹³ мкс (13 ч) при 192 кГц.
     */
    fun usToFrames(us: Long, fs: Int): Long = Math.floorDiv(us * fs + 500_000L, 1_000_000L)

    /** нс → кадры, half-up (Android csd-1 Opus: задержка в нс → pre-skip в кадрах 48 кГц, RFC 7845 §5.1). */
    fun nsToFrames(ns: Long, fs: Int): Long = Math.floorDiv(ns * fs + 500_000_000L, 1_000_000_000L)

    /** Кадры → PTS (мкс), вниз: для queueInputBuffer энкодера и MediaMuxer (монотонно, точное значение не важно). */
    fun framesToUs(frames: Long, fs: Int): Long = Math.floorDiv(frames * 1_000_000L, fs.toLong())

    /**
     * Маска WAVE_FORMAT_EXTENSIBLE (dwChannelMask, SPEAKER_*: FL = 0x1, FR = 0x2, FC = 0x4, LFE = 0x8, …)
     * → маска Android AudioFormat.CHANNEL_OUT_* (FL = 0x4, …). Для первых 18 позиций CHANNEL_OUT_X = SPEAKER_X << 2
     * (Microsoft «Multiple Channel Audio Data and WAVE Files»; Android AudioFormat, «channel position masks»).
     * Downmix.matrix (dsp.md FR-C5, П-12) ждёт маску Android.
     */
    fun waveMaskToAndroid(waveMask: Int): Int = (waveMask and 0x3FFFF) shl 2
}

/** Целый PCM выхода MediaCodec → float [−1, 1) (dsp.md: 16 бит — ×1/32768 и т.д.) и замена NaN/Inf (timeline.md). */
object PcmDecode {
    /**
     * [samples] сэмплов из [b] начиная с байта [offset] в dst[0…). Порядок байт — как у [b] (MediaCodec: nativeOrder),
     * 24 бит — упакованные LE (AudioFormat.ENCODING_PCM_24BIT_PACKED). bits: 8 (беззнаковые), 16, 24, 32; isFloat — float32.
     */
    fun toFloat(b: ByteBuffer, offset: Int, bits: Int, isFloat: Boolean, samples: Int, dst: FloatArray) {
        when {
            isFloat -> for (i in 0 until samples) dst[i] = b.getFloat(offset + 4 * i)
            bits == 16 -> for (i in 0 until samples) dst[i] = b.getShort(offset + 2 * i) / 32768f
            bits == 32 -> for (i in 0 until samples) dst[i] = (b.getInt(offset + 4 * i) / 2147483648.0).toFloat()
            bits == 24 -> { var p = offset; for (i in 0 until samples) {
                val v = (b.get(p).toInt() and 0xFF) or ((b.get(p + 1).toInt() and 0xFF) shl 8) or (b.get(p + 2).toInt() shl 16)
                dst[i] = v / 8388608f; p += 3 } }
            else -> for (i in 0 until samples) dst[i] = ((b.get(offset + i).toInt() and 0xFF) - 128) / 128f
        }
    }

    /** Байт на сэмпл для формата выхода декодера. */
    fun bytesPerSample(bits: Int, isFloat: Boolean): Int = when { isFloat || bits == 32 -> 4; bits == 24 -> 3; bits == 8 -> 1; else -> 2 }

    /** NaN/±Inf → 0 в buf[from, from+n); возвращает число замен. */
    fun sanitize(buf: FloatArray, from: Int, n: Int): Int {
        var c = 0
        for (i in from until from + n) { val v = buf[i]; if (v.isNaN() || v.isInfinite()) { buf[i] = 0f; c++ } }
        return c
    }
}

/**
 * Параметры GaplessTrimmer для входа (FR-26, timeline.md «риск двойной обрезки»): задержка/хвост из метаданных,
 * пересчёт HE-AAC к выходной частоте, отсев неправдоподобных значений, «декодер сам отбрасывает».
 */
object DecoderTrim {
    data class Params(val delay: Long, val padding: Long, val decoderDrops: Long, val notes: List<String>)

    /**
     * @param metaRate шкала, в которой записаны delay/padding (частота дорожки; Opus — 48 000).
     * @param calibrated калибровка DeviceProfile «декодер сам отбрасывает» (кадры выхода); null — не калибровалось.
     * @param dropsWholeDelayByDefault допущение без калибровки: декодер сам отбрасывает всю задержку (Opus: pre-skip из csd-1).
     * @param calibratedTail калибровка DeviceProfile «изменение хвоста декодером» X_t (кадры выхода, tail из
     *   DecoderDropCalibration): X_t > 0 — декодер сам обрезает X_t в конце, X_t < 0 — дописывает −X_t (C2SoftMp3Dec:
     *   529 нулей, [предположение по AOSP]). GaplessTrimmer получает хвост P' = max(0, P − X_t) (аудит T21 п. 3:
     *   длина L₀ = L − X_h − X_t ⇒ обрезать надо P − X_t, чтобы длина была точной). null или 0 — без поправки.
     */
    fun plan(delay0: Long, padding0: Long, metaRate: Int, outRate: Int, calibrated: Long?, dropsWholeDelayByDefault: Boolean,
             calibratedTail: Long? = null): Params {
        val notes = ArrayList<String>()
        var delay = delay0; var pad = padding0
        // HE-AAC: значения в шкале частоты ядра (mdhd = 22,05/24 кГц), выход вдвое выше (timeline.md «Приоритет источников»).
        if (metaRate > 0 && outRate != metaRate && outRate % metaRate == 0) {
            val k = outRate / metaRate
            delay *= k; pad *= k
            notes += "FR-26: задержка пересчитана ×$k к выходной частоте $outRate Гц"
        }
        // Больше 1 с — явно ошибочные метаданные: не обрезать.
        if (pad < 0 || pad > outRate) { if (pad != 0L) notes += "FR-26: хвост $pad кадров отброшен как неправдоподобный"; pad = 0 }
        if (delay < 0 || delay > outRate) { notes += "FR-26: задержка $delay кадров отброшена как неправдоподобная"; delay = 0 }
        val drops = calibrated ?: if (dropsWholeDelayByDefault) delay else 0L
        // Поправка хвоста декодера (калибровка): P' = P − X_t; отрицательное — прижать к 0 (декодер обрезал больше хвоста).
        if (calibratedTail != null && calibratedTail != 0L) {
            val p2 = pad - calibratedTail
            notes += "FR-26: хвост с поправкой декодера ${-calibratedTail} кадров: $pad → ${maxOf(0L, p2)}"
            pad = maxOf(0L, p2)
        }
        return Params(delay, pad, drops, notes)
    }
}

/**
 * Вывод калибровки «сколько кадров системный декодер отбрасывает сам» по эталонам (SelfTest, FR-26).
 *
 * Модель (проход без поправки профиля: GaplessTrimmer режет всю заявленную задержку D и хвост P):
 * декодер отбрасывает X_h кадров в начале и меняет длину хвоста на −X_t (X_t < 0 — дописывает, напр. C2SoftMp3Dec
 * дописывает 529 нулей [предположение по AOSP]). Тогда на выходе импульс p₀ = I − X_h, длина L₀ = L − X_h − X_t:
 *   X_imp = I − p₀ = X_h (± tol из-за сжатия),   X_len = L − L₀ = X_h + X_t (точно).
 * Если |X_len − X_imp| ≤ tol на всех файлах и X_len совпадает — X_h = X_len (точное целое), X_t = 0.
 * Иначе X_h = медиана X_imp, X_t = X_len − X_h (если согласован по файлам).
 * Для Opus (allowWholeDelay) декодер обычно отбрасывает весь pre-skip, который у файлов разный, поэтому сначала
 * проверяется |X_imp,i − D_i| ≤ tol для каждого файла (а не сравнение с одной D).
 */
object DecoderDropCalibration {
    class Sample(val xImp: Long, val xLen: Long, val declaredDelay: Long)

    sealed class Result {
        /** Декодер отбрасывает ровно заявленную задержку (Opus) — профилю ставится null (допущение по умолчанию). */
        data class WholeDelay(val tail: Long?) : Result()
        /** Абсолютное число кадров (в профиль). tail — изменение хвоста (null — не согласовано по файлам). */
        data class Absolute(val head: Long, val tail: Long?) : Result()
        data class Inconsistent(val imps: List<Long>) : Result()
        data class Implausible(val head: Long) : Result()
    }

    fun infer(s: List<Sample>, tol: Long = 2, max: Long = 8192, allowWholeDelay: Boolean = false): Result {
        require(s.isNotEmpty())
        val imps = s.map { it.xImp }
        val lens = s.map { it.xLen }
        if (allowWholeDelay && s.all { Math.abs(it.xImp - it.declaredDelay) <= tol }) {
            val tails = s.map { it.xLen - it.declaredDelay }
            return Result.WholeDelay(tails.first().takeIf { t -> tails.all { it == t } })
        }
        val xl = lens.first()
        val lensAgree = lens.all { it == xl }
        val head: Long
        val tail: Long?
        // Точная длина согласована и каждая оценка по импульсу в пределах ± tol от неё — разброс импульсов до 2·tol
        // допустим (до T21 SelfTest требовал разброс ≤ tol и отвергал, например, +2 / −2).
        if (lensAgree && s.all { Math.abs(xl - it.xImp) <= tol }) { head = xl; tail = 0 }
        else {
            if (imps.max() - imps.min() > tol) return Result.Inconsistent(imps)
            head = imps.sorted()[imps.size / 2]
            val tails = lens.map { it - head }
            tail = if (tails.max() - tails.min() <= tol) tails.sorted()[tails.size / 2] else null
        }
        if (head < 0 || head > max) return Result.Implausible(head)
        return Result.Absolute(head, tail)
    }
}

/** Задержка энкодера (FR-25, SelfTest). */
object EncoderDelay {
    enum class Source(val text: String) { OVERRIDE("калибровка"), PROFILE("DeviceProfile"), ENCODER("формат энкодера"), ASSUMED("допущение") }

    /** Порядок источников: явный параметр → DeviceProfile → формат энкодера → допущение. Ровно один источник, без сложения. */
    fun choose(override: Int?, profile: Int?, fromEncoder: Int?, assumed: Int): Pair<Int, Source> = when {
        override != null -> override to Source.OVERRIDE
        profile != null -> profile to Source.PROFILE
        fromEncoder != null -> fromEncoder to Source.ENCODER
        else -> assumed to Source.ASSUMED
    }

    /**
     * Истинная задержка по сдвигу импульса: в контейнер записано D_исп, на выходе декодера импульс на p вместо I.
     * Сырой поток: импульс на D + I; обрезано D_исп → p = I + D − D_исп ⇒ D = D_исп + (p − I).
     */
    fun trueDelay(used: Int, measuredImpulse: Long, expectedImpulse: Long): Long = used + (measuredImpulse - expectedImpulse)
}

/** Допуски FR-33 для сжатых форматов (timeline.md «FR-33», таблица: ±1 сэмпл; запасной режим FR-26 — ±1 кадр). */
object LengthTolerance {
    enum class Verdict { OK, WARN, FAIL }
    fun lossy(decoded: Long, expected: Long, frame: Int): Verdict {
        val d = Math.abs(decoded - expected)
        return when { d <= 1 -> Verdict.OK; d <= frame -> Verdict.WARN; else -> Verdict.FAIL }
    }

    /**
     * «Кадр» запасного допуска Opus = длительность последнего пакета по TOC (timeline.md FR-33 «по размеру последнего
     * пакета», до 2880 и больше у пакетов из нескольких кадров; RFC 6716 §3.1). Пакета нет или TOC не читается — 960 (20 мс).
     */
    fun opusFrame(lastPacket: ByteArray?): Int =
        if (lastPacket == null || lastPacket.isEmpty()) 960 else try { OpusToc.samples48(lastPacket) } catch (_: IllegalArgumentException) { 960 }

    /** То же по уже посчитанной длине последнего пакета (кадры 48 кГц); ≤ 0 — 960. */
    fun opusFrame(lastPacketFrames: Int): Int = if (lastPacketFrames > 0) lastPacketFrames else 960
}

/** NFR-7: линейная экстраполяция времени замера на целевую длительность (все этапы потоковые). */
object Nfr7 {
    fun extrapolate(measuredSec: Double, sampleSec: Int, targetSec: Int = 1800): Double = measuredSec * targetSec / sampleSec
}
