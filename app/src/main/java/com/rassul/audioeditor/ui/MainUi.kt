package com.rassul.audioeditor.ui

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.rassul.audioeditor.editor.AppController
import com.rassul.audioeditor.editor.Screen

/** Application реализует это, когда USE_FAKE = false: отдаёт настоящий контроллер (создаётся в интеграции T20). */
interface AppControllerHost {
    val appController: AppController
}

/**
 * Навигация между экранами-View: экран контроллера (AppController.screen) + стек вложенных экранов
 * («Сохранённые», «Настройки», «Самопроверка»), которые живут только поверх Главной.
 */
internal class MainUi(private val host: Host, private val container: FrameLayout, private val jobs: JobsPanel) {
    private val sub = ArrayList<String>()
    private var current: ScreenView? = null
    private var currentKey: Any? = null
    private var currentData: Screen? = null

    private val controller: AppController get() = host.controller

    fun push(name: String) { sub.add(name); render() }
    fun pop() { if (sub.isNotEmpty()) sub.removeAt(sub.size - 1); render() }
    fun clearSub() { sub.clear() }

    /** true — «назад» обработано внутри приложения. */
    fun handleBack(): Boolean {
        if (sub.isNotEmpty() && controller.screen is Screen.Home) { pop(); return true }
        if (current?.onBack() == true) return true
        return when (controller.screen) {
            is Screen.Home -> false
            else -> { controller.goHome(); true }
        }
    }

    fun onPause() { current?.onPause() }
    fun onStateChanged() { render(); jobs.refresh() }
    fun rebuild() { currentKey = null; render() }
    fun dispose() { (current as? SelfTestScreen)?.dispose(); current = null }

    fun render() {
        val s = controller.screen
        if (s !is Screen.Home && sub.isNotEmpty()) sub.clear()
        val key: Any = if (sub.isNotEmpty()) sub.last() else s::class
        val cur = current
        if (cur != null && key == currentKey) {
            if (sub.isEmpty()) {
                if (s == currentData) { cur.refresh(); jobs.refresh(); return }
                if (cur.update(s)) { currentData = s; cur.refresh(); jobs.refresh(); return }
                if (s is Screen.Home) { cur.refresh(); jobs.refresh(); return }
            } else { cur.refresh(); jobs.refresh(); return }
        }
        val view = create(s) ?: return
        val old = cur
        (old as? SelfTestScreen)?.dispose()
        current = view
        currentKey = key
        currentData = s
        container.addView(view.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (old != null) {
            val ov = old.root
            ov.animate().cancel()
            ov.animate().alpha(0f).setDuration(Tokens.DUR_EXIT).withEndAction { container.removeView(ov) }.start()
            view.root.alpha = 0f
            view.root.translationY = host.activity.dp(16).toFloat()
            view.root.animate().alpha(1f).translationY(0f).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
        }
        jobs.refresh()
    }

    private fun create(s: Screen): ScreenView? {
        if (sub.isNotEmpty()) return when (sub.last()) {
            "saved" -> SavedScreen(host)
            "settings" -> SettingsScreen(host)
            else -> SelfTestScreen(host)
        }
        return when (s) {
            is Screen.Home -> HomeScreen(host)
            is Screen.Probing -> ProbingScreen(host, s)
            is Screen.Extract -> ExtractScreen(host, s)
            is Screen.Decoding -> DecodingScreen(host, s)
            is Screen.Editor -> controller.editor?.let { EditorScreen(host, it) }
            is Screen.Error -> ErrorScreen(host, s)
        }
    }
}
