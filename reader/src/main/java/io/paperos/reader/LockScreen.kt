package io.paperos.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.view.Display
import io.paperos.ui.Ink
import io.paperos.ui.Metrics
import io.paperos.ui.Type
import java.io.File

/**
 * What the tablet shows while asleep or switched off. Bigme's standby service displays the images
 * `/data/xrz/standby.png` (sleep) and `/data/xrz/poweroff.png` (off); PaperOS renders its own
 * screens into those files whenever the current book changes.
 */
object LockScreen {
    enum class Mode(val label: String) { COVER("Book cover"), BLANK("Blank"), STOCK("Bigme default") }

    private const val SLEEP = "/data/xrz/standby.png"
    private const val OFF = "/data/xrz/poweroff.png"

    fun mode(context: Context): Mode =
        Mode.entries.getOrElse(context.getSharedPreferences("lockscreen", Context.MODE_PRIVATE).getInt("mode", 0)) { Mode.COVER }

    fun setMode(context: Context, mode: Mode) {
        context.getSharedPreferences("lockscreen", Context.MODE_PRIVATE).edit().putInt("mode", mode.ordinal).apply()
        update(context)
    }

    /** Re-render the sleep and power-off images. Cheap; call when a book is closed or changes. */
    fun update(context: Context) {
        val app = context.applicationContext
        Thread {
            runCatching {
                val mode = mode(app)
                if (mode == Mode.STOCK) return@runCatching
                val book = Library.of(app).recent().firstOrNull { Library.of(app).progress(it.path)?.finished == false }
                write(render(app, if (mode == Mode.COVER) book else null, "Sleeping · press the power button to wake"), SLEEP)
                write(render(app, if (mode == Mode.COVER) book else null, "Switched off · hold the power button to start"), OFF)
            }.onFailure { android.util.Log.w("PaperOS", "lock screen image not written", it) }
        }.start()
    }

    private fun write(bmp: Bitmap, path: String) {
        File(path).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    /** The canvas's "Sleep" screen at the panel's real resolution. */
    fun render(context: Context, book: BookEntry?, footer: String): Bitmap {
        val m = Metrics.of(context)
        val size = Point()
        @Suppress("DEPRECATION")
        context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(size)
        val w = size.x; val h = size.y
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Ink.WHITE)

        if (book != null) {
            val p = Library.of(context).progress(book.path)
            val coverW = w * 0.58f
            val coverH = coverW * 1.42f
            val box = RectF((w - coverW) / 2, h * 0.073f, (w + coverW) / 2, h * 0.073f + coverH)
            Covers.draw(c, box, book.title, book.author, m)

            var y = box.bottom + m.dp(44f)
            if (p != null && p.chapter.isNotEmpty()) {
                val chapter = Ink.textPaint(m.sp(17f), Type.serif(400, italic = true), 0xFF333333.toInt()).apply { textAlign = Paint.Align.CENTER }
                c.drawText(ellipsize(p.chapter, chapter, coverW), w / 2f, y, chapter)
                y += m.dp(18f)
            }
            if (p != null) {
                val track = Ink.fillPaint(Ink.TRACK); val ink = Ink.fillPaint()
                c.drawRoundRect(box.left, y, box.right, y + m.dp(4f), m.dp(2f), m.dp(2f), track)
                c.drawRoundRect(box.left, y, box.left + coverW * p.percent, y + m.dp(4f), m.dp(2f), m.dp(2f), ink)
                y += m.dp(26f)
                c.drawText(progressLabel(p), box.left, y, Ink.textPaint(m.small, Ink.bold))
                val right = Ink.textPaint(m.small, Ink.body, 0xFF4A4A4A.toInt()).apply { textAlign = Paint.Align.RIGHT }
                c.drawText(book.title.split(';', ':').first().trim(), box.right, y, right)
            }
        }

        val foot = Ink.textPaint(m.small, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.CENTER }
        c.drawText(footer, w / 2f, h - m.dp(34f), foot)
        return bmp
    }
}
