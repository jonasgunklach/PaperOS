package io.paperos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

/**
 * "‹    Page 1 of 3    ›" — the pager. 48dp chevron targets at both ends (gray when there is
 * nowhere to go); the label is optionally tappable (e.g. "Go to" by letter). Redraws only when
 * the page changes.
 */
class PageBar(context: Context) : TapView(context) {
    private var pager: Pageable? = null
    private var page = 0
    private var count = 1
    private val text = Ink.textPaint(m.label, Ink.medium).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val rule = Ink.fillPaint()
    private val fill = Ink.fillPaint()

    /** Called when the page label is tapped. */
    var onLabelTap: (() -> Unit)? = null
    /** Prefix for the label, e.g. the letter range of the page ("A – S · "). */
    var prefix: ((Int) -> String)? = null
    /** Draw a heavy rule along the top. */
    var topRule = false

    fun bind(p: Pageable) {
        pager = p
        p.addPageListener { pg, c ->
            val changed = pg != page || c != count
            page = pg; count = c
            if (changed) invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(52f))

    override fun zoneAt(x: Float, y: Float) = when {
        x < m.touch + paddingLeft -> if (page > 0) 0 else -1
        x > width - paddingRight - m.touch -> if (page < count - 1) 2 else -1
        else -> if (onLabelTap != null && count > 1) 1 else -1
    }

    override fun onZone(zone: Int) {
        when (zone) {
            0 -> pager?.previous()
            2 -> pager?.next()
            1 -> onLabelTap?.invoke()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (topRule) canvas.drawRect(paddingLeft.toFloat(), 0f, (width - paddingRight).toFloat(), m.border, rule)
        val cy = height / 2f
        val l = paddingLeft + m.touch / 2
        val r = width - paddingRight - m.touch / 2
        for ((i, cx) in listOf(0 to l, 2 to r)) {
            val enabled = if (i == 0) page > 0 else page < count - 1
            val down = pressedZone == i
            if (down) canvas.drawRoundRect(cx - m.touch / 2, cy - m.touch / 2, cx + m.touch / 2, cy + m.touch / 2, m.radius, m.radius, fill)
            Glyphs.draw(canvas, if (i == 0) Glyph.BACK else Glyph.FORWARD, cx, cy, m.dp(20f), m.dp(2.2f),
                if (down) Ink.WHITE else if (enabled) Ink.BLACK else Ink.LIGHT)
        }
        text.color = if (pressedZone == 1) Ink.GRAY else Ink.BLACK
        val label = (prefix?.invoke(page) ?: "") + "Page ${page + 1} of $count"
        canvas.drawText(label, width / 2f, cy - (text.ascent() + text.descent()) / 2, text)
    }
}

/**
 * A vertical stack that never scrolls: children that don't fit flow onto the next page.
 * Used for settings and other forms. Off-page children are invisible and not laid out.
 */
class PagedColumn(context: Context) : ViewGroup(context), Pageable {

    private var pageOf = IntArray(0)
    private val listeners = mutableListOf<(Int, Int) -> Unit>()
    private var swipeX = 0f
    private var swipeY = 0f
    private val swipeMin = Metrics.of(context).dp(56f)

    override var page = 0
        private set
    override var pageCount = 1
        private set

    override fun addPageListener(listener: (page: Int, count: Int) -> Unit) {
        listeners += listener
        listener(page, pageCount)
    }

    override fun goTo(page: Int) {
        val p = page.coerceIn(0, pageCount - 1)
        if (p == this.page) return
        this.page = p
        applyVisibility()
        requestLayout()
        Epd.pageTurn(this)
        listeners.forEach { it(this.page, pageCount) }
    }

    /** Turn to the page that holds child [index]. */
    fun goToChildPage(index: Int) = goTo(pageOf.getOrElse(index) { 0 })

    private fun applyVisibility() {
        for (i in 0 until childCount) {
            getChildAt(i).visibility = if (pageOf.getOrElse(i) { 0 } == page) VISIBLE else INVISIBLE
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val avail = h - paddingTop - paddingBottom
        val childW = MeasureSpec.makeMeasureSpec(w - paddingLeft - paddingRight, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST)
        if (pageOf.size != childCount) pageOf = IntArray(childCount)
        var p = 0
        var used = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(childW, childH)
            if (used > 0 && used + c.measuredHeight > avail) { p++; used = 0 }
            pageOf[i] = p
            used += c.measuredHeight
        }
        val newCount = p + 1
        if (newCount != pageCount || page >= newCount) {
            pageCount = newCount
            page = page.coerceIn(0, pageCount - 1)
            post { listeners.forEach { it(page, pageCount) } }
        }
        applyVisibility()
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var y = paddingTop
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (pageOf.getOrElse(i) { 0 } != page) { c.layout(0, 0, 0, 0); continue }
            c.layout(paddingLeft, y, paddingLeft + c.measuredWidth, y + c.measuredHeight)
            y += c.measuredHeight
        }
    }

    // Swipe anywhere on the column turns the page; taps still reach the rows.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeX = ev.x; swipeY = ev.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeX; val dy = ev.y - swipeY
                if (pageCount > 1 && (kotlin.math.abs(dx) > swipeMin || kotlin.math.abs(dy) > swipeMin)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_UP) {
            val dx = ev.x - swipeX; val dy = ev.y - swipeY
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) > swipeMin) {
                val forward = if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) dx < 0 else dy < 0
                if (forward) next() else previous()
            }
        }
        return true
    }
}
