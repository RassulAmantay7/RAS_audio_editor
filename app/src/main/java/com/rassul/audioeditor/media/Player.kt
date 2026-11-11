package com.rassul.audioeditor.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.rassul.audioeditor.core.edit.LoopCursor
import com.rassul.audioeditor.core.edit.Range
import com.rassul.audioeditor.core.edit.Renderer

/*
 * Зона MEDIA, T17. FR-13 (воспроизведение результата правок тем же Renderer, что и экспорт), FR-32 (повтор).
 * Поставщик кадров — LoopCursor (:core) с журналом сегментов; курсор по AudioTrack.getTimestamp
 * (timeline.md «Курсор без накопленной ошибки»): F = framePosition + round_half_up((now − nanoTime)·fs/10⁹),
 * позиция = p0 + (F − w0). Задержка тракта уже учтена в framePosition — отдельно не вычитается.
 * Блок ≤ 20 мс и буфер AudioTrack ≈ 2× минимального → новые границы цикла звучат ≤ 200 мс (timeline.md FR-32).
 *
 * Все публичные методы — из UI-потока; колбэки Listener — в главном потоке.
 */
class Player(renderer: Renderer) {
    interface Listener {
        fun onPlayStateChanged(playing: Boolean) {}
        /** Дошли до конца результата (без повтора). */
        fun onEnded() {}
        fun onError(message: String) {}
    }

    var listener: Listener? = null
    val cursor = LoopCursor(renderer)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private var track: AudioTrack? = null
    private var trackRate = 0
    private var trackCh = 0
    private var thread: Thread? = null
    @Volatile private var playing = false
    @Volatile private var released = false
    @Volatile private var endOfData = false
    /** Номер последнего записанного в AudioTrack кадра (шкала вывода = шкала журнала). */
    @Volatile private var written = 0L
    /** Позиция при паузе/перемотке (когда журнал ещё пуст). */
    @Volatile private var restPos = 0L
    /** Счётчик сбросов AudioTrack (flush): поток записи отбрасывает недописанный блок. */
    private var flushGen = 0L
    private val ts = AudioTimestamp()

    val isPlaying: Boolean get() = playing
    val renderer: Renderer get() = cursor.renderer

    /** Позиция воспроизведения в кадрах результата (опрашивать с кадром анимации). */
    val position: Long
        get() = synchronized(lock) {
            val t = track ?: return restPos
            if (!playing) return restPos
            val p = if (t.getTimestamp(ts)) cursor.cursorAt(ts.framePosition, ts.nanoTime, System.nanoTime())
                    else cursor.renderPositionAt(t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
            // Очень короткий цикл: сегмент мог уже выпасть из журнала — показываем начало цикла (аудит EDIT).
            if (p != null) restPos = p else cursor.loopBounds()?.let { restPos = it.start }
            restPos
        }

    /** Выделение для повтора (координаты результата); null — весь файл. Применяется с первого незаписанного блока. */
    fun setSelection(sel: Range?) { cursor.selection = sel }

    /** «Повтор» (FR-32). */
    fun setRepeat(repeat: Boolean) { cursor.repeat = repeat }

    /**
     * Новый Renderer после правки. [newPosition] — позиция в новых координатах (null — оставить текущую, ограничив длиной).
     * Если позиция меняется — недоигранный буфер сбрасывается (возможен короткий щелчок), иначе доиграет плавно.
     */
    fun setRenderer(r: Renderer, newPosition: Long? = null) {
        val pos = newPosition ?: minOf(position, r.length)
        cursor.renderer = r
        if (newPosition != null || r.spec.sampleRate != trackRate || r.spec.channels != trackCh) seekTo(pos)
        else if (!playing) { restPos = pos; cursor.seek(pos) }
    }

    fun play() {
        if (released) return
        synchronized(lock) {
            if (playing) return
            val r = cursor.renderer
            if (!cursor.repeat && restPos >= r.length) { restPos = 0 }
            val t = ensureTrack() ?: return
            if (endOfData || cursor.framesWritten == 0L) {
                // Чистый старт с restPos: сбросить журнал и буфер.
                t.pause(); t.flush(); flushGen++
                cursor.seek(restPos); cursor.resetOutput(0); written = 0
                endOfData = false
            }
            playing = true
            t.play()
            ensureThread()
            lock.notifyAll()
        }
        post { it.onPlayStateChanged(true) }
    }

    fun pause() {
        synchronized(lock) {
            if (!playing) return
            restPos = position
            playing = false
            track?.pause()
            // Записанное, но не проигранное, отбрасываем: следующее «Играть» начнёт ровно с restPos.
            track?.flush(); flushGen++
            cursor.seek(restPos); cursor.resetOutput(0); written = 0
            lock.notifyAll()
        }
        post { it.onPlayStateChanged(false) }
    }

    /** Для интегратора: Activity.onPause / уход приложения в фон (FR-13). */
    fun onAppPause() = pause()

    fun seekTo(frame: Long) {
        synchronized(lock) {
            val f = frame.coerceIn(0L, cursor.renderer.length)
            restPos = f
            val t = track
            val was = playing
            if (t != null) { t.pause(); t.flush() }; flushGen++
            cursor.seek(f); cursor.resetOutput(0); written = 0; endOfData = false
            if (was && t != null) t.play()
            lock.notifyAll()
        }
    }

    fun release() {
        synchronized(lock) {
            released = true; playing = false
            lock.notifyAll()
        }
        thread?.join(500)
        synchronized(lock) {
            try { track?.stop() } catch (_: Exception) {}
            track?.release(); track = null
        }
    }

    private fun ensureTrack(): AudioTrack? {
        val spec = cursor.renderer.spec
        track?.let { if (trackRate == spec.sampleRate && trackCh == spec.channels) return it; it.release(); track = null }
        val chMask = if (spec.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        return try {
            val min = AudioTrack.getMinBufferSize(spec.sampleRate, chMask, AudioFormat.ENCODING_PCM_FLOAT)
            val frameBytes = 4 * spec.channels
            // ≈ 2× минимального, но не меньше 40 мс и не больше 120 мс (timeline.md FR-32: запись вперёд + блок ≤ 200 мс).
            val want = (2 * maxOf(min, 0)).coerceIn(spec.sampleRate / 25 * frameBytes, spec.sampleRate * 120 / 1000 * frameBytes)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(spec.sampleRate).setChannelMask(chMask).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(want)
                .build()
            trackRate = spec.sampleRate; trackCh = spec.channels
            track = t
            cursor.resetOutput(0); written = 0; flushGen++
            t
        } catch (e: Exception) {
            post { it.onError("Не удалось запустить воспроизведение.") }
            null
        }
    }

    private fun ensureThread() {
        if (thread?.isAlive == true) return
        thread = Thread(::audioLoop, "player").apply { priority = Thread.MAX_PRIORITY; start() }
    }

    private fun audioLoop() {
        var buf = FloatArray(0)
        var pending = 0       // сэмплов в buf, ещё не принятых AudioTrack
        var off = 0
        var gen = -1L
        while (true) {
            var idle = false
            synchronized(lock) {
                while (!released && (!playing || endOfData)) {
                    if (playing && endOfData) drainCheck()
                    if (!released && (!playing || endOfData)) lock.wait(if (playing && endOfData) 20L else 0L)
                }
                if (released) return
                val t = track ?: return
                if (gen != flushGen) { pending = 0; off = 0; gen = flushGen }   // был flush — старый блок не дописывать
                if (pending == 0) {
                    val spec = cursor.renderer.spec
                    val block = maxOf(64, spec.sampleRate / 50)          // 20 мс (timeline.md FR-32)
                    if (buf.size < block * spec.channels) buf = FloatArray(block * spec.channels)
                    val n = cursor.fill(buf, block)
                    if (n <= 0) endOfData = true
                    else { pending = n * spec.channels; off = 0; written += n }
                }
                if (pending > 0) {
                    // Неблокирующая запись под замком: flush/seek не могут вклиниться между fill и write.
                    val w = t.write(buf, off, pending, AudioTrack.WRITE_NON_BLOCKING)
                    if (w < 0) { playing = false; post { it.onError("Ошибка воспроизведения ($w).") } }
                    else { pending -= w; off += w; idle = w == 0 }
                }
            }
            if (idle) try { Thread.sleep(4) } catch (_: InterruptedException) { return }
        }
    }

    /** При endOfData: дождаться, пока доиграет записанное, затем остановиться (без повтора). true — разбудить цикл. */
    private fun drainCheck(): Boolean {
        val t = track ?: return false
        val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        if (head >= written) {
            restPos = cursor.renderer.length
            playing = false
            t.pause()
            post { it.onPlayStateChanged(false); it.onEnded() }
        }
        return false
    }

    private fun post(f: (Listener) -> Unit) { val l = listener ?: return; main.post { f(l) } }
}
