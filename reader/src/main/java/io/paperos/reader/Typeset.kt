package io.paperos.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Html
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.QuoteSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.text.style.UnderlineSpan
import io.paperos.ui.Ink
import io.paperos.ui.Metrics
import io.paperos.ui.Type

/** The reader's typographic settings. Small, discrete steps — no sliders. */
data class ReaderStyle(
    val size: Int = 3,          // index into SIZES
    val font: Int = 0,          // 0 Literata, 1 Atkinson, 2 the device's serif
    val spacing: Int = 1,       // 0 tight, 1 normal, 2 loose
    val margin: Int = 1,        // 0 narrow, 1 normal, 2 wide
    val justify: Boolean = true,
) {
    fun textSize(m: Metrics) = m.sp(SIZES[size.coerceIn(0, SIZES.size - 1)])
    val lineSpacing get() = floatArrayOf(1.38f, 1.55f, 1.75f)[spacing]
    fun marginPx(m: Metrics) = m.dp(floatArrayOf(24f, 40f, 56f)[margin])

    fun typeface(): Typeface = when (font) {
        1 -> Type.readingFamily(serif = false)
        2 -> Typeface.SERIF
        else -> Type.readingFamily(serif = true)
    }

    fun paint(m: Metrics) = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = textSize(m)
        color = Ink.BLACK
        typeface = typeface()
        fontFeatureSettings = "liga, kern"
    }

    companion object {
        val SIZES = floatArrayOf(14f, 15.5f, 17f, 18.5f, 20f, 22f, 24f, 26.5f, 29f, 32f)
        val FONT_NAMES = listOf("Literata", "Atkinson", "Serif")
    }
}

/** Zero-width marker for an element id, so TOC links with #fragments can find their place. */
class AnchorSpan(val id: String)

/** Marks a paragraph as part of a chapter heading (laid out centred, unjustified, with space around). */
class HeadingMark

/** Sets face, size, tracking and colour in one span (Html's heading spans can't do tracking). */
class FaceSpan(private val face: Typeface, private val size: Float, private val tracking: Float = 0f, private val color: Int = Ink.BLACK) : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(p: TextPaint) = apply(p)
    private fun apply(p: TextPaint) { p.typeface = face; p.textSize = size; p.letterSpacing = tracking; p.color = color }
}

/** Small caps on the opening words of a chapter, from the font's own smcp glyphs. */
class SmallCapsSpan : MetricAffectingSpan() {
    override fun updateMeasureState(p: TextPaint) = apply(p)
    override fun updateDrawState(p: TextPaint) = apply(p)
    private fun apply(p: TextPaint) { p.fontFeatureSettings = "smcp, c2sc, liga"; p.letterSpacing = 0.04f }
}

/** The small rule–diamond–rule ornament under a chapter title. */
class OrnamentSpan(private val m: Metrics) : ReplacementSpan() {
    private val ink = Ink.fillPaint()
    private val diamond = android.graphics.Path()

    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        fm?.let { val h = m.dpi(22f); it.ascent = -h; it.top = -h; it.descent = m.dpi(12f); it.bottom = m.dpi(12f) }
        return m.dpi(44f)
    }

    override fun draw(c: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val cy = y - m.dp(6f)
        c.drawRect(x, cy - m.dp(0.5f), x + m.dp(17f), cy + m.dp(0.5f), ink)
        c.drawRect(x + m.dp(27f), cy - m.dp(0.5f), x + m.dp(44f), cy + m.dp(0.5f), ink)
        diamond.reset()
        diamond.moveTo(x + m.dp(22f), cy - m.dp(4f)); diamond.lineTo(x + m.dp(26f), cy)
        diamond.lineTo(x + m.dp(22f), cy + m.dp(4f)); diamond.lineTo(x + m.dp(18f), cy); diamond.close()
        c.drawPath(diamond, ink)
    }
}

/**
 * Turns a chapter's XHTML into book-typeset text:
 *  - `<head>`, `<style>`, `<script>` are dropped (Html.fromHtml would print their text)
 *  - SVG cover wrappers become plain images; table rows get their own lines
 *  - colours and in-book link styling are removed
 *  - headings become a letter-spaced label ("CHAPTER II") and an italic display title, followed
 *    by an ornament; the opening words after them are set in small caps
 *  - body paragraphs get a first-line indent, except the first after a heading (classic book style)
 */
object Typeset {
    private val HEAD = Regex("""<head\b[\s\S]*?</head>""", RegexOption.IGNORE_CASE)
    private val NOISE = Regex("""<(style|script)\b[\s\S]*?</\1>""", RegexOption.IGNORE_CASE)
    private val BODY = Regex("""<body\b[^>]*>([\s\S]*)</body>""", RegexOption.IGNORE_CASE)
    private val TABLE_ROW_OPEN = Regex("""<tr\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val TABLE_ROW_CLOSE = Regex("""</tr>""", RegexOption.IGNORE_CASE)
    private val TABLE_CELL_CLOSE = Regex("""</t[dh]>""", RegexOption.IGNORE_CASE)
    private val SVG_IMAGE = Regex("""<svg\b[\s\S]*?<image\b[^>]*?(?:xlink:)?href\s*=\s*["']([^"']+)["'][\s\S]*?</svg>""", RegexOption.IGNORE_CASE)
    private val ID_ATTR = Regex("""(<[a-zA-Z][^>]*?\sid\s*=\s*["']([^"']+)["'][^>]*>)""")
    private val ID_MARK = Regex("\uE000([^\uE001]*)\uE001")
    private val LABEL = Regex("""^((chapter|part|book|volume|section|letter|stave|canto|kapitel|teil|buch|chapitre|capítulo)\b.{0,14}|[IVXLCDM]+\.?|\d{1,3}\.?)$""", RegexOption.IGNORE_CASE)

    fun chapter(context: Context, epub: Epub, index: Int, style: ReaderStyle, pageW: Int, pageH: Int): Spanned {
        val path = epub.spine[index]
        val dir = path.substringBeforeLast('/', "")
        val raw = epub.read(path)?.let { decode(it) } ?: return SpannableStringBuilder("")
        var html = raw.replace(HEAD, "").replace(NOISE, "")
        html = BODY.find(html)?.groupValues?.get(1) ?: html
        html = html.replace(SVG_IMAGE) { "<img src=\"${it.groupValues[1]}\"/>" }
        // Html.fromHtml ignores tables; give rows their own lines so contents pages don't run together.
        html = html.replace(TABLE_ROW_OPEN, "<div>").replace(TABLE_ROW_CLOSE, "</div>").replace(TABLE_CELL_CLOSE, " ")

        // Keep element ids as private-use markers through Html.fromHtml (which drops ids).
        html = html.replace(ID_ATTR) { "${it.groupValues[1]}\uE000${it.groupValues[2]}\uE001" }

        val images = Html.ImageGetter { src -> image(epub, Epub.resolve(dir, src), pageW, pageH) }
        val spanned = anchors(SpannableStringBuilder(Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, images, null)))
        return polish(spanned, Metrics.of(context), style)
    }

    /** Replace id markers with zero-width AnchorSpans (they then follow every later edit). */
    private fun anchors(sb: SpannableStringBuilder): SpannableStringBuilder {
        while (true) {
            val m = ID_MARK.find(sb) ?: break
            sb.delete(m.range.first, m.range.last + 1)
            sb.setSpan(AnchorSpan(m.groupValues[1]), m.range.first, m.range.first, Spannable.SPAN_POINT_POINT)
        }
        return sb
    }

    /** Plain text (TXT files): blank lines separate paragraphs. */
    fun plain(context: Context, text: String, style: ReaderStyle): Spanned {
        val paragraphs = text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { it.replace(Regex("\\s*\n\\s*"), " ").trim() }.filter { it.isNotEmpty() }
        return polish(SpannableStringBuilder(paragraphs.joinToString("\n")), Metrics.of(context), style)
    }

    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val cs = Regex("""encoding\s*=\s*["']([\w-]+)["']""").find(head)?.groupValues?.get(1)
        return runCatching { String(bytes, charset(cs ?: "UTF-8")) }.getOrDefault(String(bytes, Charsets.UTF_8))
    }

    private fun image(epub: Epub, path: String, pageW: Int, pageH: Int): Drawable? {
        val bytes = epub.read(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= pageW || bounds.outHeight / (sample * 2) >= pageH) sample *= 2
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // Fit inside the page (minus a line so the image never needs a page of its own plus one).
        val maxH = (pageH * 0.92f)
        val scale = minOf(pageW / bmp.width.toFloat(), maxH / bmp.height, 2.5f)
        return BitmapDrawable(null, bmp).apply { setBounds(0, 0, (bmp.width * scale).toInt(), (bmp.height * scale).toInt()) }
    }

    private fun polish(src: Spanned, m: Metrics, style: ReaderStyle): Spanned {
        val sb = SpannableStringBuilder(src)
        // Ink only: drop colours and Html's blue quote bar; keep emphasis, sizes, images.
        for (s in sb.getSpans(0, sb.length, Any::class.java)) {
            when (s) {
                is ForegroundColorSpan, is BackgroundColorSpan, is AbsoluteSizeSpan -> sb.removeSpan(s)
                is QuoteSpan -> {
                    val a = sb.getSpanStart(s); val b = sb.getSpanEnd(s)
                    sb.removeSpan(s)
                    sb.setSpan(LeadingMarginSpan.Standard(m.dp(24f).toInt()), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                // In-book links (footnotes, TOC pages) are not tappable yet: show them as plain text.
                is URLSpan, is UnderlineSpan -> sb.removeSpan(s)
            }
        }
        trim(sb)

        val body = style.textSize(m)
        val indent = (body * 1.4f).toInt()

        // Classify every paragraph before changing anything (a heading's spans can cover several
        // lines, e.g. "CHAPTER I.<br/>Down the Rabbit-Hole").
        class Para(val start: Int, val end: Int, val size: Float, val image: Boolean, val centred: Boolean) {
            val heading get() = size > 1.05f
            val breaks get() = heading || image || centred || end - start < 3
        }
        val paras = ArrayList<Para>()
        var i = 0
        while (i < sb.length) {
            var e = sb.indexOf('\n', i); if (e < 0) e = sb.length
            if (e > i) paras += Para(i, e, headingSize(sb, i, e),
                sb.getSpans(i, e, ImageSpan::class.java).isNotEmpty(),
                sb.getSpans(i, e, AlignmentSpan::class.java).any { it.alignment == Layout.Alignment.ALIGN_CENTER })
            i = e + 1
        }
        // Then style back to front, so edits never shift paragraphs still to come.
        for (k in paras.indices.reversed()) {
            val p = paras[k]
            val prev = paras.getOrNull(k - 1)
            val next = paras.getOrNull(k + 1)
            when {
                p.heading -> {
                    var end = p.end
                    if (next?.heading != true) {
                        sb.insert(end, "\n\uFFFC")
                        sb.setSpan(OrnamentSpan(m), end + 1, end + 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                        mark(sb, end + 1, end + 2)
                    }
                    val text = sb.substring(p.start, end).trim()
                    val label = LABEL.matches(text)
                    sb.getSpans(p.start, end, StyleSpan::class.java).forEach {
                        if (sb.getSpanStart(it) >= p.start && sb.getSpanEnd(it) <= end + 1) sb.removeSpan(it)
                    }
                    if (label) {
                        val up = text.uppercase().trimEnd('.')
                        sb.replace(p.start, end, up)
                        end = p.start + up.length
                    }
                    val span = when {
                        label -> FaceSpan(Type.sans(700), m.sp(11.5f), 0.3f, 0xFF444444.toInt())
                        p.size >= 1.3f -> FaceSpan(Type.serif(400, italic = true, opsz = 36), body * (if (p.size >= 1.5f) 1.75f else 1.6f))
                        else -> FaceSpan(Type.serif(600, opsz = 24), body * 1.12f)
                    }
                    sb.setSpan(span, p.start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    mark(sb, p.start, end)
                }
                p.image -> center(sb, p.start, p.end)
                p.centred || p.end - p.start < 3 -> {}
                else -> {
                    if (prev?.heading == true) smallCaps(sb, p.start, p.end)
                    if (prev != null && !prev.breaks) sb.setSpan(LeadingMarginSpan.Standard(indent, 0), p.start, if (p.end < sb.length) p.end + 1 else p.end, Spannable.SPAN_PARAGRAPH)
                }
            }
        }
        // Heading sizes are expressed by FaceSpan now.
        sb.getSpans(0, sb.length, RelativeSizeSpan::class.java).forEach { if (sb.getSpans(sb.getSpanStart(it), sb.getSpanEnd(it), HeadingMark::class.java).isNotEmpty()) sb.removeSpan(it) }
        return sb
    }

    /** The relative size of a span covering the whole paragraph, i.e. an `<h1>`–`<h6>`. */
    private fun headingSize(sb: SpannableStringBuilder, start: Int, end: Int): Float =
        sb.getSpans(start, end, RelativeSizeSpan::class.java)
            .filter { sb.getSpanStart(it) <= start && sb.getSpanEnd(it) >= end - 1 }.maxOfOrNull { it.sizeChange } ?: 1f

    /** Centre + HeadingMark on a paragraph. */
    private fun mark(sb: SpannableStringBuilder, start: Int, end: Int) {
        center(sb, start, end)
        sb.setSpan(HeadingMark(), start, if (end < sb.length) end + 1 else end, Spannable.SPAN_PARAGRAPH)
    }

    /** First three words (or up to the first punctuation) in small caps. */
    private fun smallCaps(sb: SpannableStringBuilder, start: Int, end: Int) {
        var words = 0
        var i = start
        while (i < end && words < 3) {
            val ch = sb[i]
            if (ch == ',' || ch == ';' || ch == '.' || ch == ':' || ch == '!' || ch == '?' || ch == '(') break
            if (ch == ' ' && i > start && sb[i - 1] != ' ') words++
            i++
        }
        if (i > start) sb.setSpan(SmallCapsSpan(), start, i, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun center(sb: SpannableStringBuilder, start: Int, end: Int) {
        val e = if (end < sb.length) end + 1 else end
        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), start, e, Spannable.SPAN_PARAGRAPH)
    }

    /** Remove leading/trailing whitespace and runs of empty lines (Html leaves plenty). */
    private fun trim(sb: SpannableStringBuilder) {
        var i = 0
        while (i < sb.length) {
            if (sb[i] == '\n') {
                var j = i + 1
                while (j < sb.length && (sb[j] == '\n' || sb[j] == ' ')) j++
                if (j - i > 1) {
                    // Keep one blank line where there were several: a scene break.
                    val keep = if (sb.substring(i, j).count { it == '\n' } > 2) 2 else 1
                    sb.replace(i, j, "\n".repeat(keep))
                }
            }
            i++
        }
        while (sb.isNotEmpty() && sb[0].isWhitespace()) sb.delete(0, 1)
        while (sb.isNotEmpty() && sb[sb.length - 1].isWhitespace()) sb.delete(sb.length - 1, sb.length)
    }
}
