package com.rassul.audioeditor.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.InputType
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.rassul.audioeditor.core.dsp.FadeCurve
import com.rassul.audioeditor.core.loudness.NormalizeTarget
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.core.pipeline.ExportRequest
import com.rassul.audioeditor.editor.EditorController
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/* Листы редактора: Громкость, Fade, Нормализация, Экспорт, Быстрая обрезка. Подтверждение кнопкой «Применить» внизу. */

internal fun parseNum(s: String): Double? =
    s.trim().replace('−', '-').replace(',', '.').removePrefix("+").toDoubleOrNull()

private fun scrollRow(ctx: Context, content: View): HorizontalScrollView = HorizontalScrollView(ctx).apply {
    isHorizontalScrollBarEnabled = false
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(content)
}

private fun scopeText(e: EditorController): String =
    if (e.state.selection != null) "Применится к выделенному участку" else "Применится ко всему файлу"

// ====================================================================== Громкость (FR-20)
internal object VolumeSheet {
    private const val MIN = -40.0
    private const val MAX = 20.0

    fun show(host: Host, e: EditorController) {
        val c = host.activity
        val p = Ui.colors
        val body = Scaffold.sheetBody(c)
        var db = 0.0
        lateinit var dialog: Dialog

        val readout = Kit.text(c, "", 40f, p.primary, Tokens.FONT_MONO).apply { gravity = Gravity.CENTER; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val factor = Kit.text(c, "", Tokens.TEXT_LABEL, p.textSecondary).apply { gravity = Gravity.CENTER }
        val seek = SeekBar(c).apply {
            max = ((MAX - MIN) * 10).toInt()
            progressTintList = ColorStateList.valueOf(p.primary)
            thumbTintList = ColorStateList.valueOf(p.primary)
            progressBackgroundTintList = ColorStateList.valueOf(p.outline)
            contentDescription = "Громкость в децибелах"
        }
        val field = Kit.Field(c, "дБ", "0,0", mono = true, label = "Точное значение, дБ",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED)
        val warn = Kit.notice(c, Kit.Tone.WARNING, "Возможна перегрузка: участки выше 0 дБ подсветятся красным, а при сохранении будут обрезаны.")

        fun show(v: Double, fromField: Boolean, fromSeek: Boolean = false) {
            db = (Math.round(v.coerceIn(MIN, MAX) * 10.0) / 10.0)
            readout.text = Format.db(db)
            // g = 10^(dB/20) (FR-20); только для показа
            factor.text = "Множитель ×" + Format.num(10.0.pow(db / 20.0), 3)
            if (!fromSeek) seek.progress = ((db - MIN) * 10).toInt()
            if (!fromField) field.text = Format.num(db, 1)
            field.setError(null)
            warn.visibility = if (db > 0.0) View.VISIBLE else View.GONE
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, pr: Int, user: Boolean) { if (user) show(MIN + pr / 10.0, false, true) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        field.onSubmit = {
            val v = parseNum(field.text)
            if (v == null || v < MIN || v > MAX) field.setError("Введите число от −40,0 до +20,0")
            else show(v, true)
        }

        body.addView(Kit.text(c, scopeText(e), Tokens.TEXT_BODY, p.textSecondary).apply { gravity = Gravity.CENTER })
        body.addView(readout, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))
        body.addView(factor, lp(Kit.MATCH, Kit.WRAP))
        body.addView(seek, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, c.dp(4)))
        val steps = Kit.horizontal(c)
        steps.addView(Ui.button(c, "−0,1", false) { show(db - 0.1, false) }, lp(Kit.WRAP, Kit.WRAP))
        steps.addView(Ui.button(c, "+0,1", false) { show(db + 0.1, false) }, lp(Kit.WRAP, Kit.WRAP))
        steps.addView(View(c), lp(0, 1, 1f))
        steps.addView(Ui.button(c, "Сбросить в 0", false) { show(0.0, false) }, lp(Kit.WRAP, Kit.WRAP))
        body.addView(steps, lp(Kit.MATCH, Kit.WRAP))
        body.addView(field.view, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, 0))
        body.addView(warn, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))
        show(0.0, false)
        body.addView(Scaffold.wideButton(c, "Применить") {
            field.onSubmit?.invoke()
            if (parseNum(field.text) != null && field.text.isNotBlank()) {
                val v = parseNum(field.text)!!
                if (v in MIN..MAX) { dialog.dismiss(); e.volume(db) }
            }
        })
        dialog = Ui.showSheet(c, "Громкость", body)
    }
}

// ====================================================================== Fade (FR-21)
/** Маленький график формы кривой fade. Формулы — FR-C2: линейная g=x; равной мощности g=sin(πx/2); логарифмическая g=10^(−60(1−x)/20). */
internal class CurveGraph(ctx: Context, private val curve: FadeCurve, fadeIn: Boolean) : View(ctx) {
    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Ui.dp(ctx, 2).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val axis = Paint().apply { color = Tokens.withAlpha(Ui.colors.textSecondary, 0.5f); strokeWidth = Ui.dp(ctx, 1).toFloat() }
    var fadeIn: Boolean = fadeIn
        set(v) { field = v; rebuild(); invalidate() }
    var color: Int = Ui.colors.text
        set(v) { field = v; paint.color = v; invalidate() }

    init { paint.color = color }

    private fun g(x: Double): Double = when (curve) {
        FadeCurve.LINEAR -> x
        FadeCurve.EQUAL_POWER -> sin(PI * x / 2)
        FadeCurve.LOGARITHMIC -> if (x <= 0.0) 0.0 else 10.0.pow(-60.0 * (1 - x) / 20.0)
    }

    private fun rebuild() {
        path.rewind()
        if (width == 0) return
        val pad = Ui.dp(context, 6).toFloat()
        val w = width - 2 * pad
        val h = height - 2 * pad
        val n = 40
        for (i in 0..n) {
            val x = i.toDouble() / n
            val y = g(if (fadeIn) x else 1 - x)
            val px = pad + (x * w).toFloat()
            val py = pad + ((1 - y) * h).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { rebuild() }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(Ui.dp(context, 72), Ui.dp(context, 44)) }
    override fun onDraw(canvas: Canvas) {
        val pad = Ui.dp(context, 6).toFloat()
        canvas.drawLine(pad, height - pad, width - pad, height - pad, axis)
        canvas.drawLine(pad, pad, pad, height - pad, axis)
        canvas.drawPath(path, paint)
    }
}

internal object FadeSheet {
    fun show(host: Host, e: EditorController) {
        val c = host.activity
        val p = Ui.colors
        val body = Scaffold.sheetBody(c)
        val st = e.state
        val sel = st.selection
        var fadeIn = true
        var curve = FadeCurve.EQUAL_POWER
        lateinit var dialog: Dialog
        val totalSec = st.length.toDouble() / st.sampleRate

        val graphs = ArrayList<CurveGraph>()
        val cards = ArrayList<LinearLayout>()
        val curves = listOf(
            Triple(FadeCurve.LINEAR, "Линейная", "Ровное нарастание"),
            Triple(FadeCurve.EQUAL_POWER, "Равной мощности", "По умолчанию, без «провала»"),
            Triple(FadeCurve.LOGARITHMIC, "Логарифмическая", "Мягкий старт"),
        )
        val note = Kit.text(c, "", Tokens.TEXT_LABEL, p.textSecondary).apply { setPadding(c.dp(4), c.dp(8), 0, 0) }
        fun refreshCurves() {
            cards.forEachIndexed { i, card ->
                val on = curves[i].first == curve
                card.background = Ui.ripple(c,
                    if (on) Ui.roundRect(c, Tokens.primaryContainer(p), Tokens.RADIUS_M, p.primary, 1.5f)
                    else Ui.roundRect(c, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M, p.outline, 1f), oval = false)
                graphs[i].color = if (on) p.primary else p.textSecondary
                graphs[i].fadeIn = fadeIn
                card.isSelected = on
            }
            note.text = when {
                sel != null -> "Fade применится ко всему выделенному участку."
                fadeIn -> "Без выделения: нарастание от начала файла."
                else -> "Без выделения: затухание к концу файла."
            }
        }

        body.addView(Ui.segmented(c, listOf("Нарастание", "Затухание"), 0) { fadeIn = it == 0; refreshCurves() },
            lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(16)))

        body.addView(Kit.heading(c, "ФОРМА КРИВОЙ").apply { setPadding(c.dp(4), 0, 0, c.dp(8)) })
        val row = Kit.horizontal(c, center = false)
        curves.forEachIndexed { i, (cv, name, _) ->
            val card = Kit.vertical(c).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(c.dp(6), c.dp(10), c.dp(6), c.dp(10))
                isClickable = true; isFocusable = true
                contentDescription = "Кривая: $name"
            }
            val g = CurveGraph(c, cv, true)
            graphs += g
            card.addView(g, lp(Kit.WRAP, Kit.WRAP))
            card.addView(Kit.text(c, name, Tokens.TEXT_CAPTION, p.text, Tokens.FONT_MEDIUM).apply { gravity = Gravity.CENTER; setPadding(0, c.dp(6), 0, 0) })
            card.setOnClickListener { curve = cv; refreshCurves() }
            cards += card
            row.addView(card, lp(0, Kit.WRAP, 1f).margins(if (i == 0) 0 else c.dp(8)))
        }
        body.addView(row, lp(Kit.MATCH, Kit.WRAP))
        body.addView(note, lp(Kit.MATCH, Kit.WRAP))

        val dur = Kit.Field(c, "сек", "3,0", mono = true, label = "Длительность, с",
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        if (sel != null) { dur.text = Format.num((sel.end - sel.start).toDouble() / st.sampleRate, 3); dur.setEnabledLook(false) }
        body.addView(dur.view, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(16), 0, 0))
        refreshCurves()

        body.addView(Scaffold.wideButton(c, "Применить") {
            val d = if (sel != null) (sel.end - sel.start).toDouble() / st.sampleRate else parseNum(dur.text)
            if (d == null || d <= 0.0 || d > totalSec) {
                dur.setError("Длительность должна быть больше 0 и не больше длины файла (${Format.num(totalSec, 1)} с)")
            } else { dialog.dismiss(); e.fade(fadeIn, curve, d) }
        })
        dialog = Ui.showSheet(c, "Fade", body)
    }
}

// ====================================================================== Нормализация (FR-22)
internal object NormalizeSheet {
    fun show(host: Host, e: EditorController) {
        val c = host.activity
        val p = Ui.colors
        val body = Scaffold.sheetBody(c)
        var mode = 0
        var preset = 0
        val targets = doubleArrayOf(-14.0, -16.0, -23.0)
        lateinit var dialog: Dialog

        val lufsBox = Kit.vertical(c)
        val peakBox = Kit.vertical(c)
        lufsBox.addView(Kit.text(c, "Цель по громкости", Tokens.TEXT_LABEL, p.textSecondary, Tokens.FONT_MEDIUM).apply { setPadding(c.dp(4), 0, 0, c.dp(8)) })
        val chips = Kit.ChipRow(c, listOf("−14 LUFS", "−16 LUFS", "−23 LUFS"), 0) { preset = it }
        lufsBox.addView(scrollRow(c, chips.view))
        lufsBox.addView(Kit.text(c, "−14 — стриминг и подкасты, −16 — видео, −23 — вещание (EBU R 128). Громкость измеряется по ITU-R BS.1770-5; пик не выше −1,0 dBTP.",
            Tokens.TEXT_LABEL, p.textSecondary).apply { setPadding(c.dp(4), c.dp(12), 0, 0); setLineSpacing(0f, 1.15f) })
        peakBox.addView(Kit.text(c, "Самый громкий сэмпл будет доведён до −1,0 dBFS. Подходит, если важно не «разгонять» запись по громкости.",
            Tokens.TEXT_BODY, p.textSecondary).apply { setLineSpacing(0f, 1.2f) })
        peakBox.visibility = View.GONE

        body.addView(Ui.segmented(c, listOf("На слух (LUFS)", "По пику"), 0) {
            mode = it
            TransitionManager.beginDelayedTransition(body)
            lufsBox.visibility = if (it == 0) View.VISIBLE else View.GONE
            peakBox.visibility = if (it == 1) View.VISIBLE else View.GONE
        }, lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(16)))
        body.addView(lufsBox, lp(Kit.MATCH, Kit.WRAP))
        body.addView(peakBox, lp(Kit.MATCH, Kit.WRAP))
        body.addView(Kit.notice(c, Kit.Tone.INFO, "Нормализация применяется ко всему файлу после остальных правок. Если файл изменится, усиление пересчитается."),
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(16), 0, 0))
        body.addView(Scaffold.wideButton(c, "Нормализовать") {
            dialog.dismiss()
            e.normalize(if (mode == 0) NormalizeTarget.Lufs(targets[preset]) else NormalizeTarget.Peak())
        })
        dialog = Ui.showSheet(c, "Нормализация", body)
    }
}

// ====================================================================== Экспорт (FR-24)
internal object ExportSheet {
    private val RATES_STD = listOf(8000, 16000, 22050, 32000, 44100, 48000)
    private val RATES_WAV = RATES_STD + listOf(96000, 192000)

    /**
     * locked != null — формат уже выбран (извлечение «Другой формат…»), иначе выбор из четырёх, по умолчанию ВСЕГДА M4A.
     * editor — для describeExport (может быть null при извлечении).
     */
    fun show(host: Host, locked: ExportFormat?, editor: EditorController?, onConfirm: (ExportRequest) -> Unit) {
        val c = host.activity
        val p = Ui.colors
        val body = Scaffold.sheetBody(c)
        var format = locked ?: ExportFormat.M4A     // выбор не запоминается (Д-13)
        var rate: Int? = null
        var bits: Int? = null
        var bitrate: Int? = null
        var advOpen = false
        lateinit var dialog: Dialog

        val formatBox = Kit.vertical(c)
        val adv = Kit.vertical(c)
        val advToggle = Kit.horizontal(c)
        val advChevron = Kit.icon(c, Icon.CHEVRON_DOWN, p.textSecondary)
        val summary = Kit.vertical(c)

        fun request() = ExportRequest(format, rate, bits, bitrate)

        fun refreshSummary() {
            summary.removeAllViews()
            summary.addView(Kit.heading(c, "ЧТО БУДЕТ СОХРАНЕНО").apply { setPadding(c.dp(4), 0, 0, c.dp(8)) })
            val lines = editor?.describeExport(request()) ?: defaultLines(format, request())
            for (l in lines) summary.addView(Kit.notice(c, if (l.startsWith("Повторное") || l.contains("обрез")) Kit.Tone.WARNING else Kit.Tone.INFO, l),
                lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(8)))
        }

        fun buildAdvanced() {
            adv.removeAllViews()
            fun section(title: String, labels: List<String>, selected: Int, on: (Int) -> Unit) {
                adv.addView(Kit.text(c, title, Tokens.TEXT_LABEL, p.textSecondary, Tokens.FONT_MEDIUM).apply { setPadding(c.dp(4), c.dp(12), 0, c.dp(8)) })
                adv.addView(scrollRow(c, Kit.ChipRow(c, labels, selected, on).view))
            }
            if (format == ExportFormat.M4A || format == ExportFormat.OPUS) {
                val opts = if (format == ExportFormat.M4A) listOf(64, 96, 128, 160, 192, 256, 320) else listOf(32, 64, 96, 128, 192, 256)
                val def = if (format == ExportFormat.M4A) 192 else 128
                val cur = bitrate ?: def
                section("Битрейт, кбит/с", opts.map { if (it == def) "$it · по умолч." else "$it" }, opts.indexOf(cur)) {
                    bitrate = opts[it].takeIf { v -> v != def }; refreshSummary()
                }
            }
            if (format == ExportFormat.FLAC || format == ExportFormat.WAV) {
                val opts = if (format == ExportFormat.FLAC) listOf<Int?>(null, 16, 24) else listOf<Int?>(null, 16, 24, 32)
                section("Разрядность", opts.map { when (it) { null -> "Авто"; 32 -> "32 float"; else -> "$it бит" } }, opts.indexOf(bits)) {
                    bits = opts[it]; refreshSummary()
                }
            }
            if (format == ExportFormat.OPUS) {
                adv.addView(Kit.text(c, "Частота для Opus всегда 48 кГц.", Tokens.TEXT_LABEL, p.textSecondary).apply { setPadding(c.dp(4), c.dp(12), 0, 0) })
            } else {
                val rates = if (format == ExportFormat.WAV) RATES_WAV else RATES_STD
                val opts = listOf<Int?>(null) + rates
                section("Частота дискретизации", opts.map { if (it == null) "Как в исходнике" else Format.kHz(it) }, opts.indexOf(rate)) {
                    rate = opts[it]; refreshSummary()
                }
            }
        }

        fun buildFormats() {
            formatBox.removeAllViews()
            if (locked != null) return
            val cards = ArrayList<LinearLayout>()
            ExportFormat.values().forEach { f ->
                val on = f == format
                val row = Kit.horizontal(c)
                row.minimumHeight = c.dp(56)
                row.setPadding(c.dp(14), c.dp(8), c.dp(14), c.dp(8))
                row.background = Ui.ripple(c,
                    if (on) Ui.roundRect(c, Tokens.primaryContainer(p), Tokens.RADIUS_M, p.primary, 1.5f)
                    else Ui.roundRect(c, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M, p.outline, 1f), oval = false)
                row.isClickable = true; row.isFocusable = true
                row.stateDescription = if (on) "Выбрано" else "Не выбрано"
                val col = Kit.vertical(c)
                col.addView(Kit.text(c, f.title + if (f == ExportFormat.M4A) "  ·  по умолчанию" else "", Tokens.TEXT_BODY, if (on) p.primary else p.text, Tokens.FONT_MEDIUM))
                col.addView(Kit.text(c, when (f) {
                    ExportFormat.M4A -> ".m4a, AAC-LC 192 кбит/с — открывается везде"
                    ExportFormat.OPUS -> ".ogg, мало места, хорошо для речи"
                    ExportFormat.FLAC -> ".flac, без потерь, для архива"
                    ExportFormat.WAV -> ".wav, без сжатия, для студии"
                }, Tokens.TEXT_CAPTION, p.textSecondary))
                row.addView(col, lp(0, Kit.WRAP, 1f))
                if (on) row.addView(Kit.icon(c, Icon.CHECK, p.primary))
                row.setOnClickListener {
                    if (format != f) { format = f; rate = null; bits = null; bitrate = null; buildFormats(); buildAdvanced(); refreshSummary() }
                }
                formatBox.addView(row, lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(8)))
                cards += row
            }
        }

        if (locked == null) body.addView(Kit.heading(c, "ФОРМАТ").apply { setPadding(c.dp(4), 0, 0, c.dp(8)) })
        body.addView(formatBox, lp(Kit.MATCH, Kit.WRAP))

        advToggle.minimumHeight = c.dp(48)
        advToggle.setPadding(c.dp(4), 0, c.dp(4), 0)
        advToggle.background = Ui.ripple(c, null, oval = false, radiusDp = Tokens.RADIUS_M)
        advToggle.isClickable = true; advToggle.isFocusable = true
        advToggle.addView(Kit.text(c, "Дополнительно", Tokens.TEXT_BODY, p.text, Tokens.FONT_MEDIUM), lp(0, Kit.WRAP, 1f))
        advToggle.addView(advChevron, lp(c.dp(24), c.dp(24)))
        advToggle.setOnClickListener {
            advOpen = !advOpen
            TransitionManager.beginDelayedTransition(body)
            adv.visibility = if (advOpen) View.VISIBLE else View.GONE
            advChevron.animate().rotation(if (advOpen) 180f else 0f).setDuration(Tokens.DUR_STANDARD).start()
            advToggle.stateDescription = if (advOpen) "Развёрнуто" else "Свёрнуто"
        }
        advToggle.stateDescription = "Свёрнуто"
        body.addView(advToggle, lp(Kit.MATCH, Kit.WRAP))
        adv.visibility = View.GONE
        body.addView(adv, lp(Kit.MATCH, Kit.WRAP))
        body.addView(summary, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(16), 0, 0))

        buildFormats(); buildAdvanced(); refreshSummary()
        body.addView(Scaffold.wideButton(c, "Сохранить") { dialog.dismiss(); onConfirm(request()) })
        dialog = Ui.showSheet(c, if (locked != null) "Параметры: ${locked.title}" else "Экспорт", body)
    }

    /** Пояснение без редактора (извлечение): короткое описание по выбранным параметрам. */
    private fun defaultLines(f: ExportFormat, r: ExportRequest): List<String> {
        val out = ArrayList<String>()
        when (f) {
            ExportFormat.M4A -> out += "AAC-LC, ${r.bitrateKbps ?: 192} кбит/с" + (r.sampleRate?.let { ", ${Format.kHz(it)}" } ?: ", частота как в исходнике")
            ExportFormat.OPUS -> out += "Opus, ${r.bitrateKbps ?: 128} кбит/с, 48 кГц"
            ExportFormat.FLAC -> out += "FLAC без потерь, " + (r.bits?.let { "$it бит" } ?: "разрядность как в исходнике")
            ExportFormat.WAV -> out += "WAV без сжатия, " + (r.bits?.let { if (it == 32) "32 бит float" else "$it бит" } ?: "разрядность как в исходнике")
        }
        if (f == ExportFormat.M4A || f == ExportFormat.OPUS) out += "Повторное сжатие немного снижает качество. Для архива выберите FLAC или WAV."
        return out
    }
}

// ====================================================================== Быстрая обрезка (FR-23)
internal object FastTrimSheet {
    fun show(host: Host, e: EditorController) {
        val c = host.activity
        val p = Ui.colors
        val preview = e.fastTrimPreview()
        if (preview == null) { host.toast("Быстрая обрезка сейчас недоступна: нужна одна обрезка начала и/или конца без других правок."); return }
        val body = Scaffold.sheetBody(c)
        lateinit var dialog: Dialog
        body.addView(Kit.text(c, "Звук копируется без перекодирования, поэтому границы округляются до кадров сжатого потока. Выделенное не потеряется.",
            Tokens.TEXT_BODY, p.textSecondary).apply { setLineSpacing(0f, 1.2f) })
        body.addView(Kit.heading(c, "ФАКТИЧЕСКИЕ ГРАНИЦЫ").apply { setPadding(c.dp(4), c.dp(16), 0, c.dp(8)) })
        body.addView(Kit.vertical(c).apply {
            background = Ui.roundRect(c, if (p.isDark) p.bg else p.surface, Tokens.RADIUS_M, p.outline, 1f)
            setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            addView(Kit.text(c, preview, Tokens.TEXT_BODY, p.text).apply { setLineSpacing(0f, 1.2f); setTextIsSelectable(true) })
        })
        body.addView(Kit.notice(c, Kit.Tone.INFO, "Нужна точность до сэмпла? Используйте обычный экспорт; для архива выберите FLAC/WAV."),
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))
        body.addView(Scaffold.wideButton(c, "Сохранить без потерь") { dialog.dismiss(); host.beforeSave { e.fastTrim() } })
        body.addView(Scaffold.wideButton(c, "Отмена", primary = false) { dialog.dismiss() })
        dialog = Ui.showSheet(c, "Быстро, без потерь", body)
    }
}
