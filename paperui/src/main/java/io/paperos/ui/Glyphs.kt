package io.paperos.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Monoline icons on a 24-unit grid, drawn in code (no drawables to inflate). Outlined when idle,
 * solid when active — the same set as the design canvas.
 */
enum class Glyph {
    HOME, LIBRARY, APPS, GEAR, WIFI, BLUETOOTH, AIRPLANE, REFRESH, LIGHT, DARK,
    BACK, FORWARD, CHEVRON_DOWN, ARROW_RIGHT, CONTENTS, SEARCH, BOOKMARK, SORT, PLUS, MINUS,
    SPACING_TIGHT, SPACING_NORMAL, SPACING_LOOSE, MARGIN_NARROW, MARGIN_NORMAL, MARGIN_WIDE,
}

object Glyphs {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private val oval = RectF()

    /** Draw [g] centred at (cx, cy) in a [size] px square; [stroke] is in px. */
    fun draw(c: Canvas, g: Glyph, cx: Float, cy: Float, size: Float, stroke: Float, color: Int = Ink.BLACK, filled: Boolean = false, knockout: Int = Ink.WHITE) {
        val k = size / 24f
        c.save()
        c.translate(cx - size / 2, cy - size / 2)
        c.scale(k, k)
        p.color = color
        p.strokeWidth = stroke / k
        p.style = Paint.Style.STROKE
        fun line(x0: Float, y0: Float, x1: Float, y1: Float) = c.drawLine(x0, y0, x1, y1, p)
        fun poly(vararg pts: Float, close: Boolean = false) {
            path.reset(); path.moveTo(pts[0], pts[1])
            var i = 2; while (i < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
            if (close) path.close()
            c.drawPath(path, p)
        }
        when (g) {
            Glyph.HOME -> {
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                poly(4f, 10.5f, 12f, 4f, 20f, 10.5f, 20f, 20f, 4f, 20f, close = true)
                if (filled) { p.color = knockout; p.style = Paint.Style.FILL; c.drawRect(10f, 15f, 14f, 20.5f, p) }
                else poly(10f, 20f, 10f, 15f, 14f, 15f, 14f, 20f)
            }
            Glyph.LIBRARY -> {
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                c.drawRect(4f, 4.5f, 8f, 19.5f, p)
                c.drawRect(10f, 4.5f, 14f, 19.5f, p)
                poly(16f, 6f, 19.4f, 5.1f, 22f, 18.9f, 18.6f, 19.8f, close = true)
            }
            Glyph.APPS -> {
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                for ((x, y) in listOf(4.5f to 4.5f, 13.5f to 4.5f, 4.5f to 13.5f, 13.5f to 13.5f)) c.drawRoundRect(x, y, x + 6f, y + 6f, 1f, 1f, p)
            }
            Glyph.GEAR -> {
                c.drawCircle(12f, 12f, 3f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    val r0 = if (i % 2 == 0) 6.5f else 6.2f
                    line(12f + r0 * cos(a).toFloat(), 12f + r0 * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
                }
            }
            Glyph.WIFI -> {
                // Three arcs around (12, 19.6), as in the canvas icon.
                for ((r, half) in listOf(14f to 42.7f, 10f to 40.5f, 5f to 44.4f)) {
                    oval.set(12f - r, 19.6f - r, 12f + r, 19.6f + r)
                    c.drawArc(oval, 270f - half, half * 2, false, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(12f, 19.3f, 1.1f, p)
            }
            Glyph.BLUETOOTH -> poly(7f, 7.5f, 17f, 16.5f, 12f, 21f, 12f, 3f, 17f, 7.5f, 7f, 16.5f)
            Glyph.AIRPLANE -> {
                path.reset()
                path.moveTo(12f, 2.5f); path.cubicTo(13f, 2.5f, 13.5f, 3.5f, 13.5f, 4.5f); path.lineTo(13.5f, 9.5f); path.lineTo(21f, 14f)
                path.lineTo(21f, 16f); path.lineTo(13.5f, 13.5f); path.lineTo(13.5f, 18.5f); path.lineTo(16f, 20.5f); path.lineTo(16f, 22f)
                path.lineTo(12f, 21f); path.lineTo(8f, 22f); path.lineTo(8f, 20.5f); path.lineTo(10.5f, 18.5f); path.lineTo(10.5f, 13.5f)
                path.lineTo(3f, 16f); path.lineTo(3f, 14f); path.lineTo(10.5f, 9.5f); path.lineTo(10.5f, 4.5f); path.cubicTo(10.5f, 3.5f, 11f, 2.5f, 12f, 2.5f); path.close()
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(path, p)
            }
            Glyph.REFRESH -> {
                oval.set(4.5f, 4.5f, 19.5f, 19.5f)
                c.drawArc(oval, -45f, 315f, false, p)
                poly(19.5f, 4f, 19.5f, 8f, 15.5f, 8f)
            }
            Glyph.LIGHT -> {
                c.drawCircle(12f, 12f, 3.6f, p)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat(), 12f + 9f * cos(a).toFloat(), 12f + 9f * sin(a).toFloat())
                }
            }
            Glyph.DARK -> {
                // A crescent: a circle with a bite taken out by a second, offset circle.
                path.reset()
                path.addCircle(12f, 12f, 8f, Path.Direction.CW)
                val bite = Path().apply { addCircle(16f, 8.5f, 6.5f, Path.Direction.CW) }
                path.op(bite, Path.Op.DIFFERENCE)
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                c.drawPath(path, p)
            }
            Glyph.BACK -> poly(15f, 5f, 8f, 12f, 15f, 19f)
            Glyph.FORWARD -> poly(9f, 5f, 16f, 12f, 9f, 19f)
            Glyph.CHEVRON_DOWN -> poly(5f, 9f, 12f, 16f, 19f, 9f)
            Glyph.ARROW_RIGHT -> { line(5f, 12f, 19f, 12f); poly(13f, 6f, 19f, 12f, 13f, 18f) }
            Glyph.CONTENTS -> {
                line(9f, 6f, 20f, 6f); line(9f, 12f, 20f, 12f); line(9f, 18f, 20f, 18f)
                p.style = Paint.Style.FILL
                c.drawCircle(4.5f, 6f, 1.2f, p); c.drawCircle(4.5f, 12f, 1.2f, p); c.drawCircle(4.5f, 18f, 1.2f, p)
            }
            Glyph.SEARCH -> { c.drawCircle(10.5f, 10.5f, 6f, p); line(15f, 15f, 20f, 20f) }
            Glyph.BOOKMARK -> {
                if (filled) p.style = Paint.Style.FILL_AND_STROKE
                poly(6f, 3.5f, 18f, 3.5f, 18f, 21f, 12f, 16.5f, 6f, 21f, close = true)
            }
            Glyph.SORT -> { line(4f, 7f, 20f, 7f); line(7f, 12f, 17f, 12f); line(10f, 17f, 14f, 17f) }
            Glyph.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Glyph.MINUS -> line(5f, 12f, 19f, 12f)
            Glyph.SPACING_TIGHT -> { line(4f, 8f, 20f, 8f); line(4f, 12f, 20f, 12f); line(4f, 16f, 20f, 16f) }
            Glyph.SPACING_NORMAL -> { line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 20f, 18f) }
            Glyph.SPACING_LOOSE -> { line(4f, 4f, 20f, 4f); line(4f, 12f, 20f, 12f); line(4f, 20f, 20f, 20f) }
            Glyph.MARGIN_NARROW, Glyph.MARGIN_NORMAL, Glyph.MARGIN_WIDE -> {
                val thin = p.strokeWidth
                p.strokeWidth = thin * 0.8f
                c.drawRoundRect(3f, 3f, 21f, 21f, 1f, 1f, p)
                p.strokeWidth = thin
                val inset = when (g) { Glyph.MARGIN_NARROW -> 5.5f; Glyph.MARGIN_NORMAL -> 7.5f; else -> 9.5f }
                line(inset, 8f, 24f - inset, 8f); line(inset, 12f, 24f - inset, 12f); line(inset, 16f, 24f - inset, 16f)
            }
        }
        c.restore()
    }
}
