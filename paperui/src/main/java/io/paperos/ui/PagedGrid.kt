package io.paperos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

/** Anything that shows its content one page at a time instead of scrolling. */
interface Pageable {
    val page: Int
    val pageCount: Int
    fun goTo(page: Int)
    fun addPageListener(listener: (page: Int, count: Int) -> Unit)

    fun next() = goTo(page + 1)
    fun previous() = goTo(page - 1)
}

/** Draws one grid cell onto the canvas. No inflation, no allocation per frame. */
fun interface CellRenderer<T> {
    fun draw(canvas: Canvas, item: T, bounds: RectF, pressed: Boolean)
}

/**
 * A fixed grid that shows `rows × columns` items per page — the e-ink replacement for a list.
 *
 * - The grid size is derived from the available space, so a page is always exactly full: no item
 *   is cut off and nothing scrolls.
 * - Each cell is its own lightweight View (own RenderNode). Pressing a cell re-records only that
 *   node, so HWUI's damage rectangle — and the panel update — is just that cell.
 * - The cells are a fixed pool sized to one page, not a recycling list: turning the page rebinds
 *   them once and is counted against the ghosting budget.
 */
class PagedGrid<T>(context: Context, private val renderer: CellRenderer<T>) : ViewGroup(context), Pageable {

    private val m = Metrics.of(context)

    /** Minimum cell size; the grid stretches cells to fill the space exactly. */
    var minCellWidth = m.dp(120f)
    var minCellHeight = m.dp(132f)
    /** Force a column/row count instead of deriving it (0 = auto). */
    var fixedColumns = 0
    var fixedRows = 0

    var onItemTap: ((T) -> Unit)? = null
    var onItemLongPress: ((T) -> Unit)? = null
    /** Drawn centred when there are no items at all. */
    var emptyText: String = ""
        set(v) { field = v; if (items.isEmpty()) invalidate() }

    var items: List<T> = emptyList()
        set(value) {
            val wasEmpty = field.isEmpty()
            field = value
            page = page.coerceIn(0, max(0, pageCount - 1))
            bind()
            if (wasEmpty != value.isEmpty()) invalidate()
            notifyPage()
        }

    var columns = 1; private set
    var rows = 1; private set
    private val perPage get() = columns * rows

    override var page = 0
        private set
    override val pageCount get() = if (items.isEmpty()) 1 else (items.size + perPage - 1) / perPage

    private val listeners = mutableListOf<(Int, Int) -> Unit>()
    private val cells = ArrayList<Cell>()
    private val emptyPaint = Ink.textPaint(m.body, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.CENTER }

    init { setWillNotDraw(false) }

    /** One slot of the page. Draws whatever item is currently bound to it. */
    private inner class Cell : View(context) {
        var item: T? = null
        var down = false
        private val bounds = RectF()

        override fun onDraw(canvas: Canvas) {
            val it = item ?: return
            bounds.set(0f, 0f, width.toFloat(), height.toFloat())
            renderer.draw(canvas, it, bounds, down)
        }
    }

    private val tracker = TapTracker(this, object : TapTracker.Target {
        override fun hitTest(x: Float, y: Float): Int {
            val slot = slotAt(x, y)
            return if (slot >= 0 && cells.getOrNull(slot)?.item != null) slot else -1
        }

        override fun showPressed(id: Int, pressed: Boolean) {
            val c = cells.getOrNull(id) ?: return
            if (c.down != pressed) { c.down = pressed; c.invalidate() }
        }

        override fun onTap(id: Int) { cells.getOrNull(id)?.item?.let { onItemTap?.invoke(it) } }
        override fun onLongPress(id: Int): Boolean {
            val item = cells.getOrNull(id)?.item ?: return false
            val l = onItemLongPress ?: return false
            l(item); return true
        }
        override fun onSwipe(forward: Boolean): Boolean {
            if (pageCount <= 1) return false
            if (forward) next() else previous(); return true
        }
    })

    override fun addPageListener(listener: (page: Int, count: Int) -> Unit) {
        listeners += listener
        listener(page, pageCount)
    }

    override fun goTo(page: Int) {
        val p = page.coerceIn(0, pageCount - 1)
        if (p == this.page) return
        this.page = p
        bind()
        Epd.pageTurn(this)
        notifyPage()
    }

    /** Jump to the page holding [item]. */
    fun showItem(item: T) {
        val i = items.indexOf(item)
        if (i >= 0) goTo(i / perPage)
    }

    /** Index range of the items on [page], e.g. for "A–F" page labels. */
    fun pageRange(page: Int): IntRange {
        val start = page * perPage
        return start until minOf(items.size, start + perPage)
    }

    private fun notifyPage() = listeners.forEach { it(page, pageCount) }

    /** Point each cell at its item for the current page. Cells whose item didn't change are left alone. */
    private fun bind() {
        val first = page * perPage
        cells.forEachIndexed { i, c ->
            val next = items.getOrNull(first + i)
            if (c.item !== next || c.down) {
                c.item = next
                c.down = false
                c.invalidate()
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val usableW = w - paddingLeft - paddingRight
        val usableH = h - paddingTop - paddingBottom
        val cols = if (fixedColumns > 0) fixedColumns else max(1, (usableW / minCellWidth).toInt())
        val rws = if (fixedRows > 0) fixedRows else max(1, (usableH / minCellHeight).toInt())
        if (cols != columns || rws != rows || cells.size != cols * rws) {
            // Keep the first visible item visible across rotation / resizing.
            val firstItem = page * perPage
            columns = cols; rows = rws
            while (cells.size < perPage) Cell().also { cells += it; addViewInLayout(it, -1, LayoutParams(0, 0), true) }
            while (cells.size > perPage) removeViewInLayout(cells.removeAt(cells.size - 1))
            page = (firstItem / perPage).coerceIn(0, pageCount - 1)
            bind()
            post { notifyPage() }
        }
        val cw = usableW / columns
        val ch = usableH / rows
        val ws = MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY)
        val hs = MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY)
        cells.forEach { it.measure(ws, hs) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val usableW = width - paddingLeft - paddingRight
        val usableH = height - paddingTop - paddingBottom
        cells.forEachIndexed { i, c ->
            val col = i % columns; val row = i / columns
            val x0 = paddingLeft + col * usableW / columns
            val y0 = paddingTop + row * usableH / rows
            val x1 = paddingLeft + (col + 1) * usableW / columns
            val y1 = paddingTop + (row + 1) * usableH / rows
            c.layout(x0, y0, x1, y1)
        }
    }

    private fun slotAt(x: Float, y: Float): Int {
        val c = ((x - paddingLeft) * columns / (width - paddingLeft - paddingRight)).toInt()
        val r = ((y - paddingTop) * rows / (height - paddingTop - paddingBottom)).toInt()
        if (c !in 0 until columns || r !in 0 until rows) return -1
        return r * columns + c
    }

    override fun onDraw(canvas: Canvas) {
        if (items.isEmpty() && emptyText.isNotEmpty()) {
            drawMultiline(canvas, emptyText, width / 2f, height / 2f, emptyPaint)
        }
    }

    // The grid, not the cells, interprets touches: cells are pure drawing surfaces.
    override fun onInterceptTouchEvent(ev: MotionEvent) = true
    override fun onTouchEvent(event: MotionEvent) = tracker.onTouchEvent(event)
}

internal fun drawMultiline(canvas: Canvas, text: String, cx: Float, cy: Float, paint: Paint) {
    val lines = text.split('\n')
    val lh = paint.fontSpacing
    var y = cy - lh * (lines.size - 1) / 2f - (paint.ascent() + paint.descent()) / 2f
    for (line in lines) { canvas.drawText(line, cx, y, paint); y += lh }
}
