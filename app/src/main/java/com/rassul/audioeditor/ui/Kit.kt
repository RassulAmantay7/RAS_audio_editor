package com.rassul.audioeditor.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/* Общие строительные блоки экранов: карточки, поля с ошибкой под полем, чипы, крупные кнопки, индикаторы. */

internal fun Context.dp(v: Int): Int = Ui.dp(this, v)
internal fun Context.dp(v: Float): Int = Ui.dp(this, v)

internal fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

internal fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

internal object Kit {
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun vertical(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun horizontal(ctx: Context, center: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        if (center) gravity = Gravity.CENTER_VERTICAL
    }

    /** Карточка: поверхность, радиус 20dp, в тёмной теме — тонкая светлая обводка вместо тени. */
    fun card(ctx: Context, color: Int = Ui.colors.surface, radius: Float = Tokens.RADIUS_L): LinearLayout = vertical(ctx).apply {
        val p = Ui.colors
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = ctx.dp(radius).toFloat()
            if (p.isDark) setStroke(ctx.dp(1).coerceAtLeast(1), 0x14FFFFFF)
        }
        if (!p.isDark) { elevation = ctx.dp(Tokens.ELEV_CARD).toFloat(); outlineProvider = ViewOutlineProvider.BACKGROUND }
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float = Tokens.TEXT_BODY, color: Int = Ui.colors.text,
             font: android.graphics.Typeface = Tokens.FONT_REGULAR): TextView = TextView(ctx).apply {
        text = s
        typeface = font
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
    }

    fun heading(ctx: Context, s: String): TextView = text(ctx, s, Tokens.TEXT_LABEL, Ui.colors.primary, Tokens.FONT_MEDIUM).apply {
        letterSpacing = 0.04f
        isAccessibilityHeading = true
    }

    fun icon(ctx: Context, icon: Icon, tint: Int, sizeDp: Int = 24): ImageView = ImageView(ctx).apply {
        setImageDrawable(IconDrawable(icon, tint, ctx.dp(sizeDp)))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    enum class Tone { INFO, WARNING, DANGER, OK }

    /** Строка-пояснение с иконкой и текстом (смысл не передаётся только цветом). */
    fun notice(ctx: Context, tone: Tone, s: CharSequence, boxed: Boolean = true): LinearLayout {
        val p = Ui.colors
        val (ic, tint, bg) = when (tone) {
            Tone.INFO -> Triple(Icon.INFO, p.primary, Tokens.primaryContainer(p))
            Tone.OK -> Triple(Icon.CHECK, p.primary, Tokens.primaryContainer(p))
            Tone.WARNING -> Triple(Icon.WARNING, p.warning, Tokens.warningContainer(p))
            Tone.DANGER -> Triple(Icon.ERROR, p.danger, Tokens.dangerContainer(p))
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            if (boxed) {
                background = Ui.roundRect(ctx, bg, Tokens.RADIUS_M)
                setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            }
            addView(icon(ctx, ic, tint, 20), lp(ctx.dp(20), ctx.dp(20)).margins(0, ctx.dp(1), ctx.dp(10), 0))
            addView(text(ctx, s, Tokens.TEXT_LABEL, p.text).apply { setLineSpacing(0f, 1.15f) }, lp(0, Kit.WRAP, 1f))
        }
    }

    /** Крупная кнопка-плитка: иконка в круге, заголовок, подзаголовок. */
    fun tile(ctx: Context, icon: Icon, title: String, subtitle: String?, primary: Boolean, trailingChevron: Boolean = false,
             onClick: () -> Unit): LinearLayout {
        val p = Ui.colors
        val fg = if (primary) p.onPrimary else p.text
        val sub = if (primary) Tokens.withAlpha(p.onPrimary, 0.85f) else p.textSecondary
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(80)
            setPadding(ctx.dp(18), ctx.dp(14), ctx.dp(18), ctx.dp(14))
            val base = if (primary) Ui.roundRect(ctx, p.primary, Tokens.RADIUS_L)
            else Ui.roundRect(ctx, p.surface, Tokens.RADIUS_L, if (p.isDark) 0x14FFFFFF else p.outline, 1f)
            background = Ui.ripple(ctx, base, oval = false, radiusDp = Tokens.RADIUS_L)
            elevation = ctx.dp(if (primary) Tokens.ELEV_FAB else 0f).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            isClickable = true; isFocusable = true
            val badge = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (primary) Tokens.withAlpha(p.onPrimary, 0.12f) else Tokens.primaryContainer(p))
                }
                addView(icon(ctx, icon, if (primary) p.onPrimary else p.primary, 26))
            }
            addView(badge, lp(ctx.dp(52), ctx.dp(52)).margins(0, 0, ctx.dp(16), 0))
            val col = vertical(ctx)
            col.addView(text(ctx, title, 18f, fg, Tokens.FONT_MEDIUM))
            if (subtitle != null) col.addView(text(ctx, subtitle, Tokens.TEXT_LABEL, sub).apply {
                setPadding(0, ctx.dp(2), 0, 0); setLineSpacing(0f, 1.1f)
            })
            addView(col, lp(0, WRAP, 1f))
            if (trailingChevron) addView(icon(ctx, Icon.CHEVRON_RIGHT, sub), lp(ctx.dp(24), ctx.dp(24)).margins(ctx.dp(8)))
            setOnClickListener { onClick() }
            Ui.pressScale(this, 0.98f)
        }
    }

    /** Чип выбора. */
    fun chip(ctx: Context, s: String, selected: Boolean, onClick: () -> Unit): TextView {
        val p = Ui.colors
        return TextView(ctx).apply {
            text = s
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            minHeight = ctx.dp(44)
            minWidth = ctx.dp(56)
            maxLines = 1
            setPadding(ctx.dp(14), 0, ctx.dp(14), 0)
            isClickable = true; isFocusable = true
            applyChip(this, selected)
            setOnClickListener { onClick() }
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = host.isSelected
                }
            }
            isSelected = selected
            // p используется в applyChip через Ui.colors; ссылка нужна, чтобы палитра читалась при создании
            p.hashCode()
        }
    }

    fun applyChip(v: TextView, selected: Boolean) {
        val ctx = v.context
        val p = Ui.colors
        v.isSelected = selected
        val base = if (selected) Ui.roundRect(ctx, Tokens.primaryContainer(p), Tokens.RADIUS_M, p.primary, 1.5f)
        else Ui.roundRect(ctx, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M, p.outline, 1f)
        v.background = Ui.ripple(ctx, base, oval = false, radiusDp = Tokens.RADIUS_M)
        v.setTextColor(if (selected) p.primary else p.text)
    }

    /** Группа чипов с одиночным выбором (переносится по строкам вручную — через горизонтальный ряд, без переносов). */
    class ChipRow(val ctx: Context, val labels: List<String>, selected: Int, private val onSelect: (Int) -> Unit) {
        val view = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        private val chips = ArrayList<TextView>()
        var selected = selected
            private set

        init {
            labels.forEachIndexed { i, s ->
                val c = chip(ctx, s, i == selected) { select(i); onSelect(i) }
                chips += c
                view.addView(c, lp(WRAP, WRAP).margins(if (i == 0) 0 else ctx.dp(8)))
            }
        }

        fun select(i: Int) { selected = i; chips.forEachIndexed { j, c -> applyChip(c, j == i) } }
    }

    /** Поле ввода с сообщением об ошибке ПОД полем (NFR-6). */
    class Field(val ctx: Context, hint: String, initial: String = "", mono: Boolean = false,
                inputType: Int = InputType.TYPE_CLASS_TEXT, label: String? = null) {
        val edit = EditText(ctx)
        private val err = TextView(ctx)
        val view: LinearLayout = vertical(ctx)
        private var focused = false
        private var hasError = false
        private val p = Ui.colors
        var onSubmit: (() -> Unit)? = null
        var onTextChanged: (() -> Unit)? = null

        init {
            if (label != null) view.addView(text(ctx, label, Tokens.TEXT_LABEL, p.textSecondary, Tokens.FONT_MEDIUM).apply {
                setPadding(ctx.dp(4), 0, 0, ctx.dp(6))
            })
            edit.setText(initial)
            edit.hint = hint
            edit.setTextColor(p.text)
            edit.setHintTextColor(Tokens.withAlpha(p.textSecondary, 0.8f))
            edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_SUBTITLE)
            edit.typeface = if (mono) Tokens.FONT_MONO else Tokens.FONT_REGULAR
            edit.highlightColor = Tokens.withAlpha(p.primary, 0.35f)
            edit.minHeight = ctx.dp(48)
            edit.setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
            edit.isSingleLine = true
            edit.imeOptions = EditorInfo.IME_ACTION_DONE
            edit.inputType = inputType
            edit.textCursorDrawable = GradientDrawable().apply { setColor(p.primary); setSize(ctx.dp(2), ctx.dp(20)) }
            listOfNotNull(edit.textSelectHandle, edit.textSelectHandleLeft, edit.textSelectHandleRight).forEach { it.setTint(p.primary) }
            updateBg()
            edit.setOnFocusChangeListener { _, f -> focused = f; updateBg(); if (!f) onSubmit?.invoke() }
            edit.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_DONE) { onSubmit?.invoke(); edit.clearFocus(); true } else false }
            edit.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { onTextChanged?.invoke() }
            })
            view.addView(edit, lp(MATCH, WRAP))
            err.setTextColor(p.danger)
            err.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
            err.setPadding(ctx.dp(4), ctx.dp(6), ctx.dp(4), 0)
            err.visibility = View.GONE
            err.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            view.addView(err, lp(MATCH, WRAP))
        }

        var text: String
            get() = edit.text.toString()
            set(v) { if (edit.text.toString() != v) edit.setText(v) }

        /** Ошибка под полем: красная обводка + иконка-символ «!» в тексте (не только цвет). */
        fun setError(message: String?) {
            hasError = message != null
            if (message != null) { err.text = "⚠ $message"; err.visibility = View.VISIBLE } else err.visibility = View.GONE
            updateBg()
        }

        fun setEnabledLook(on: Boolean) { edit.isEnabled = on; view.alpha = if (on) 1f else 0.5f }

        private fun updateBg() {
            edit.background = Ui.roundRect(ctx, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M,
                when { hasError -> p.danger; focused -> p.primary; else -> p.outline }, if (focused || hasError) 2f else 1f)
        }
    }

    fun spacer(ctx: Context, h: Int): View = View(ctx).apply { layoutParams = lp(MATCH, ctx.dp(h)) }
}

/** Полоска прогресса (6dp, скруглённая); progress < 0 — неопределённый (бегущий сегмент). */
class ProgressLine(ctx: Context) : View(ctx) {
    private val p = Ui.colors
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.outline }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.primary }
    private val r = RectF()
    private var anim: ValueAnimator? = null
    private var phase = 0f
    var progress = 0f
        set(v) {
            field = if (v < 0f) -1f else v.coerceIn(0f, 1f)
            if (field < 0f) startAnim() else stopAnim()
            invalidate()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(context, 6))
    }

    private fun startAnim() {
        if (anim != null) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { phase = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopAnim() { anim?.cancel(); anim = null }

    override fun onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (progress < 0f) startAnim() }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        r.set(0f, 0f, width.toFloat(), h)
        canvas.drawRoundRect(r, h / 2, h / 2, track)
        if (progress < 0f) {
            val w = width * 0.3f
            val x = -w + (width + w) * phase
            r.set(maxOf(0f, x), 0f, minOf(width.toFloat(), x + w), h)
            if (r.width() > 0f) canvas.drawRoundRect(r, h / 2, h / 2, fill)
        } else if (progress > 0f) {
            r.right = maxOf(h, width * progress)
            canvas.drawRoundRect(r, h / 2, h / 2, fill)
        }
    }
}

/** Крутящаяся дуга (неопределённое ожидание). */
class SpinnerView(ctx: Context) : View(ctx) {
    private val p = Ui.colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = Ui.dp(ctx, 4).toFloat()
        color = p.primary
    }
    private val back = Paint(paint).apply { color = p.outline }
    private val oval = RectF()
    private var anim: ValueAnimator? = null
    private var angle = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = Ui.dp(context, 56)
        setMeasuredDimension(s, s)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { angle = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDetachedFromWindow() { anim?.cancel(); anim = null; super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val m = paint.strokeWidth
        oval.set(m, m, width - m, height - m)
        canvas.drawArc(oval, 0f, 360f, false, back)
        canvas.drawArc(oval, angle, 100f, false, paint)
    }
}
