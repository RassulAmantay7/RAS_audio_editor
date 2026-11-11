package com.rassul.audioeditor.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/*
 * КОНТРАКТ UI-кита (владелец реализации — агент «ui-designer»).
 * Сигнатуры ниже используют другие агенты — НЕ МЕНЯТЬ (можно добавлять новое).
 * Без XML-разметки и без R.*: всё строится в коде. Без androidx.
 * Токены (цвета, отступы, радиусы, длительности) — в Tokens.kt и docs/design.md.
 */

/** Палитра. Значения ARGB. */
data class Palette(
    val bg: Int,             // фон экрана
    val surface: Int,        // карточки, панели
    val surfaceHigh: Int,    // приподнятые панели, диалоги
    val primary: Int,        // акцент
    val onPrimary: Int,
    val text: Int,
    val textSecondary: Int,
    val outline: Int,
    val danger: Int,
    val warning: Int,        // предупреждения, постоянные notices (всегда с иконкой и текстом)
    val waveform: Int,       // волна
    val waveBg: Int,         // подложка волны
    val overload: Int,       // перегрузка |x| > 1 на волне (+ маркер и подпись, не только цвет)
    val scrim: Int,          // затемнение под диалогами
    val isDark: Boolean,
)

enum class Icon {
    BACK, CLOSE, MORE, SETTINGS, CHECK, CHEVRON_RIGHT, CHEVRON_DOWN, CHEVRON_UP,
    VIDEO, FOLDER, LIBRARY, PLAY, PAUSE, LOOP, SKIP_START, UNDO, REDO, ZOOM_IN, ZOOM_OUT, FIT_ALL,
    KEEP, VOLUME, FADE_IN, FADE_OUT, NORMALIZE, BOLT, EXPORT, SHARE, OPEN_EDITOR, DELETE,
    INFO, WARNING, ERROR, AUDIO_FILE, COPY, STETHOSCOPE,
}

object Ui {
    @Volatile
    private var current: Palette = Tokens.DARK

    /** Текущая палитра (зависит от настройки тёмной темы). */
    val colors: Palette get() = current

    /** Перечитать настройку темы (вызывать в onCreate активити до построения экрана). */
    fun applyTheme(activity: Activity, dark: Boolean = true) {
        current = if (dark) Tokens.DARK else Tokens.LIGHT
        val p = current
        val w = activity.window
        w.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        run {
            w.statusBarColor = Color.TRANSPARENT
            w.navigationBarColor = Color.TRANSPARENT
        }
        w.isNavigationBarContrastEnforced = false
        w.isStatusBarContrastEnforced = false
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        w.decorView // гарантирует создание decorView до обращения к insetsController
        w.insetsController?.setSystemBarsAppearance(if (p.isDark) 0 else mask, mask)
    }

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).let {
            if (it >= 0) (it + 0.5f).toInt() else (it - 0.5f).toInt()
        }
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun icon(ctx: Context, icon: Icon, tint: Int = colors.text): Drawable = IconDrawable(icon, tint, dp(ctx, 24))

    /** Круглая кнопка-иконка 48dp с рябью. contentDescription = desc. */
    fun iconButton(ctx: Context, icon: Icon, desc: String, onClick: (View) -> Unit): View {
        val v = ImageView(ctx)
        val s = dp(ctx, 48)
        v.layoutParams = LinearLayout.LayoutParams(s, s)
        v.scaleType = ImageView.ScaleType.CENTER
        v.setImageDrawable(icon(ctx, icon))
        v.contentDescription = desc
        v.tooltipText = desc
        v.background = ripple(ctx, null, oval = true)
        v.isClickable = true
        v.isFocusable = true
        v.setOnClickListener { onClick(it) }
        return v
    }

    /** Переключаемая кнопка-иконка (для инструментов). */
    fun toggleIconButton(ctx: Context, icon: Icon, desc: String, onToggle: (Boolean) -> Unit): ToggleIcon =
        ToggleIconImpl(ctx, icon, desc, onToggle)

    /** Кнопка с текстом. primary=true — заливка акцентом, иначе текстовая. */
    fun button(ctx: Context, text: String, primary: Boolean = true, onClick: (View) -> Unit): TextView {
        val p = colors
        val b = TextView(ctx)
        b.text = text
        b.typeface = Tokens.FONT_MEDIUM
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_BODY)
        b.letterSpacing = 0.01f
        b.gravity = Gravity.CENTER
        b.minHeight = dp(ctx, 48)
        b.minWidth = dp(ctx, 64)
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.setPadding(dp(ctx, if (primary) 24 else 16), 0, dp(ctx, if (primary) 24 else 16), 0)
        if (primary) {
            b.setTextColor(p.onPrimary)
            b.background = ripple(ctx, roundRect(ctx, p.primary, Tokens.RADIUS_FULL), oval = false, radiusDp = Tokens.RADIUS_FULL)
            b.elevation = dp(ctx, 1).toFloat()
            b.outlineProvider = ViewOutlineProvider.BACKGROUND
        } else {
            b.setTextColor(p.primary)
            b.background = ripple(ctx, null, oval = false, radiusDp = Tokens.RADIUS_FULL)
        }
        b.isClickable = true
        b.isFocusable = true
        b.setOnClickListener { onClick(it) }
        b.stateListAnimator = null
        pressScale(b)
        return b
    }

    fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Tokens.FONT_MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_TITLE)
        setTextColor(colors.text)
        setLineSpacing(0f, 1.1f)
    }

    fun body(ctx: Context, text: String, secondary: Boolean = false): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Tokens.FONT_REGULAR
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_BODY)
        setTextColor(if (secondary) colors.textSecondary else colors.text)
        setLineSpacing(0f, 1.2f)
    }

    /** Подпись-метка (13sp, вторичный цвет). */
    fun label(ctx: Context, text: String, color: Int = colors.textSecondary): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Tokens.FONT_MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
        setTextColor(color)
    }

    /** Верхняя панель: кнопка назад (если onBack != null), заголовок/подзаголовок, справа actions. */
    fun topBar(ctx: Context, title: String, subtitle: String? = null, onBack: (() -> Unit)?, actions: List<View>): TopBar =
        TopBarImpl(ctx, title, subtitle, onBack, actions)

    /** Нижний лист (bottom sheet) с заголовком и произвольным содержимым. Возвращает показанный диалог. */
    fun showSheet(activity: Activity, title: String, content: View): Dialog =
        Overlays.sheet(activity, title, content)

    /** Список пунктов в нижнем листе. */
    fun showListSheet(activity: Activity, title: String, items: List<SheetItem>): Dialog =
        Overlays.listSheet(activity, title, items)

    /** Диалог-вопрос с 1–3 кнопками (последняя — основная). */
    fun showChoice(activity: Activity, title: String, message: String?, buttons: List<Pair<String, () -> Unit>>): Dialog =
        Overlays.choice(activity, title, message, buttons)

    /** Диалог ввода строки. inputNumber=true — цифровая клавиатура. error(text) -> текст ошибки под полем или null (диалог не закрывается). */
    fun showPrompt(
        activity: Activity, title: String, hint: String, initial: String = "",
        inputNumber: Boolean = false, password: Boolean = false, multiline: Boolean = false,
        okText: String = "OK", validate: (String) -> String? = { null }, onOk: (String) -> Unit,
    ): Dialog = Overlays.prompt(activity, title, hint, initial, inputNumber, password, multiline, okText, validate, onOk)

    /** Короткое всплывающее сообщение внизу экрана (свой «snackbar»). */
    fun toast(activity: Activity, message: String) = Snackbar.show(activity, message, null, null)

    /** «Snackbar» с действием (например, «Отменить»). */
    fun toast(activity: Activity, message: String, actionText: String, onAction: () -> Unit) =
        Snackbar.show(activity, message, actionText, onAction)

    /**
     * Плавное появление/скрытие (для панелей ридера). Скрытая панель получает INVISIBLE
     * (не сдвигает раскладку). slideFromTop: true — уезжает вверх, false — вниз, null — только прозрачность.
     */
    fun fade(view: View, show: Boolean, slideFromTop: Boolean? = null) {
        val dist = when (slideFromTop) {
            true -> -dp(view.context, 24).toFloat()
            false -> dp(view.context, 24).toFloat()
            null -> 0f
        }
        view.animate().cancel()
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.translationY = dist
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).translationY(0f).setDuration(Tokens.DUR_STANDARD)
                .setInterpolator(Tokens.EASE_ENTER).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().alpha(0f).translationY(dist).setDuration(Tokens.DUR_EXIT)
                .setInterpolator(Tokens.EASE_STANDARD)
                .withEndAction { view.visibility = View.INVISIBLE; view.translationY = 0f }.start()
        }
    }

    /**
     * Скруглённый фон-панель с тенью (для плавающих панелей). Тень даёт elevation (8dp) —
     * drawable сам выставит её View-хозяину при первой раскладке, если elevation не задан.
     */
    fun panelBackground(ctx: Context, radiusDp: Float = 20f, color: Int = colors.surfaceHigh): Drawable =
        PanelDrawable(ctx, radiusDp, color)

    // ---------------------------------------------------------------- дополнительно (не контракт)

    /** Включены ли анимации в системе (настройка «Убрать анимации»). */
    fun animationsEnabled(): Boolean = ValueAnimator.areAnimatorsEnabled()

    /** Скруглённый прямоугольник-заливка. */
    fun roundRect(ctx: Context, color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0f) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    /** Рябь поверх content (null — прозрачная, маска по форме). */
    fun ripple(ctx: Context, content: Drawable?, oval: Boolean, radiusDp: Float = Tokens.RADIUS_M): RippleDrawable {
        val mask = if (oval) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        else roundRect(ctx, Color.WHITE, radiusDp)
        val rd = RippleDrawable(ColorStateList.valueOf(Tokens.ripple(colors)), content, mask)
        if (oval) rd.radius = dp(ctx, 24)
        return rd
    }

    /** Лёгкое «вдавливание» при нажатии. */
    fun pressScale(v: View, scale: Float = 0.97f) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(scale).scaleY(scale).setDuration(Tokens.DUR_FAST).setInterpolator(Tokens.EASE_STANDARD).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(Tokens.DUR_STANDARD).setInterpolator(Tokens.EASE_ENTER).start()
            }
            false
        }
    }

    /** Переключатель в цветах палитры. */
    fun switchView(ctx: Context, checked: Boolean, onChange: (Boolean) -> Unit): SwitchView =
        SwitchView(ctx).also { it.setChecked(checked, animate = false); it.onChange = onChange }

    /** Строка настроек: заголовок, пояснение, переключатель справа; нажатие по всей строке. */
    fun switchRow(ctx: Context, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = settingRow(ctx, title, subtitle)
        val sw = switchView(ctx, checked, onChange)
        sw.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        row.addView(sw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(ctx, 12)
        })
        row.setOnClickListener { sw.toggle() }
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = sw.isChecked
            }
        }
        return row
    }

    /** Строка настроек без управляющего элемента (для своих). */
    fun settingRow(ctx: Context, title: String, subtitle: String?, titleColor: Int = colors.text): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(ctx, 56)
        row.setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 16), dp(ctx, 10))
        row.background = ripple(ctx, null, oval = false, radiusDp = Tokens.RADIUS_L)
        row.isClickable = true
        row.isFocusable = true
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.addView(TextView(ctx).apply {
            text = title
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_SUBTITLE)
            setTextColor(titleColor)
        })
        if (subtitle != null) col.addView(TextView(ctx).apply {
            text = subtitle
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
            setTextColor(colors.textSecondary)
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Заголовок раздела (в листах и списках). */
    fun sectionHeader(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        typeface = Tokens.FONT_MEDIUM
        setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
        letterSpacing = 0.04f
        setTextColor(colors.primary)
        setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 6))
    }

    /** Сегментированный переключатель (2–3 варианта). */
    fun segmented(ctx: Context, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View =
        Segmented(ctx, options, selected, onSelect)
}

data class SheetItem(
    val text: String,
    val icon: Icon? = null,
    val secondary: String? = null,
    val level: Int = 0,            // отступ (для оглавления)
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

/** Верхняя панель. */
interface TopBar {
    val view: View
    fun setTitle(title: String, subtitle: String?)
}

/** Переключаемая иконка. */
interface ToggleIcon {
    val view: View
    var checked: Boolean
}

/**
 * Корневой контейнер экрана с учётом вырезов/системных панелей (edge-to-edge).
 * Создаёт вертикальный LinearLayout с фоном палитры, отступами под системные панели,
 * вырез и клавиатуру, и СРАЗУ ставит его как contentView активити
 * (повторный setContentView(root) безопасен).
 */
fun Activity.screenRoot(): LinearLayout {
    val root = LinearLayout(this)
    root.orientation = LinearLayout.VERTICAL
    root.setBackgroundColor(Ui.colors.bg)
    root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    root.setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val ime = insets.getInsets(WindowInsets.Type.ime())
        v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        insets
    }
    setContentView(root)
    root.requestApplyInsets()
    return root
}

// ==================================================================== реализации

private class TopBarImpl(
    ctx: Context, title: String, subtitle: String?, onBack: (() -> Unit)?, actions: List<View>,
) : TopBar {
    private val titleView: TextView
    private val subView: TextView
    override val view: View

    init {
        val p = Ui.colors
        val bar = LinearLayout(ctx)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = Ui.dp(ctx, 64)
        bar.setPadding(Ui.dp(ctx, 4), 0, Ui.dp(ctx, 4), 0)
        if (onBack != null) bar.addView(Ui.iconButton(ctx, Icon.BACK, "Назад") { onBack() })
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(Ui.dp(ctx, if (onBack != null) 4 else 16), 0, Ui.dp(ctx, 8), 0)
        titleView = TextView(ctx).apply {
            typeface = Tokens.FONT_MEDIUM
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(p.text)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subView = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
            setTextColor(p.textSecondary)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        col.addView(titleView)
        col.addView(subView)
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (a in actions) bar.addView(a)
        view = bar
        setTitle(title, subtitle)
    }

    override fun setTitle(title: String, subtitle: String?) {
        titleView.text = title
        subView.text = subtitle ?: ""
        subView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }
}

private class ToggleIconImpl(
    ctx: Context, icon: Icon, private val desc: String, private val onToggle: (Boolean) -> Unit,
) : ToggleIcon {
    private val iv = ImageView(ctx)
    private val iconD = IconDrawable(icon, Ui.colors.text, Ui.dp(ctx, 24))
    private val bg = ToggleBgDrawable(ctx)
    private var anim: ValueAnimator? = null
    private var value = false
    override val view: View get() = iv

    init {
        val s = Ui.dp(ctx, 48)
        iv.layoutParams = LinearLayout.LayoutParams(s, s)
        iv.scaleType = ImageView.ScaleType.CENTER
        iv.setImageDrawable(iconD)
        iv.background = bg
        iv.foreground = Ui.ripple(ctx, null, oval = false, radiusDp = 14f)
        iv.contentDescription = desc
        iv.tooltipText = desc
        iv.isClickable = true
        iv.isFocusable = true
        iv.setOnClickListener {
            setValue(!value, true)
            iv.animate().scaleX(0.88f).scaleY(0.88f).setDuration(Tokens.DUR_FAST).withEndAction {
                iv.animate().scaleX(1f).scaleY(1f).setDuration(Tokens.DUR_STANDARD).setInterpolator(Tokens.EASE_ENTER).start()
            }.start()
            onToggle(value)
        }
        iv.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.ToggleButton::class.java.name
                info.isCheckable = true
                info.isChecked = value
            }
        }
        apply(0f)
    }

    override var checked: Boolean
        get() = value
        set(v) = setValue(v, iv.isAttachedToWindow)

    private fun setValue(v: Boolean, animate: Boolean) {
        if (v == value && anim == null) { apply(if (v) 1f else 0f); return }
        value = v
        iv.isSelected = v
        iv.stateDescription = if (v) "Включено" else "Выключено"
        anim?.cancel()
        val from = bg.fraction
        val to = if (v) 1f else 0f
        if (!animate) { apply(to); return }
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = Tokens.DUR_STANDARD
            interpolator = Tokens.EASE_STANDARD
            addUpdateListener { apply(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { anim = null }
            })
            start()
        }
    }

    private fun apply(f: Float) {
        bg.fraction = f
        val p = Ui.colors
        iconD.color = ArgbEvaluator().evaluate(f, p.text, p.primary) as Int
    }
}

/** Подложка выбранной иконки-инструмента: скруглённый квадрат, «вырастает» при включении. */
private class ToggleBgDrawable(ctx: Context) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Tokens.primaryContainer(Ui.colors) }
    private val r = Ui.dp(ctx, 14).toFloat()
    private val inset = Ui.dp(ctx, 3).toFloat()
    private val rect = RectF()
    var fraction = 0f
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        if (fraction <= 0f) return
        val b = bounds
        val s = 0.6f + 0.4f * fraction
        val w = (b.width() - 2 * inset) * s
        val h = (b.height() - 2 * inset) * s
        rect.set(b.exactCenterX() - w / 2, b.exactCenterY() - h / 2, b.exactCenterX() + w / 2, b.exactCenterY() + h / 2)
        paint.alpha = (255 * fraction).toInt()
        canvas.drawRoundRect(rect, r * s, r * s, paint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Фон плавающей панели: скругление, тонкая обводка в тёмной теме, тень через elevation хозяина. */
private class PanelDrawable(ctx: Context, radiusDp: Float, color: Int) : GradientDrawable() {
    private val elev = Ui.dp(ctx, Tokens.ELEV_PANEL).toFloat()

    init {
        setColor(color)
        cornerRadius = Ui.dp(ctx, radiusDp).toFloat()
        if (Ui.colors.isDark) setStroke(Ui.dp(ctx, 1f).coerceAtLeast(1), 0x14FFFFFF)
    }

    override fun onBoundsChange(r: android.graphics.Rect) {
        super.onBoundsChange(r)
        val host = callback as? View ?: return
        if (host.elevation == 0f && host.background === this) {
            host.outlineProvider = ViewOutlineProvider.BACKGROUND
            host.elevation = elev
        }
    }
}

/** Сегментированный переключатель с «плавающей» подложкой. */
private class Segmented(
    ctx: Context, options: List<String>, private var selected: Int, private val onSelect: (Int) -> Unit,
) : FrameLayout(ctx) {
    private val indicator = View(ctx)
    private val row = LinearLayout(ctx)
    private val labels = ArrayList<TextView>()
    private var animating = false

    init {
        val p = Ui.colors
        val pad = Ui.dp(ctx, 4)
        setPadding(pad, pad, pad, pad)
        background = Ui.roundRect(ctx, if (p.isDark) p.bg else Tokens.blend(p.text, p.surfaceHigh, 0.06f), Tokens.RADIUS_FULL)
        indicator.background = Ui.roundRect(ctx, Tokens.primaryContainer(p), Tokens.RADIUS_FULL)
        addView(indicator, LayoutParams(0, LayoutParams.MATCH_PARENT))
        row.orientation = LinearLayout.HORIZONTAL
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(ctx, 44)))
        options.forEachIndexed { i, s ->
            val t = TextView(ctx)
            t.text = s
            t.gravity = Gravity.CENTER
            t.typeface = Tokens.FONT_MEDIUM
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            t.maxLines = 1
            t.ellipsize = TextUtils.TruncateAt.END
            t.background = Ui.ripple(ctx, null, oval = false, radiusDp = Tokens.RADIUS_FULL)
            t.isClickable = true
            t.isFocusable = true
            t.setOnClickListener { select(i, true); onSelect(i) }
            t.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = android.widget.RadioButton::class.java.name
                    info.isCheckable = true
                    info.isChecked = i == selected
                }
            }
            labels += t
            row.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        }
        select(selected, false)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val n = labels.size.coerceAtLeast(1)
        val w = (width - paddingLeft - paddingRight) / n
        indicator.layout(paddingLeft, paddingTop, paddingLeft + w, height - paddingBottom)
        if (!animating) indicator.translationX = (w * selected).toFloat()
    }

    private fun select(i: Int, animate: Boolean) {
        selected = i
        val p = Ui.colors
        labels.forEachIndexed { j, t -> t.setTextColor(if (j == i) p.primary else p.textSecondary) }
        val n = labels.size.coerceAtLeast(1)
        val w = (width - paddingLeft - paddingRight) / n
        if (animate && w > 0) {
            animating = true
            indicator.animate().translationX((w * i).toFloat()).setDuration(Tokens.DUR_STANDARD)
                .setInterpolator(Tokens.EASE_STANDARD).withEndAction { animating = false }.start()
        }
        else indicator.translationX = (w * i).toFloat()
    }
}
