package com.rassul.audioeditor.ui

import android.app.Activity
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import com.rassul.audioeditor.editor.AppController
import com.rassul.audioeditor.editor.Screen

/** Что экраны берут у MainActivity. */
internal interface Host {
    val activity: Activity
    val controller: AppController
    /** Выполнить сохранение; перед первым — запросить уведомления с пояснением (FR-29). */
    fun beforeSave(action: () -> Unit)
    fun toast(msg: String)
    fun showSaved()
    fun showSettings()
    fun showSelfTest()
    fun back()
}

/** Экран-View. update(s) — обновить на месте (true) или попросить пересоздать (false). */
internal abstract class ScreenView(val host: Host) {
    abstract val root: View
    open fun update(s: Screen): Boolean = false
    /** Состояние контроллера изменилось (для экранов, зависящих от jobs/editor). */
    open fun refresh() {}
    open fun onPause() {}
    /** Нажатие «назад». true — обработано. */
    open fun onBack(): Boolean = false
    val ctx: Context get() = host.activity
}

internal object Scaffold {
    const val MAX_CONTENT_DP = 560

    /** Страница: верхняя панель + содержимое. */
    fun page(host: Host, title: String, subtitle: String?, onBack: (() -> Unit)?, actions: List<View>, body: View): LinearLayout {
        val ctx = host.activity
        val col = Kit.vertical(ctx)
        col.addView(Ui.topBar(ctx, title, subtitle, onBack, actions).view, lp(Kit.MATCH, Kit.WRAP))
        col.addView(body, lp(Kit.MATCH, 0, 1f))
        return col
    }

    /** Прокручиваемая колонка не шире MAX_CONTENT_DP, по центру. */
    fun scrollColumn(ctx: Context, column: LinearLayout, maxDp: Int = MAX_CONTENT_DP): ScrollView {
        val sv = ScrollView(ctx)
        sv.isVerticalScrollBarEnabled = false
        sv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val fl = FrameLayout(ctx)
        val maxPx = ctx.dp(maxDp)
        val w = if (ctx.resources.displayMetrics.widthPixels > maxPx) maxPx else ViewGroup.LayoutParams.MATCH_PARENT
        fl.addView(column, FrameLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        sv.addView(fl, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return sv
    }

    fun padded(col: LinearLayout, ctx: Context, h: Int = 16, top: Int = 8, bottom: Int = 24): LinearLayout {
        col.setPadding(ctx.dp(h), ctx.dp(top), ctx.dp(h), ctx.dp(bottom))
        return col
    }

    /** Тело листа: поля 24dp. */
    fun sheetBody(ctx: Context): LinearLayout = Kit.vertical(ctx).apply { setPadding(ctx.dp(24), ctx.dp(4), ctx.dp(24), ctx.dp(8)) }

    /** Кнопка во всю ширину. */
    fun wideButton(ctx: Context, text: String, primary: Boolean = true, onClick: () -> Unit): View =
        Ui.button(ctx, text, primary) { onClick() }.apply {
            layoutParams = lp(Kit.MATCH, ctx.dp(52)).margins(0, ctx.dp(16), 0, 0)
        }
}
