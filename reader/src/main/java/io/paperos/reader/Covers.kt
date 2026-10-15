package io.paperos.reader

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.paperos.ui.Ink
import io.paperos.ui.Metrics
import io.paperos.ui.Type

/**
 * Typographic covers drawn in pure black and white, so every book looks intentional on e-ink
 * (publisher art turns to mud in 16 grays). Six templates; the title picks one, so a book
 * always gets the same cover. All sizes are relative to the cover width, so one design works
 * from the small "Recent" row up to the Home hero.
 */
object Covers {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val caps = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { letterSpacing = 0.22f }
    private val serif = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    fun template(title: String) = (title.lowercase().hashCode() and 0x7fffffff) % 6

    fun draw(c: Canvas, box: RectF, fullTitle: String, author: String, m: Metrics) {
        // Covers carry the main title only: "Moby Dick; Or, The Whale" → "Moby Dick".
        val title = fullTitle.split(';', ':').first().trim().ifEmpty { fullTitle }
        val w = box.width()
        val k = w / m.dp(150f)                  // 1.0 at the Library cover size
        fun s(v: Float) = m.dp(v) * k
        val border = m.dp(1.5f)
        caps.typeface = Type.sans(700); caps.textSize = s(7.5f).coerceAtLeast(m.sp(7f))
        val authorCaps = author.uppercase()

        c.save()
        c.clipRect(box)
        when (template(title)) {
            0 -> { // Framed: black, inner white frame, centred italic title.
                fill.color = Ink.BLACK; c.drawRoundRect(box, s(2f), s(2f), fill)
                r.set(box.left + s(8f), box.top + s(8f), box.right - s(8f), box.bottom - s(8f))
                stroke.color = Ink.WHITE; stroke.strokeWidth = m.dp(1f); c.drawRect(r, stroke)
                serif.typeface = Type.serif(400, italic = true, opsz = 24); serif.color = Ink.WHITE
                val t = fitted(title, serif, r.width() - s(20f), r.height() - s(70f), Layout.Alignment.ALIGN_CENTER, s(17f))
                caps.color = Ink.WHITE
                val block = t.height + s(42f)
                var y = r.centerY() - block / 2
                centeredText(c, authorCaps, r.centerX(), y + caps.textSize, caps, r.width() - s(16f)); y += caps.textSize + s(10f)
                fill.color = Ink.WHITE; c.drawRect(r.centerX() - s(10f), y, r.centerX() + s(10f), y + m.dp(1f), fill); y += s(12f)
                drawLayout(c, t, r.centerX() - t.width / 2f, y)
            }
            1 -> { // Band: white, author top-left, title reversed out of a black band at the foot.
                plate(c, box, border, m)
                caps.color = Ink.BLACK
                c.drawText(fit(authorCaps, caps, w - s(20f)), box.left + s(10f), box.top + s(12f) + caps.textSize, caps)
                serif.typeface = Type.serif(600, opsz = 24); serif.color = Ink.WHITE
                val t = fitted(title, serif, w - s(20f), box.height() * 0.55f, Layout.Alignment.ALIGN_NORMAL, s(24f))
                val bandTop = box.bottom - t.height - s(30f)
                fill.color = Ink.BLACK; c.drawRect(box.left, bandTop, box.right, box.bottom, fill)
                drawLayout(c, t, box.left + s(10f), bandTop + s(14f))
            }
            2 -> { // Double frame, centred roman title.
                plate(c, box, border, m)
                r.set(box.left + s(5f), box.top + s(5f), box.right - s(5f), box.bottom - s(5f))
                stroke.color = Ink.BLACK; stroke.strokeWidth = m.dp(1f); c.drawRect(r, stroke)
                serif.typeface = Type.serif(400, opsz = 24); serif.color = Ink.BLACK
                val t = fitted(title, serif, r.width() - s(20f), r.height() - s(56f), Layout.Alignment.ALIGN_CENTER, s(20f))
                caps.color = Ink.BLACK
                val block = t.height + s(24f) + caps.textSize
                var y = r.centerY() - block / 2
                drawLayout(c, t, r.centerX() - t.width / 2f, y); y += t.height + s(12f)
                fill.color = Ink.BLACK; c.drawRect(r.centerX() - s(10f), y, r.centerX() + s(10f), y + m.dp(1f), fill); y += s(12f)
                centeredText(c, authorCaps, r.centerX(), y + caps.textSize, caps, r.width() - s(16f))
            }
            3 -> { // Initial: black, a huge first letter.
                fill.color = Ink.BLACK; c.drawRoundRect(box, s(2f), s(2f), fill)
                caps.color = Ink.WHITE
                c.drawText(fit(authorCaps, caps, w - s(20f)), box.left + s(10f), box.top + s(10f) + caps.textSize, caps)
                serif.typeface = Type.serif(500, opsz = 72); serif.textSize = s(108f); serif.color = Ink.WHITE
                val initial = title.trimStart('“', '"', '‘', '\'', ' ').firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·"
                c.drawText(initial, box.left + s(8f), box.top + box.height() * 0.62f, serif)
                serif.typeface = Type.serif(400, opsz = 24); serif.letterSpacing = 0.06f
                val t = fitted(title, serif, w - s(20f), box.height() * 0.3f, Layout.Alignment.ALIGN_NORMAL, s(16f))
                drawLayout(c, t, box.left + s(10f), box.bottom - s(10f) - t.height)
                serif.letterSpacing = 0f
            }
            4 -> { // Stripes across the top, italic title at the foot.
                plate(c, box, border, m)
                fill.color = Ink.BLACK
                for (i in 0 until 5) {
                    val y = box.top + s(12f) + i * s(8f)
                    c.drawRect(box.left + s(10f), y, box.right - s(10f), y + s(3f), fill)
                }
                serif.typeface = Type.serif(400, italic = true, opsz = 24); serif.color = Ink.BLACK
                val t = fitted(title, serif, w - s(20f), box.height() - s(80f), Layout.Alignment.ALIGN_NORMAL, s(21f))
                caps.color = Ink.BLACK
                val authorY = box.bottom - s(10f)
                c.drawText(fit(authorCaps, caps, w - s(20f)), box.left + s(10f), authorY, caps)
                drawLayout(c, t, box.left + s(10f), authorY - caps.textSize - s(8f) - t.height)
            }
            else -> { // Rings: concentric circles, title at the foot.
                plate(c, box, border, m)
                caps.color = Ink.BLACK
                c.drawText(fit(authorCaps, caps, w - s(20f)), box.left + s(10f), box.top + s(10f) + caps.textSize, caps)
                serif.typeface = Type.serif(400, opsz = 24); serif.color = Ink.BLACK
                val t = fitted(title, serif, w - s(20f), box.height() * 0.35f, Layout.Alignment.ALIGN_NORMAL, s(19f))
                val ty = box.bottom - s(10f) - t.height
                val cy = (box.top + s(24f) + ty) / 2
                val rad = minOf(s(36f), (ty - box.top - s(30f)) / 2)
                stroke.color = Ink.BLACK; stroke.strokeWidth = border
                c.drawCircle(box.centerX(), cy, rad, stroke)
                c.drawCircle(box.centerX(), cy, rad * 0.65f, stroke)
                fill.color = Ink.BLACK; c.drawCircle(box.centerX(), cy, rad * 0.3f, fill)
                drawLayout(c, t, box.left + s(10f), ty)
            }
        }
        c.restore()
    }

    /** A bitmap cover (the book's own art), framed with a hairline so white covers have an edge. */
    fun drawBitmap(c: Canvas, box: RectF, bmp: android.graphics.Bitmap, m: Metrics) {
        val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
        // Crop to the box's aspect ratio (cover-fit).
        val boxRatio = box.width() / box.height()
        val bmpRatio = bmp.width / bmp.height.toFloat()
        if (bmpRatio > boxRatio) { val w = (bmp.height * boxRatio).toInt(); src.left = (bmp.width - w) / 2; src.right = src.left + w }
        else { val h = (bmp.width / boxRatio).toInt(); src.top = (bmp.height - h) / 2; src.bottom = src.top + h }
        c.drawBitmap(bmp, src, box, Paint(Paint.FILTER_BITMAP_FLAG))
        stroke.color = Ink.BLACK; stroke.strokeWidth = m.dp(1f)
        c.drawRect(box, stroke)
    }

    private fun plate(c: Canvas, box: RectF, border: Float, m: Metrics) {
        fill.color = Ink.WHITE; c.drawRect(box, fill)
        stroke.color = Ink.BLACK; stroke.strokeWidth = border
        r.set(box); r.inset(border / 2, border / 2)
        c.drawRoundRect(r, m.dp(2f), m.dp(2f), stroke)
    }

    /** Largest size ≤ [start] (down to 55%) at which [text] fits [maxH]; then ellipsize. */
    private fun fitted(text: String, p: TextPaint, width: Float, maxH: Float, align: Layout.Alignment, start: Float): StaticLayout {
        var size = start
        while (true) {
            p.textSize = size
            val l = layout(text, p, width, align, 6)
            if (l.height <= maxH || size <= start * 0.55f) {
                if (l.height <= maxH) return l
                val lines = maxOf(1, (maxH / (l.height / l.lineCount.toFloat())).toInt())
                return layout(text, p, width, align, lines)
            }
            size *= 0.9f
        }
    }

    private fun layout(text: String, p: TextPaint, width: Float, align: Layout.Alignment, lines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, width.toInt().coerceAtLeast(1))
            .setAlignment(align).setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false).setLineSpacing(0f, 1.05f).build()

    private fun drawLayout(c: Canvas, l: StaticLayout, x: Float, y: Float) {
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    private fun centeredText(c: Canvas, text: String, cx: Float, y: Float, p: Paint, maxW: Float) {
        val t = fit(text, p, maxW)
        c.drawText(t, cx - p.measureText(t) / 2, y, p)
    }

    private fun fit(text: String, p: Paint, maxW: Float): String =
        TextUtils.ellipsize(text, p as? TextPaint ?: TextPaint(p), maxW, TextUtils.TruncateAt.END).toString()
}
