package com.rassul.audioeditor.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Переключатель (вкл/выкл) в цветах палитры: дорожка 52×32dp, «бегунок» с анимацией. */
class SwitchView(ctx: Context) : View(ctx) {
    var onChange: ((Boolean) -> Unit)? = null
    var isChecked = false
        private set

    private val p = Ui.colors
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dp(ctx, 2).toFloat() }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val check = IconDrawable(Icon.CHECK, p.primary, Ui.dp(ctx, 16))
    private val tw = Ui.dp(ctx, 52).toFloat()
    private val th = Ui.dp(ctx, 32).toFloat()
    private val rect = RectF()
    private var f = 0f
    private var anim: ValueAnimator? = null
    private val argb = ArgbEvaluator()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
        minimumWidth = Ui.dp(ctx, 52)
        minimumHeight = Ui.dp(ctx, 48)
    }

    fun toggle() {
        setChecked(!isChecked, animate = true)
        onChange?.invoke(isChecked)
    }

    fun setChecked(v: Boolean, animate: Boolean) {
        isChecked = v
        stateDescription = if (v) "Включено" else "Выключено"
        anim?.cancel()
        val to = if (v) 1f else 0f
        if (!animate) { f = to; invalidate(); return }
        anim = ValueAnimator.ofFloat(f, to).apply {
            duration = Tokens.DUR_STANDARD
            interpolator = Tokens.EASE_STANDARD
            addUpdateListener { f = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(minimumWidth, widthMeasureSpec), resolveSize(minimumHeight, heightMeasureSpec))
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Switch::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isChecked
    }

    override fun onDraw(canvas: Canvas) {
        val left = (width - tw) / 2f
        val top = (height - th) / 2f
        rect.set(left, top, left + tw, top + th)
        val r = th / 2f
        val offTrack = if (p.isDark) p.bg else Tokens.blend(p.text, p.surfaceHigh, 0.08f)
        track.color = argb.evaluate(f, offTrack, p.primary) as Int
        canvas.drawRoundRect(rect, r, r, track)
        if (f < 1f) {
            trackEdge.color = Tokens.withAlpha(p.textSecondary, 1f - f)
            val inset = trackEdge.strokeWidth / 2
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, r - inset, r - inset, trackEdge)
        }
        // бегунок: 16dp выкл → 24dp вкл
        val d = Ui.dp(context, 1).toFloat()
        val rad = (8f + 4f * f) * d
        val cxOff = left + r
        val cxOn = left + tw - r
        val cx = cxOff + (cxOn - cxOff) * f
        val cy = top + r
        thumb.color = argb.evaluate(f, p.textSecondary, p.onPrimary) as Int
        canvas.drawCircle(cx, cy, rad, thumb)
        if (f > 0.5f) {
            val s = (check.intrinsicWidth * (f - 0.5f) * 2).toInt()
            check.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            check.draw(canvas)
        }
    }
}

/** Свой «snackbar»: всплывает снизу поверх содержимого активити. */
internal object Snackbar {
    private const val TAG = "audioeditor.snackbar"

    fun show(activity: Activity, message: String, actionText: String?, onAction: (() -> Unit)?) {
        if (activity.isFinishing || activity.isDestroyed) return
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG)?.let { old -> hide(content, old) }

        val ctx: Context = activity
        val p = Ui.colors
        // Инверсная поверхность — заметна на любом фоне.
        val bgColor = if (p.isDark) 0xFFE6E8F0.toInt() else 0xFF2A2E38.toInt()
        val fg = if (p.isDark) 0xFF161922.toInt() else 0xFFECEEF4.toInt()
        val action = if (p.isDark) Tokens.LIGHT.primary else Tokens.DARK.primary

        val bar = LinearLayout(ctx)
        bar.tag = TAG
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.minimumHeight = Ui.dp(ctx, 52)
        bar.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 6), Ui.dp(ctx, if (actionText != null) 6 else 18), Ui.dp(ctx, 6))
        bar.background = Ui.roundRect(ctx, bgColor, 16f)
        bar.elevation = Ui.dp(ctx, Tokens.ELEV_PANEL).toFloat()
        bar.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val msg = TextView(ctx).apply {
            text = message
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setLineSpacing(0f, 1.15f)
            maxLines = 3
        }
        bar.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (actionText != null) {
            val btn = TextView(ctx).apply {
                text = actionText
                setTextColor(action)
                typeface = Tokens.FONT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                minHeight = Ui.dp(ctx, 48)
                setPadding(Ui.dp(ctx, 14), 0, Ui.dp(ctx, 14), 0)
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Tokens.withAlpha(action, 0.25f)), null,
                    Ui.roundRect(ctx, -1, 12f),
                )
                setOnClickListener {
                    onAction?.invoke()
                    hide(content, bar)
                }
            }
            bar.addView(btn)
        }

        val insets = content.rootWindowInsets
        val bottomInset = insets?.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())?.bottom ?: 0
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        val side = Ui.dp(ctx, 16)
        lp.setMargins(side, 0, side, bottomInset + Ui.dp(ctx, 16))
        val maxW = Ui.dp(ctx, 560)
        if (ctx.resources.displayMetrics.widthPixels - 2 * side > maxW) lp.width = maxW
        content.addView(bar, lp)

        bar.alpha = 0f
        bar.translationY = Ui.dp(ctx, 24).toFloat()
        bar.animate().alpha(1f).translationY(0f).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
        bar.postDelayed({ hide(content, bar) }, if (actionText != null) Tokens.DUR_SNACK_ACTION else Tokens.DUR_SNACK_SHOW)
    }

    private fun hide(parent: ViewGroup, v: View) {
        if (v.tag != TAG) return
        v.tag = null
        v.animate().cancel()
        v.animate().alpha(0f).translationY(Ui.dp(v.context, 16).toFloat()).setDuration(Tokens.DUR_EXIT)
            .setInterpolator(Tokens.EASE_EXIT).withEndAction { parent.removeView(v) }.start()
    }
}
