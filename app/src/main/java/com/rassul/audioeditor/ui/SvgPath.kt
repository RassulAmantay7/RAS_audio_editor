package com.rassul.audioeditor.ui

import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Маленький разборщик SVG path data → android.graphics.Path.
 * Поддерживает M L H V C S Q T A Z (абсолютные и относительные). Дуги переводятся в кривые Безье.
 */
internal object SvgPath {
    private val cache = HashMap<String, Path>()

    fun parse(d: String): Path = synchronized(cache) { cache.getOrPut(d) { build(d) } }

    private class Tok(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\t')) i++ }
        fun hasNumber(): Boolean { skip(); return i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.') }
        fun num(): Float {
            skip()
            val st = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++; if (s[i] == '-' || s[i] == '+') i++
                } else break
            }
            return s.substring(st, i).toFloat()
        }
        fun flag(): Boolean { skip(); val c = s[i]; i++; return c == '1' }
    }

    private fun build(d: String): Path {
        val p = Path()
        val t = Tok(d)
        var cx = 0f; var cy = 0f        // текущая точка
        var sx = 0f; var sy = 0f        // начало подпути
        var lcx = 0f; var lcy = 0f      // последняя контрольная точка
        var prev = ' '
        var cmd = ' '
        while (true) {
            t.skip()
            if (t.i >= d.length) break
            val c = d[t.i]
            if (c.isLetter() && c != 'e' && c != 'E') { cmd = c; t.i++ } else if (cmd == ' ') break
            val rel = cmd.isLowerCase()
            val ox = if (rel) cx else 0f
            val oy = if (rel) cy else 0f
            when (cmd.uppercaseChar()) {
                'M' -> {
                    cx = ox + t.num(); cy = oy + t.num(); p.moveTo(cx, cy); sx = cx; sy = cy
                    cmd = if (rel) 'l' else 'L' // последующие пары — lineTo
                    prev = 'M'; continue
                }
                'L' -> { cx = ox + t.num(); cy = oy + t.num(); p.lineTo(cx, cy) }
                'H' -> { cx = ox + t.num(); p.lineTo(cx, cy) }
                'V' -> { cy = oy + t.num(); p.lineTo(cx, cy) }
                'C' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    val x = ox + t.num(); val y = oy + t.num()
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'S' -> {
                    val x1 = if (prev == 'C' || prev == 'S') 2 * cx - lcx else cx
                    val y1 = if (prev == 'C' || prev == 'S') 2 * cy - lcy else cy
                    val x2 = ox + t.num(); val y2 = oy + t.num()
                    val x = ox + t.num(); val y = oy + t.num()
                    p.cubicTo(x1, y1, x2, y2, x, y); lcx = x2; lcy = y2; cx = x; cy = y
                }
                'Q' -> {
                    val x1 = ox + t.num(); val y1 = oy + t.num()
                    val x = ox + t.num(); val y = oy + t.num()
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'T' -> {
                    val x1 = if (prev == 'Q' || prev == 'T') 2 * cx - lcx else cx
                    val y1 = if (prev == 'Q' || prev == 'T') 2 * cy - lcy else cy
                    val x = ox + t.num(); val y = oy + t.num()
                    p.quadTo(x1, y1, x, y); lcx = x1; lcy = y1; cx = x; cy = y
                }
                'A' -> {
                    val rx = t.num(); val ry = t.num(); val rot = t.num()
                    val large = t.flag(); val sweep = t.flag()
                    val x = ox + t.num(); val y = oy + t.num()
                    arcTo(p, cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y
                }
                'Z' -> { p.close(); cx = sx; cy = sy; prev = 'Z'; continue }
                else -> break
            }
            prev = cmd.uppercaseChar()
            if (!t.hasNumber()) continue
        }
        return p
    }

    /** Дуга SVG (эллиптическая, по конечным точкам) → кубические кривые. */
    private fun arcTo(
        p: Path, x1: Float, y1: Float, rxIn: Float, ryIn: Float, angleDeg: Float,
        large: Boolean, sweep: Boolean, x2: Float, y2: Float,
    ) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { p.lineTo(x2, y2); return }
        val phi = angleDeg * PI / 180
        val cphi = cos(phi); val sphi = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cphi * dx + sphi * dy
        val y1p = -sphi * dx + cphi * dy
        val lam = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lam > 1) { val s = sqrt(lam); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt(maxOf(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)
        val cx = cphi * cxp - sphi * cyp + (x1 + x2) / 2.0
        val cy = sphi * cxp + cphi * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            var a = kotlin.math.acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) a = -a
            return a
        }
        val th1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dth = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dth > 0) dth -= 2 * PI else if (sweep && dth < 0) dth += 2 * PI
        val segs = ceil(abs(dth) / (PI / 2)).toInt().coerceAtLeast(1)
        val step = dth / segs
        val k = 4.0 / 3.0 * tan(step / 4)
        var th = th1
        for (i in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val c2 = cos(th + step); val s2 = sin(th + step)
            val e1x = c1 - k * s1; val e1y = s1 + k * c1
            val e2x = c2 + k * s2; val e2y = s2 - k * c2
            fun tx(ux: Double, uy: Double) = (cx + rx * ux * cphi - ry * uy * sphi).toFloat()
            fun ty(ux: Double, uy: Double) = (cy + rx * ux * sphi + ry * uy * cphi).toFloat()
            p.cubicTo(tx(e1x, e1y), ty(e1x, e1y), tx(e2x, e2y), ty(e2x, e2y), tx(c2, s2), ty(c2, s2))
            th += step
        }
    }
}
