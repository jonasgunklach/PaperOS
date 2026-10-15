package io.paperos.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.format.DateFormat
import android.view.View
import java.util.Date

/**
 * Single-line text that is re-evaluated on a system event but only redrawn when the resulting
 * string actually differs. Tabular digits keep the width stable, so a changing time never shifts
 * neighbouring views (which would widen the damaged region).
 *
 * No timers, alarms or wake locks: we piggyback on broadcasts the system sends anyway, and only
 * while the view is attached (i.e. the screen showing it is visible).
 */
open class LiveText(
    context: Context,
    size: Float,
    face: Typeface = Ink.body,
    color: Int = Ink.BLACK,
    private val sample: String,
    private val compute: (Context) -> String,
) : View(context) {

    protected val paint = Ink.textPaint(size, face, color)
    var align: Paint.Align = Paint.Align.LEFT
        set(v) { field = v; paint.textAlign = v; invalidate() }
    protected var text = ""
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refresh()
    }

    protected open fun filter() = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    /** Recompute; invalidate only on change. */
    fun refresh() {
        val next = compute(context)
        if (next != text) {
            val widthChanged = paint.measureText(next) > measuredWidth
            text = next
            if (widthChanged) requestLayout()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        text = compute(context)
        context.registerReceiver(receiver, filter())
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(receiver)
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText(if (text.length > sample.length) text else sample)
        val h = paint.fontMetrics.let { it.descent - it.ascent }
        setMeasuredDimension(
            resolveSize(w.toInt() + paddingLeft + paddingRight + 1, widthMeasureSpec),
            resolveSize(h.toInt() + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val x = when (align) {
            Paint.Align.LEFT -> paddingLeft.toFloat()
            Paint.Align.CENTER -> width / 2f
            Paint.Align.RIGHT -> (width - paddingRight).toFloat()
        }
        val y = paddingTop - paint.fontMetrics.ascent
        canvas.drawText(text, x, y, paint)
    }

    companion object {
        fun clock(context: Context, size: Float, face: Typeface = Ink.bold) =
            LiveText(context, size, face, Ink.BLACK, "00:00") { c ->
                DateFormat.getTimeFormat(c).format(Date())
            }

        fun date(context: Context, size: Float, pattern: String = "EEEEdMMMM") =
            LiveText(context, size, Ink.body, Ink.BLACK, "Wednesday, 30 September") { _ ->
                DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), pattern), Date()).toString()
            }
    }
}

/**
 * Battery level as a glyph + percentage. ACTION_BATTERY_CHANGED fires often (voltage and
 * temperature changes too), so we quantise to whole percent + charging state and ignore the rest.
 */
class BatteryView(context: Context, size: Float) : View(context) {
    private val m = Metrics.of(context)
    private val textPaint = Ink.textPaint(size, Ink.medium).apply { textAlign = Paint.Align.RIGHT }
    private val outline = Ink.strokePaint(m.border)
    private val outlineThin = Ink.strokePaint(m.dp(0.6f))
    private val fill = Ink.fillPaint()
    private val white = Ink.fillPaint(Ink.WHITE)
    private val bolt = android.graphics.Path()
    private var level = -1
    private var charging = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = update(i)
    }

    private fun update(i: Intent?) {
        i ?: return
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = l * 100 / s
        val ch = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (pct != level || ch != charging) {
            level = pct; charging = ch
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        update(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    }

    override fun onDetachedFromWindow() {
        context.unregisterReceiver(receiver)
        super.onDetachedFromWindow()
    }

    private val glyphW get() = m.dp(24f)
    private val glyphH get() = m.dp(12f)
    private val gap get() = m.dp(8f)
    private val box = android.graphics.RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = textPaint.measureText("100%") + gap + glyphW + paddingLeft + paddingRight
        val h = maxOf(textPaint.fontMetrics.let { it.descent - it.ascent }, glyphH) + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w.toInt() + 1, widthMeasureSpec), resolveSize(h.toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        if (level < 0) return
        val right = (width - paddingRight).toFloat()
        val cy = height / 2f
        canvas.drawText("$level%", right - glyphW - gap, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)

        // Body (20 x 10.5 of 24 x 12), nub, and a fill proportional to the level.
        val k = glyphW / 24f
        val left = right - glyphW
        val top = cy - glyphH / 2
        box.set(left + 0.75f * k, top + 0.75f * k, left + 20.75f * k, top + 11.25f * k)
        canvas.drawRoundRect(box, 2 * k, 2 * k, outline)
        box.set(left + 22 * k, top + 3.5f * k, left + 23.6f * k, top + 8.5f * k)
        canvas.drawRoundRect(box, 0.6f * k, 0.6f * k, fill)
        box.set(left + 3 * k, top + 3 * k, left + (3 + 14.75f * level / 100f) * k, top + 9 * k)
        canvas.drawRoundRect(box, 0.8f * k, 0.8f * k, fill)
        if (charging) {
            val cx = left + 10.75f * k; val s = 4.2f * k
            bolt.reset()
            bolt.moveTo(cx + s * 0.25f, cy - s); bolt.lineTo(cx - s * 0.55f, cy + s * 0.15f)
            bolt.lineTo(cx, cy + s * 0.15f); bolt.lineTo(cx - s * 0.25f, cy + s)
            bolt.lineTo(cx + s * 0.55f, cy - s * 0.15f); bolt.lineTo(cx, cy - s * 0.15f); bolt.close()
            canvas.drawPath(bolt, white)
            canvas.drawPath(bolt, outlineThin)
        }
    }
}
