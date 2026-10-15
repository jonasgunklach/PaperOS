package io.paperos.reader

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import java.io.Closeable
import java.io.File

/** Where the reader is: a chapter and the first character shown. Survives font/size changes. */
data class Location(val chapter: Int, val offset: Int) {
    fun encode() = "$chapter:$offset"

    companion object {
        fun decode(s: String?): Location? = s?.split(':')?.takeIf { it.size == 2 }?.let {
            Location(it[0].toIntOrNull() ?: return null, it[1].toIntOrNull() ?: return null)
        }
    }
}

/** A reflowable book: a list of chapters that are typeset on demand. */
interface Reflowable : Closeable {
    val title: String
    val author: String
    val chapterCount: Int
    val toc: List<TocEntry>
    /** Relative size of each chapter, for whole-book progress without laying everything out. */
    fun weight(chapter: Int): Long
    fun text(context: Context, chapter: Int, style: ReaderStyle, pageW: Int, pageH: Int): Spanned
    /** How far into its chapter file a TOC entry's anchor sits (0..1). */
    fun anchorFraction(e: TocEntry): Float = 0f
}

class EpubBook(file: File) : Reflowable {
    private val epub = Epub(file)
    override val title get() = epub.title
    override val author get() = epub.author
    override val chapterCount get() = epub.spine.size
    override val toc get() = epub.toc
    override fun weight(chapter: Int) = epub.size(epub.spine[chapter])
    override fun text(context: Context, chapter: Int, style: ReaderStyle, pageW: Int, pageH: Int) =
        Typeset.chapter(context, epub, chapter, style, pageW, pageH)
    private val fractions = HashMap<TocEntry, Float>()
    override fun anchorFraction(e: TocEntry) = fractions.getOrPut(e) { epub.anchorFraction(epub.spine[e.chapter], e.anchor) }
    override fun close() = epub.close()
}

/** Plain text, cut into ~40k-character chapters at paragraph breaks so layout stays fast. */
class TextBook(file: File) : Reflowable {
    private val chunks: List<String>
    override val title = Library.prettyName(file)
    override val author = ""

    init {
        val all = file.readText(Charsets.UTF_8).removePrefix("﻿")
        val out = ArrayList<String>()
        var start = 0
        while (start < all.length) {
            var end = minOf(all.length, start + CHUNK)
            if (end < all.length) {
                val brk = all.indexOf("\n\n", end).let { if (it < 0 || it - end > CHUNK / 2) all.indexOf('\n', end) else it }
                end = if (brk < 0) all.length else brk
            }
            out += all.substring(start, end)
            start = end
        }
        chunks = out.ifEmpty { listOf("") }
    }

    override val chapterCount get() = chunks.size
    override val toc get() = chunks.indices.map { TocEntry("Part ${it + 1}", it) }
    override fun weight(chapter: Int) = chunks[chapter].length.toLong().coerceAtLeast(1)
    override fun text(context: Context, chapter: Int, style: ReaderStyle, pageW: Int, pageH: Int) =
        Typeset.plain(context, chunks[chapter], style)
    override fun close() {}

    companion object { private const val CHUNK = 40_000 }
}

/**
 * One chapter laid out once and cut into pages by whole lines.
 *
 * The chapter is a vertical stack of blocks, each its own StaticLayout: runs of body paragraphs
 * (justified if enabled) and centred blocks — headings and images — which must never be
 * justified and get breathing room above and below. Pages are cut on line boundaries across the
 * whole stack; drawing a page draws only the visible lines of the blocks it touches.
 */
class ChapterPages private constructor(
    val chapter: Int,
    private val text: CharSequence,
    private val blocks: List<Block>,
    pageHeight: Int,
) {
    private class Block(val start: Int, val layout: StaticLayout, val y: Int)

    private val rowBlock: IntArray
    private val rowLine: IntArray
    private val rowTop: IntArray
    private val rowBottom: IntArray
    private val rowStart: IntArray
    private val pages: IntArray

    init {
        val n = blocks.sumOf { it.layout.lineCount }
        rowBlock = IntArray(n); rowLine = IntArray(n); rowTop = IntArray(n); rowBottom = IntArray(n); rowStart = IntArray(n)
        var r = 0
        blocks.forEachIndexed { bi, b ->
            for (l in 0 until b.layout.lineCount) {
                rowBlock[r] = bi; rowLine[r] = l
                rowTop[r] = b.y + b.layout.getLineTop(l)
                rowBottom[r] = b.y + b.layout.getLineBottom(l)
                rowStart[r] = b.start + b.layout.getLineStart(l)
                r++
            }
        }
        val list = ArrayList<Int>()
        var row = 0
        while (row < n) {
            list += row
            val top = rowTop[row]
            var k = row
            while (k < n && rowBottom[k] - top <= pageHeight) k++
            row = if (k == row) row + 1 else k
        }
        pages = if (list.isEmpty()) intArrayOf(0) else list.toIntArray()
    }

    val pageCount get() = pages.size
    val length get() = text.length

    /** Character offset of an in-chapter anchor (`id="…"` in the source), if present. */
    fun anchorOffset(id: String?): Int? {
        if (id == null) return null
        val sp = text as? Spanned ?: return null
        return sp.getSpans(0, sp.length, AnchorSpan::class.java).firstOrNull { it.id == id }?.let { sp.getSpanStart(it) }
    }

    /** The opening words of [page], for bookmarks. */
    fun snippet(page: Int): String {
        val a = startOffset(page)
        return text.subSequence(a, minOf(text.length, a + 120)).toString()
            .replace('\uFFFC', ' ').replace('\n', ' ').trim().let { if (it.length > 90) it.take(90).substringBeforeLast(' ') + "…" else it }
    }

    fun startOffset(page: Int): Int = rowStart.getOrElse(pages[page.coerceIn(0, pages.size - 1)]) { 0 }

    fun pageOf(offset: Int): Int {
        if (rowStart.isEmpty()) return 0
        var row = rowStart.binarySearch(offset)
        if (row < 0) row = (-row - 2).coerceAtLeast(0)
        val i = pages.binarySearch(row)
        return if (i >= 0) i else (-i - 2).coerceAtLeast(0)
    }

    fun draw(canvas: Canvas, page: Int) {
        if (rowTop.isEmpty()) return
        val first = pages[page]
        val last = (if (page + 1 < pages.size) pages[page + 1] else rowTop.size) - 1
        val pageTop = rowTop[first]
        var r = first
        while (r <= last) {
            val bi = rowBlock[r]
            var e = r
            while (e + 1 <= last && rowBlock[e + 1] == bi) e++
            val b = blocks[bi]
            canvas.save()
            canvas.translate(0f, (b.y - pageTop).toFloat())
            canvas.clipRect(0, b.layout.getLineTop(rowLine[r]), b.layout.width, b.layout.getLineBottom(rowLine[e]))
            b.layout.draw(canvas)
            canvas.restore()
            r = e + 1
        }
    }

    companion object {
        fun build(chapter: Int, text: Spanned, paint: TextPaint, style: ReaderStyle, width: Int, height: Int): ChapterPages {
            // Line height as typographers mean it: a multiple of the type size (CSS-style), not of
            // the font's own (already generous) line gap.
            val lineH = paint.textSize * style.lineSpacing
            val extra = lineH - paint.fontSpacing
            val blocks = ArrayList<Block>()
            var y = 0
            for (g in groups(text)) {
                if (blocks.isNotEmpty()) y += (lineH * g.spaceBefore).toInt()
                val sub = text.subSequence(g.start, g.end)
                val layout = StaticLayout.Builder.obtain(sub, 0, sub.length, paint, width)
                    .setLineSpacing(extra, 1f)
                    .setIncludePad(false)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                    .setHyphenationFrequency(if (g.centred) Layout.HYPHENATION_FREQUENCY_NONE else Layout.HYPHENATION_FREQUENCY_FULL)
                    .setJustificationMode(if (style.justify && !g.centred) Layout.JUSTIFICATION_MODE_INTER_WORD else Layout.JUSTIFICATION_MODE_NONE)
                    .build()
                blocks += Block(g.start, layout, y)
                y += layout.height + (lineH * g.spaceAfter).toInt()
            }
            return ChapterPages(chapter, text, blocks, height)
        }

        private class Group(val start: Int, var end: Int, val centred: Boolean, val heading: Boolean) {
            val spaceBefore get() = if (heading) 1.8f else if (centred) 0.5f else 0f
            val spaceAfter get() = if (heading) 0.7f else if (centred) 0.5f else 0f
        }

        /** Consecutive paragraphs of the same kind become one block. */
        private fun groups(text: Spanned): List<Group> {
            val out = ArrayList<Group>()
            var start = 0
            while (start < text.length) {
                var end = text.indexOf('\n', start)
                end = if (end < 0) text.length else end + 1
                val centred = text.getSpans(start, start + 1, android.text.style.AlignmentSpan::class.java)
                    .any { it.alignment == Layout.Alignment.ALIGN_CENTER }
                val heading = text.getSpans(start, start + 1, HeadingMark::class.java).isNotEmpty()
                val last = out.lastOrNull()
                if (last != null && !last.centred && !centred) last.end = end
                else if (last != null && last.centred && centred && last.heading == heading) last.end = end
                else out += Group(start, end, centred, heading)
                start = end
            }
            return out
        }
    }
}
