package io.paperos.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import io.paperos.ui.CellRenderer
import io.paperos.ui.Glyph
import io.paperos.ui.Glyphs
import io.paperos.ui.Ink
import io.paperos.ui.Metrics
import io.paperos.ui.TapView
import io.paperos.ui.Type

/** How a book's reading state reads in one short phrase. */
fun progressLabel(p: Progress?): String = when {
    p == null -> "New"
    p.finished -> "Finished"
    else -> "${(p.percent * 100).toInt().coerceAtLeast(1)}%"
}

/**
 * A book in a grid cell: the cover at book proportions, a thin progress bar once started, and
 * one line underneath — title on the left, "41%" / "New" on the right. (The cover already carries
 * title and author in large type.) Pressing frames the cover with a heavy rule.
 */
class BookCell(
    context: Context,
    private val library: Library,
    private val withAuthor: Boolean,
    private val gapX: Float,
    private val gapY: Float,
) : CellRenderer<BookEntry> {
    private val m = Metrics.of(context)
    private val title = TextPaint(Ink.textPaint(m.small, Ink.bold))
    private val meta = TextPaint(Ink.textPaint(m.caption, Ink.body, Ink.GRAY)).apply { textAlign = Paint.Align.RIGHT }
    private val track = Ink.fillPaint(Ink.TRACK)
    private val ink = Ink.fillPaint()
    private val frame = Ink.strokePaint(m.dp(3f))
    private val cover = RectF()

    override fun draw(canvas: Canvas, item: BookEntry, bounds: RectF, pressed: Boolean) {
        val p = library.progress(item.path)
        val textH = m.dp(30f)
        val maxW = bounds.width() - gapX
        val maxH = bounds.height() - gapY - textH
        // Keep a book's proportions (≈ 1 : 1.32); centre in the cell if the cell is wider.
        val w = minOf(maxW, maxH / 1.32f)
        val h = minOf(maxH, w * 1.32f)
        val left = bounds.left + (bounds.width() - w) / 2
        cover.set(left, bounds.top, left + w, bounds.top + h)
        Covers.draw(canvas, cover, item.title, item.author, m)
        if (pressed) {
            val o = m.dp(2.5f)
            canvas.drawRect(cover.left - o, cover.top - o, cover.right + o, cover.bottom + o, frame)
        }
        var y = cover.bottom + m.dp(7f)
        if (p != null && !p.finished) {
            canvas.drawRect(cover.left, y, cover.right, y + m.dp(3f), track)
            canvas.drawRect(cover.left, y, cover.left + cover.width() * p.percent, y + m.dp(3f), ink)
        }
        y += m.dp(9f) - title.ascent()
        val metaText = progressLabel(p)
        val metaW = meta.measureText(metaText) + m.dp(8f)
        canvas.drawText(ellipsize(item.title, title, cover.width() - metaW), cover.left, y, title)
        canvas.drawText(metaText, cover.right, y, meta)
    }
}

/**
 * Home's "Reading now": the cover, the title in display serif, where you are in it, and a
 * solid "Continue reading" button. The whole block is one tap target.
 */
class HeroView(context: Context, private val library: Library) : TapView(context) {
    private val titlePaint = TextPaint(Ink.textPaint(m.sp(28f), Type.serif(600, opsz = 24)))
    private val author = Ink.textPaint(m.body, Ink.body, 0xFF4A4A4A.toInt())
    private val chapter = TextPaint(Ink.textPaint(m.label, Type.serif(400, italic = true), 0xFF333333.toInt()))
    private val small = Ink.textPaint(m.small, Ink.body, 0xFF4A4A4A.toInt())
    private val smallBold = Ink.textPaint(m.small, Ink.bold)
    private val button = Ink.textPaint(m.sp(16f), Ink.bold).apply { textAlign = Paint.Align.CENTER }
    private val track = Ink.fillPaint(Ink.TRACK)
    private val ink = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val box = RectF()

    var book: BookEntry? = null
        set(v) { field = v; layoutCache = null; invalidate() }
    /** Shown when there is no book: (title, subtitle, button label). */
    var empty = Triple("Nothing open yet", "Books you start reading appear here.", "Open Library")
    private var layoutCache: StaticLayout? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(255f))

    override fun onDraw(canvas: Canvas) {
        val b = book
        val coverW = m.dp(170f)
        val colX = coverW + m.dp(24f)
        val colW = width - colX
        val p = b?.let { library.progress(it.path) }

        if (b != null) {
            box.set(0f, 0f, coverW, height.toFloat())
            Covers.draw(canvas, box, b.title, b.author, m)
        } else {
            box.set(m.border / 2, m.border / 2, coverW - m.border / 2, height - m.border / 2)
            border.color = Ink.TRACK; canvas.drawRoundRect(box, m.dp(2f), m.dp(2f), border); border.color = Ink.BLACK
            Glyphs.draw(canvas, Glyph.LIBRARY, coverW / 2, height / 2f, m.dp(40f), m.dp(1.6f), Ink.LIGHT)
        }

        val title = b?.title?.split(';', ':')?.first()?.trim()?.ifEmpty { b.title } ?: empty.first
        val layout = layoutCache ?: StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, colW.toInt().coerceAtLeast(1))
            .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).setLineSpacing(0f, 1.08f).build()
            .also { layoutCache = it }
        canvas.save(); canvas.translate(colX, 0f); layout.draw(canvas); canvas.restore()
        val authorText = if (b != null) b.author else empty.second
        drawWrapped(canvas, authorText, colX, layout.height + m.dp(8f) - author.ascent(), colW, author)

        // Bottom-up: button, progress line, bar, chapter.
        val btnTop = height - m.touch
        val down = pressedZone == 0
        box.set(colX + m.border / 2, btnTop + m.border / 2, width - m.border / 2, height - m.border / 2)
        if (down) canvas.drawRoundRect(box, m.radius, m.radius, border) else canvas.drawRoundRect(box, m.radius, m.radius, ink)
        val label = if (b != null) "Continue reading" else empty.third
        button.color = if (down) Ink.BLACK else Ink.WHITE
        val tw = button.measureText(label)
        val cx = box.centerX() - (if (b != null) m.dp(14f) else 0f)
        canvas.drawText(label, cx, box.centerY() - (button.ascent() + button.descent()) / 2, button)
        if (b != null) Glyphs.draw(canvas, Glyph.ARROW_RIGHT, cx + tw / 2 + m.dp(19f), box.centerY(), m.dp(18f), m.dp(2.2f), button.color)

        if (b != null && p != null) {
            val rowY = btnTop - m.dp(16f) - small.descent()
            canvas.drawText(progressLabel(p), colX, rowY, smallBold)
            if (p.pagesLeft >= 0) {
                val left = when (p.pagesLeft) { 0 -> "Last page in chapter"; 1 -> "1 page left in chapter"; else -> "${p.pagesLeft} pages left in chapter" }
                small.textAlign = Paint.Align.RIGHT
                canvas.drawText(left, width.toFloat(), rowY, small)
                small.textAlign = Paint.Align.LEFT
            }
            val barY = rowY + small.ascent() - m.dp(11f)
            box.set(colX, barY, width.toFloat(), barY + m.dp(4f))
            canvas.drawRoundRect(box, m.dp(2f), m.dp(2f), track)
            box.right = colX + colW * p.percent
            canvas.drawRoundRect(box, m.dp(2f), m.dp(2f), ink)
            if (p.chapter.isNotEmpty()) canvas.drawText(ellipsize(p.chapter, chapter, colW), colX, barY - m.dp(10f), chapter)
        }
    }

    private fun drawWrapped(c: Canvas, text: String, x: Float, y: Float, w: Float, p: Paint) {
        val l = StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(p), w.toInt().coerceAtLeast(1))
            .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        c.save(); c.translate(x, y + p.ascent()); l.draw(c); c.restore()
    }
}

internal fun ellipsize(text: String, p: Paint, w: Float): String =
    TextUtils.ellipsize(text, p as? TextPaint ?: TextPaint(p), w, TextUtils.TruncateAt.END).toString()
