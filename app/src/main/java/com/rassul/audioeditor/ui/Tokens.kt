package com.rassul.audioeditor.ui

import android.graphics.Color
import android.graphics.Typeface
import android.view.animation.PathInterpolator

/*
 * Дизайн-токены. Источник истины — docs/design.md (там же расчёт контраста WCAG AA).
 * Меняете значение здесь — перезапустите docs/design/contrast.py и обновите таблицу в design.md.
 */
object Tokens {
    val DARK = Palette(
        bg = 0xFF0E1014.toInt(),            // = bg_start в res/values/themes.xml
        surface = 0xFF161A20.toInt(),
        surfaceHigh = 0xFF1E242C.toInt(),
        primary = 0xFF5EE0C2.toInt(),       // акцент приложения
        onPrimary = 0xFF04201A.toInt(),
        text = 0xFFE8EDF1.toInt(),
        textSecondary = 0xFF9AA6B2.toInt(),
        outline = 0xFF313A44.toInt(),
        danger = 0xFFFF7A7A.toInt(),
        warning = 0xFFFFC857.toInt(),
        waveform = 0xFF5EE0C2.toInt(),
        waveBg = 0xFF0A0C0F.toInt(),
        overload = 0xFFFF5C5C.toInt(),
        scrim = 0x99000000.toInt(),
        isDark = true,
    )

    val LIGHT = Palette(
        bg = 0xFFF2F5F7.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceHigh = 0xFFFFFFFF.toInt(),
        primary = 0xFF006B5A.toInt(),
        onPrimary = 0xFFFFFFFF.toInt(),
        text = 0xFF12181D.toInt(),
        textSecondary = 0xFF4A5762.toInt(),
        outline = 0xFFD2DAE0.toInt(),
        danger = 0xFFC42B2B.toInt(),
        warning = 0xFF8A5A00.toInt(),
        waveform = 0xFF006B5A.toInt(),
        waveBg = 0xFFE6ECEF.toInt(),
        overload = 0xFFD42A2A.toInt(),
        scrim = 0x66000000,
        isDark = false,
    )

    // Отступы (dp)
    const val SPACE_XS = 4
    const val SPACE_S = 8
    const val SPACE_M = 12
    const val SPACE_L = 16
    const val SPACE_XL = 24
    const val SPACE_XXL = 32

    // Радиусы (dp)
    const val RADIUS_S = 8f
    const val RADIUS_M = 12f      // поля ввода, чипы
    const val RADIUS_L = 20f      // карточки, панели
    const val RADIUS_XL = 28f     // нижние листы, диалоги
    const val RADIUS_FULL = 999f  // «пилюли»

    // Высоты (тени, dp)
    const val ELEV_CARD = 2f
    const val ELEV_FAB = 6f
    const val ELEV_PANEL = 8f
    const val ELEV_SHEET = 16f

    // Типографика (sp)
    const val TEXT_DISPLAY = 28f
    const val TEXT_TITLE = 20f
    const val TEXT_SUBTITLE = 16f
    const val TEXT_BODY = 15f
    const val TEXT_LABEL = 13f
    const val TEXT_CAPTION = 12f
    /** Крупные числовые значения (время, результат) — моноширинные цифры. */
    const val TEXT_VALUE = 24f

    // Длительности (мс). Системная настройка «Убрать анимации» обнуляет их автоматически (ValueAnimator).
    const val DUR_FAST = 120L
    const val DUR_STANDARD = 200L
    const val DUR_ENTER = 280L
    const val DUR_EXIT = 200L
    const val DUR_SNACK_SHOW = 2800L
    const val DUR_SNACK_ACTION = 5000L
    const val STAGGER = 35L

    /** Стандартная кривая (M3 standard). */
    val EASE_STANDARD get() = PathInterpolator(0.2f, 0f, 0f, 1f)
    /** Появление (M3 emphasized decelerate). */
    val EASE_ENTER get() = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    /** Уход (M3 emphasized accelerate). */
    val EASE_EXIT get() = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    val FONT_REGULAR: Typeface = Typeface.create(Typeface.DEFAULT, 400, false)
    val FONT_MEDIUM: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)
    val FONT_BOLD: Typeface = Typeface.create(Typeface.DEFAULT, 650, false)
    /** Моноширинный для времени и отчётов (цифры не «прыгают»). */
    val FONT_MONO: Typeface = Typeface.create(Typeface.MONOSPACE, 500, false)

    fun withAlpha(color: Int, alpha: Float): Int =
        Color.argb((Color.alpha(color) * alpha).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    /** Смешивание fg поверх bg с непрозрачностью a. */
    fun blend(fg: Int, bg: Int, a: Float): Int = Color.rgb(
        (Color.red(fg) * a + Color.red(bg) * (1 - a)).toInt(),
        (Color.green(fg) * a + Color.green(bg) * (1 - a)).toInt(),
        (Color.blue(fg) * a + Color.blue(bg) * (1 - a)).toInt(),
    )

    /** «Контейнер» акцента: подложка выбранных элементов. */
    fun primaryContainer(p: Palette): Int = blend(p.primary, p.surfaceHigh, 0.14f)

    /** Подложка предупреждения (notices). */
    fun warningContainer(p: Palette): Int = blend(p.warning, p.surface, 0.14f)

    /** Подложка ошибки. */
    fun dangerContainer(p: Palette): Int = blend(p.danger, p.surface, 0.14f)

    /** Цвет ряби. */
    fun ripple(p: Palette): Int = if (p.isDark) 0x33FFFFFF else 0x1F000000
}
