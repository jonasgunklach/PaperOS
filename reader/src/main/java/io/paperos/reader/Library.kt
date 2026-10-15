package io.paperos.reader

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.LruCache
import io.paperos.ui.Ink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

data class BookEntry(val path: String, val title: String, val author: String, val format: String, val size: Long, val modified: Long) {
    val key get() = path
}

/** Where a book was left. [chapter] and [pagesLeft] let Home describe it without opening the file. */
data class Progress(val location: String, val percent: Float, val opened: Long, val chapter: String = "", val pagesLeft: Int = -1) {
    val finished get() = percent >= 0.995f
}

data class Bookmark(val location: String, val percent: Float, val chapter: String, val snippet: String)

/**
 * The books on this device: found by walking shared storage, indexed to a small JSON file so the
 * Library opens instantly, with per-book reading progress and cached cover thumbnails.
 */
class Library private constructor(context: Context) {
    private val app = context.applicationContext
    private val indexFile = File(app.filesDir, "library.json")
    private val progressPrefs = app.getSharedPreferences("progress", Context.MODE_PRIVATE)
    private val bookmarkPrefs = app.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    private val coverDir = File(app.cacheDir, "covers").apply { mkdirs() }
    private val covers = object : LruCache<String, Bitmap>(6 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "paper-library").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    @Volatile var books: List<BookEntry> = readIndex()
        private set

    fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    fun accessIntent(): Intent =
        if (Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${app.packageName}"))
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))

    /** Rescan storage in the background; [done] runs on the main thread only if something changed. */
    fun scan(done: (List<BookEntry>) -> Unit) {
        if (!hasAccess()) return
        io.execute {
            val known = books.associateBy { it.path }
            val found = ArrayList<BookEntry>()
            walk(Environment.getExternalStorageDirectory(), 0) { f ->
                val old = known[f.path]
                found += if (old != null && old.modified == f.lastModified() && old.size == f.length()) old else describe(f)
            }
            found.sortBy { it.title.lowercase() }
            if (found != books) {
                books = found
                writeIndex(found)
                main.post { done(found) }
            }
        }
    }

    fun progress(path: String): Progress? {
        val raw = progressPrefs.getString(path, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            Progress(o.getString("l"), o.getDouble("p").toFloat(), o.getLong("t"), o.optString("c"), o.optInt("left", -1))
        }.getOrElse {
            // Older "location|percent|time" records.
            raw.split('|').takeIf { it.size == 3 }?.let { Progress(it[0], it[1].toFloatOrNull() ?: 0f, it[2].toLongOrNull() ?: 0L) }
        }
    }

    fun saveProgress(path: String, location: String, percent: Float, chapter: String = "", pagesLeft: Int = -1) {
        val o = JSONObject().put("l", location).put("p", percent.toDouble()).put("t", System.currentTimeMillis()).put("c", chapter).put("left", pagesLeft)
        progressPrefs.edit().putString(path, o.toString()).apply()
    }

    fun bookmarks(path: String): List<Bookmark> = runCatching {
        val a = JSONArray(bookmarkPrefs.getString(path, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Bookmark(o.getString("l"), o.getDouble("p").toFloat(), o.optString("c"), o.optString("s")) } }
    }.getOrDefault(emptyList())

    fun isBookmarked(path: String, location: String) = bookmarks(path).any { it.location == location }

    /** Add or remove a bookmark at [b]'s location. Returns true if it is now bookmarked. */
    fun toggleBookmark(path: String, b: Bookmark): Boolean {
        val list = bookmarks(path).toMutableList()
        val existing = list.indexOfFirst { it.location == b.location }
        if (existing >= 0) list.removeAt(existing) else list += b
        list.sortBy { it.percent }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("l", it.location).put("p", it.percent.toDouble()).put("c", it.chapter).put("s", it.snippet)) }
        bookmarkPrefs.edit().putString(path, a.toString()).apply()
        return existing < 0
    }

    /** Books opened at least once, most recent first. */
    fun recent(): List<BookEntry> {
        val byPath = books.associateBy { it.path }
        return progressPrefs.all.keys.mapNotNull { p -> byPath[p]?.let { it to (progress(p)?.opened ?: 0L) } }
            .sortedByDescending { it.second }.map { it.first }
    }

    /**
     * Cover thumbnail, [w] px wide; null when the book has none (callers draw a typographic cover).
     * Cheap when cached; otherwise call from a background thread or accept a short stall.
     */
    fun cover(entry: BookEntry, w: Int): Bitmap? {
        val key = "${entry.path.hashCode()}_${entry.modified}_$w"
        covers.get(key)?.let { return it }
        val disk = File(coverDir, "$key.png")
        val bmp = if (disk.exists()) BitmapFactory.decodeFile(disk.path)
        else extractCover(entry, w)?.also { b -> runCatching { disk.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        if (bmp != null) covers.put(key, bmp)
        return bmp
    }

    /** Fill the cover cache for [entries] in the background, then call [done] on the main thread. */
    fun warmCovers(entries: List<BookEntry>, w: Int, done: () -> Unit) {
        io.execute {
            entries.forEach { runCatching { cover(it, w) } }
            main.post(done)
        }
    }

    fun cachedCover(entry: BookEntry, w: Int): Bitmap? = covers.get("${entry.path.hashCode()}_${entry.modified}_$w")

    private fun extractCover(entry: BookEntry, w: Int): Bitmap? = runCatching {
        when (entry.format) {
            "epub" -> Epub(File(entry.path)).use { e ->
                val bytes = e.coverImage?.let { e.read(it) } ?: return null
                decodeScaled(bytes, w)
            }
            "pdf" -> ParcelFileDescriptor.open(File(entry.path), ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    r.openPage(0).use { pg ->
                        val s = w / pg.width.toFloat()
                        Bitmap.createBitmap(w, (pg.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Ink.WHITE)
                            pg.render(it, null, Matrix().apply { setScale(s, s) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            else -> null
        }
    }.getOrNull()

    private fun decodeScaled(bytes: ByteArray, w: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= w) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (b.width <= w) return b
        return Bitmap.createScaledBitmap(b, w, (b.height * w / b.width.toFloat()).toInt(), true).also { if (it !== b) b.recycle() }
    }

    private fun walk(dir: File, depth: Int, onBook: (File) -> Unit) {
        if (depth > 5) return
        val children = dir.listFiles() ?: return
        for (f in children) {
            if (f.name.startsWith('.')) continue
            if (f.isDirectory) { if (!(depth == 0 && f.name == "Android")) walk(f, depth + 1, onBook) }
            else if (f.extension.lowercase() in FORMATS) onBook(f)
        }
    }

    private fun describe(f: File): BookEntry {
        val fmt = f.extension.lowercase()
        var title = prettyName(f)
        var author = ""
        if (fmt == "epub") runCatching { Epub(f).use { title = it.title; author = it.author } }
        return BookEntry(f.path, title, author, fmt, f.length(), f.lastModified())
    }

    private fun readIndex(): List<BookEntry> = runCatching {
        val a = JSONArray(indexFile.readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).let { o -> BookEntry(o.getString("p"), o.getString("t"), o.optString("a"), o.getString("f"), o.getLong("s"), o.getLong("m")) }
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<BookEntry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("p", it.path).put("t", it.title).put("a", it.author).put("f", it.format).put("s", it.size).put("m", it.modified)) }
        runCatching { indexFile.writeText(a.toString()) }
    }

    companion object {
        /** "moby_dick-1851" → "Moby Dick 1851" for files without metadata. */
        fun prettyName(f: File) = f.nameWithoutExtension.replace('_', ' ').replace('-', ' ').trim()
            .split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

        val FORMATS = setOf("epub", "pdf", "txt")

        @Volatile private var instance: Library? = null
        fun of(context: Context) = instance ?: synchronized(this) { instance ?: Library(context).also { instance = it } }

        fun open(context: Context, path: String) {
            context.startActivity(Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_PATH, path),
                android.app.ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
        }
    }
}
