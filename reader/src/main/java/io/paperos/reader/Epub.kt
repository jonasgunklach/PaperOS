package io.paperos.reader

import android.text.Html
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** One entry of a book's table of contents. */
data class TocEntry(val title: String, val chapter: Int, val depth: Int = 0, val anchor: String? = null)

/**
 * Minimal EPUB 2/3 container reader: OPF metadata, spine, cover and table of contents.
 * Only the framework is used: ZipFile, XmlPullParser for the (strict XML) package files, and
 * forgiving regexes for nav/NCX, which in the wild are often not well-formed.
 */
class Epub(file: File) : Closeable {
    private val zip = ZipFile(file)

    val title: String
    val author: String
    val language: String?
    /** Zip paths of the reading-order documents. */
    val spine: List<String>
    val coverImage: String?
    val toc: List<TocEntry>

    init {
        val opfPath = rootFile() ?: throw IllegalArgumentException("not an EPUB: no rootfile")
        val opfDir = opfPath.substringBeforeLast('/', "")
        val opf = Opf.parse(read(opfPath) ?: throw IllegalArgumentException("missing $opfPath"))
        title = opf.title?.takeIf { it.isNotBlank() } ?: file.nameWithoutExtension
        author = opf.creator.orEmpty()
        language = opf.language
        spine = opf.spine.mapNotNull { opf.manifest[it]?.href }.map { resolve(opfDir, it) }
        coverImage = (opf.manifest.values.firstOrNull { "cover-image" in it.properties }
            ?: opf.coverId?.let { opf.manifest[it] })?.let { resolve(opfDir, it.href) }

        val nav = opf.manifest.values.firstOrNull { "nav" in it.properties }
        val ncx = opf.tocId?.let { opf.manifest[it] } ?: opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        toc = runCatching {
            when {
                nav != null -> parseNav(resolve(opfDir, nav.href))
                ncx != null -> parseNcx(resolve(opfDir, ncx.href))
                else -> emptyList()
            }
        }.getOrDefault(emptyList()).ifEmpty { spine.indices.map { TocEntry("Section ${it + 1}", it) } }
    }

    fun read(path: String): ByteArray? = zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
    /** Where `id="[anchor]"` sits in [path], as a fraction of the file (for progress ticks and page numbers). */
    fun anchorFraction(path: String, anchor: String?): Float {
        if (anchor == null) return 0f
        val text = read(path)?.toString(Charsets.UTF_8) ?: return 0f
        val i = Regex("""\bid\s*=\s*["']${Regex.escape(anchor)}["']""").find(text)?.range?.first ?: return 0f
        return i / text.length.toFloat()
    }

    fun size(path: String): Long = zip.getEntry(path)?.size?.coerceAtLeast(1) ?: 1

    override fun close() = zip.close()

    private fun rootFile(): String? {
        val xml = read("META-INF/container.xml")?.toString(Charsets.UTF_8) ?: return null
        return Regex("""full-path\s*=\s*["']([^"']+)["']""").find(xml)?.groupValues?.get(1)
    }

    /** EPUB 3: `<nav epub:type="toc">` with nested `<ol><li><a href>`. */
    private fun parseNav(path: String): List<TocEntry> {
        val html = read(path)?.toString(Charsets.UTF_8) ?: return emptyList()
        val dir = path.substringBeforeLast('/', "")
        val navBlock = Regex("""<nav[^>]*toc[^>]*>([\s\S]*?)</nav>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1) ?: html
        val out = ArrayList<TocEntry>()
        var depth = 0
        val token = Regex("""<(/?)ol\b[^>]*>|<a\b[^>]*href\s*=\s*["']([^"']*)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
        for (m in token.findAll(navBlock)) {
            if (m.value.startsWith("<ol", true)) depth++
            else if (m.value.startsWith("</ol", true)) depth--
            else addToc(out, dir, m.groupValues[2], m.groupValues[3], (depth - 1).coerceAtLeast(0))
        }
        return out
    }

    /** EPUB 2: navPoints in toc.ncx. Nesting depth from the navPoint stack. */
    private fun parseNcx(path: String): List<TocEntry> {
        val xml = read(path)?.toString(Charsets.UTF_8) ?: return emptyList()
        val dir = path.substringBeforeLast('/', "")
        val out = ArrayList<TocEntry>()
        var depth = -1
        var label: String? = null
        val token = Regex("""<navPoint\b|</navPoint>|<text>([\s\S]*?)</text>|<content\b[^>]*src\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        for (m in token.findAll(xml)) {
            when {
                m.value.startsWith("<navPoint", true) -> depth++
                m.value.startsWith("</navPoint", true) -> depth--
                m.value.startsWith("<text", true) -> label = m.groupValues[1]
                else -> { addToc(out, dir, m.groupValues[2], label.orEmpty(), depth.coerceAtLeast(0)); label = null }
            }
        }
        return out
    }

    private fun addToc(out: MutableList<TocEntry>, dir: String, href: String, rawTitle: String, depth: Int) {
        val target = resolve(dir, href.substringBefore('#'))
        val chapter = spine.indexOf(target)
        val title = Html.fromHtml(rawTitle, Html.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+"), " ").trim()
        val anchor = href.substringAfter('#', "").ifEmpty { null }
        if (chapter >= 0 && title.isNotEmpty()) out += TocEntry(title, chapter, depth, anchor)
    }

    companion object {
        /** Resolve [href] against [dir] inside the zip: handles "../", "./" and %-escapes. */
        fun resolve(dir: String, href: String): String {
            val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrDefault(href)
            if (decoded.startsWith("/")) return decoded.trimStart('/')
            val parts = ArrayList<String>()
            if (dir.isNotEmpty()) parts += dir.split('/')
            for (p in decoded.split('/')) when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
            return parts.joinToString("/")
        }
    }
}

/** The bits of the OPF package document we need. */
private class Opf {
    class Item(val href: String, val mediaType: String, val properties: List<String>)

    var title: String? = null
    var creator: String? = null
    var language: String? = null
    var coverId: String? = null
    var tocId: String? = null
    val manifest = HashMap<String, Item>()
    val spine = ArrayList<String>()

    companion object {
        fun parse(bytes: ByteArray): Opf {
            val opf = Opf()
            val p = Xml.newPullParser()
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            p.setInput(bytes.inputStream(), null)
            var text: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = p.name.substringAfter(':')
                        fun a(n: String) = (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).substringAfter(':') == n }?.let { p.getAttributeValue(it) }
                        when (name) {
                            "item" -> {
                                val id = a("id"); val href = a("href")
                                if (id != null && href != null) opf.manifest[id] = Item(href, a("media-type").orEmpty(), a("properties").orEmpty().split(' '))
                            }
                            "itemref" -> if (a("linear") != "no") a("idref")?.let { opf.spine += it }
                            "spine" -> opf.tocId = a("toc")
                            "meta" -> if (a("name") == "cover") opf.coverId = a("content")
                        }
                        text = ""
                    }
                    XmlPullParser.TEXT -> text = (text ?: "") + p.text
                    XmlPullParser.END_TAG -> {
                        val t = text?.trim()
                        when (p.name.substringAfter(':')) {
                            "title" -> if (opf.title == null) opf.title = t
                            "creator" -> if (opf.creator == null) opf.creator = t
                            "language" -> if (opf.language == null) opf.language = t
                        }
                        text = null
                    }
                }
            }
            return opf
        }
    }
}
