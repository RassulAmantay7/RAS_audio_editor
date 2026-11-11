package com.rassul.audioeditor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.rassul.audioeditor.core.pipeline.ExportFormat
import com.rassul.audioeditor.editor.ExtractOption
import com.rassul.audioeditor.editor.Screen
import kotlin.math.abs
import kotlin.math.sin

/** Декоративная «волна» главной: столбики с градиентом акцента (рисуется кодом). */
internal class WaveHero(ctx: Context) : View(ctx) {
    private val p = Ui.colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var shader: LinearGradient? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.dp(context, 96))
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(Tokens.withAlpha(p.primary, 0.25f), p.primary, Tokens.withAlpha(p.primary, 0.25f)), null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        paint.shader = shader
        val step = Ui.dp(context, 7).toFloat()
        val bw = Ui.dp(context, 4).toFloat()
        val n = (width / step).toInt()
        val cy = height / 2f
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val a = (0.18 + 0.82 * abs(sin(t * 9.0) * sin(t * 2.3 + 0.7)) * (0.55 + 0.45 * sin(t * 31.0 + 1.0))).toFloat()
            val hh = (height * 0.92f * a).coerceAtLeast(bw)
            r.set(i * step + (step - bw) / 2, cy - hh / 2, i * step + (step + bw) / 2, cy + hh / 2)
            canvas.drawRoundRect(r, bw / 2, bw / 2, paint)
        }
    }
}

internal class HomeScreen(host: Host) : ScreenView(host) {
    override val root: View

    init {
        val c = ctx
        val col = Scaffold.padded(Kit.vertical(c), c, 16, 0, 24)

        val head = Kit.horizontal(c)
        val titles = Kit.vertical(c)
        titles.addView(Kit.text(c, "Аудиоредактор", Tokens.TEXT_DISPLAY, Ui.colors.text, Tokens.FONT_BOLD).apply { isAccessibilityHeading = true })
        titles.addView(Kit.text(c, "Звук из видео и правка записей", Tokens.TEXT_BODY, Ui.colors.textSecondary))
        head.addView(titles, lp(0, Kit.WRAP, 1f))
        head.addView(Ui.iconButton(c, Icon.SETTINGS, "Настройки") { host.showSettings() })
        head.setPadding(c.dp(4), c.dp(16), 0, c.dp(8))
        col.addView(head, lp(Kit.MATCH, Kit.WRAP))

        col.addView(WaveHero(c), lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, c.dp(20)))

        col.addView(Kit.tile(c, Icon.VIDEO, "Видео из галереи", "Достать звук из ролика или отредактировать его", true) {
            host.controller.pickVideo(host.activity)
        }, lp(Kit.MATCH, Kit.WRAP))
        col.addView(Kit.tile(c, Icon.FOLDER, "Файл", "Аудио или видео из «Файлов»", false) {
            host.controller.pickAudioOrVideoFile(host.activity)
        }, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))

        val n = try { host.controller.savedFiles().size } catch (_: Exception) { 0 }
        val sub = if (n == 0) "Здесь появятся сохранённые файлы" else "$n ${Format.plural(n.toLong(), "файл", "файла", "файлов")}"
        col.addView(Kit.tile(c, Icon.LIBRARY, "Сохранённые", sub, false, trailingChevron = true) { host.showSaved() },
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))

        col.addView(Kit.notice(c, Kit.Tone.INFO, "Можно и так: в галерее нажмите «Поделиться» и выберите это приложение."),
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(20), 0, 0))
        root = Scaffold.scrollColumn(c, col)
    }
}

internal class ProbingScreen(host: Host, private var s: Screen.Probing) : ScreenView(host) {
    override val root: View

    init {
        val c = ctx
        val col = Kit.vertical(c).apply { gravity = Gravity.CENTER; setPadding(c.dp(32), c.dp(32), c.dp(32), c.dp(32)) }
        col.addView(SpinnerView(c))
        col.addView(Kit.text(c, "Анализируем файл…", Tokens.TEXT_TITLE, Ui.colors.text, Tokens.FONT_MEDIUM).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(20), 0, c.dp(4)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        })
        col.addView(Kit.text(c, s.sourceName, Tokens.TEXT_BODY, Ui.colors.textSecondary).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(Ui.button(c, "Отмена", primary = false) { host.controller.goHome() }, lp(Kit.WRAP, Kit.WRAP).margins(0, c.dp(24), 0, 0))
        root = col
    }
}

internal class DecodingScreen(host: Host, s: Screen.Decoding) : ScreenView(host) {
    override val root: View
    private val pct: TextView
    private val line = ProgressLine(ctx)

    init {
        val c = ctx
        val col = Kit.vertical(c).apply { gravity = Gravity.CENTER; setPadding(c.dp(32), c.dp(32), c.dp(32), c.dp(32)) }
        col.addView(Kit.text(c, "Готовим звук для редактора", Tokens.TEXT_TITLE, Ui.colors.text, Tokens.FONT_MEDIUM).apply { gravity = Gravity.CENTER })
        col.addView(Kit.text(c, s.sourceName, Tokens.TEXT_BODY, Ui.colors.textSecondary).apply {
            gravity = Gravity.CENTER; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; setPadding(0, c.dp(4), 0, c.dp(24))
        })
        pct = Kit.text(c, "", 40f, Ui.colors.primary, Tokens.FONT_MONO).apply { gravity = Gravity.CENTER }
        col.addView(pct, lp(Kit.MATCH, Kit.WRAP))
        col.addView(line, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(16), 0, c.dp(16)))
        col.addView(Kit.text(c, "Файл раскодируется во временную копию на телефоне. Исходный файл не меняется.", Tokens.TEXT_LABEL, Ui.colors.textSecondary)
            .apply { gravity = Gravity.CENTER })
        col.addView(Ui.button(c, "Отмена", primary = false) { host.controller.goHome() }, lp(Kit.WRAP, Kit.WRAP).margins(0, c.dp(24), 0, 0))
        val box = android.widget.FrameLayout(c)
        box.addView(col, android.widget.FrameLayout.LayoutParams(minOf(c.resources.displayMetrics.widthPixels, c.dp(480)), -2, Gravity.CENTER))
        root = box
        apply(s.progress)
    }

    private fun apply(p: Double) {
        pct.text = "${(p * 100).toInt().coerceIn(0, 100)} %"
        line.progress = p.toFloat()
        pct.contentDescription = "Готово ${(p * 100).toInt()} процентов"
    }

    override fun update(s: Screen): Boolean {
        if (s !is Screen.Decoding) return false
        apply(s.progress); return true
    }
}

internal class ErrorScreen(host: Host, s: Screen.Error) : ScreenView(host) {
    override val root: View

    init {
        val c = ctx
        val p = Ui.colors
        val col = Kit.vertical(c).apply { gravity = Gravity.CENTER; setPadding(c.dp(32), c.dp(32), c.dp(32), c.dp(32)) }
        val badge = LinearLayout(c).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Tokens.dangerContainer(p)) }
            addView(Kit.icon(c, Icon.ERROR, p.danger, 40))
        }
        col.addView(badge, lp(c.dp(80), c.dp(80)))
        col.addView(Kit.text(c, s.title, Tokens.TEXT_TITLE, p.text, Tokens.FONT_MEDIUM).apply {
            gravity = Gravity.CENTER; setPadding(0, c.dp(20), 0, c.dp(8)); isAccessibilityHeading = true
        })
        col.addView(Kit.text(c, s.message, Tokens.TEXT_BODY, p.textSecondary).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f); setTextIsSelectable(true)
        })
        col.addView(Ui.button(c, "На главную") { host.controller.goHome() }, lp(Kit.WRAP, c.dp(52)).margins(0, c.dp(24), 0, 0))
        val box = android.widget.FrameLayout(c)
        box.addView(col, android.widget.FrameLayout.LayoutParams(minOf(c.resources.displayMetrics.widthPixels, c.dp(480)), -2, Gravity.CENTER))
        root = box
    }
}

/** Радио-точка списка дорожек. */
private class RadioDot(ctx: Context, var checked: Boolean) : View(ctx) {
    private val p = Ui.colors
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) { val s = Ui.dp(context, 24); setMeasuredDimension(s, s) }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(context, 2).toFloat()
        paint.color = if (checked) p.primary else p.textSecondary
        canvas.drawCircle(cx, cy, Ui.dp(context, 9).toFloat(), paint)
        if (checked) { paint.style = Paint.Style.FILL; canvas.drawCircle(cx, cy, Ui.dp(context, 5).toFloat(), paint) }
    }
}

/** Извлечение: дорожки (FR-5, FR-9), главная кнопка (FR-6a), «Другой формат…», «Открыть в редакторе». */
internal class ExtractScreen(host: Host, private var s: Screen.Extract) : ScreenView(host) {
    override val root: View
    private val body = Kit.vertical(ctx)

    init {
        val c = ctx
        Scaffold.padded(body, c, 16, 4, 24)
        val sv = Scaffold.scrollColumn(c, body)
        root = Scaffold.page(host, s.sourceName, if (s.isVideo) "Видео" else "Аудиофайл", { host.controller.goHome() }, emptyList(), sv)
        populate()
    }

    override fun update(s: Screen): Boolean {
        if (s !is Screen.Extract) return false
        this.s = s; populate(); return true
    }

    private fun codecOf(title: String): String? {
        val known = listOf("E-AC-3", "AC-3", "AAC", "MP3", "Opus", "Vorbis", "FLAC", "PCM", "AMR", "DTS")
        return title.split(" · ").firstOrNull { it in known }
    }

    private fun populate() {
        val c = ctx
        val p = Ui.colors
        body.removeAllViews()
        val track = s.tracks.getOrNull(s.selected)
        val usable = track?.decodable != false

        body.addView(Kit.heading(c, if (s.tracks.size > 1) "ЗВУКОВАЯ ДОРОЖКА" else "ЗВУК").apply { setPadding(c.dp(4), c.dp(8), 0, c.dp(8)) })
        if (s.tracks.isEmpty()) {
            body.addView(Kit.notice(c, Kit.Tone.DANGER, "В этом файле нет звука."))
        }
        s.tracks.forEach { t ->
            val multi = s.tracks.size > 1
            val sel = multi && t.index == s.selected
            val row = Kit.horizontal(c, center = false)
            row.minimumHeight = c.dp(64)
            row.setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
            row.background = Ui.ripple(c,
                if (sel) Ui.roundRect(c, Tokens.primaryContainer(p), Tokens.RADIUS_L, p.primary, 1.5f)
                else Ui.roundRect(c, p.surface, Tokens.RADIUS_L, if (p.isDark) 0x14FFFFFF else p.outline, 1f),
                oval = false, radiusDp = Tokens.RADIUS_L)
            row.isClickable = multi
            row.isFocusable = multi
            if (multi) {
                row.addView(RadioDot(c, sel), lp(c.dp(24), c.dp(24)).margins(0, c.dp(2), c.dp(14), 0))
                row.setOnClickListener { if (t.index != s.selected) host.controller.selectTrack(t.index) }
                row.stateDescription = if (sel) "Выбрано" else "Не выбрано"
            } else row.addView(Kit.icon(c, Icon.AUDIO_FILE, p.primary), lp(c.dp(24), c.dp(24)).margins(0, c.dp(2), c.dp(14), 0))
            val col = Kit.vertical(c)
            col.addView(Kit.text(c, t.title, Tokens.TEXT_BODY, p.text, Tokens.FONT_MEDIUM).apply { setLineSpacing(0f, 1.15f) })
            if (t.isDefault && multi) col.addView(Kit.text(c, "по умолчанию", Tokens.TEXT_CAPTION, p.textSecondary).apply { setPadding(0, c.dp(2), 0, 0) })
            if (!t.decodable && t.reason != null) col.addView(Kit.notice(c, Kit.Tone.WARNING, t.reason, boxed = false),
                lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(8), 0, 0))
            row.addView(col, lp(0, Kit.WRAP, 1f))
            body.addView(row, lp(Kit.MATCH, Kit.WRAP).margins(0, 0, 0, c.dp(8)))
        }

        // главное действие (FR-6a)
        body.addView(Kit.heading(c, "СОХРАНИТЬ ЗВУК").apply { setPadding(c.dp(4), c.dp(16), 0, c.dp(8)) })
        val pr = s.primary
        val big = Kit.tile(c, Icon.EXPORT, pr.title, pr.subtitle, true) { host.beforeSave { host.controller.extractPrimary() } }
        big.alpha = if (pr.enabled) 1f else 0.4f
        big.isEnabled = pr.enabled
        if (!pr.enabled) big.setOnClickListener(null)
        body.addView(big, lp(Kit.MATCH, Kit.WRAP))
        if (pr.hint != null) body.addView(Kit.notice(c, Kit.Tone.INFO, pr.hint), lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))

        body.addView(Kit.tile(c, Icon.MORE, "Другой формат…", "Как есть, M4A, Opus, FLAC или WAV", false, trailingChevron = true) {
            chooseFormat(track, usable)
        }, lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))

        body.addView(Kit.heading(c, "РЕДАКТИРОВАНИЕ").apply { setPadding(c.dp(4), c.dp(20), 0, c.dp(8)) })
        val ed = Kit.tile(c, Icon.OPEN_EDITOR, "Открыть в редакторе", "Обрезать, убрать лишнее, выровнять громкость", false, trailingChevron = true) {
            host.controller.openInEditor()
        }
        if (!usable || s.tracks.isEmpty()) {
            ed.alpha = 0.4f; ed.setOnClickListener(null); ed.isClickable = false
            ed.contentDescription = "Открыть в редакторе. Недоступно: этот телефон не умеет раскодировать выбранную дорожку"
        }
        body.addView(ed, lp(Kit.MATCH, Kit.WRAP))
        if (!usable) body.addView(Kit.notice(c, Kit.Tone.WARNING, "Редактор и перекодирование недоступны для этой дорожки: её можно только сохранить без изменений."),
            lp(Kit.MATCH, Kit.WRAP).margins(0, c.dp(12), 0, 0))
    }

    private fun chooseFormat(track: com.rassul.audioeditor.editor.TrackItem?, usable: Boolean) {
        val a = host.activity
        val codec = track?.let { codecOf(it.title) }
        val items = ArrayList<SheetItem>()
        items += SheetItem("Как есть" + (if (codec != null) " ($codec, без потерь)" else " (без потерь)"), Icon.COPY,
            "Копирование без перекодирования, исходный кодек") { host.beforeSave { host.controller.extractAs(ExtractOption.AS_IS, null) } }
        if (usable) {
            fun add(t: String, sub: String, opt: ExtractOption, f: ExportFormat) {
                items += SheetItem(t, Icon.EXPORT, sub) {
                    ExportSheet.show(host, f, null) { req -> host.beforeSave { host.controller.extractAs(opt, req) } }
                }
            }
            add("M4A (AAC)", "Рекомендуется: открывается везде", ExtractOption.M4A, ExportFormat.M4A)
            add("Opus", "Мало места, хорошо для речи", ExtractOption.OPUS, ExportFormat.OPUS)
            add("FLAC", "Без потерь, меньше WAV", ExtractOption.FLAC, ExportFormat.FLAC)
            add("WAV", "Без сжатия, для архива и студии", ExtractOption.WAV, ExportFormat.WAV)
        }
        Ui.showListSheet(a, "Другой формат", items)
    }
}
