package io.paperos.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.view.View
import io.paperos.ui.Epd
import io.paperos.ui.Ink
import io.paperos.ui.Metrics
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/** Typesetting and PDF rendering happen off the main thread, one job at a time. */
internal val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "paper-typeset").apply { priority = Thread.NORM_PRIORITY - 1 } }

/**
 * A full-screen page with Kindle's tap zones: the top strip opens the menu, the left third goes
 * back, the rest goes forward. Swipes work too. No animation: a page turn is one frame.
 */
abstract class PageView(context: Context) : View(context) {
    protected val m = Metrics.of(context)
    private val footer = Ink.textPaint(m.caption, Ink.body, Ink.GRAY).apply { fontFeatureSettings = "tnum" }

    var onMenu: (() -> Unit)? = null
    /** Called after every move. */
    var onMoved: (() -> Unit)? = null

    abstract fun next()
    abstract fun previous()
    /** Whole-book progress, 0..1. */
    abstract val progress: Float
    abstract val toc: List<TocEntry>
    abstract val chapter: Int
    abstract fun goToChapter(chapter: Int)
    /** Open a contents entry, honouring its #anchor. */
    open fun goToEntry(e: TocEntry) = goToChapter(e.chapter)
    /** Index into [toc] of the entry the reader is in. */
    open fun currentEntryIndex(): Int = toc.indexOfLast { it.chapter <= chapter }
    /** Where a contents entry starts, as a fraction of the book. */
    open fun entryFraction(e: TocEntry): Float = chapterStarts().getOrElse(e.chapter) { 0f }
    /** Jump to a fraction of the whole book (tapping the progress track). */
    abstract fun goToFraction(f: Float)
    /** Stable encoding of the current position. */
    abstract val location: String
    abstract fun goToLocation(loc: String)
    abstract val pagesLeftInChapter: Int
    /** Estimated (current page, total pages) for the whole book; exact for PDF. */
    abstract fun pageEstimate(): Pair<Int, Int>
    /** Where each chapter starts, as fractions of the book (ticks on the progress track). */
    abstract fun chapterStarts(): FloatArray
    abstract fun snippet(): String

    /** Free file handles; the view is not used afterwards. */
    open fun release() {}

    val chapterTitle: String get() = toc.getOrNull(currentEntryIndex())?.title.orEmpty()

    open fun footerLeft(): String = when (val n = pagesLeftInChapter) {
        0 -> "Last page in chapter"
        1 -> "1 page left in chapter"
        else -> "$n pages left in chapter"
    }
    open fun footerRight(): String = "${(progress * 100).toInt()}%"

    protected val footerHeight get() = m.dp(46f)

    protected fun drawFooter(canvas: Canvas, margin: Float) {
        val y = height - footerHeight / 2 - (footer.ascent() + footer.descent()) / 2
        footer.textAlign = Paint.Align.LEFT
        canvas.drawText(footerLeft(), margin, y, footer)
        footer.textAlign = Paint.Align.RIGHT
        canvas.drawText(footerRight(), width - margin, y, footer)
    }

    /** After content for a new page is ready. */
    protected fun turned() {
        onMoved?.invoke()
        Epd.pageTurn(this)
    }

    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
            MotionEvent.ACTION_UP -> {
                val dx = e.x - downX; val dy = e.y - downY
                val swipe = m.dp(48f)
                when {
                    abs(dx) > swipe && abs(dx) > abs(dy) -> if (dx < 0) next() else previous()
                    abs(dy) > swipe -> if (dy > 0 && downY < height * 0.2f) onMenu?.invoke() else if (dy < 0) next() else previous()
                    e.y < height * 0.15f || e.y > height - footerHeight -> onMenu?.invoke()
                    e.x < width * 0.3f -> previous()
                    else -> next()
                }
            }
        }
        return true
    }
}

/** EPUB / TXT: chapters typeset with StaticLayout, paginated by line. */
class ReflowView(context: Context, private val book: Reflowable, style: ReaderStyle, start: Location) : PageView(context) {
    private var loc = start
    private var current: ChapterPages? = null
    private var page = 0
    private var generation = 0
    private val cache = HashMap<Int, ChapterPages>()
    private val weights = LongArray(book.chapterCount) { book.weight(it) }
    private val prefix = LongArray(weights.size + 1).also { for (i in weights.indices) it[i + 1] = it[i] + weights[i] }
    private val total = prefix.last().coerceAtLeast(1)
    private val loading = Ink.textPaint(m.body, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.CENTER }

    var style = style
        set(v) { if (v != field) { field = v; relayout() } }

    override val toc get() = book.toc
    override val chapter get() = loc.chapter
    override val location get() = loc.encode()

    private val margin get() = style.marginPx(m)
    private val topMargin get() = m.dp(44f)
    private val pageW get() = (width - 2 * margin).toInt()
    private val pageH get() = (height - topMargin - footerHeight).toInt()

    override val progress: Float
        get() {
            val c = loc.chapter.coerceIn(0, weights.size - 1)
            val inChapter = current?.let { if (it.length == 0) 0f else loc.offset / it.length.toFloat() } ?: 0f
            return ((prefix[c] + weights[c] * inChapter) / total).coerceIn(0f, 1f)
        }

    override val pagesLeftInChapter get() = current?.let { it.pageCount - 1 - page } ?: 0

    /** Book-wide page numbers, extrapolated from the density of the chapter we have laid out. */
    override fun pageEstimate(): Pair<Int, Int> {
        val c = current ?: return 1 to 1
        val perWeight = c.pageCount / weights[c.chapter].toFloat().coerceAtLeast(1f)
        val totalPages = (total * perWeight).toInt().coerceAtLeast(c.pageCount)
        val before = (prefix[c.chapter] * perWeight).toInt()
        return (before + page + 1).coerceAtMost(totalPages) to totalPages
    }

    override fun chapterStarts() = FloatArray(weights.size) { prefix[it] / total.toFloat() }

    override fun snippet() = current?.snippet(page).orEmpty()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    /** Throw away all layouts (style or size changed) and re-find the current location. */
    private fun relayout() {
        if (width == 0) return
        generation++
        cache.clear()
        current = null
        invalidate()
        load(loc.chapter) { pages ->
            show(pages, pages.pageOf(loc.offset))
            prefetch()
        }
    }

    private fun typeset(ch: Int): ChapterPages {
        val text = book.text(context, ch, style, pageW, pageH)
        return ChapterPages.build(ch, text, style.paint(m), style, pageW, pageH)
    }

    /** Typeset [ch] on the worker unless cached, then continue on the main thread. */
    private fun load(ch: Int, then: (ChapterPages) -> Unit) {
        cache[ch]?.let { return then(it) }
        val gen = generation
        worker.execute {
            val pages = runCatching { typeset(ch) }.onFailure { android.util.Log.w("PaperOS", "typeset chapter $ch failed", it) }.getOrNull()
            post {
                if (gen != generation || pages == null) return@post
                cache[ch] = pages
                then(pages)
            }
        }
    }

    private fun prefetch() {
        val c = loc.chapter
        // Keep only neighbours; typeset the next chapter while the user reads this one.
        cache.keys.filter { abs(it - c) > 1 }.forEach { cache.remove(it) }
        if (c + 1 < book.chapterCount && c + 1 !in cache) load(c + 1) {}
        if (c - 1 >= 0 && c - 1 !in cache) load(c - 1) {}
    }

    private fun show(pages: ChapterPages, p: Int) {
        current = pages
        page = p.coerceIn(0, pages.pageCount - 1)
        loc = Location(pages.chapter, pages.startOffset(page))
        turned()
    }

    override fun next() {
        val c = current ?: return
        when {
            page + 1 < c.pageCount -> show(c, page + 1)
            c.chapter + 1 < book.chapterCount -> load(c.chapter + 1) { show(it, 0); prefetch() }
        }
    }

    override fun previous() {
        val c = current ?: return
        when {
            page > 0 -> show(c, page - 1)
            c.chapter > 0 -> load(c.chapter - 1) { show(it, it.pageCount - 1); prefetch() }
        }
    }

    override fun goToChapter(chapter: Int) {
        if (chapter !in 0 until book.chapterCount) return
        loc = Location(chapter, 0)
        load(chapter) { show(it, 0); prefetch() }
    }

    override fun goToEntry(e: TocEntry) {
        if (e.chapter !in 0 until book.chapterCount) return
        load(e.chapter) { pages -> show(pages, pages.pageOf(pages.anchorOffset(e.anchor) ?: 0)); prefetch() }
    }

    override fun currentEntryIndex(): Int {
        val c = current
        return toc.indexOfLast { e ->
            e.chapter < loc.chapter || (e.chapter == loc.chapter && ((c?.anchorOffset(e.anchor) ?: 0) <= loc.offset))
        }.coerceAtLeast(if (toc.isEmpty()) -1 else 0)
    }

    override fun entryFraction(e: TocEntry): Float =
        ((prefix[e.chapter] + weights[e.chapter] * book.anchorFraction(e)) / total.toFloat()).coerceIn(0f, 1f)

    override fun goToFraction(f: Float) {
        val target = (f.coerceIn(0f, 1f) * total).toLong()
        val ch = (0 until weights.size).lastOrNull { prefix[it] <= target } ?: 0
        val within = ((target - prefix[ch]) / weights[ch].toFloat()).coerceIn(0f, 1f)
        load(ch) { pages -> show(pages, pages.pageOf((pages.length * within).toInt())); prefetch() }
    }

    override fun goToLocation(loc: String) {
        val l = Location.decode(loc) ?: return
        load(l.chapter) { pages -> show(pages, pages.pageOf(l.offset)); prefetch() }
    }

    override fun onDraw(canvas: Canvas) {
        val c = current
        if (c == null) {
            canvas.drawText("Opening…", width / 2f, height / 2f, loading)
            return
        }
        canvas.save()
        canvas.translate(margin, topMargin)
        c.draw(canvas, page)
        canvas.restore()
        drawFooter(canvas, margin)
    }
}

/**
 * PDF via the framework's PdfRenderer, rendered on the worker thread to a bitmap that fits the
 * screen. One bitmap is kept; a page turn renders the next one.
 */
class PdfView(context: Context, file: File, startPage: Int) : PageView(context) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val count = renderer.pageCount
    private var page = startPage.coerceIn(0, (count - 1).coerceAtLeast(0))
    private var bitmap: Bitmap? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override val progress get() = if (count <= 1) 1f else page / (count - 1f)
    override val toc get() = (0 until count).map { TocEntry("Page ${it + 1}", it) }
    override val chapter get() = page
    override val location get() = page.toString()
    override val pagesLeftInChapter get() = count - 1 - page
    override fun pageEstimate() = (page + 1) to count
    override fun chapterStarts() = FloatArray(0)
    override fun snippet() = "Page ${page + 1}"
    override fun footerLeft() = "Page ${page + 1} of $count"

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = render(page)

    private fun render(p: Int) {
        val w = width; val h = height - footerHeight.toInt()
        if (w <= 0 || h <= 0 || count == 0) return
        worker.execute {
            val bmp = synchronized(renderer) {
                renderer.openPage(p).use { pg ->
                    val s = minOf(w / pg.width.toFloat(), h / pg.height.toFloat())
                    val out = Bitmap.createBitmap((pg.width * s).toInt().coerceAtLeast(1), (pg.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                    out.eraseColor(Ink.WHITE)
                    pg.render(out, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out
                }
            }
            post {
                bitmap?.recycle()
                bitmap = bmp
                page = p
                turned()
            }
        }
    }

    override fun next() { if (page + 1 < count) render(page + 1) }
    override fun previous() { if (page > 0) render(page - 1) }
    override fun goToChapter(chapter: Int) = render(chapter.coerceIn(0, count - 1))
    override fun goToFraction(f: Float) = render((f * (count - 1)).toInt().coerceIn(0, count - 1))
    override fun goToLocation(loc: String) { loc.toIntOrNull()?.let { render(it.coerceIn(0, count - 1)) } }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, (width - b.width) / 2f, 0f, bmpPaint)
        drawFooter(canvas, m.dp(24f))
    }

    override fun release() {
        worker.execute { synchronized(renderer) { renderer.close(); fd.close() } }
    }
}
