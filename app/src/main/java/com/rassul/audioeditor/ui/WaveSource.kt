package com.rassul.audioeditor.ui

import com.rassul.audioeditor.editor.EditorController
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * Источник данных для WaveformView (адаптер между рисованием и тем, откуда берутся пики).
 *  - EditorWaveSource  — продакшен: поверх EditorController.peaks (PeakPyramid) и readSamples;
 *  - SyntheticWaveSource — фейк: синтетическая 30-минутная волна без PeakPyramid.
 * Вызывается из onDraw: реализации НЕ должны аллоцировать память на каждый вызов.
 */
interface WaveSource {
    val channels: Int
    /** Длина результата правок, кадры. */
    val lengthFrames: Long
    /** Меняется при каждом изменении данных (правки, дописанные блоки) — повод сбросить кэш колонок. */
    val version: Int

    /**
     * Мин/макс по колонкам экрана. Колонка c покрывает кадры [startFrame + c·fpp, startFrame + (c+1)·fpp).
     * Результат: out[(c·channels + ch)·2] = min, out[...+1] = max. Колонка без данных (за пределами файла): min > max.
     */
    fun columns(startFrame: Double, framesPerPx: Double, count: Int, out: FloatArray)

    /** Сырые сэмплы (чередование каналов); возвращает прочитанные кадры. */
    fun readSamples(from: Long, dst: FloatArray, frames: Int): Int
}

private const val CHUNK = 4096

/** Общий путь «сырых» сэмплов (масштаб крупнее базового уровня пирамиды): один проход по видимым кадрам. */
private fun rawColumns(src: WaveSource, scratch: FloatArray, startFrame: Double, fpp: Double, count: Int, out: FloatArray) {
    val ch = src.channels
    for (i in 0 until count * ch) { out[i * 2] = Float.POSITIVE_INFINITY; out[i * 2 + 1] = Float.NEGATIVE_INFINITY }
    val len = src.lengthFrames
    var f = max(0L, floor(startFrame).toLong())
    val end = min(len, ceil(startFrame + count * fpp).toLong() + 1)
    while (f < end) {
        val n = min(CHUNK.toLong(), end - f).toInt()
        val got = src.readSamples(f, scratch, n)
        if (got <= 0) break
        for (k in 0 until got) {
            val col = ((f + k - startFrame) / fpp).toInt()
            if (col < 0 || col >= count) continue
            for (c in 0 until ch) {
                val v = scratch[k * ch + c]
                val o = (col * ch + c) * 2
                if (v < out[o]) out[o] = v
                if (v > out[o + 1]) out[o + 1] = v
            }
        }
        f += got
    }
    // пустые колонки -> min > max (уже так: +inf, -inf)
}

/** Продакшен: пики из PeakPyramid результата правок. */
class EditorWaveSource(private val editor: EditorController) : WaveSource {
    private val scratch = FloatArray(CHUNK * 8)
    override var version: Int = 0
        private set
    /** Вызывать из onStateChanged: пирамида могла измениться. */
    fun touch() { version++ }

    override val channels: Int get() = editor.state.channels.coerceIn(1, 8)
    override val lengthFrames: Long get() = editor.state.length

    override fun columns(startFrame: Double, framesPerPx: Double, count: Int, out: FloatArray) {
        if (framesPerPx < com.rassul.audioeditor.core.peaks.PeakPyramid.BASE) {
            rawColumns(this, scratch, startFrame, framesPerPx, count, out); return
        }
        val pyr = editor.peaks
        val ch = channels
        val pch = pyr.channels
        var level = 0
        var block = com.rassul.audioeditor.core.peaks.PeakPyramid.BASE.toDouble()
        while (level + 1 < pyr.levelCount && block * com.rassul.audioeditor.core.peaks.PeakPyramid.FACTOR <= framesPerPx) {
            level++; block *= com.rassul.audioeditor.core.peaks.PeakPyramid.FACTOR
        }
        val size = pyr.size(level)
        val len = lengthFrames
        for (c in 0 until count) {
            val f0 = startFrame + c * framesPerPx
            val f1 = f0 + framesPerPx
            val empty = f1 <= 0 || f0 >= len || size == 0
            var i0 = floor(max(0.0, f0) / block).toInt()
            var i1 = floor((f1 - 1e-6) / block).toInt()
            i0 = i0.coerceIn(0, max(0, size - 1)); i1 = i1.coerceIn(i0, max(0, size - 1))
            for (k in 0 until ch) {
                val o = (c * ch + k) * 2
                if (empty) { out[o] = 1f; out[o + 1] = -1f; continue }
                val pc = min(k, pch - 1)
                var mn = Float.POSITIVE_INFINITY
                var mx = Float.NEGATIVE_INFINITY
                for (i in i0..i1) {
                    val a = pyr.min(level, pc, i); val b = pyr.max(level, pc, i)
                    if (a < mn) mn = a
                    if (b > mx) mx = b
                }
                out[o] = mn; out[o + 1] = mx
            }
        }
    }

    override fun readSamples(from: Long, dst: FloatArray, frames: Int): Int = editor.readSamples(from, dst, frames)
}

/** Фрагмент синтетического «исходника»: кадры [srcStart, srcStart+len) с усилением gain. */
data class SynthSeg(val srcStart: Long, val len: Long, val gain: Double)

/**
 * Фейк: 30 минут «речи» (огибающая + два тона), без PeakPyramid. Поддерживает «правки» списком фрагментов,
 * чтобы FakeEditorController показывал реальные изменения волны (обрезка, удаление, громкость).
 */
class SyntheticWaveSource(val sampleRate: Int = 48000, durationSec: Int = 1800, override val channels: Int = 2) : WaveSource {
    private val srcLen = durationSec.toLong() * sampleRate
    var segments: List<SynthSeg> = listOf(SynthSeg(0, srcLen, 1.0))
        set(v) { field = v; version++; total = v.sumOf { it.len } }
    private var total = srcLen
    override var version: Int = 0
        private set
    override val lengthFrames: Long get() = total
    private val scratch = FloatArray(CHUNK * 2)

    private fun env(i: Long): Double {
        val t = i.toDouble() / sampleRate
        // огибающая «речи»: фразы по ~3 с, медленная модуляция; пик ≈ 0,8 (CC0, выдумано для демонстрации)
        val phrase = abs(sin(PI * t / 3.1)).pow(1.5)
        val slow = 0.6 + 0.4 * sin(2 * PI * t / 37.0)
        return 0.08 + 0.7 * phrase * slow
    }

    private fun hash(i: Long): Double {
        var x = i * -7046029254386353131L
        x = x xor (x ushr 29); x *= -4658895280553007687L; x = x xor (x ushr 32)
        return ((x and 0xFFFFFF) / 16777215.0)
    }

    /** Сегмент результата для кадра rf и исходный кадр. */
    private var lastSeg = 0
    private fun locate(rf: Long): Int {
        var acc = 0L
        val s = segments
        for (i in s.indices) { if (rf < acc + s[i].len) { lastSeg = i; return (s[i].srcStart + (rf - acc)).let { _ -> i } }; acc += s[i].len }
        return -1
    }

    private fun segStart(idx: Int): Long { var a = 0L; for (i in 0 until idx) a += segments[i].len; return a }

    private fun sample(src: Long, ch: Int, gain: Double): Float {
        val t = src.toDouble() / sampleRate
        val v = env(src) * (0.7 * sin(2 * PI * 220.0 * t + ch) + 0.3 * sin(2 * PI * 1370.0 * t + 1.0))
        return (v * gain).toFloat()
    }

    override fun readSamples(from: Long, dst: FloatArray, frames: Int): Int {
        var n = 0
        var rf = from
        while (n < frames && rf < total) {
            val si = locate(rf)
            if (si < 0) break
            val seg = segments[si]
            val off = rf - segStart(si)
            val take = min((frames - n).toLong(), seg.len - off).toInt()
            for (k in 0 until take) for (c in 0 until channels) dst[(n + k) * channels + c] = sample(seg.srcStart + off + k, c, seg.gain)
            n += take; rf += take
        }
        return n
    }

    override fun columns(startFrame: Double, framesPerPx: Double, count: Int, out: FloatArray) {
        if (framesPerPx < 64) { rawColumns(this, scratch, startFrame, framesPerPx, count, out); return }
        // Крупный масштаб: сетка блоков со степенью двойки — картинка не «дрожит» при прокрутке.
        var stride = 64L
        while (stride < framesPerPx / 3) stride *= 2
        for (c in 0 until count) {
            val f0 = startFrame + c * framesPerPx
            val f1 = f0 + framesPerPx
            val empty = f1 <= 0 || f0 >= total
            val g0 = floor(max(0.0, f0) / stride).toLong()
            val g1 = max(g0, floor((min(f1, total.toDouble()) - 1e-6) / stride).toLong())
            for (k in 0 until channels) {
                val o = (c * channels + k) * 2
                if (empty) { out[o] = 1f; out[o + 1] = -1f; continue }
                var mn = 0f; var mx = 0f
                for (g in g0..g1) {
                    val rf = min(total - 1, g * stride + stride / 2)
                    val si = locate(rf)
                    if (si < 0) continue
                    val seg = segments[si]
                    val src = seg.srcStart + (rf - segStart(si))
                    val a = env(src) * seg.gain * (0.7 + 0.3 * hash(src / stride + k * 977L))
                    val b = env(src) * seg.gain * (0.7 + 0.3 * hash(src / stride + 31L + k * 977L))
                    if (a > mx) mx = a.toFloat()
                    if (-b < mn) mn = (-b).toFloat()
                }
                out[o] = mn; out[o + 1] = mx
            }
        }
    }
}
