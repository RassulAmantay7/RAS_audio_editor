package com.rassul.audioeditor.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.GestureDetector
import android.view.View
import android.widget.OverScroller
import com.rassul.audioeditor.editor.Selection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Волна + шкала времени + выделение + курсор (FR-12, 14, 15).
 *  - щипок: от «весь файл» до 1 сэмпл = 8 px (MIN_FPP = 1/8 кадра на пиксель); прокрутка с инерцией;
 *  - тап — перемотка; долгое нажатие + движение — новое выделение; ручки выделения тянутся пальцем;
 *  - fpp < 1: рисуются отдельные сэмплы (линия + точки);
 *  - красная подсветка |x| > 1 (+ полоса-маркер сверху и подпись — смысл не только цветом);
 *  - курсор воспроизведения плавно ведётся через postOnAnimation (экстраполяция между опросами контроллера);
 *  - onDraw не аллоцирует: все Paint/буфера/подписи шкалы (CharArray) создаются заранее.
 */
class WaveformView(ctx: Context) : View(ctx) {
    interface Listener {
        fun onSeek(frame: Long)
        /** Выделение меняется во время перетаскивания (для обновления полей времени). */
        fun onSelectionDrag(sel: Selection)
        /** Перетаскивание закончено. */
        fun onSelectionCommit(sel: Selection)
    }

    companion object {
        const val MIN_FPP = 0.125          // 1 сэмпл = 8 px (FR-12)
        private const val RANGE = 1.15f    // вертикальный диапазон ±1,15: перегрузка видна над линией 0 дБ
        private const val MAX_TICKS = 64
        private val STEPS = doubleArrayOf(
            0.001, 0.002, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
            60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0,
        )
    }

    var listener: Listener? = null
    var sampleRate: Int = 48000
    /** Текущая позиция курсора (кадры); опрашивается каждый кадр анимации. */
    var playheadProvider: () -> Long = { 0L }

    var source: WaveSource? = null
        set(v) { field = v; ensureBuffers(); if (fitMode) fitAll() else clampViewport(); invalidate() }

    var selection: Selection? = null
        set(v) { field = v; if (mode == Mode.NONE) invalidate() }

    var playing: Boolean = false
        set(v) {
            field = v
            if (v) { lastTarget = playheadProvider(); lastTargetNanos = System.nanoTime(); shown = lastTarget.toDouble() }
            ensureTicking(); invalidate()
        }

    // ---- вид
    private var startFrame = 0.0
    private var fpp = 1.0
    private var fitMode = true
    private var lengthCached = 0L

    // ---- краски (созданы один раз)
    private val p = Ui.colors
    private val dens = ctx.resources.displayMetrics.density
    private val rulerH = 28f * dens
    private val paintBg = Paint().apply { color = p.waveBg }
    private val paintRuler = Paint().apply { color = Tokens.blend(p.text, p.waveBg, if (p.isDark) 0.05f else 0.06f) }
    private val paintWave = Paint().apply { color = p.waveform; strokeWidth = 1f; isAntiAlias = false }
    private val paintDim = Paint().apply { color = Tokens.blend(p.waveform, p.waveBg, if (p.isDark) 0.45f else 0.7f); strokeWidth = 1f; isAntiAlias = false }
    private val paintOver = Paint().apply { color = p.overload; strokeWidth = 1f; isAntiAlias = false }
    private val paintGrid = Paint().apply {
        color = Tokens.withAlpha(p.textSecondary, 0.35f); strokeWidth = dens
        pathEffect = DashPathEffect(floatArrayOf(4f * dens, 4f * dens), 0f)
    }
    private val paintCenter = Paint().apply { color = Tokens.withAlpha(p.textSecondary, 0.45f); strokeWidth = dens }
    private val paintLane = Paint().apply { color = Tokens.withAlpha(p.textSecondary, 0.3f); strokeWidth = dens }
    private val paintTick = Paint().apply { color = Tokens.withAlpha(p.textSecondary, 0.8f); strokeWidth = dens }
    private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.textSecondary; textSize = 11f * ctx.resources.displayMetrics.scaledDensity; typeface = Tokens.FONT_MONO
    }
    private val paintSel = Paint().apply { color = Tokens.withAlpha(p.primary, if (p.isDark) 0.20f else 0.16f) }
    private val paintSelEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary; strokeWidth = 2f * dens }
    private val paintKnob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary }
    private val paintKnobMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.onPrimary; strokeWidth = 2f * dens; strokeCap = Paint.Cap.ROUND }
    private val paintHead = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.text; strokeWidth = 2f * dens }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.waveform }
    private val paintDotOver = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.overload }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.waveform; strokeWidth = 1.5f * dens }
    private val paintOverStrip = Paint().apply { color = p.overload }
    private val paintPill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.dangerContainer(p) }
    private val paintPillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = p.text; textSize = 11f * ctx.resources.displayMetrics.scaledDensity; typeface = Tokens.FONT_MEDIUM
    }
    private val pill = RectF()

    // ---- буферы (меняются только при смене размера/источника)
    private var cols = FloatArray(0)
    private var ptsA = FloatArray(0)       // в выделении / без выделения
    private var ptsB = FloatArray(0)       // вне выделения
    private var ptsC = FloatArray(0)       // перегрузка
    private var ptsStrip = FloatArray(0)
    private var sampBuf = FloatArray(0)
    private var colsKeyStart = Double.NaN
    private var colsKeyFpp = 0.0
    private var colsKeyW = -1
    private var colsKeyVer = -1
    private var colsKeyLen = -1L
    private var anyOverload = false

    // шкала времени
    private val tickX = FloatArray(MAX_TICKS)
    private val tickLen = IntArray(MAX_TICKS)
    private val tickChars = Array(MAX_TICKS) { CharArray(16) }
    private var tickCount = 0
    private var tickKeyStart = Double.NaN
    private var tickKeyFpp = 0.0
    private var tickKeyRate = 0

    // ---- жесты
    private enum class Mode { NONE, HANDLE, NEWSEL }
    private var mode = Mode.NONE
    private var anchor = 0L
    private var dragSel: Selection? = null
    private var lastX = 0f
    private val scroller = OverScroller(ctx)
    private var flinging = false
    private var zoomAnim: ValueAnimator? = null
    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomAt(d.focusX, fpp / d.scaleFactor.toDouble())
            return true
        }
    })
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (mode != Mode.NONE) return true
            val f = frameAtPx(e.x).coerceIn(0L, max(0L, lengthFrames() - 1))
            listener?.onSeek(f)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (mode != Mode.NONE || scaleDetector.isInProgress) return true
            fitMode = false
            startFrame += dx * fpp
            pauseFollow()
            clampViewport(); invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (mode != Mode.NONE || scaleDetector.isInProgress) return true
            val x0 = (startFrame / fpp).toInt()
            val maxX = (maxStart() / fpp).toInt()
            scroller.fling(x0, 0, (-vx).toInt(), 0, 0, max(0, maxX), 0, 0)
            flinging = true
            ensureTicking()
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            if (mode != Mode.NONE || scaleDetector.isInProgress) return
            anchor = frameAtPx(e.x).coerceIn(0L, lengthFrames())
            dragSel = Selection(anchor, min(lengthFrames(), anchor + 1))
            mode = Mode.NEWSEL
            lastX = e.x
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }).apply { setIsLongpressEnabled(true) }

    // ---- курсор
    private var shown = 0.0
    private var lastTarget = 0L
    private var lastTargetNanos = 0L
    private var followPausedUntil = 0L
    private var tickScheduled = false
    private val ticker = Runnable { tickScheduled = false; tick() }

    init {
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun lengthFrames(): Long = source?.lengthFrames ?: 0L
    private fun frameAtPx(x: Float): Long = (startFrame + x * fpp).roundToLong()
    private fun pxOf(frame: Double): Float = ((frame - startFrame) / fpp).toFloat()
    private fun plotW(): Int = max(1, width)
    private fun maxFpp(): Double = max(MIN_FPP, lengthFrames().toDouble() / plotW())
    private fun maxStart(): Double = max(0.0, lengthFrames() - plotW() * fpp)

    // ------------------------------------------------------------------ публичное управление видом

    /** «Весь файл» на экране. */
    fun fitAll() {
        fitMode = true
        zoomAnim?.cancel()
        fpp = maxFpp(); startFrame = 0.0
        invalidate()
    }

    /** Приблизить/отдалить вокруг центра (или курсора, если он виден). */
    fun zoomBy(factor: Double) {
        val head = pxOf(shown.takeIf { playing } ?: playheadProvider().toDouble())
        val cx = if (head in 0f..plotW().toFloat()) head else plotW() / 2f
        val target = (fpp / factor).coerceIn(MIN_FPP, maxFpp())
        zoomAnim?.cancel()
        zoomAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            val from = fpp
            duration = Tokens.DUR_STANDARD
            interpolator = Tokens.EASE_STANDARD
            addUpdateListener {
                val t = it.animatedValue as Float
                // интерполяция в логарифмической шкале — равномерное ощущение зума
                val f = from * Math.pow(target / from, t.toDouble())
                zoomAt(cx, f)
            }
            start()
        }
    }

    /** Показать кадр (например, после ввода времени). */
    fun reveal(frame: Long) {
        val px = pxOf(frame.toDouble())
        if (px < 0f || px > plotW()) { startFrame = frame - plotW() * fpp * 0.3; clampViewport(); invalidate() }
    }

    fun framesPerPixel(): Double = fpp

    // ------------------------------------------------------------------ геометрия

    private fun ensureBuffers() {
        val w = max(1, width)
        val ch = max(1, min(source?.channels ?: 1, 8))
        val lanes = min(ch, 2)
        if (cols.size != w * ch * 2) cols = FloatArray(w * ch * 2)
        val n = (w + 8) * lanes * 4
        if (ptsA.size != n) { ptsA = FloatArray(n); ptsB = FloatArray(n); ptsC = FloatArray(n) }
        if (ptsStrip.size != (w + 8) * 4) ptsStrip = FloatArray((w + 8) * 4)
        val sb = (w + 8) * ch
        if (sampBuf.size != sb) sampBuf = FloatArray(sb)
        colsKeyW = -1
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureBuffers()
        if (fitMode) fpp = maxFpp() else clampViewport()
    }

    private fun clampViewport() {
        val len = lengthFrames()
        lengthCached = len
        fpp = fpp.coerceIn(MIN_FPP, maxFpp())
        startFrame = startFrame.coerceIn(0.0, maxStart())
    }

    private fun zoomAt(cx: Float, newFpp: Double) {
        val focus = startFrame + cx * fpp
        fitMode = false
        fpp = newFpp.coerceIn(MIN_FPP, maxFpp())
        startFrame = focus - cx * fpp
        clampViewport()
        invalidate()
    }

    private fun pauseFollow() { followPausedUntil = System.nanoTime() + 3_000_000_000L }

    // ------------------------------------------------------------------ касания

    private fun handleAt(x: Float): Int { // 0 нет, 1 начало, 2 конец
        val s = selection ?: return 0
        val touch = 28f * dens
        val ds = abs(x - pxOf(s.start.toDouble()))
        val de = abs(x - pxOf(s.end.toDouble()))
        if (ds > touch && de > touch) return 0
        return if (ds < de || (ds == de && x < pxOf(s.start.toDouble()))) 1 else 2
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) {
            if (mode != Mode.NONE) { mode = Mode.NONE; dragSel = null }
            pauseFollow()
            return true
        }
        val pre = ev.actionMasked
        if (pre == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            scroller.forceFinished(true); flinging = false
            pauseFollow()
            val h = handleAt(ev.x)
            val s = selection
            if (h != 0 && s != null) {
                mode = Mode.HANDLE
                anchor = if (h == 1) s.end else s.start
                dragSel = s
                lastX = ev.x
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        gd.onTouchEvent(ev)
        when (pre) {
            MotionEvent.ACTION_MOVE -> if (mode != Mode.NONE) {
                lastX = ev.x
                updateDrag(ev.x)
                ensureTicking()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != Mode.NONE) {
                    val d = dragSel
                    val old = selection
                    mode = Mode.NONE
                    dragSel = null
                    if (pre == MotionEvent.ACTION_UP && d != null && d.end > d.start) {
                        selection = d
                        listener?.onSelectionCommit(d)
                    } else selection = old
                    invalidate()
                }
            }
        }
        return true
    }

    private fun updateDrag(x: Float) {
        val len = lengthFrames()
        val f = frameAtPx(x).coerceIn(0L, len)
        val a = min(anchor, f)
        val b = max(anchor, f)
        val s = Selection(a, if (b == a) min(len, a + 1) else b)
        dragSel = s
        listener?.onSelectionDrag(s)
        invalidate()
    }

    // ------------------------------------------------------------------ анимация (курсор, инерция, автопрокрутка)

    private fun ensureTicking() {
        if (tickScheduled || !isAttachedToWindow) return
        if (playing || flinging || mode != Mode.NONE) { tickScheduled = true; postOnAnimation(ticker) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureTicking() }
    override fun onDetachedFromWindow() { removeCallbacks(ticker); tickScheduled = false; zoomAnim?.cancel(); super.onDetachedFromWindow() }

    private fun tick() {
        var again = false
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                startFrame = scroller.currX * fpp
                clampViewport()
                again = true
            } else flinging = false
        }
        if (mode != Mode.NONE) {
            // автопрокрутка у краёв при перетаскивании
            val edge = 40f * dens
            val w = plotW()
            val speed = when {
                lastX < edge -> -(edge - lastX) / edge
                lastX > w - edge -> (lastX - (w - edge)) / edge
                else -> 0f
            }
            if (speed != 0f) {
                startFrame += speed.toDouble() * 14.0 * dens * fpp
                clampViewport()
                updateDrag(lastX)
            }
            again = true
        }
        if (playing) {
            val target = playheadProvider()
            val now = System.nanoTime()
            if (target != lastTarget) { lastTarget = target; lastTargetNanos = now }
            // между опросами курсор едет сам (не дальше 80 мс), чтобы движение было плавным
            val extra = min(now - lastTargetNanos, 80_000_000L) / 1e9 * sampleRate
            val est = lastTarget + extra
            shown = if (abs(est - shown) > sampleRate * 0.25 || est > shown) est else shown
            if (System.nanoTime() > followPausedUntil) {
                val px = pxOf(shown)
                if (px > plotW() * 0.92f || px < 0f) { startFrame = shown - plotW() * 0.1 * fpp; clampViewport() }
            }
            again = true
        }
        invalidate()
        if (again) { tickScheduled = true; postOnAnimation(ticker) }
    }

    // ------------------------------------------------------------------ рисование

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height.toFloat()
        if (w <= 0 || h <= 0f) return
        canvas.drawRect(0f, 0f, w.toFloat(), h, paintBg)
        val src = source
        val top = rulerH
        canvas.drawRect(0f, 0f, w.toFloat(), top, paintRuler)

        if (src != null && cols.size >= w * src.channels.coerceIn(1, 8) * 2) {
            if (src.lengthFrames != lengthCached) clampViewport()
            drawWave(canvas, src, w, top, h)
        }
        drawRuler(canvas, w, top)
        drawSelectionAndHead(canvas, w, top, h)
    }

    private fun drawWave(canvas: Canvas, src: WaveSource, w: Int, top: Float, h: Float) {
        val ch = src.channels.coerceIn(1, 8)
        val lanes = min(ch, 2)
        val laneH = (h - top) / lanes
        val sel = dragSel ?: selection
        val sS = sel?.start ?: -1L
        val sE = sel?.end ?: -1L

        // сетка: линия 0 и уровни ±1,0 (0 дБ полной шкалы)
        for (l in 0 until lanes) {
            val cy = top + laneH * l + laneH / 2f
            val scale = laneH / 2f / RANGE
            canvas.drawLine(0f, cy, w.toFloat(), cy, paintCenter)
            canvas.drawLine(0f, cy - scale, w.toFloat(), cy - scale, paintGrid)
            canvas.drawLine(0f, cy + scale, w.toFloat(), cy + scale, paintGrid)
            if (l > 0) canvas.drawLine(0f, top + laneH * l, w.toFloat(), top + laneH * l, paintLane)
        }
        canvas.drawText("0 дБ", 4f * dens, top + laneH / 2f - laneH / 2f / RANGE - 3f * dens, paintText)

        if (fpp < 1.0) { drawSamples(canvas, src, w, top, laneH, ch, lanes); return }

        if (colsKeyStart != startFrame || colsKeyFpp != fpp || colsKeyW != w || colsKeyVer != src.version || colsKeyLen != src.lengthFrames) {
            src.columns(startFrame, fpp, w, cols)
            colsKeyStart = startFrame; colsKeyFpp = fpp; colsKeyW = w; colsKeyVer = src.version; colsKeyLen = src.lengthFrames
            anyOverload = false
        }
        var nA = 0; var nB = 0; var nC = 0; var nS = 0
        val hasSel = sel != null
        for (c in 0 until w) {
            val mid = startFrame + (c + 0.5) * fpp
            val inSel = hasSel && mid >= sS && mid < sE
            var colOver = false
            for (l in 0 until lanes) {
                val o = (c * ch + l) * 2
                val mn = cols[o]
                val mx = cols[o + 1]
                if (mn > mx) continue
                val cy = top + laneH * l + laneH / 2f
                val scale = laneH / 2f / RANGE
                val y1 = cy - mx * scale
                var y2 = cy - mn * scale
                if (y2 - y1 < 1f) y2 = y1 + 1f
                val x = c + 0.5f
                val over = mx > 1f || mn < -1f
                if (over) { colOver = true; ptsC[nC++] = x; ptsC[nC++] = y1; ptsC[nC++] = x; ptsC[nC++] = y2 }
                else if (!hasSel || inSel) { ptsA[nA++] = x; ptsA[nA++] = y1; ptsA[nA++] = x; ptsA[nA++] = y2 }
                else { ptsB[nB++] = x; ptsB[nB++] = y1; ptsB[nB++] = x; ptsB[nB++] = y2 }
            }
            if (colOver) { val x = c + 0.5f; ptsStrip[nS++] = x; ptsStrip[nS++] = top; ptsStrip[nS++] = x; ptsStrip[nS++] = top + 3f * dens }
        }
        if (nB > 0) canvas.drawLines(ptsB, 0, nB, paintDim)
        if (nA > 0) canvas.drawLines(ptsA, 0, nA, paintWave)
        if (nC > 0) canvas.drawLines(ptsC, 0, nC, paintOver)
        if (nS > 0) {
            paintOverStrip.strokeWidth = 1f
            canvas.drawLines(ptsStrip, 0, nS, paintOverStrip)
            drawPill(canvas, "Перегрузка > 0 дБ", w - 8f * dens, top + 8f * dens, true)
        }
    }

    private fun drawPill(canvas: Canvas, s: String, right: Float, y: Float, alignRight: Boolean) {
        val tw = paintPillText.measureText(s)
        val padX = 8f * dens
        val ph = 20f * dens
        val l = if (alignRight) right - tw - 2 * padX else right
        pill.set(l, y, l + tw + 2 * padX, y + ph)
        canvas.drawRoundRect(pill, ph / 2, ph / 2, paintPill)
        canvas.drawText(s, l + padX, y + ph / 2f + paintPillText.textSize * 0.35f, paintPillText)
    }

    /** fpp < 1: отдельные сэмплы. Линия между сэмплами; при ≥ 4 px на сэмпл — точки. */
    private fun drawSamples(canvas: Canvas, src: WaveSource, w: Int, top: Float, laneH: Float, ch: Int, lanes: Int) {
        val f0 = max(0L, floor(startFrame).toLong())
        val nWanted = (ceil(w * fpp).toInt() + 3)
        val n = min(nWanted, sampBuf.size / ch)
        val got = src.readSamples(f0, sampBuf, n)
        if (got <= 0) return
        val dots = fpp <= 0.25
        val r = 3f * dens
        var anyOver = false
        for (l in 0 until lanes) {
            val cy = top + laneH * l + laneH / 2f
            val scale = laneH / 2f / RANGE
            var k = 0
            var prevX = 0f; var prevY = 0f
            for (i in 0 until got) {
                val v = sampBuf[i * ch + l]
                val x = pxOf((f0 + i).toDouble())
                val y = cy - v * scale
                if (i > 0) {
                    if (k + 4 <= ptsA.size) { ptsA[k++] = prevX; ptsA[k++] = prevY; ptsA[k++] = x; ptsA[k++] = y }
                }
                prevX = x; prevY = y
                if (abs(v) > 1f) anyOver = true
            }
            canvas.drawLines(ptsA, 0, k, paintLine)
            if (dots) for (i in 0 until got) {
                val v = sampBuf[i * ch + l]
                canvas.drawCircle(pxOf((f0 + i).toDouble()), cy - v * scale, r, if (abs(v) > 1f) paintDotOver else paintDot)
            }
        }
        drawPill(canvas, "Отдельные сэмплы", 8f * dens, top + 8f * dens, false)
        if (anyOver) drawPill(canvas, "Перегрузка > 0 дБ", w - 8f * dens, top + 8f * dens, true)
    }

    private fun drawRuler(canvas: Canvas, w: Int, top: Float) {
        if (tickKeyStart != startFrame || tickKeyFpp != fpp || tickKeyRate != sampleRate) {
            rebuildTicks(w)
            tickKeyStart = startFrame; tickKeyFpp = fpp; tickKeyRate = sampleRate
        }
        for (i in 0 until tickCount) {
            val x = tickX[i]
            val major = tickLen[i] > 0
            canvas.drawLine(x, if (major) top - 10f * dens else top - 5f * dens, x, top, paintTick)
            if (major) canvas.drawText(tickChars[i], 0, tickLen[i], x + 4f * dens, top - 12f * dens, paintText)
        }
        canvas.drawLine(0f, top, w.toFloat(), top, paintLane)
    }

    private fun rebuildTicks(w: Int) {
        tickCount = 0
        val secPerPx = fpp / sampleRate
        var step = STEPS[STEPS.size - 1]
        for (s in STEPS) if (s / secPerPx >= 80f * dens) { step = s; break }
        val half = step / 2
        val t0 = startFrame / sampleRate
        var j = ceil(t0 / half).toLong()
        val decimals = when { step >= 1.0 -> 0; step >= 0.1 -> 1; step >= 0.01 -> 2; else -> 3 }
        while (tickCount < MAX_TICKS) {
            val t = j * half
            val x = ((t * sampleRate - startFrame) / fpp).toFloat()
            if (x > w) break
            tickX[tickCount] = x
            tickLen[tickCount] = if (j % 2L == 0L) writeTime(tickChars[tickCount], t, decimals) else 0
            tickCount++
            j++
        }
    }

    /** Время в CharArray без аллокаций: «м:сс», «ч:мм:сс», с долями при мелком шаге. Возвращает длину. */
    private fun writeTime(buf: CharArray, sec: Double, decimals: Int): Int {
        var ms = (sec * 1000.0).roundToLong()
        if (ms < 0) ms = 0
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val frac = (ms % 1000).toInt()
        var n = 0
        if (h > 0) { n = putInt(buf, n, h.toInt(), 1); buf[n++] = ':'; n = putInt(buf, n, m.toInt(), 2) }
        else n = putInt(buf, n, m.toInt(), 1)
        buf[n++] = ':'
        n = putInt(buf, n, s.toInt(), 2)
        if (decimals > 0) {
            buf[n++] = ','
            val d3 = intArrayOfDigits(frac)
            for (k in 0 until decimals) buf[n++] = ('0' + ((frac / d3[k]) % 10))
        }
        return n
    }

    private val pow10 = intArrayOf(100, 10, 1)
    private fun intArrayOfDigits(@Suppress("UNUSED_PARAMETER") v: Int): IntArray = pow10

    private fun putInt(buf: CharArray, at: Int, v: Int, minDigits: Int): Int {
        var digits = 1
        var t = v
        while (t >= 10) { t /= 10; digits++ }
        val total = max(digits, minDigits)
        var x = v
        for (i in total - 1 downTo 0) { buf[at + i] = '0' + (x % 10); x /= 10 }
        return at + total
    }

    private fun drawSelectionAndHead(canvas: Canvas, w: Int, top: Float, h: Float) {
        val sel = dragSel ?: selection
        if (sel != null) {
            val xs = pxOf(sel.start.toDouble())
            val xe = pxOf(sel.end.toDouble())
            canvas.drawRect(max(0f, xs), top, min(w.toFloat(), max(xe, xs + 1f)), h, paintSel)
            val r = 9f * dens
            if (xs >= -r && xs <= w + r) {
                canvas.drawLine(xs, top, xs, h, paintSelEdge)
                canvas.drawCircle(xs, top + 22f * dens, r, paintKnob)
                canvas.drawLine(xs - 2.5f * dens, top + 18f * dens, xs - 2.5f * dens, top + 26f * dens, paintKnobMark)
                canvas.drawLine(xs + 2.5f * dens, top + 18f * dens, xs + 2.5f * dens, top + 26f * dens, paintKnobMark)
            }
            if (xe >= -r && xe <= w + r) {
                canvas.drawLine(xe, top, xe, h, paintSelEdge)
                canvas.drawCircle(xe, h - 22f * dens, r, paintKnob)
                canvas.drawLine(xe - 2.5f * dens, h - 26f * dens, xe - 2.5f * dens, h - 18f * dens, paintKnobMark)
                canvas.drawLine(xe + 2.5f * dens, h - 26f * dens, xe + 2.5f * dens, h - 18f * dens, paintKnobMark)
            }
        }
        val ph = if (playing) shown else playheadProvider().toDouble()
        val x = pxOf(ph)
        if (x >= -6f * dens && x <= w + 6f * dens) {
            canvas.drawLine(x, 0f, x, h, paintHead)
            canvas.drawCircle(x, 5f * dens, 5f * dens, paintHead)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val minH = (220f * dens).toInt()
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) minH else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }
}
