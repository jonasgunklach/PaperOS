package io.paperos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.widget.TextView

/** Static text. A plain TextView, stripped of everything that animates or scrolls. */
fun paperText(context: Context, text: CharSequence, size: Float, face: android.graphics.Typeface = Ink.body, color: Int = Ink.BLACK, maxLines: Int = Int.MAX_VALUE) =
    TextView(context).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
        typeface = face
        setTextColor(color)
        includeFontPadding = false
        this.maxLines = maxLines
        if (maxLines != Int.MAX_VALUE) ellipsize = TextUtils.TruncateAt.END
        isHorizontalFadingEdgeEnabled = false
        isVerticalScrollBarEnabled = false
        setLineSpacing(0f, 1.15f)
    }

/**
 * A button. Inverts while pressed; no ripple, no elevation, no state animation.
 */
class PaperButton(context: Context, label: String, private val style: Style = Style.OUTLINE) : View(context) {

    enum class Style { OUTLINE, FILLED, PLAIN }

    private val m = Metrics.of(context)
    private val text = Ink.textPaint(m.body, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val border = Ink.strokePaint(m.border)
    private val fill = Ink.fillPaint()
    private val box = RectF()
    private var pressed = false

    var onClick: (() -> Unit)? = null
    var onLongClick: (() -> Unit)? = null

    var label: String = label
        set(v) {
            if (v == field) return
            field = v
            requestLayout(); invalidate()
        }

    init {
        val pad = m.gutter.toInt()
        setPadding(pad, 0, pad, 0)
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = text.measureText(label) + paddingLeft + paddingRight
        setMeasuredDimension(resolveSize(w.toInt(), widthMeasureSpec), resolveSize(m.touch.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val inset = m.border / 2 + m.dp(2f)
        box.set(inset, inset, width - inset, height - inset)
        val inverted = pressed xor (style == Style.FILLED)
        if (inverted) canvas.drawRoundRect(box, m.radius, m.radius, fill)
        else if (style == Style.OUTLINE) canvas.drawRoundRect(box, m.radius, m.radius, border)
        text.color = if (inverted) Ink.WHITE else Ink.BLACK
        val clipped = TextUtils.ellipsize(label, android.text.TextPaint(text), width - paddingLeft - paddingRight.toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(clipped, 0, clipped.length, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2, text)
    }

    private val tracker = TapTracker(this, object : TapTracker.Target {
        override fun hitTest(x: Float, y: Float) = if (onClick != null || onLongClick != null) 0 else -1
        override fun showPressed(id: Int, pressed: Boolean) { this@PaperButton.pressed = pressed; invalidate() }
        override fun onTap(id: Int) { onClick?.invoke() }
        override fun onLongPress(id: Int): Boolean { val l = onLongClick ?: return false; l(); return true }
    })

    override fun onTouchEvent(event: MotionEvent) = tracker.onTouchEvent(event)
}

/** Base for full-width setting rows: a title, an optional secondary line, and a control on the right. */
abstract class RowView(context: Context, title: String, sub: String? = null) : View(context) {
    protected val m = Metrics.of(context)
    protected val titlePaint = Ink.textPaint(m.body, Ink.body)
    protected val subPaint = Ink.textPaint(m.small, Ink.body, Ink.GRAY)
    protected val ink = Ink.fillPaint()
    protected val border = Ink.strokePaint(m.border)
    protected val rule = Ink.fillPaint(Ink.DIVIDER)
    protected val r = RectF()

    var title = title; set(v) { if (v != field) { field = v; invalidate() } }
    var sub = sub; set(v) { if (v != field) { field = v; invalidate() } }

    init { setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0) }

    /** Width reserved on the right for the control. */
    protected abstract fun controlWidth(): Float
    protected abstract fun drawControl(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = if (sub.isNullOrEmpty()) m.touch + m.dp(8f) else m.touch + m.dp(24f)
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val textRight = width - paddingRight - controlWidth() - m.gutter
        val cy = height / 2f
        val s = sub
        if (s.isNullOrEmpty()) {
            drawEllipsized(canvas, title, paddingLeft.toFloat(), cy - (titlePaint.ascent() + titlePaint.descent()) / 2, textRight, titlePaint)
        } else {
            drawEllipsized(canvas, title, paddingLeft.toFloat(), cy - m.dp(4f), textRight, titlePaint)
            drawEllipsized(canvas, s, paddingLeft.toFloat(), cy + m.dp(4f) - subPaint.ascent(), textRight, subPaint)
        }
        canvas.drawRect(paddingLeft.toFloat(), height - m.hairline, (width - paddingRight).toFloat(), height.toFloat(), rule)
        drawControl(canvas, width - paddingRight - controlWidth(), m.dp(8f), (width - paddingRight).toFloat(), height - m.dp(8f))
    }

    protected fun drawEllipsized(c: Canvas, t: String, x: Float, y: Float, right: Float, p: Paint) {
        val e = TextUtils.ellipsize(t, android.text.TextPaint(p), right - x, TextUtils.TruncateAt.END)
        c.drawText(e, 0, e.length, x, y, p)
    }
}

/** On/off. Feedback is the state change itself, so there is no press inversion. */
class ToggleRow(context: Context, title: String, sub: String?, initial: Boolean, private val onChange: (Boolean) -> Unit) :
    RowView(context, title, sub) {

    var checked = initial
        private set
    private val label = Ink.textPaint(m.caption, Ink.bold).apply { textAlign = Paint.Align.CENTER }

    override fun controlWidth() = m.dp(76f)

    override fun drawControl(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val h = m.dp(34f)
        val cy = (top + bottom) / 2
        r.set(left + m.border / 2, cy - h / 2, right - m.border / 2, cy + h / 2)
        if (checked) canvas.drawRoundRect(r, h / 2, h / 2, ink) else canvas.drawRoundRect(r, h / 2, h / 2, border)
        label.color = if (checked) Ink.WHITE else Ink.BLACK
        canvas.drawText(if (checked) "ON" else "OFF", r.centerX(), cy - (label.ascent() + label.descent()) / 2, label)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            checked = !checked
            invalidate()
            onChange(checked)
        }
        return true
    }
}

/** "−  5  +" instead of a slider: sliders would need a redraw on every touch-move. */
class StepperRow(
    context: Context, title: String, sub: String?,
    private val range: IntRange, private val step: Int, initial: Int,
    private val format: (Int) -> String = { it.toString() },
    private val onChange: (Int) -> Unit,
) : RowView(context, title, sub) {

    var value = initial.coerceIn(range)
        private set
    private val valuePaint = Ink.textPaint(m.body, Ink.bold).apply { textAlign = Paint.Align.CENTER; fontFeatureSettings = "tnum" }
    private val glyph = Ink.strokePaint(m.border)
    private val btn get() = m.touch - m.dp(8f)

    override fun controlWidth() = btn * 2 + m.dp(84f)

    override fun drawControl(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val cy = (top + bottom) / 2
        val b = btn
        drawStep(canvas, left, cy, b, minus = true, enabled = value > range.first)
        drawStep(canvas, right - b, cy, b, minus = false, enabled = value < range.last)
        canvas.drawText(format(value), (left + right) / 2, cy - (valuePaint.ascent() + valuePaint.descent()) / 2, valuePaint)
    }

    private fun drawStep(c: Canvas, x: Float, cy: Float, b: Float, minus: Boolean, enabled: Boolean) {
        val color = if (enabled) Ink.BLACK else Ink.LIGHT
        border.color = color; glyph.color = color
        r.set(x + m.border / 2, cy - b / 2, x + b - m.border / 2, cy + b / 2)
        c.drawRoundRect(r, m.radius, m.radius, border)
        val s = b * 0.22f
        c.drawLine(r.centerX() - s, cy, r.centerX() + s, cy, glyph)
        if (!minus) c.drawLine(r.centerX(), cy - s, r.centerX(), cy + s, glyph)
        border.color = Ink.BLACK
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val left = width - paddingRight - controlWidth()
            val delta = when {
                event.x in left..(left + btn) -> -step
                event.x >= width - paddingRight - btn -> step
                else -> 0
            }
            val next = (value + delta).coerceIn(range)
            if (next != value) {
                value = next
                invalidate()
                onChange(value)
            }
        }
        return true
    }
}

/** A segmented choice. The selected option is drawn inverted. */
class ChoiceRow(context: Context, title: String, private val options: List<String>, initial: Int, private val onChange: (Int) -> Unit) :
    RowView(context, title, null) {

    var selected = initial
        private set
    private val optPaint = Ink.textPaint(m.caption, Ink.bold).apply { textAlign = Paint.Align.CENTER }

    override fun controlWidth(): Float =
        options.maxOf { optPaint.measureText(it) }.plus(m.dp(24f)).times(options.size)

    override fun drawControl(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val h = m.dp(36f)
        val cy = (top + bottom) / 2
        val w = (right - left) / options.size
        r.set(left + m.border / 2, cy - h / 2, right - m.border / 2, cy + h / 2)
        canvas.drawRoundRect(r, m.radius, m.radius, border)
        options.forEachIndexed { i, o ->
            val x0 = left + i * w
            if (i == selected) canvas.drawRect(x0 + m.border, cy - h / 2 + m.border, x0 + w - m.border, cy + h / 2 - m.border, ink)
            else if (i > 0 && i != selected + 1) canvas.drawRect(x0 - m.hairline / 2, cy - h / 2, x0 + m.hairline / 2, cy + h / 2, ink)
            optPaint.color = if (i == selected) Ink.WHITE else Ink.BLACK
            canvas.drawText(o, x0 + w / 2, cy - (optPaint.ascent() + optPaint.descent()) / 2, optPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val left = width - paddingRight - controlWidth()
            if (event.x >= left) {
                val i = ((event.x - left) / (controlWidth() / options.size)).toInt().coerceIn(0, options.size - 1)
                if (i != selected) {
                    selected = i
                    invalidate()
                    onChange(i)
                }
            }
        }
        return true
    }
}

/** A row that opens something. Inverts while pressed. */
class ActionRow(context: Context, title: String, sub: String? = null, private val onClick: () -> Unit) :
    RowView(context, title, sub) {

    private var pressed = false
    private val chevron = Ink.strokePaint(m.border).apply { strokeCap = Paint.Cap.SQUARE }

    override fun controlWidth() = m.dp(20f)

    override fun onDraw(canvas: Canvas) {
        if (pressed) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), ink)
            titlePaint.color = Ink.WHITE; subPaint.color = Ink.WHITE; chevron.color = Ink.WHITE
        } else {
            titlePaint.color = Ink.BLACK; subPaint.color = Ink.GRAY; chevron.color = Ink.BLACK
        }
        super.onDraw(canvas)
    }

    override fun drawControl(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val cy = (top + bottom) / 2; val s = m.dp(7f); val cx = (left + right) / 2
        canvas.drawLine(cx - s / 2, cy - s, cx + s / 2, cy, chevron)
        canvas.drawLine(cx + s / 2, cy, cx - s / 2, cy + s, chevron)
    }

    private val tracker = TapTracker(this, object : TapTracker.Target {
        override fun hitTest(x: Float, y: Float) = 0
        override fun showPressed(id: Int, pressed: Boolean) { this@ActionRow.pressed = pressed; invalidate() }
        override fun onTap(id: Int) = onClick()
    })

    override fun onTouchEvent(event: MotionEvent) = tracker.onTouchEvent(event)
}

/** Letter-spaced caps label that opens a group of rows. */
class SectionHeader(context: Context, private val label: String) : View(context) {
    private val m = Metrics.of(context)
    private val paint = Ink.textPaint(m.caption, Ink.bold, Ink.GRAY, 0.16f)

    init { setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), m.dpi(48f))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawText(label.uppercase(), paddingLeft.toFloat(), height - m.dp(12f), paint)
    }
}
