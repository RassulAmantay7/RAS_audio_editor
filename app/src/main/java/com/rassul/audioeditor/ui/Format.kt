package com.rassul.audioeditor.ui

import java.util.Calendar

/** Форматирование для интерфейса (русский язык). */
object Format {
    private val MONTHS = arrayOf("янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек.")

    /** Русское множественное число: plural(3, "день", "дня", "дней") → "дня". */
    fun plural(n: Long, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1L -> one
            b in 2..4 -> few
            else -> many
        }
    }

    /** «только что», «5 минут назад», «сегодня, 14:05», «вчера», «3 дня назад», «12 окт.», «12 окт. 2025». */
    fun relativeTime(t: Long, now: Long = System.currentTimeMillis()): String {
        if (t <= 0) return ""
        val diff = now - t
        if (diff < 60_000L) return "только что"
        if (diff < 3_600_000L) {
            val m = diff / 60_000L
            return "$m ${plural(m, "минуту", "минуты", "минут")} назад"
        }
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val days = dayIndex(today) - dayIndex(c)
        return when {
            days <= 0 -> "сегодня, %02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            days == 1L -> "вчера"
            days < 7 -> "$days ${plural(days, "день", "дня", "дней")} назад"
            c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]}"
            else -> "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS[c.get(Calendar.MONTH)]} ${c.get(Calendar.YEAR)}"
        }
    }

    private fun dayIndex(c: Calendar): Long {
        val local = c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET)
        return Math.floorDiv(local, 86_400_000L)
    }

    /** Число с десятичной запятой: num(-3.0, 1) → «−3,0» (знак минус — настоящий). */
    fun num(v: Double, digits: Int): String {
        val t = String.format(java.util.Locale.US, "%.${digits}f", v).replace('.', ',')
        return if (t.startsWith("-")) "\u2212" + t.substring(1) else t
    }

    /** Дробь со знаком для дБ: «+6,0 дБ», «−3,5 дБ», «0,0 дБ». */
    fun db(v: Double): String {
        val r = Math.round(v * 10.0) / 10.0
        return (if (r > 0) "+" else "") + num(r, 1) + " дБ"
    }

    /** «12,4 МБ», «850 КБ», «1,20 ГБ». */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> num(bytes / 1073741824.0, 2) + " ГБ"
        bytes >= 1L shl 20 -> num(bytes / 1048576.0, 1) + " МБ"
        bytes >= 1L shl 10 -> (bytes shr 10).toString() + " КБ"
        else -> "$bytes Б"
    }

    /** «30:00», «1:02:03» (для списков). */
    fun duration(ms: Long): String {
        val t = (ms.coerceAtLeast(0) + 500) / 1000
        val h = t / 3600
        val m = (t / 60) % 60
        val s = t % 60
        return if (h > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(java.util.Locale.ROOT, "%d:%02d", m, s)
    }

    /** «48 кГц», «44,1 кГц». */
    fun kHz(rate: Int): String {
        val k = rate / 1000.0
        return (if (rate % 1000 == 0) (rate / 1000).toString() else num(k, 1)) + " кГц"
    }

    fun channels(n: Int): String = when (n) { 1 -> "моно"; 2 -> "стерео"; else -> "$n канала" }
}
