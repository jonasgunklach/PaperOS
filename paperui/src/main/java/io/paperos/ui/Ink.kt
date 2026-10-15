package io.paperos.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue

/**
 * Design tokens for electrophoretic displays.
 *
 * Near-black ink on white. Everything interactive is drawn in ink only (it survives the fast
 * 1-bit waveforms); the grays are for static secondary text, tracks and hairlines.
 */
object Ink {
    const val BLACK = 0xFF111111.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    /** Secondary text: authors, captions, metadata. */
    const val GRAY = 0xFF5A5A5A.toInt()
    /** Tertiary text: numerals in a contents list, unselected tab labels. */
    const val MUTED = 0xFF6A6A6A.toInt()
    /** Progress-bar track. */
    const val TRACK = 0xFFD4D4D4.toInt()
    /** Hairline under headers. */
    const val RULE = 0xFFCFCFCF.toInt()
    /** Row separators. */
    const val DIVIDER = 0xFFE3E3E3.toInt()
    /** Disabled chevrons. */
    const val LIGHT = 0xFFBDBDBD.toInt()

    /** UI text: Atkinson Hyperlegible Next. */
    val body: Typeface get() = Type.sans(400)
    val medium: Typeface get() = Type.sans(500)
    val bold: Typeface get() = Type.sans(700)
    val light: Typeface get() = Type.sans(400)

    fun textPaint(size: Float, face: Typeface = body, color: Int = BLACK, tracking: Float = 0f) =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size
            typeface = face
            this.color = color
            letterSpacing = tracking
        }

    fun fillPaint(color: Int = BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    fun strokePaint(width: Float, color: Int = BLACK) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        this.color = color
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
}

/**
 * The two bundled typefaces (both SIL OFL, subset to Latin):
 *  - Literata: a serif drawn for e-reading; book text and titles. Variable weight and optical size.
 *  - Atkinson Hyperlegible Next: a sans built for legibility; all UI labels.
 * Instances are created once per (weight, italic, optical size) and cached.
 */
object Type {
    private lateinit var app: Context
    private val cache = HashMap<String, Typeface>()

    fun init(context: Context) { if (!::app.isInitialized) app = context.applicationContext }

    /** [opsz] is the optical size in points: ~12 for reading text, 24–36 for display titles. */
    fun serif(weight: Int = 400, italic: Boolean = false, opsz: Int = 12): Typeface =
        load(if (italic) "fonts/Literata-Italic.ttf" else "fonts/Literata.ttf", "'wght' $weight, 'opsz' $opsz", Typeface.SERIF)

    fun sans(weight: Int = 400): Typeface = load("fonts/Atkinson.ttf", "'wght' $weight", Typeface.SANS_SERIF)

    /**
     * A real font family for running text, so `<b>` and `<i>` pick true bold and italic
     * instances instead of synthesized ones. API 29+; older devices get the regular face.
     */
    fun readingFamily(serif: Boolean): Typeface {
        val key = "family|$serif"
        cache[key]?.let { return it }
        val tf = if (android.os.Build.VERSION.SDK_INT >= 29 && ::app.isInitialized) runCatching {
            fun font(path: String, w: Int, italic: Boolean) = android.graphics.fonts.Font.Builder(app.assets, path)
                .setWeight(w)
                .setSlant(if (italic) android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings(if (serif) "'wght' $w, 'opsz' 12" else "'wght' $w")
                .build()
            val b = if (serif) android.graphics.fonts.FontFamily.Builder(font("fonts/Literata.ttf", 400, false))
                .addFont(font("fonts/Literata.ttf", 700, false))
                .addFont(font("fonts/Literata-Italic.ttf", 400, true))
                .addFont(font("fonts/Literata-Italic.ttf", 700, true))
            else android.graphics.fonts.FontFamily.Builder(font("fonts/Atkinson.ttf", 400, false))
                .addFont(font("fonts/Atkinson.ttf", 700, false))
            Typeface.CustomFallbackBuilder(b.build()).setSystemFallback(if (serif) "serif" else "sans-serif").build()
        }.getOrNull() else null
        return (tf ?: if (serif) serif() else sans()).also { cache[key] = it }
    }

    private fun load(path: String, axes: String, fallback: Typeface): Typeface {
        if (!::app.isInitialized) return fallback
        val key = "$path|$axes"
        return cache.getOrPut(key) {
            runCatching { Typeface.Builder(app.assets, path).setFontVariationSettings(axes).build() }.getOrNull() ?: fallback
        }
    }
}

/** Density-aware sizes, straight from the design canvas (which is drawn in dp). */
class Metrics(context: Context) {
    private val dm = context.resources.displayMetrics

    fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, dm)
    fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, dm)
    fun dpi(v: Float) = dp(v).toInt()

    val border = dp(1.5f)
    val hairline = dp(1f)
    val gutter = dp(28f)
    val touch = dp(48f)
    val radius = dp(6f)

    val caption = sp(12f)
    val small = sp(13f)
    val label = sp(14f)
    val body = sp(15f)
    val title = sp(22f)
    val headline = sp(34f)
    val display = sp(84f)

    companion object {
        @Volatile private var cached: Metrics? = null
        fun of(context: Context): Metrics =
            cached ?: Metrics(context.applicationContext).also { cached = it }
    }
}
