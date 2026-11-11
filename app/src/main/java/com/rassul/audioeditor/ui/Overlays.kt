package com.rassul.audioeditor.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * Полноэкранное окно поверх активити: своё затемнение (scrim) и панель —
 * либо нижний лист (bottom = true), либо карточка по центру.
 * Анимации появления/ухода свои; закрытие — назад, тап по затемнению, свайп листа вниз.
 */
internal class OverlayDialog(val activity: Activity, private val bottom: Boolean) : Dialog(
    activity,
    if (Ui.colors.isDark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar,
) {
    private val p = Ui.colors
    val scrim = FrameLayout(activity)
    val panel: SheetLayout = SheetLayout(activity, bottom) { dismiss() }
    private var closing = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        scrim.setBackgroundColor(p.scrim)
        scrim.setOnClickListener { if (isCancelableCompat) dismiss() }
        panel.isClickable = true // не пропускать тапы на затемнение
        val lp = FrameLayout.LayoutParams(
            if (bottom) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.gravity = if (bottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        scrim.addView(panel, lp)
        setContentView(scrim, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setDecorFitsSystemWindows(false)
            @Suppress("DEPRECATION")
            run { w.statusBarColor = Color.TRANSPARENT; w.navigationBarColor = Color.TRANSPARENT }
            w.isNavigationBarContrastEnforced = false
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setWindowAnimations(0)
            w.attributes = w.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.decorView
            w.insetsController?.setSystemBarsAppearance(if (p.isDark) 0 else mask, mask)
        }

        val ctx: Context = activity
        scrim.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val bot = maxOf(bars.bottom, ime.bottom)
            if (bottom) {
                scrim.setPadding(bars.left, bars.top + Ui.dp(ctx, 24), bars.right, 0)
                panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, bot + Ui.dp(ctx, 12))
            } else {
                val m = Ui.dp(ctx, 24)
                scrim.setPadding(bars.left + m, bars.top + m, bars.right + m, bot + m)
            }
            insets
        }
        panel.maxWidthPx = Ui.dp(ctx, if (bottom) 640 else 420)
    }

    private val isCancelableCompat: Boolean get() = cancelable
    private var cancelable = true
    override fun setCancelable(flag: Boolean) { super.setCancelable(flag); cancelable = flag; panel.draggable = flag }

    /** Закрыть окно без анимации до уничтожения активити — иначе WindowLeaked (finish() сразу после dismiss()). */
    private val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityPreDestroyed(a: Activity) { if (a === activity) dismissNow() }
        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
        override fun onActivityStarted(a: Activity) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }

    override fun onStop() {
        try { activity.unregisterActivityLifecycleCallbacks(lifecycle) } catch (_: Exception) { }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        try { activity.registerActivityLifecycleCallbacks(lifecycle) } catch (_: Exception) { }
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(Tokens.DUR_STANDARD).start()
        if (bottom) {
            panel.translationY = activity.resources.displayMetrics.heightPixels.toFloat() * 0.6f
            panel.post {
                panel.translationY = panel.height.toFloat().coerceAtLeast(1f)
                panel.animate().translationY(0f).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
            }
        } else {
            panel.alpha = 0f
            panel.scaleX = 0.92f; panel.scaleY = 0.92f
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
        }
    }

    /** Анимированное закрытие. */
    override fun dismiss() {
        if (closing) return
        closing = true
        currentFocus?.let { f ->
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(f.windowToken, 0)
        }
        if (!isShowing || !scrim.isAttachedToWindow || activity.isFinishing || activity.isDestroyed) { safeDismiss(); return }
        scrim.animate().alpha(0f).setDuration(Tokens.DUR_EXIT).start()
        val a = if (bottom) panel.animate().translationY(panel.height.toFloat())
        else panel.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f)
        a.setDuration(Tokens.DUR_EXIT).setInterpolator(Tokens.EASE_EXIT).withEndAction { safeDismiss() }.start()
    }

    /** Закрыть без анимации (например, перед activity.recreate()). */
    fun dismissNow() { closing = true; safeDismiss() }

    private fun safeDismiss() {
        try { super.dismiss() } catch (_: IllegalArgumentException) { }
    }

    /** Показать, если активити ещё жива. */
    fun showSafe(): Dialog {
        if (!activity.isFinishing && !activity.isDestroyed) show()
        return this
    }
}

/**
 * Контейнер панели: ограничение по ширине/высоте, скругление, тень; для листа — «грабер»
 * и закрытие свайпом вниз (когда вложенный список прокручен к началу).
 */
@SuppressLint("ViewConstructor")
internal class SheetLayout(ctx: Context, private val bottom: Boolean, private val onSwipeClose: () -> Unit) : LinearLayout(ctx) {
    var maxWidthPx = Int.MAX_VALUE
    var draggable = bottom
    var scrollChild: View? = null
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null

    init {
        orientation = VERTICAL
        val p = Ui.colors
        val r = Ui.dp(ctx, Tokens.RADIUS_XL).toFloat()
        background = GradientDrawable().apply {
            setColor(p.surfaceHigh)
            cornerRadii = if (bottom) floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) else FloatArray(8) { r }
            if (p.isDark) setStroke(Ui.dp(ctx, 1f).coerceAtLeast(1), 0x14FFFFFF)
        }
        elevation = Ui.dp(ctx, Tokens.ELEV_SHEET).toFloat()
        clipToOutline = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var ws = widthMeasureSpec
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (w > maxWidthPx) ws = MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.EXACTLY)
        var hs = heightMeasureSpec
        if (MeasureSpec.getMode(hs) == MeasureSpec.UNSPECIFIED) {
            hs = MeasureSpec.makeMeasureSpec(resources.displayMetrics.heightPixels, MeasureSpec.AT_MOST)
        }
        super.onMeasure(ws, hs)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!draggable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX
                val atTop = scrollChild?.canScrollVertically(-1) != true
                if (dy > slop && dy > abs(dx) * 1.5f && atTop) { dragging = true; return true }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!draggable) return super.onTouchEvent(ev)
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX; return true }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!dragging && dy > slop) dragging = true
                if (dragging) translationY = maxOf(0f, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    if (translationY > height / 3f || vy > Ui.dp(context, 1000)) onSwipeClose()
                    else animate().translationY(0f).setDuration(Tokens.DUR_STANDARD).setInterpolator(Tokens.EASE_ENTER).start()
                } else if (ev.actionMasked == MotionEvent.ACTION_UP) performClick()
                dragging = false
                vt?.recycle(); vt = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    override fun performClick(): Boolean = super.performClick()
}

internal object Overlays {

    private fun grabber(ctx: Context): View {
        val wrap = FrameLayout(ctx)
        val g = View(ctx)
        g.background = Ui.roundRect(ctx, Tokens.withAlpha(Ui.colors.textSecondary, 0.5f), 2f)
        val lp = FrameLayout.LayoutParams(Ui.dp(ctx, 36), Ui.dp(ctx, 4))
        lp.gravity = Gravity.CENTER
        wrap.addView(g, lp)
        wrap.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        wrap.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 22))
        return wrap
    }

    /** Нижний лист: грабер, заголовок, содержимое в прокрутке. */
    fun sheet(activity: Activity, title: String, content: View): Dialog {
        val d = OverlayDialog(activity, bottom = true)
        val ctx: Context = activity
        d.panel.addView(grabber(ctx))
        if (title.isNotEmpty()) {
            val t = Ui.title(ctx, title)
            t.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 4), Ui.dp(ctx, 24), Ui.dp(ctx, 12))
            t.isAccessibilityHeading = true
            d.panel.addView(t)
        }
        val scroll = ScrollView(ctx)
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        (content.parent as? ViewGroup)?.removeView(content)
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        d.panel.scrollChild = scroll
        return d.showSafe()
    }

    fun listSheet(activity: Activity, title: String, items: List<SheetItem>): Dialog {
        val ctx: Context = activity
        val p = Ui.colors
        val list = LinearLayout(ctx)
        list.orientation = LinearLayout.VERTICAL
        list.setPadding(Ui.dp(ctx, 8), 0, Ui.dp(ctx, 8), Ui.dp(ctx, 4))
        var dialog: Dialog? = null
        for (it in items) {
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.minimumHeight = Ui.dp(ctx, if (it.secondary != null) 60 else 52)
            row.setPadding(Ui.dp(ctx, 16 + 16 * it.level.coerceIn(0, 6)), Ui.dp(ctx, 6), Ui.dp(ctx, 16), Ui.dp(ctx, 6))
            row.background = Ui.ripple(ctx, null, oval = false, radiusDp = Tokens.RADIUS_M)
            if (it.icon != null) {
                val iv = ImageView(ctx)
                iv.setImageDrawable(Ui.icon(ctx, it.icon, if (it.icon == Icon.DELETE) p.danger else p.textSecondary))
                row.addView(iv, LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24)).apply { marginEnd = Ui.dp(ctx, 16) })
            }
            val col = LinearLayout(ctx)
            col.orientation = LinearLayout.VERTICAL
            col.addView(TextView(ctx).apply {
                text = it.text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_SUBTITLE)
                setTextColor(if (it.icon == Icon.DELETE) p.danger else p.text)
                typeface = if (it.level == 0) Tokens.FONT_MEDIUM else Tokens.FONT_REGULAR
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            })
            if (it.secondary != null) col.addView(TextView(ctx).apply {
                text = it.secondary
                setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
                setTextColor(p.textSecondary)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.setOnClickListener { _ -> dialog?.dismiss(); it.onClick() }
            it.onLongClick?.let { lc -> row.setOnLongClickListener { _ -> dialog?.dismiss(); lc(); true } }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (items.isEmpty()) {
            list.addView(Ui.body(ctx, "Пусто", secondary = true).apply {
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
            })
        }
        dialog = sheet(activity, title, list)
        return dialog
    }

    private fun buttonRow(ctx: Context, d: Dialog, buttons: List<Pair<String, () -> Unit>>, primaryLast: Boolean = true): LinearLayout {
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
        buttons.forEachIndexed { i, (text, action) ->
            val primary = primaryLast && i == buttons.lastIndex
            val b = Ui.button(ctx, text, primary) { d.dismiss(); action() }
            row.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(ctx, 8)
            })
        }
        return row
    }

    private fun cardHeader(ctx: Context, d: OverlayDialog, title: String, message: String?) {
        val t = Ui.title(ctx, title)
        t.isAccessibilityHeading = true
        t.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 24), Ui.dp(ctx, 24), 0)
        d.panel.addView(t)
        if (!message.isNullOrEmpty()) {
            val m = Ui.body(ctx, message, secondary = true)
            m.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 10), Ui.dp(ctx, 24), 0)
            d.panel.addView(m)
        }
    }

    fun choice(activity: Activity, title: String, message: String?, buttons: List<Pair<String, () -> Unit>>): Dialog {
        val d = OverlayDialog(activity, bottom = false)
        val ctx: Context = activity
        cardHeader(ctx, d, title, message)
        val bs = buttons.ifEmpty { listOf("OK" to {}) }.take(3)
        val row = buttonRow(ctx, d, bs)
        // Длинные подписи: если кнопки не помещаются в строку — столбиком.
        row.post {
            val need = (0 until row.childCount).sumOf { row.getChildAt(it).measuredWidth + Ui.dp(ctx, 8) }
            if (need > row.width - row.paddingLeft - row.paddingRight) {
                row.orientation = LinearLayout.VERTICAL
                row.gravity = Gravity.END
                for (i in 0 until row.childCount) (row.getChildAt(i).layoutParams as LinearLayout.LayoutParams).apply {
                    marginStart = 0; topMargin = Ui.dp(ctx, 4)
                }
                row.requestLayout()
            }
        }
        d.panel.addView(row.apply { setPadding(paddingLeft, Ui.dp(ctx, 20), paddingRight, paddingBottom) })
        return d.showSafe()
    }

    fun prompt(
        activity: Activity, title: String, hint: String, initial: String,
        inputNumber: Boolean, password: Boolean, multiline: Boolean,
        okText: String, validate: (String) -> String?, onOk: (String) -> Unit,
    ): Dialog {
        val d = OverlayDialog(activity, bottom = false)
        val ctx: Context = activity
        val p = Ui.colors
        cardHeader(ctx, d, title, null)

        val field = EditText(ctx)
        field.setText(initial)
        field.hint = hint
        field.setTextColor(p.text)
        field.setHintTextColor(Tokens.withAlpha(p.textSecondary, 0.8f))
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_SUBTITLE)
        field.highlightColor = Tokens.withAlpha(p.primary, 0.35f)
        field.minHeight = Ui.dp(ctx, 52)
        field.setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
        field.textCursorDrawable = GradientDrawable().apply { setColor(p.primary); setSize(Ui.dp(ctx, 2), Ui.dp(ctx, 20)) }
        listOfNotNull(field.textSelectHandle, field.textSelectHandleLeft, field.textSelectHandleRight).forEach { it.setTint(p.primary) }
        val fieldBg = { focused: Boolean, error: Boolean ->
            Ui.roundRect(ctx, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M,
                when { error -> p.danger; focused -> p.primary; else -> p.outline }, if (focused || error) 2f else 1f)
        }
        var hasError = false
        field.background = fieldBg(false, false)
        field.setOnFocusChangeListener { _, f -> field.background = fieldBg(f, hasError) }
        field.inputType = when {
            inputNumber -> InputType.TYPE_CLASS_NUMBER
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multiline) { field.minLines = 3; field.maxLines = 8; field.gravity = Gravity.TOP or Gravity.START }
        else { field.isSingleLine = true; field.imeOptions = EditorInfo.IME_ACTION_DONE }
        field.setSelection(field.text.length)

        val err = TextView(ctx)
        err.setTextColor(p.danger)
        err.setTextSize(TypedValue.COMPLEX_UNIT_SP, Tokens.TEXT_LABEL)
        err.setPadding(Ui.dp(ctx, 4), Ui.dp(ctx, 6), Ui.dp(ctx, 4), 0)
        err.visibility = View.GONE
        err.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE

        val box = LinearLayout(ctx)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 16), Ui.dp(ctx, 24), 0)
        box.addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(err)
        d.panel.addView(box)

        fun submit() {
            val text = field.text.toString()
            val e = try { validate(text) } catch (_: Exception) { "Не удалось проверить значение" }
            if (e != null) {
                hasError = true
                err.text = e
                err.visibility = View.VISIBLE
                field.background = fieldBg(field.hasFocus(), true)
                // лёгкое «покачивание» поля
                field.animate().cancel()
                field.translationX = 0f
                val dx = Ui.dp(ctx, 6).toFloat()
                field.animate().translationX(dx).setDuration(50).withEndAction {
                    field.animate().translationX(-dx).setDuration(70).withEndAction {
                        field.animate().translationX(0f).setDuration(60).start()
                    }.start()
                }.start()
                return
            }
            d.dismiss()
            onOk(text)
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (hasError) { hasError = false; err.visibility = View.GONE; field.background = fieldBg(field.hasFocus(), false) }
            }
        })
        field.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        row.setPadding(Ui.dp(ctx, 12), Ui.dp(ctx, 20), Ui.dp(ctx, 16), Ui.dp(ctx, 16))
        row.addView(Ui.button(ctx, "Отмена", primary = false) { d.dismiss() })
        row.addView(Ui.button(ctx, okText, primary = true) { submit() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = Ui.dp(ctx, 8) })
        d.panel.addView(row)

        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        field.requestFocus()
        d.showSafe()
        field.post { field.windowInsetsController?.show(WindowInsets.Type.ime()) }
        return d
    }
}
