package io.paperos.reader

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.paperos.ui.BoxStepper
import io.paperos.ui.Epd
import io.paperos.ui.FilterTabs
import io.paperos.ui.FontCards
import io.paperos.ui.Glyph
import io.paperos.ui.IconButton
import io.paperos.ui.Ink
import io.paperos.ui.LinkText
import io.paperos.ui.Metrics
import io.paperos.ui.OutlineButton
import io.paperos.ui.PageBar
import io.paperos.ui.Pageable
import io.paperos.ui.PagedColumn
import io.paperos.ui.Panel
import io.paperos.ui.PaperActivity
import io.paperos.ui.Screen
import io.paperos.ui.SectionLabel
import io.paperos.ui.Segmented
import io.paperos.ui.SizeBars
import io.paperos.ui.StatusBar
import io.paperos.ui.TapView
import io.paperos.ui.Type
import io.paperos.ui.paperText
import java.io.File

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

/**
 * The reader. Opens EPUB, TXT and PDF from a path (launcher) or a VIEW intent (other apps).
 * The page is the whole screen; tapping the top strip or the footer opens the menu.
 */
class ReaderActivity : PaperActivity() {
    private lateinit var library: Library
    private var view: PageView? = null
    private var book: Reflowable? = null
    private var path: String? = null
    private var title = ""
    private val settings by lazy { getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private var lastTurn = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = Library.of(this)
        open(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent) {
        val file = (intent.getStringExtra(EXTRA_PATH)?.let { File(it) } ?: intent.data?.let { importUri(it) })?.let { runCatching { it.canonicalFile }.getOrDefault(it) }
        if (file == null || !file.exists()) { finish(); return }
        if (file.path == path && view != null) return
        closeBook()
        path = file.path
        val saved = library.progress(file.path)
        val pv: PageView = runCatching {
            when (file.extension.lowercase()) {
                "pdf" -> PdfView(this, file, saved?.location?.toIntOrNull() ?: 0).also { title = file.nameWithoutExtension }
                else -> {
                    val b: Reflowable = if (file.extension.equals("txt", true)) TextBook(file) else EpubBook(file)
                    book = b
                    title = b.title
                    ReflowView(this, b, loadStyle(), Location.decode(saved?.location) ?: Location(0, 0))
                }
            }
        }.getOrElse { finish(); return }
        pv.onMoved = { moved(file.path, pv) }
        pv.onMenu = { showMenu() }
        view = pv
        Epd.ghosts.pageTurnsPerFull = settings.getInt("fullEvery", 6)
        resetTo(PageScreen())
    }

    private fun moved(path: String, v: PageView) {
        library.saveProgress(path, v.location, v.progress, v.chapterTitle, v.pagesLeftInChapter)
        // Learn the reader's pace from page turns 3 s – 10 min apart, for "time left".
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTurn) / 1000f
        if (lastTurn != 0L && dt in 3f..600f) {
            settings.edit().putFloat("secPerPage", settings.getFloat("secPerPage", 50f) * 0.85f + dt * 0.15f).apply()
        }
        lastTurn = now
    }

    /** Other apps hand us content:// URIs; EPUB needs random access, so keep a private copy. */
    private fun importUri(uri: Uri): File? = runCatching {
        if (uri.scheme == "file") return File(uri.path!!)
        val name = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "book-${uri.hashCode()}.epub"
        val dir = File(getExternalFilesDir(null) ?: filesDir, "Imported").apply { mkdirs() }
        val out = File(dir, name.replace('/', '_'))
        if (!out.exists()) contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrNull()

    private fun closeBook() {
        view?.release()
        runCatching { book?.close() }
        book = null
        view = null
    }

    override fun onStop() {
        super.onStop()
        // Leaving the book (or the screen going off): refresh the sleep screen with its cover.
        LockScreen.update(this)
    }

    override fun onDestroy() {
        closeBook()
        super.onDestroy()
    }

    private fun loadStyle() = ReaderStyle(
        size = settings.getInt("size", 3),
        font = settings.getInt("font", 0),
        spacing = settings.getInt("spacing", 1),
        margin = settings.getInt("margin", 1),
        justify = settings.getBoolean("justify", true),
    )

    private fun saveStyle(s: ReaderStyle) {
        settings.edit().putInt("size", s.size).putInt("font", s.font).putInt("spacing", s.spacing)
            .putInt("margin", s.margin).putBoolean("justify", s.justify).apply()
        (view as? ReflowView)?.style = s
    }

    override fun onPageKey(forward: Boolean): Boolean {
        if (current !is PageScreen) return super.onPageKey(forward)
        val v = view ?: return false
        if (forward) v.next() else v.previous()
        return true
    }

    override val volumeKeysTurnPages get() = true

    /** The page itself, edge to edge. */
    private inner class PageScreen : Screen(this) {
        override fun create(): View = view!!.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    }

    // ---- Menu: a toolbar from the top and a progress panel from the bottom ----

    private fun showMenu() {
        val v = view ?: return
        val p = path ?: return
        val m = Metrics.of(this)
        val ctx = this

        val top = Panel(this, onTop = true).apply {
            setPadding(0, 0, 0, m.dpi(4f))
            addView(StatusBar(ctx), lp())
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(m.dpi(8f), m.dpi(2f), m.dpi(12f), m.dpi(6f))
                addView(IconButton(ctx, Glyph.BACK, "Back to home").apply { onClick = { finish() } })
                addView(paperText(ctx, title, m.sp(17f), Type.serif(500), maxLines = 1).apply { setPadding(m.dpi(4f), 0, m.dpi(4f), 0) }, lp(0, WRAP, 1f))
                if (v is ReflowView) addView(IconButton(ctx, null, "Display settings", "Aa").apply { onClick = { showDisplay() } })
                addView(IconButton(ctx, Glyph.CONTENTS, "Contents").apply { onClick = { dismissPanel(); push(ContentsScreen()) } })
                addView(IconButton(ctx, Glyph.BOOKMARK, "Bookmark this page").apply {
                    active = library.isBookmarked(p, v.location)
                    onClick = { active = library.toggleBookmark(p, Bookmark(v.location, v.progress, v.chapterTitle, v.snippet())) }
                })
            }, lp())
        }

        val (pageNo, pages) = v.pageEstimate()
        val bottom = Panel(this, onTop = false).apply {
            setPadding(m.gutter.toInt(), m.dpi(18f), m.gutter.toInt(), m.dpi(18f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
                addView(paperText(ctx, v.chapterTitle.ifEmpty { title }, m.sp(16f), Type.serif(400, italic = true), maxLines = 1), lp(0, WRAP, 1f))
                addView(paperText(ctx, timeLeft(pages - pageNo), m.small, Ink.body, Ink.GRAY, maxLines = 1).apply { setPadding(m.dpi(12f), 0, 0, 0) })
            }, lp())
            addView(ProgressTrack(ctx, v.progress, v.toc.filter { it.depth == 0 }.map { v.entryFraction(it) }.toFloatArray()) { f -> dismissPanel(); v.goToFraction(f) },
                lp().apply { topMargin = m.dpi(14f) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(OutlineButton(ctx, "Chapter", leading = Glyph.BACK).apply { onClick = { dismissPanel(); skip(v, -1) } })
                addView(paperText(ctx, pageLabel(pageNo, pages, v.progress), m.label, Ink.body).apply { gravity = Gravity.CENTER }, lp(0, WRAP, 1f))
                addView(OutlineButton(ctx, "Chapter", trailing = Glyph.FORWARD).apply { onClick = { dismissPanel(); skip(v, +1) } })
            }, lp().apply { topMargin = m.dpi(12f) })
        }
        showPanels(top, bottom)
    }

    private fun pageLabel(page: Int, pages: Int, progress: Float): CharSequence {
        val s = android.text.SpannableStringBuilder()
        s.append("Page $page", android.text.style.StyleSpan(Typeface.BOLD), 0)
        s.append(" of $pages · ${(progress * 100).toInt()}%", android.text.style.ForegroundColorSpan(Ink.GRAY), 0)
        return s
    }

    private fun timeLeft(pagesLeft: Int): String {
        val min = (pagesLeft * settings.getFloat("secPerPage", 50f) / 60f).toInt()
        return when {
            min < 1 -> "Almost done"
            min < 60 -> "About $min min left"
            else -> "About ${min / 60} h ${min % 60} min left"
        }
    }

    /** Chapter skips follow the contents (anchors included), not the file structure. */
    private fun skip(v: PageView, delta: Int) {
        val toc = v.toc
        if (toc.isEmpty()) return
        val i = (v.currentEntryIndex() + delta).coerceIn(0, toc.size - 1)
        v.goToEntry(toc[i])
    }

    // ---- Display ("Aa") ----

    private fun showDisplay() {
        val m = Metrics.of(this)
        var s = loadStyle()
        val ctx = this
        val panel = Panel(this, onTop = false).apply {
            setPadding(m.gutter.toInt(), m.dpi(14f), m.gutter.toInt(), m.dpi(20f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(paperText(ctx, "Display", m.title, Type.serif(600, opsz = 24)), lp(0, WRAP, 1f))
                addView(LinkText(ctx, "Done").apply { onClick = { dismissPanel() } })
            }, lp())
            addView(SectionLabel(ctx, "Font"), lp().apply { topMargin = m.dpi(6f) })
            addView(FontCards(ctx, listOf(Type.serif(400, opsz = 24) to "Literata", Type.sans(400) to "Atkinson", Typeface.SERIF to "Serif"), s.font) {
                s = s.copy(font = it); saveStyle(s)
            }, lp().apply { topMargin = m.dpi(6f) })
            val sizeLabel = SectionLabel(ctx, "Size", meta = "${s.size + 1} of ${ReaderStyle.SIZES.size}")
            addView(sizeLabel, lp().apply { topMargin = m.dpi(14f) })
            addView(SizeBars(ctx, ReaderStyle.SIZES.size, s.size) {
                s = s.copy(size = it); sizeLabel.meta = "${it + 1} of ${ReaderStyle.SIZES.size}"; saveStyle(s)
            }, lp().apply { topMargin = m.dpi(6f) })
            addView(twoColumns(
                "Line spacing", Segmented(ctx, listOf(Glyph.SPACING_TIGHT, Glyph.SPACING_NORMAL, Glyph.SPACING_LOOSE), emptyList(), s.spacing) { s = s.copy(spacing = it); saveStyle(s) },
                "Margins", Segmented(ctx, listOf(Glyph.MARGIN_NARROW, Glyph.MARGIN_NORMAL, Glyph.MARGIN_WIDE), emptyList(), s.margin) { s = s.copy(margin = it); saveStyle(s) },
            ), lp().apply { topMargin = m.dpi(14f) })
            addView(twoColumns(
                "Alignment", Segmented(ctx, emptyList(), listOf("Left", "Justified"), if (s.justify) 1 else 0) { s = s.copy(justify = it == 1); saveStyle(s) },
                "Full refresh", BoxStepper(ctx, 0..20, settings.getInt("fullEvery", 6), { if (it == 0) "Never" else "Every $it pages" }) {
                    settings.edit().putInt("fullEvery", it).apply(); Epd.ghosts.pageTurnsPerFull = it
                },
            ), lp().apply { topMargin = m.dpi(14f) })
        }
        showPanel(panel, top = false)
    }

    private fun twoColumns(a: String, av: View, b: String, bv: View): View {
        val m = Metrics.of(this)
        fun col(label: String, v: View) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(SectionLabel(this@ReaderActivity, label), lp())
            addView(v, lp().apply { topMargin = m.dpi(6f) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(col(a, av), lp(0, WRAP, 1f).apply { marginEnd = m.dpi(9f) })
            addView(col(b, bv), lp(0, WRAP, 1f).apply { marginStart = m.dpi(9f) })
        }
    }

    // ---- Contents ----

    /** Chapters (with estimated page numbers and "You are here") and bookmarks, a page at a time. */
    private inner class ContentsScreen : Screen(this) {
        private lateinit var column: PagedColumn

        override fun create(): View {
            val m = Metrics.of(context)
            column = PagedColumn(context)
            val bookmarks = library.bookmarks(path!!)
            val tabs = FilterTabs(context, listOf("Chapters", if (bookmarks.isEmpty()) "Bookmarks" else "Bookmarks ${bookmarks.size}")).apply {
                setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0)
                onSelect = { fill(it) }
            }
            fill(0)
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(StatusBar(context), lp())
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(m.dpi(8f), m.dpi(4f), m.gutter.toInt(), 0)
                    addView(IconButton(context, Glyph.BACK, "Back to book").apply { onClick = { pop() } })
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(paperText(context, "Contents", m.sp(28f), Type.serif(600, opsz = 24), maxLines = 1))
                        addView(paperText(context, title, m.label, Type.serif(400, italic = true), 0xFF4A4A4A.toInt(), maxLines = 1).apply { setPadding(0, m.dpi(2f), 0, 0) })
                    }, lp(0, WRAP, 1f))
                }, lp())
                addView(tabs, lp().apply { topMargin = m.dpi(14f) })
                addView(column, lp(h = 0, weight = 1f).apply { topMargin = m.dpi(6f) })
                addView(PageBar(context).apply { topRule = true; setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0); bind(column) }, lp())
            }
        }

        private fun fill(tab: Int) {
            val v = view ?: return
            val m = Metrics.of(context)
            column.removeAllViews()
            if (tab == 0) {
                val (_, pages) = v.pageEstimate()
                val target = v.currentEntryIndex()
                v.toc.forEachIndexed { i, e ->
                    val pageNo = (v.entryFraction(e) * pages).toInt() + 1
                    column.addView(ContentsRow(context, e, pageNo, i == target) { pop(); v.goToEntry(e) })
                }
                if (target > 0) column.post { column.goToChildPage(target) }
            } else {
                val list = library.bookmarks(path!!)
                if (list.isEmpty()) column.addView(paperText(context, "No bookmarks yet. Tap the ribbon in the menu to mark a page.", m.body, Ink.body, Ink.GRAY).apply {
                    setPadding(m.gutter.toInt(), m.dpi(24f), m.gutter.toInt(), 0)
                })
                list.forEach { b -> column.addView(BookmarkRow(context, b) { pop(); v.goToLocation(b.location) }) }
            }
            column.goTo(0)
            column.requestLayout()
        }

        override fun pager(): Pageable = column
    }

    companion object {
        const val EXTRA_PATH = "path"
    }
}

/** Progress track with a tick per chapter and a ring at the current position. Tap to jump. */
private class ProgressTrack(context: Context, private val progress: Float, private val ticks: FloatArray, private val onJump: (Float) -> Unit) : TapView(context) {
    private val track = Ink.fillPaint(Ink.TRACK)
    private val ink = Ink.fillPaint()
    private val tickLater = Ink.fillPaint(0xFF8A8A8A.toInt())
    private val white = Ink.fillPaint(Ink.WHITE)
    private val ring = Ink.strokePaint(m.dp(2.5f))
    private var tapX = 0f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(28f))
    override fun zoneAt(x: Float, y: Float): Int { tapX = x; return 0 }
    override fun onZone(zone: Int) = onJump((tapX / width).coerceIn(0f, 1f))

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        canvas.drawRoundRect(0f, cy - m.dp(2f), width.toFloat(), cy + m.dp(2f), m.dp(2f), m.dp(2f), track)
        canvas.drawRoundRect(0f, cy - m.dp(2f), width * progress, cy + m.dp(2f), m.dp(2f), m.dp(2f), ink)
        for (t in ticks) {
            if (t <= 0f) continue
            val x = width * t
            canvas.drawRect(x - m.dp(0.75f), cy - m.dp(6f), x + m.dp(0.75f), cy + m.dp(6f), if (t <= progress) ink else tickLater)
        }
        val kx = (width * progress).coerceIn(m.dp(9f), width - m.dp(9f))
        canvas.drawCircle(kx, cy, m.dp(8f), white)
        canvas.drawCircle(kx, cy, m.dp(7.75f), ring)
    }
}

/** A contents line: numeral column, title, page number — or "You are here" with a heavy bar. */
private class ContentsRow(context: Context, private val entry: TocEntry, private val page: Int, private val isCurrent: Boolean, click: () -> Unit) : TapView(context) {
    private val numeralPaint = Ink.textPaint(m.body, Type.serif(if (isCurrent) 600 else 400), if (isCurrent) Ink.BLACK else Ink.MUTED)
    private val titlePaint = Ink.textPaint(m.sp(17f), Type.serif(if (isCurrent) 600 else 400))
    private val right = Ink.textPaint(m.small, if (isCurrent) Ink.bold else Ink.body, if (isCurrent) Ink.BLACK else Ink.MUTED).apply { textAlign = Paint.Align.RIGHT }
    private val divider = Ink.fillPaint(Ink.DIVIDER)
    private val ink = Ink.fillPaint()
    private val numeral: String
    private val name: String

    init {
        val match = Regex("""^(chapter|part|book|stave|letter)\s+([IVXLCDM]+|\d+)\.?\s*(.*)$""", RegexOption.IGNORE_CASE).find(entry.title)
        numeral = match?.groupValues?.get(2).orEmpty()
        name = match?.groupValues?.get(3)?.ifBlank { null } ?: entry.title
        onClick = click
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(54f))

    override fun onDraw(canvas: Canvas) {
        val down = pressedZone == 0
        if (down) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
        if (isCurrent && !down) canvas.drawRect(0f, m.dp(12f), m.dp(5f), height - m.dp(12f), ink)
        val left = m.gutter + entry.depth * m.dp(18f)
        val cy = height / 2f
        val fg = if (down) Ink.WHITE else null
        numeralPaint.color = fg ?: if (isCurrent) Ink.BLACK else Ink.MUTED
        titlePaint.color = fg ?: Ink.BLACK
        right.color = fg ?: if (isCurrent) Ink.BLACK else Ink.MUTED
        val rightText = if (isCurrent) "You are here" else "$page"
        val rightW = right.measureText(rightText) + m.dp(14f)
        var x = left
        if (numeral.isNotEmpty()) { canvas.drawText(numeral, x, baseline(numeralPaint, cy), numeralPaint); x += m.dp(44f) }
        canvas.drawText(ellipsize(name, titlePaint, width - m.gutter - rightW - x), x, baseline(titlePaint, cy), titlePaint)
        canvas.drawText(rightText, width - m.gutter, baseline(right, cy), right)
        canvas.drawRect(m.gutter, height - m.hairline, width - m.gutter, height.toFloat(), divider)
    }
}

/** A bookmark: where it is, the opening words of the page, and how far into the book. */
private class BookmarkRow(context: Context, private val b: Bookmark, click: () -> Unit) : TapView(context) {
    private val titlePaint = Ink.textPaint(m.body, Type.serif(500))
    private val snippetPaint = Ink.textPaint(m.small, Type.serif(400, italic = true), Ink.GRAY)
    private val pct = Ink.textPaint(m.small, Ink.body, Ink.MUTED).apply { textAlign = Paint.Align.RIGHT }
    private val divider = Ink.fillPaint(Ink.DIVIDER)
    private val ink = Ink.fillPaint()

    init { onClick = click }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(68f))

    override fun onDraw(canvas: Canvas) {
        val down = pressedZone == 0
        if (down) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
        titlePaint.color = if (down) Ink.WHITE else Ink.BLACK
        snippetPaint.color = if (down) Ink.WHITE else Ink.GRAY
        pct.color = if (down) Ink.WHITE else Ink.MUTED
        val w = width - 2 * m.gutter - m.dp(56f)
        canvas.drawText(ellipsize(b.chapter.ifEmpty { "Bookmark" }, titlePaint, w), m.gutter, m.dp(28f), titlePaint)
        canvas.drawText(ellipsize(b.snippet, snippetPaint, w), m.gutter, m.dp(50f), snippetPaint)
        canvas.drawText("${(b.percent * 100).toInt()}%", width - m.gutter, m.dp(28f), pct)
        canvas.drawRect(m.gutter, height - m.hairline, width - m.gutter, height.toFloat(), divider)
    }
}
