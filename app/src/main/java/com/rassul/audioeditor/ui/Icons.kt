package com.rassul.audioeditor.ui

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/*
 * Набор иконок: векторные пути в сетке 24×24, обводка 2 (в единицах сетки), скругления
 * в духе Material Symbols Rounded / Lucide. Пути хранятся строками SVG path data —
 * их же читает HTML-модель (docs/design/model.py) для визуальной проверки.
 *
 * Префиксы строк:  "F:" — только заливка,  "B:" — заливка + обводка,  без префикса — только обводка.
 */
internal object IconPaths {
    private const val GEAR =
        "M9.83 5.13L10.35 2.64L13.65 2.64L14.17 5.13L15.32 5.61L17.45 4.22L19.78 6.55L18.39 8.68L18.87 9.83" +
            "L21.36 10.35L21.36 13.65L18.87 14.17L18.39 15.32L19.78 17.45L17.45 19.78L15.32 18.39L14.17 18.87" +
            "L13.65 21.36L10.35 21.36L9.83 18.87L8.68 18.39L6.55 19.78L4.22 17.45L5.61 15.32L5.13 14.17" +
            "L2.64 13.65L2.64 10.35L5.13 9.83L5.61 8.68L4.22 6.55L6.55 4.22L8.68 5.61Z"
    private const val DOC = "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"
    private const val DOC_FOLD = "M14 3v4a1 1 0 0 0 1 1h4"
    private const val CIRCLE = "M21 12a9 9 0 1 1-18 0a9 9 0 1 1 18 0Z"

    val paths: Map<Icon, List<String>> = mapOf(
        Icon.BACK to listOf("M19 12H5", "M11.5 18.5 5 12l6.5-6.5"),
        Icon.CLOSE to listOf("M18 6 6 18", "M6 6l12 12"),
        Icon.MORE to listOf(
            "F:M12 3.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
            "F:M12 10.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
            "F:M12 17.25a1.75 1.75 0 1 1 0 3.5a1.75 1.75 0 1 1 0-3.5Z",
        ),
        Icon.SETTINGS to listOf(GEAR, "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z"),
        Icon.CHECK to listOf("M4.5 12.5l5 5L19.5 7"),
        Icon.CHEVRON_RIGHT to listOf("M9 6l6 6-6 6"),
        Icon.CHEVRON_DOWN to listOf("M6 9l6 6 6-6"),
        Icon.CHEVRON_UP to listOf("M6 15l6-6 6 6"),
        Icon.VIDEO to listOf(
            "M4.5 5.5h9A2.5 2.5 0 0 1 16 8v8a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 2 16V8a2.5 2.5 0 0 1 2.5-2.5Z",
            "M16 10.2l4.6-2.7a.6.6 0 0 1 .9.5v8a.6.6 0 0 1-.9.5L16 13.8",
        ),
        Icon.FOLDER to listOf(
            "M3 17.5V6.5A2.5 2.5 0 0 1 5.5 4h3.2a2 2 0 0 1 1.6.8L11.5 6.5h7A2.5 2.5 0 0 1 21 9v8.5a2.5 2.5 0 0 1-2.5 2.5h-13A2.5 2.5 0 0 1 3 17.5Z",
        ),
        Icon.LIBRARY to listOf(
            "M4 6.5h10", "M4 11.5h7", "M4 16.5h5",
            "M19 17.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0Z", "M19 17.5V5.5", "M19 5.5l2.5 1.5",
        ),
        Icon.PLAY to listOf("B:M7.5 5v14l12-7Z"),
        Icon.PAUSE to listOf("B:M7 5h3v14H7Z", "B:M14 5h3v14h-3Z"),
        Icon.LOOP to listOf("M17 2.5l4 4-4 4", "M3 11V9.5a3 3 0 0 1 3-3h15", "M7 21.5l-4-4 4-4", "M21 12.5V14a3 3 0 0 1-3 3H3"),
        Icon.SKIP_START to listOf("M5.5 5v14", "B:M18.5 6v12L9 12Z"),
        Icon.UNDO to listOf("M9 14.5 4 9.5l5-5", "M4 9.5h10.5a5.25 5.25 0 0 1 0 10.5H11"),
        Icon.REDO to listOf("M15 14.5l5-5-5-5", "M20 9.5H9.5a5.25 5.25 0 0 0 0 10.5H13"),
        Icon.ZOOM_IN to listOf("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0Z", "M20.5 20.5l-4.5-4.5", "M11 8v6", "M8 11h6"),
        Icon.ZOOM_OUT to listOf("M18 11a7 7 0 1 1-14 0a7 7 0 1 1 14 0Z", "M20.5 20.5l-4.5-4.5", "M8 11h6"),
        Icon.FIT_ALL to listOf("M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5"),
        Icon.KEEP to listOf("M6 2.5v13.5a2 2 0 0 0 2 2h13.5", "M18 21.5V8a2 2 0 0 0-2-2H2.5"),
        Icon.VOLUME to listOf("M11 5 6.5 9H3v6h3.5L11 19Z", "M15.5 8.5a5 5 0 0 1 0 7", "M18.8 5.2a9.6 9.6 0 0 1 0 13.6"),
        Icon.FADE_IN to listOf("M3 19h18", "M3 19 19 5v14"),
        Icon.FADE_OUT to listOf("M3 19h18", "M21 19 5 5v14"),
        Icon.NORMALIZE to listOf("M4 4.5h16", "M7 20v-8", "M12 20V8", "M17 20v-5"),
        Icon.BOLT to listOf("B:M13 2.5 4.5 14H11l-1 7.5L19.5 10H13Z"),
        Icon.EXPORT to listOf("M12 15V3.5", "M7.5 8 12 3.5 16.5 8", "M5 13.5V18a3 3 0 0 0 3 3h8a3 3 0 0 0 3-3v-4.5"),
        Icon.SHARE to listOf(
            "M21 5a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z", "M9 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z", "M21 19a3 3 0 1 1-6 0a3 3 0 1 1 6 0Z",
            "M8.6 13.5l6.8 4", "M15.4 6.5l-6.8 4",
        ),
        Icon.OPEN_EDITOR to listOf("M2.5 12h3l2.2-6.5 4 13 3-9.5 1.6 3H21.5"),
        Icon.DELETE to listOf(
            "M4 6.5h16",
            "M9 6.5V5a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 5v1.5",
            "M6 6.5l.9 12.2a2 2 0 0 0 2 1.8h6.2a2 2 0 0 0 2-1.8L18 6.5",
            "M10 11v5", "M14 11v5",
        ),
        Icon.INFO to listOf(CIRCLE, "M12 11v5.5", "F:M12 6.9a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4Z"),
        Icon.WARNING to listOf(
            "M10.3 4.3 2.6 17.6a2 2 0 0 0 1.7 3h15.4a2 2 0 0 0 1.7-3L13.7 4.3a2 2 0 0 0-3.4 0Z",
            "M12 9.5v4.5", "F:M12 16.2a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4Z",
        ),
        Icon.ERROR to listOf(CIRCLE, "M12 7.5v5.5", "F:M12 14.8a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4Z"),
        Icon.AUDIO_FILE to listOf(DOC, DOC_FOLD, "M11.5 17a1.5 1.5 0 1 1-3 0a1.5 1.5 0 1 1 3 0Z", "M11.5 17v-5.5l3 1"),
        Icon.COPY to listOf(
            "M10.5 8.5h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2Z",
            "M15.5 8.5V5.5a2 2 0 0 0-2-2h-8a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h3",
        ),
        Icon.STETHOSCOPE to listOf("M2.5 12h4.5l2.5-6.5 4 13 2.5-6.5h5.5"),
    )
}

/** Drawable, рисующий иконку из [IconPaths]. Внутренний размер — 24dp. */
class IconDrawable(icon: Icon, tint: Int, private val sizePx: Int) : Drawable() {
    private class Part(val path: Path, val fill: Boolean, val stroke: Boolean)

    private val parts: List<Part> = (IconPaths.paths[icon] ?: emptyList()).map { s ->
        when {
            s.startsWith("F:") -> Part(SvgPath.parse(s.substring(2)), fill = true, stroke = false)
            s.startsWith("B:") -> Part(SvgPath.parse(s.substring(2)), fill = true, stroke = true)
            else -> Part(SvgPath.parse(s), fill = false, stroke = true)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = tint
    }
    private val scaled = Path()
    private val m = Matrix()
    private var baseAlpha = android.graphics.Color.alpha(tint)
    private var drawAlpha = 255

    var color: Int
        get() = paint.color
        set(v) {
            baseAlpha = android.graphics.Color.alpha(v)
            paint.color = v
            paint.alpha = baseAlpha * drawAlpha / 255
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val side = minOf(b.width(), b.height()).toFloat()
        val s = side / 24f
        m.setScale(s, s)
        m.postTranslate(b.left + (b.width() - side) / 2f, b.top + (b.height() - side) / 2f)
        paint.strokeWidth = 2f * s
        for (p in parts) {
            p.path.transform(m, scaled)
            if (p.fill) { paint.style = Paint.Style.FILL; canvas.drawPath(scaled, paint) }
            if (p.stroke) { paint.style = Paint.Style.STROKE; canvas.drawPath(scaled, paint) }
        }
    }

    override fun setTintList(tint: ColorStateList?) {
        if (tint != null) color = tint.getColorForState(state, tint.defaultColor)
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha
        paint.alpha = baseAlpha * alpha / 255
        invalidateSelf()
    }

    override fun getAlpha(): Int = drawAlpha
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx
}
