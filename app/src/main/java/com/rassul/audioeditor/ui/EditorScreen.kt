package com.rassul.audioeditor.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rassul.audioeditor.editor.EditorController
import com.rassul.audioeditor.editor.Screen
import com.rassul.audioeditor.editor.Selection

/** Источник волны для редактора: у фейка — синтетический, у настоящего — поверх PeakPyramid. */
internal fun waveSourceFor(e: EditorController): WaveSource = if (e is FakeEditorController) e.wave else EditorWaveSource(e)

/**
 * Редактор: волна, поля выделения, транспорт, панель действий, экспорт (С3, FR-12…FR-24, FR-32).
 * Всё состояние берётся из EditorController.state; сам экран хранит только промежуточные тексты полей.
 */
internal class EditorScreen(host: Host, private val e: EditorController) : ScreenView(host) {
    override val root: View
    private val wave = WaveformView(ctx)
    private val source: WaveSource = waveSourceFor(e)
    private val topBar: TopBar
    private val undoBtn: View
    private val redoBtn: View
    private val notices = Kit.vertical(ctx)
    private val busyBox = Kit.vertical(ctx)
    private val busyText = Kit.text(ctx, "", Tokens.TEXT_BODY, Ui.colors.text)
    private val timeLabel = Kit.text(ctx, "", 18f, Ui.colors.text, Tokens.FONT_MONO)
    private val selInfo = Kit.text(ctx, "", Tokens.TEXT_LABEL, Ui.colors.textSecondary)
    private val startField = Kit.Field(ctx, "0:00.000", mono = true, label = "Начало", inputType = timeInput())
    private val endField = Kit.Field(ctx, "0:00.000", mono = true, label = "Конец", inputType = timeInput())
    private val playBtn: ImageView
    private val loopBtn: ToggleIcon
    private val keepBtn: View
    private val delBtn: View
    private val volBtn: View
    private val fadeBtn: View
    private val normBtn: View
    private val fastBtn: View
    private val exportBtn: View
    private val tools: List<View>
    private var shownTime = ""
    private var clockRunning = false
    private val clock = object : Runnable {
        override fun run() {
            updateTime()
            if (e.state.playing) root.postOnAnimation(this) else clockRunning = false
        }
    }

    private fun timeInput() = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME

    init {
        val c = ctx
        val p = Ui.colors
        val st = e.state

        // верхняя панель
        undoBtn = Ui.iconButton(c, Icon.UNDO, "Отменить") { e.undo() }
        redoBtn = Ui.iconButton(c, Icon.REDO, "Повторить") { e.redo() }
        val tb = Ui.topBar(c, st.fileName, subtitle(), { onBack() }, listOf(undoBtn, redoBtn))
        topBar = tb

        // волна
        wave.source = source
        wave.sampleRate = st.sampleRate
        wave.playheadProvider = { e.playheadFrame }
        wave.listener = object : WaveformView.Listener {
            override fun onSeek(frame: Long) { e.seekTo(frame); updateTime() }
            override fun onSelectionDrag(sel: Selection) { showSelection(sel, force = true) }
            override fun onSelectionCommit(sel: Selection) { e.setSelection(sel) }
        }

        // транспорт
        playBtn = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.ripple(c, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(p.primary) }, oval = true)
            elevation = c.dp(Tokens.ELEV_FAB).toFloat()
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            isClickable = true; isFocusable = true
            tooltipText = "Играть"
            setOnClickListener { if (e.state.playing) e.pause() else e.play() }
        }
        loopBtn = Ui.toggleIconButton(c, Icon.LOOP, "Повтор выделения") { on ->
            if (on && e.state.selection == null) {
                loopBtn.checked = false
                host.toast("Сначала выделите участок: удерживайте палец на волне и ведите в сторону.")
            } else e.setLooping(on)
        }
        val transport = Kit.horizontal(c)
        transport.addView(Ui.iconButton(c, Icon.SKIP_START, "В начало") { e.seekTo(0); wave.reveal(0); updateTime() })
        transport.addView(playBtn, lp(c.dp(56), c.dp(56)).margins(c.dp(4), 0, c.dp(4), 0))
        transport.addView(loopBtn.view)
        transport.addView(View(c), lp(0, 1, 1f))
        transport.addView(Ui.iconButton(c, Icon.ZOOM_OUT, "Отдалить") { wave.zoomBy(0.5) })
        transport.addView(Ui.iconButton(c, Icon.ZOOM_IN, "Приблизить") { wave.zoomBy(2.0) })
        transport.addView(Ui.iconButton(c, Icon.FIT_ALL, "Весь файл") { wave.fitAll() })

        // время и поля выделения
        val timeRow = Kit.horizontal(c)
        timeRow.addView(timeLabel, lp(Kit.WRAP, Kit.WRAP))
        timeRow.addView(View(c), lp(0, 1, 1f))
        timeRow.addView(selInfo, lp(Kit.WRAP, Kit.WRAP))
        startField.onSubmit = { applyFields() }
        endField.onSubmit = { applyFields() }
        val fields = Kit.horizontal(c, center = false)
        fields.addView(startField.view, lp(0, Kit.WRAP, 1f))
        fields.addView(endField.view, lp(0, Kit.WRAP, 1f).margins(c.dp(12)))

        // действия
        keepBtn = tool(Icon.KEEP, "Оставить") {
            e.keepSelection()
        }
        delBtn = tool(Icon.DELETE, "Удалить") {
            val sel = e.state.selection
            val len = if (sel != null) Format.num((sel.end - sel.start).toDouble() / e.state.sampleRate, 1) else ""
            e.deleteSelection()
            Ui.toast(host.activity, "Удалено $len с", "Отменить") { e.undo() }
        }
        volBtn = tool(Icon.VOLUME, "Громкость") { VolumeSheet.show(host, e) }
        fadeBtn = tool(Icon.FADE_IN, "Fade") { FadeSheet.show(host, e) }
        normBtn = tool(Icon.NORMALIZE, "Нормализация") { NormalizeSheet.show(host, e) }
        fastBtn = tool(Icon.BOLT, "Без потерь") { FastTrimSheet.show(host, e) }
        tools = listOf(keepBtn, delBtn, volBtn, fadeBtn, normBtn, fastBtn)
        val toolRow = Kit.horizontal(c)
        tools.forEach { toolRow.addView(it, lp(Kit.WRAP, c.dp(64)).margins(0, 0, c.dp(8), 0)) }
        val toolScroll = HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(toolRow)
        }

        exportBtn = Ui.button(c, "Экспорт") { openExport() }.also { b ->
            b.setCompoundDrawablesRelativeWithIntrinsicBounds(Ui.icon(c, Icon.EXPORT, p.onPrimary), null, null, null)
            b.compoundDrawablePadding = c.dp(10)
            b.layoutParams = lp(Kit.MATCH, c.dp(52))
        }

        // индикатор занятости и заметки
        busyBox.background = Ui.roundRect(c, Tokens.primaryContainer(p), Tokens.RADIUS_M)
        busyBox.setPadding(c.dp(14), c.dp(10), c.dp(14), c.dp(12))
        busyBox.addView(busyText)
        busyBox.addView(ProgressLine(c).apply { progress = -1f }, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, 0))
        busyBox.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        busyBox.visibility = View.GONE

        val top = Kit.vertical(c)
        top.setPadding(c.dp(12), 0, c.dp(12), 0)
        top.addView(notices, lp(Kit.MATCH, Kit.WRAP))
        top.addView(busyBox, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(4), 0, c.dp(4)))

        val controls = Kit.vertical(c)
        controls.setPadding(c.dp(12), c.dp(8), c.dp(12), c.dp(8))
        controls.addView(timeRow, lp(Kit.MATCH, Kit.WRAP).margins(c.dp(4), 0, c.dp(4), c.dp(4)))
        controls.addView(fields, lp(Kit.MATCH, Kit.WRAP))
        controls.addView(transport, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, 0))
        controls.addView(toolScroll, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(4), 0, c.dp(8)))
        controls.addView(exportBtn)

        val waveFrame = android.widget.FrameLayout(c)
        waveFrame.addView(wave, android.widget.FrameLayout.LayoutParams(-1, -1))
        waveFrame.clipToOutline = true
        waveFrame.background = Ui.roundRect(c, p.waveBg, Tokens.RADIUS_L)
        waveFrame.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, c.dp(Tokens.RADIUS_L).toFloat()) }
        }

        val landscape = c.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val page = Kit.vertical(c)
        page.addView(tb.view, lp(Kit.MATCH, Kit.WRAP))
        if (landscape) {
            val row = Kit.horizontal(c, center = false)
            val left = Kit.vertical(c)
            left.addView(top, lp(Kit.MATCH, Kit.WRAP))
            left.addView(waveFrame, lp(Kit.MATCH, 0, 1f).margins(c.dp(12), c.dp(4), c.dp(4), c.dp(12)))
            row.addView(left, lp(0, Kit.MATCH, 1f))
            val sv = android.widget.ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(controls) }
            row.addView(sv, lp(c.dp(360), Kit.MATCH))
            page.addView(row, lp(Kit.MATCH, 0, 1f))
        } else {
            page.addView(top, lp(Kit.MATCH, Kit.WRAP))
            page.addView(waveFrame, lp(Kit.MATCH, 0, 1f).margins(c.dp(12), c.dp(4), c.dp(12), 0))
            page.addView(controls, lp(Kit.MATCH, Kit.WRAP))
        }
        root = page
        refresh()
    }

    private fun tool(icon: Icon, label: String, onClick: () -> Unit): LinearLayout {
        val c = ctx
        val p = Ui.colors
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            minimumWidth = c.dp(76)
            setPadding(c.dp(10), c.dp(6), c.dp(10), c.dp(6))
            background = Ui.ripple(c, Ui.roundRect(c, p.surface, Tokens.RADIUS_M, if (p.isDark) 0x14FFFFFF else p.outline, 1f), oval = false)
            isClickable = true; isFocusable = true
            contentDescription = label
            addView(Kit.icon(c, icon, p.text), lp(c.dp(24), c.dp(24)))
            addView(Kit.text(c, label, Tokens.TEXT_CAPTION, p.text, Tokens.FONT_MEDIUM).apply { maxLines = 1; gravity = Gravity.CENTER; setPadding(0, c.dp(4), 0, 0) })
            setOnClickListener { onClick() }
            Ui.pressScale(this, 0.96f)
        }
    }

    private fun setOn(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.38f; v.stateDescription = if (on) null else "Недоступно" }

    private fun subtitle(): String {
        val st = e.state
        return "${Format.kHz(st.sampleRate)} · ${Format.channels(st.channels)} · ${e.formatTime(st.length)}"
    }

    private fun openExport() {
        if (e.state.busy != null) return
        ExportSheet.show(host, null, e) { req -> host.beforeSave { e.export(req) } }
    }

    private fun applyFields() {
        val sel = e.state.selection
        val curS = sel?.let { e.formatTime(it.start) } ?: ""
        val curE = sel?.let { e.formatTime(it.end) } ?: ""
        if (startField.text == curS && endField.text == curE) { startField.setError(null); endField.setError(null); return }
        val err = e.setSelectionText(startField.text.trim(), endField.text.trim())
        startField.setError(err.start)
        endField.setError(err.end)
        if (err.ok) e.state.selection?.let { wave.reveal(it.start) }
    }

    private fun showSelection(sel: Selection?, force: Boolean) {
        if (force || !startField.edit.hasFocus()) startField.text = sel?.let { e.formatTime(it.start) } ?: ""
        if (force || !endField.edit.hasFocus()) endField.text = sel?.let { e.formatTime(it.end) } ?: ""
        selInfo.text = if (sel == null) "Нет выделения"
        else "Выделено: " + e.formatTime(sel.end - sel.start)
    }

    private fun updateTime() {
        val st = e.state
        val s = e.formatTime(e.playheadFrame) + " / " + e.formatTime(st.length)
        if (s != shownTime) { shownTime = s; timeLabel.text = s }
    }

    override fun update(s: Screen): Boolean = s is Screen.Editor

    override fun refresh() {
        val st = e.state
        val c = ctx
        if (source is EditorWaveSource) source.touch()
        wave.sampleRate = st.sampleRate
        wave.selection = st.selection
        wave.playing = st.playing
        wave.invalidate()
        topBar.setTitle(st.fileName, subtitle())
        showSelection(st.selection, force = false)
        if (st.selection != null) { startField.setError(null); endField.setError(null) }
        playBtn.setImageDrawable(Ui.icon(c, if (st.playing) Icon.PAUSE else Icon.PLAY, Ui.colors.onPrimary))
        playBtn.contentDescription = if (st.playing) "Пауза" else "Играть"
        if (loopBtn.checked != st.looping) loopBtn.checked = st.looping
        wave.contentDescription = "Волна. Длительность ${e.formatTime(st.length)}." +
            (st.selection?.let { " Выделено с ${e.formatTime(it.start)} по ${e.formatTime(it.end)}." } ?: " Выделения нет.")
        val idle = st.busy == null
        setOn(undoBtn, st.canUndo && idle); setOn(redoBtn, st.canRedo && idle)
        val hasSel = st.selection != null
        setOn(keepBtn, hasSel && idle); setOn(delBtn, hasSel && idle)
        setOn(volBtn, idle); setOn(fadeBtn, idle); setOn(normBtn, idle); setOn(exportBtn, idle)
        fastBtn.visibility = if (st.fastTrimAvailable) View.VISIBLE else View.GONE
        setOn(fastBtn, idle)
        // занятость
        if (st.busy != null) { busyText.text = st.busy; if (busyBox.visibility != View.VISIBLE) busyBox.visibility = View.VISIBLE }
        else busyBox.visibility = View.GONE
        // постоянные заметки (FR-22) + перегрузка (FR-14)
        notices.removeAllViews()
        if (st.hasOverload) notices.addView(Kit.notice(c, Kit.Tone.DANGER, "Есть участки выше 0 дБ — они подсвечены красным. При сохранении в 16/24 бит или AAC будут обрезаны."),
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(4), 0, 0))
        for (n in st.notices) notices.addView(Kit.notice(c, Kit.Tone.WARNING, n), lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(4), 0, 0))
        updateTime()
        if (st.playing && !clockRunning) { clockRunning = true; root.postOnAnimation(clock) }
    }

    override fun onPause() { if (e.state.playing) e.pause() }

    override fun onBack(): Boolean {
        if (e.state.canUndo) {
            Ui.showChoice(host.activity, "Закрыть редактор?",
                "Правки не сохраняются между запусками. Если результат нужен, сначала нажмите «Экспорт».",
                listOf("Остаться" to {}, "Закрыть" to { host.controller.goHome() }))
        } else host.controller.goHome()
        return true
    }
}
