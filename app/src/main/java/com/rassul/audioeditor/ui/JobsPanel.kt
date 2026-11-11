package com.rassul.audioeditor.ui

import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import com.rassul.audioeditor.editor.SaveJob
import com.rassul.audioeditor.editor.SaveJobState
import com.rassul.audioeditor.editor.SavedFile

/**
 * Панель фоновых задач сохранения (FR-29, FR-33): прогресс, «Отменить», «Пропустить проверку»,
 * результат с «Поделиться» / «Открыть в редакторе». Закрытые крестиком задачи скрываются только в UI.
 * Состояние показывается и текстом, и иконкой — не одним цветом.
 */
internal class JobsPanel(private val host: Host) {
    val view = Kit.vertical(host.activity)
    private val dismissed = HashSet<Long>()
    private val shownKeys = HashMap<Long, String>()

    init {
        view.setPadding(host.activity.dp(12), 0, host.activity.dp(12), host.activity.dp(8))
        view.visibility = View.GONE
    }

    fun refresh() {
        val c = host.activity
        val all = host.controller.jobs.filter { it.id !in dismissed }
        // последние три, новые сверху
        val jobs = all.takeLast(3).reversed()
        view.removeAllViews()
        if (jobs.isEmpty()) { view.visibility = View.GONE; return }
        view.visibility = View.VISIBLE
        for (j in jobs) {
            val card = card(j)
            view.addView(card, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, 0))
            if (shownKeys[j.id] == null) {
                card.alpha = 0f; card.translationY = c.dp(16).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
            }
            shownKeys[j.id] = j.state.name
        }
        if (all.size > jobs.size) view.addView(Kit.text(c, "и ещё ${all.size - jobs.size}", Tokens.TEXT_CAPTION, Ui.colors.textSecondary).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(4), 0, 0)
        })
    }

    private fun card(j: SaveJob): LinearLayout {
        val c = host.activity
        val p = Ui.colors
        val card = Kit.card(c, p.surfaceHigh)
        card.elevation = c.dp(Tokens.ELEV_PANEL).toFloat()
        card.setPadding(c.dp(14), c.dp(12), c.dp(6), c.dp(8))
        card.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE

        val finished = j.state == SaveJobState.DONE || j.state == SaveJobState.VERIFY_FAILED ||
            j.state == SaveJobState.FAILED || j.state == SaveJobState.CANCELLED
        val (ic, tint, status) = when (j.state) {
            SaveJobState.RUNNING -> Triple(Icon.EXPORT, p.primary, if (j.progress >= 0) "Сохранение… ${(j.progress * 100).toInt()} %" else "Сохранение…")
            SaveJobState.VERIFYING -> Triple(Icon.STETHOSCOPE, p.primary, "Проверка файла…")
            SaveJobState.DONE -> Triple(Icon.CHECK, p.primary, j.message ?: "Сохранено и проверено")
            SaveJobState.VERIFY_FAILED -> Triple(Icon.WARNING, p.danger, j.message ?: "Файл сохранён, но проверка не пройдена")
            SaveJobState.FAILED -> Triple(Icon.ERROR, p.danger, j.message ?: "Не удалось сохранить")
            SaveJobState.CANCELLED -> Triple(Icon.CLOSE, p.textSecondary, j.message ?: "Отменено")
        }
        val head = Kit.horizontal(c)
        head.addView(Kit.icon(c, ic, tint, 22), lp(c.dp(22), c.dp(22)).margins(0, 0, c.dp(10), 0))
        val col = Kit.vertical(c)
        col.addView(Kit.text(c, j.fileName ?: j.title, Tokens.TEXT_BODY, p.text, Tokens.FONT_MEDIUM).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(Kit.text(c, status, Tokens.TEXT_LABEL, if (j.state == SaveJobState.VERIFY_FAILED || j.state == SaveJobState.FAILED) p.danger else p.textSecondary).apply {
            maxLines = 3; setLineSpacing(0f, 1.1f)
        })
        head.addView(col, lp(0, Kit.WRAP, 1f))
        if (finished) head.addView(Ui.iconButton(c, Icon.CLOSE, "Скрыть") { dismissed += j.id; refresh() })
        card.addView(head, lp(Kit.MATCH, Kit.WRAP))

        if (j.state == SaveJobState.RUNNING || j.state == SaveJobState.VERIFYING) {
            val line = ProgressLine(c)
            line.progress = if (j.state == SaveJobState.VERIFYING) -1f else j.progress.toFloat()
            card.addView(line, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), c.dp(8), c.dp(4)))
            val row = Kit.horizontal(c)
            row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            if (j.state == SaveJobState.VERIFYING) row.addView(Ui.button(c, "Пропустить проверку", false) { host.controller.skipVerify(j.id) })
            row.addView(Ui.button(c, "Отменить", false) { host.controller.cancelJob(j.id) })
            card.addView(row, lp(Kit.MATCH, Kit.WRAP))
        } else if ((j.state == SaveJobState.DONE || j.state == SaveJobState.VERIFY_FAILED) && j.uri != null) {
            val row = Kit.horizontal(c)
            row.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            val f = SavedFile(j.uri as Uri, j.fileName ?: "audio", 0, 0, System.currentTimeMillis(), j.state == SaveJobState.VERIFY_FAILED)
            row.addView(Ui.button(c, "Поделиться", false) { host.controller.share(host.activity, f) })
            row.addView(Ui.button(c, "Открыть в редакторе", false) { dismissed += j.id; host.controller.openSaved(f) })
            card.addView(row, lp(Kit.MATCH, Kit.WRAP))
        }
        return card
    }
}
