package com.rassul.audioeditor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.rassul.audioeditor.editor.SavedFile

/** Сохранённые файлы (С5, FR-28, FR-33): имя, размер, длительность, дата, пометка «проверка не пройдена». */
internal class SavedScreen(host: Host) : ScreenView(host) {
    override val root: View
    private val list = Kit.vertical(ctx)

    init {
        val c = ctx
        Scaffold.padded(list, c, 16, 4, 24)
        root = Scaffold.page(host, "Сохранённые", null, { host.back() }, emptyList(), Scaffold.scrollColumn(c, list))
        populate()
    }

    private fun populate() {
        val c = ctx
        val p = Ui.colors
        list.removeAllViews()
        val files = try { host.controller.savedFiles() } catch (_: Exception) { emptyList() }
        if (files.isEmpty()) {
            val box = Kit.vertical(c).apply { gravity = Gravity.CENTER; setPadding(c.dp(24), c.dp(64), c.dp(24), c.dp(24)) }
            box.addView(WaveHero(c), lp(Kit.MATCH, Kit.WRAP))
            box.addView(Kit.text(c, "Пока ничего нет", Tokens.TEXT_TITLE, p.text, Tokens.FONT_MEDIUM).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(16), 0, c.dp(8)) })
            box.addView(Kit.text(c, "Файлы, которые вы сохраните, появятся здесь: их можно будет открыть в редакторе, отправить или удалить.",
                Tokens.TEXT_BODY, p.textSecondary).apply { gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f) })
            box.addView(Ui.button(c, "Выбрать файл") { host.back(); host.controller.pickAudioOrVideoFile(host.activity) }, lp(Kit.WRAP, c.dp(52)).margins(0, c.dp(20), 0, 0))
            list.addView(box)
            return
        }
        files.forEachIndexed { i, f ->
            val row = Kit.horizontal(c)
            row.minimumHeight = c.dp(72)
            row.setPadding(c.dp(14), c.dp(10), c.dp(4), c.dp(10))
            row.background = Ui.ripple(c, Ui.roundRect(c, p.surface, Tokens.RADIUS_L, if (p.isDark) 0x14FFFFFF else p.outline, 1f), oval = false, radiusDp = Tokens.RADIUS_L)
            row.isClickable = true; row.isFocusable = true
            row.setOnClickListener { actions(f) }
            val badge = LinearLayout(c).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Tokens.primaryContainer(p)) }
                addView(Kit.icon(c, Icon.AUDIO_FILE, p.primary))
            }
            row.addView(badge, lp(c.dp(44), c.dp(44)).margins(0, 0, c.dp(14), 0))
            val col = Kit.vertical(c)
            col.addView(Kit.text(c, f.name, Tokens.TEXT_BODY, p.text, Tokens.FONT_MEDIUM).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            col.addView(Kit.text(c, "${Format.size(f.sizeBytes)} · ${Format.duration(f.durationMs)} · ${Format.relativeTime(f.dateMs)}",
                Tokens.TEXT_LABEL, p.textSecondary).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            if (f.verifyFailed) {
                val warn = Kit.horizontal(c)
                warn.setPadding(0, c.dp(4), 0, 0)
                warn.addView(Kit.icon(c, Icon.WARNING, p.danger, 16), lp(c.dp(16), c.dp(16)).margins(0, 0, c.dp(6), 0))
                warn.addView(Kit.text(c, "Проверка не пройдена", Tokens.TEXT_LABEL, p.danger, Tokens.FONT_MEDIUM))
                col.addView(warn)
            }
            row.addView(col, lp(0, Kit.WRAP, 1f))
            row.addView(Ui.iconButton(c, Icon.MORE, "Действия с файлом ${f.name}") { actions(f) })
            list.addView(row, lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(8)))
            row.alpha = 0f; row.translationY = c.dp(12).toFloat()
            row.animate().alpha(1f).translationY(0f).setStartDelay(Tokens.STAGGER * minOf(i, 12)).setDuration(Tokens.DUR_ENTER).setInterpolator(Tokens.EASE_ENTER).start()
        }
    }

    private fun actions(f: SavedFile) {
        val a = host.activity
        val items = listOf(
            SheetItem("Поделиться", Icon.SHARE) { host.controller.share(a, f) },
            SheetItem("Открыть в редакторе", Icon.OPEN_EDITOR) { host.controller.openSaved(f) },
            SheetItem("Удалить", Icon.DELETE) { confirmDelete(f) },
        )
        Ui.showListSheet(a, f.name, items)
    }

    private fun confirmDelete(f: SavedFile) {
        Ui.showChoice(host.activity, "Удалить файл?", "«${f.name}» будет удалён с телефона без возможности восстановления.",
            listOf("Отмена" to {}, "Удалить" to { host.controller.delete(f); populate() }))
    }

    override fun refresh() { populate() }
}

/** Настройки (FR-19, FR-31) + вход в самопроверку. */
internal class SettingsScreen(host: Host) : ScreenView(host) {
    override val root: View
    private val col = Kit.vertical(ctx)

    init {
        val c = ctx
        Scaffold.padded(col, c, 8, 4, 24)
        root = Scaffold.page(host, "Настройки", null, { host.back() }, emptyList(), Scaffold.scrollColumn(c, col))
        build()
    }

    private fun build() {
        val c = ctx
        val p = Ui.colors
        col.removeAllViews()
        val s = host.controller.settings

        col.addView(Ui.sectionHeader(c, "ПРАВКИ"))
        col.addView(Ui.switchRow(c, "Сглаживать стыки", "При обрезке и удалении края кусков плавно затухают и нарастают, чтобы не было щелчка", s.smoothJoints) {
            host.controller.updateSettings(host.controller.settings.copy(smoothJoints = it)); build()
        })
        // длительность сглаживания 1–50 мс
        val ms = s.jointMs.coerceIn(1.0, 50.0)
        val head = Kit.horizontal(c)
        head.setPadding(c.dp(20), c.dp(8), c.dp(20), 0)
        head.addView(Kit.text(c, "Длительность сглаживания", Tokens.TEXT_BODY, p.text), lp(0, Kit.WRAP, 1f))
        val value = Kit.text(c, Format.num(ms, 0) + " мс", Tokens.TEXT_BODY, p.primary, Tokens.FONT_MONO)
        head.addView(value)
        col.addView(head, lp(Kit.MATCH, Kit.WRAP))
        val seek = SeekBar(c).apply {
            max = 49
            progress = (ms.toInt() - 1).coerceIn(0, 49)
            isEnabled = s.smoothJoints
            progressTintList = ColorStateList.valueOf(p.primary)
            thumbTintList = ColorStateList.valueOf(p.primary)
            progressBackgroundTintList = ColorStateList.valueOf(p.outline)
            contentDescription = "Длительность сглаживания стыков, от 1 до 50 миллисекунд"
            setPadding(c.dp(32), c.dp(8), c.dp(32), c.dp(8))
            alpha = if (s.smoothJoints) 1f else 0.4f
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, pr: Int, user: Boolean) { value.text = "${pr + 1} мс" }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                host.controller.updateSettings(host.controller.settings.copy(jointMs = (sb.progress + 1).toDouble()))
            }
        })
        col.addView(seek, lp(Kit.MATCH, Kit.WRAP))
        col.addView(Kit.text(c, "От 1 до 50 мс, по умолчанию 5 мс.", Tokens.TEXT_LABEL, p.textSecondary).apply { setPadding(c.dp(20), 0, c.dp(20), c.dp(8)) })

        col.addView(Ui.sectionHeader(c, "ИЗВЛЕЧЕНИЕ"))
        col.addView(Ui.switchRow(c, "Извлекать сразу при «Поделиться»",
            "Звук из видео с одной дорожкой сохранится в M4A без лишних экранов", s.oneTapExtract) {
            host.controller.updateSettings(host.controller.settings.copy(oneTapExtract = it))
        })

        col.addView(Ui.sectionHeader(c, "ДИАГНОСТИКА"))
        val row = Ui.settingRow(c, "Самопроверка", "Проверить, какие кодеки и режимы поддерживает этот телефон")
        row.addView(Kit.icon(c, Icon.CHEVRON_RIGHT, p.textSecondary), lp(c.dp(24), c.dp(24)))
        row.setOnClickListener { host.showSelfTest() }
        col.addView(row)
    }
}

/** Самопроверка (FR-9/25/26): запуск, лог, «Скопировать отчёт». */
internal class SelfTestScreen(host: Host) : ScreenView(host) {
    override val root: View
    private val log = Kit.text(ctx, "", 13f, Ui.colors.text, Tokens.FONT_MONO)
    private val run: TextView
    private val copy: TextView
    private val state = Kit.text(ctx, "", Tokens.TEXT_BODY, Ui.colors.textSecondary)
    private var report: String? = null
    private var running = false
    private var dead = false

    init {
        val c = ctx
        val p = Ui.colors
        val col = Kit.vertical(c)
        Scaffold.padded(col, c, 16, 4, 24)
        col.addView(Kit.text(c, "Проверка выполняется на этом телефоне и ничего не отправляет в сеть. Результат поможет понять, какие форматы работают.",
            Tokens.TEXT_BODY, p.textSecondary).apply { setLineSpacing(0f, 1.2f) })
        run = Ui.button(c, "Запустить самопроверку") { start() }
        col.addView(run, lp(Kit.MATCH, c.dp(52)).margins(0, c.dp(16), 0, 0))
        col.addView(state, lp(Kit.MATCH, Kit.WRAP).margins(c.dp(4), c.dp(12), 0, c.dp(8)))
        val logBox = Kit.vertical(c).apply {
            background = Ui.roundRect(c, p.waveBg, Tokens.RADIUS_M, p.outline, 1f)
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            minimumHeight = c.dp(160)
        }
        log.setTextIsSelectable(true)
        logBox.addView(log)
        col.addView(logBox, lp(Kit.MATCH, Kit.WRAP))
        copy = Ui.button(c, "Скопировать отчёт", primary = false) { copyReport() }
        copy.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(c, Icon.COPY, p.primary), null, null, null)
        copy.compoundDrawablePadding = c.dp(8)
        copy.visibility = View.GONE
        col.addView(copy, lp(Kit.WRAP, c.dp(52)).margins(0, c.dp(8), 0, 0))
        root = Scaffold.page(host, "Самопроверка", null, { host.back() }, emptyList(), Scaffold.scrollColumn(c, col))
        log.text = "Отчёта пока нет."
    }

    private fun start() {
        if (running) return
        running = true
        report = null
        log.text = ""
        run.isEnabled = false; run.alpha = 0.5f
        copy.visibility = View.GONE
        state.text = "Идёт проверка…"
        val a = host.activity
        host.controller.runSelfTest({ line ->
            a.runOnUiThread { if (!dead) log.append(line + "\n") }
        }, { rep ->
            a.runOnUiThread {
                if (dead) return@runOnUiThread
                running = false
                report = rep
                log.text = rep
                state.text = "Готово. Отчёт можно скопировать и прислать разработчику."
                run.isEnabled = true; run.alpha = 1f; run.text = "Запустить ещё раз"
                copy.visibility = View.VISIBLE
            }
        })
    }

    private fun copyReport() {
        val r = report ?: return
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Отчёт самопроверки", r))
        host.toast("Отчёт скопирован")
    }

    override fun onPause() { }
    fun dispose() { dead = true }
}
