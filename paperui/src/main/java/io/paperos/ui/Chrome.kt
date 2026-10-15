package io.paperos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Base for everything tappable: one or more zones, each inverted while pressed (the cheapest
 * feedback e-ink can give), with the action posted after the pressed state has been drawn.
 */
open class TapView(context: Context) : View(context) {
    protected val m = Metrics.of(context)
    protected var pressedZone = -1
    var onClick: (() -> Unit)? = null

    protected open fun zoneAt(x: Float, y: Float): Int = if (onClick != null) 0 else -1
    protected open fun onZone(zone: Int) { onClick?.invoke() }

    private val tracker = TapTracker(this, object : TapTracker.Target {
        override fun hitTest(x: Float, y: Float) = zoneAt(x, y)
        override fun showPressed(id: Int, pressed: Boolean) {
            val z = if (pressed) id else -1
            if (z != pressedZone) { pressedZone = z; invalidate() }
        }
        override fun onTap(id: Int) = onZone(id)
    })

    override fun onTouchEvent(event: MotionEvent) = tracker.onTouchEvent(event)

    protected fun baseline(p: Paint, cy: Float) = cy - (p.ascent() + p.descent()) / 2
    protected fun fixedHeight(widthSpec: Int, h: Float) = setMeasuredDimension(getDefaultSize(0, widthSpec), h.toInt())
}

/** Top bar: time on the left, battery on the right. Tapping it opens quick settings. */
class StatusBar(context: Context) : FrameLayout(context) {
    private val m = Metrics.of(context)
    var onTap: (() -> Unit)? = null

    init {
        val pad = m.gutter.toInt()
        setPadding(pad, 0, pad, 0)
        addView(LiveText.clock(context, m.label, Ink.bold), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(BatteryView(context, m.label), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(m.dpi(40f), MeasureSpec.EXACTLY))

    override fun onInterceptTouchEvent(ev: MotionEvent) = onTap != null
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) onTap?.invoke()
        return onTap != null
    }
}

/**
 * Bottom navigation. The active tab: solid glyph, bold label, and a heavy bar sitting on the
 * top rule. Selecting is itself the feedback, so tabs don't invert.
 */
class NavBar(context: Context, private val tabs: List<Pair<String, Glyph>>) : View(context) {
    private val m = Metrics.of(context)
    private val label = Ink.textPaint(m.small, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val ink = Ink.fillPaint()

    var selected = 0
        set(v) { if (v != field) { field = v; invalidate() } }
    var onSelect: ((Int) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), m.dpi(68f))

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), m.border, ink)
        val w = width / tabs.size.toFloat()
        tabs.forEachIndexed { i, (text, glyph) ->
            val x0 = w * i
            val cx = x0 + w / 2
            val active = i == selected
            if (active) canvas.drawRect(x0 + w * 0.32f, 0f, x0 + w * 0.68f, m.dp(4f), ink)
            Glyphs.draw(canvas, glyph, cx, m.dp(25f), m.dp(24f), m.dp(1.8f), filled = active)
            label.typeface = if (active) Ink.bold else Ink.body
            label.color = if (active) Ink.BLACK else 0xFF333333.toInt()
            canvas.drawText(text, cx, m.dp(54f), label)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val i = (event.x / (width / tabs.size.toFloat())).toInt().coerceIn(0, tabs.size - 1)
            if (i != selected) { selected = i; onSelect?.invoke(i) }
        }
        return true
    }
}

/**
 * "RECENTLY OPENED ............ Library ›" — a letter-spaced caps label, with either a
 * tappable link or plain metadata on the right.
 */
class SectionLabel(context: Context, private val text: String, link: String? = null, meta: String? = null) : TapView(context) {
    private val labelPaint = Ink.textPaint(m.caption, Ink.bold, Ink.GRAY, 0.16f)
    private val linkPaint = Ink.textPaint(m.label, Ink.bold).apply { textAlign = Paint.Align.RIGHT }
    private val metaPaint = Ink.textPaint(m.small, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.RIGHT }
    private val fill = Ink.fillPaint()
    var link = link; set(v) { if (v != field) { field = v; invalidate() } }
    var meta = meta; set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(32f))
    override fun zoneAt(x: Float, y: Float) = if (link != null && onClick != null && x > width * 0.6f) 0 else -1

    override fun onDraw(canvas: Canvas) {
        val base = baseline(labelPaint, height / 2f)
        canvas.drawText(text.uppercase(), paddingLeft.toFloat(), base, labelPaint)
        val right = (width - paddingRight).toFloat()
        link?.let { l ->
            val chevron = m.dp(14f)
            val tw = linkPaint.measureText(l)
            if (pressedZone == 0) canvas.drawRoundRect(right - tw - chevron - m.dp(10f), 0f, right + m.dp(4f), height.toFloat(), m.radius, m.radius, fill)
            val color = if (pressedZone == 0) Ink.WHITE else Ink.BLACK
            linkPaint.color = color
            canvas.drawText(l, right - chevron - m.dp(4f), baseline(linkPaint, height / 2f), linkPaint)
            Glyphs.draw(canvas, Glyph.FORWARD, right - chevron / 2, height / 2f, chevron, m.dp(2.4f), color)
        }
        if (link == null) meta?.let { canvas.drawText(it, right, baseline(metaPaint, height / 2f), metaPaint) }
    }
}

/** Big serif page title ("Library") with metadata ("14 books") on the right. */
class PageHeader(context: Context, private val title: String, meta: String = "") : View(context) {
    private val m = Metrics.of(context)
    private val titlePaint = Ink.textPaint(m.headline, Type.serif(600, opsz = 36), tracking = -0.01f)
    private val metaPaint = Ink.textPaint(m.label, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.RIGHT }
    var meta = meta; set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(getDefaultSize(0, widthMeasureSpec), m.dpi(54f))

    override fun onDraw(canvas: Canvas) {
        val base = height - m.dp(10f)
        canvas.drawText(title, paddingLeft.toFloat(), base, titlePaint)
        canvas.drawText(meta, (width - paddingRight).toFloat(), base, metaPaint)
    }
}

/**
 * Text tabs with a heavy underline under the selected one, an optional action on the right
 * ("Recent" sort), and a hairline under the whole row.
 */
class FilterTabs(context: Context, private val labels: List<String>, selected: Int = 0) : TapView(context) {
    private val on = Ink.textPaint(m.body, Ink.bold)
    private val off = Ink.textPaint(m.body, Ink.body, 0xFF444444.toInt())
    private val actionPaint = Ink.textPaint(m.label, Ink.body).apply { textAlign = Paint.Align.RIGHT }
    private val ink = Ink.fillPaint()
    private val rule = Ink.fillPaint(Ink.RULE)
    private val gap get() = m.dp(22f)

    var selected = selected; set(v) { if (v != field) { field = v; invalidate() } }
    var onSelect: ((Int) -> Unit)? = null
    var action: String? = null; set(v) { if (v != field) { field = v; invalidate() } }
    var onAction: (() -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(44f))

    private fun tabX(i: Int): Pair<Float, Float> {
        var x = paddingLeft.toFloat()
        for (j in 0 until i) x += (if (j == selected) on else off).measureText(labels[j]) + gap
        return x to x + (if (i == selected) on else off).measureText(labels[i])
    }

    override fun zoneAt(x: Float, y: Float): Int {
        labels.indices.forEach { i -> val (a, b) = tabX(i); if (x in (a - gap / 2)..(b + gap / 2)) return i }
        if (action != null && x > width - paddingRight - m.dp(110f)) return labels.size
        return -1
    }

    override fun onZone(zone: Int) {
        if (zone == labels.size) { onAction?.invoke(); return }
        if (zone != selected) { selected = zone; onSelect?.invoke(zone) }
    }

    override fun onDraw(canvas: Canvas) {
        val base = height - m.dp(16f)
        labels.forEachIndexed { i, l ->
            val (a, b) = tabX(i)
            canvas.drawText(l, a, base, if (i == selected) on else off)
            if (i == selected) canvas.drawRect(a, height - m.dp(4f), b, height - m.dp(1f), ink)
        }
        action?.let {
            val right = (width - paddingRight).toFloat()
            actionPaint.color = if (pressedZone == labels.size) Ink.GRAY else Ink.BLACK
            canvas.drawText(it, right, base, actionPaint)
            Glyphs.draw(canvas, Glyph.SORT, right - actionPaint.measureText(it) - m.dp(14f), base - m.dp(5f), m.dp(16f), m.dp(2f))
        }
        canvas.drawRect(paddingLeft.toFloat(), height - m.hairline, (width - paddingRight).toFloat(), height.toFloat(), rule)
    }
}

/** A 48dp square holding a glyph — or a text mark like "Aa". Inverts while pressed. */
class IconButton(context: Context, private val glyph: Glyph?, description: String, private val text: String? = null) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val textPaint = Ink.textPaint(m.sp(20f), Type.serif(500)).apply { textAlign = Paint.Align.CENTER }
    var active = false; set(v) { if (v != field) { field = v; invalidate() } }

    init { contentDescription = description }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(m.touch.toInt(), m.touch.toInt())

    override fun onDraw(canvas: Canvas) {
        val s = width.toFloat()
        val down = pressedZone == 0
        if (down) canvas.drawRoundRect(m.dp(2f), m.dp(2f), s - m.dp(2f), s - m.dp(2f), m.radius, m.radius, fill)
        val color = if (down) Ink.WHITE else Ink.BLACK
        if (glyph != null) Glyphs.draw(canvas, glyph, s / 2, s / 2, m.dp(22f), m.dp(2.1f), color, filled = active, knockout = if (down) Ink.BLACK else Ink.WHITE)
        else if (text != null) { textPaint.color = color; canvas.drawText(text, s / 2, baseline(textPaint, s / 2), textPaint) }
    }
}

/** Solid black call to action with an optional trailing glyph ("Continue reading →"). */
class PrimaryButton(context: Context, label: String, private val trailing: Glyph? = null) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val textPaint = Ink.textPaint(m.sp(16f), Ink.bold).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    var label = label; set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.touch)

    override fun onDraw(canvas: Canvas) {
        val down = pressedZone == 0
        box.set(m.border / 2, m.border / 2, width - m.border / 2, height - m.border / 2)
        if (down) canvas.drawRoundRect(box, m.radius, m.radius, border) else canvas.drawRoundRect(box, m.radius, m.radius, fill)
        val color = if (down) Ink.BLACK else Ink.WHITE
        textPaint.color = color
        val icon = if (trailing != null) m.dp(18f) + m.dp(10f) else 0f
        val tw = textPaint.measureText(label)
        val x = width / 2f - icon / 2
        canvas.drawText(label, x, baseline(textPaint, height / 2f), textPaint)
        if (trailing != null) Glyphs.draw(canvas, trailing, x + tw / 2 + m.dp(10f) + m.dp(9f), height / 2f, m.dp(18f), m.dp(2.2f), color)
    }
}

/** Outlined button, e.g. "‹ Chapter". */
class OutlineButton(context: Context, private val label: String, private val leading: Glyph? = null, private val trailing: Glyph? = null) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val textPaint = Ink.textPaint(m.label, Ink.bold)
    private val box = RectF()
    private val icon get() = m.dp(16f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = textPaint.measureText(label) + m.dp(28f) + (if (leading != null) icon + m.dp(6f) else 0f) + (if (trailing != null) icon + m.dp(6f) else 0f)
        setMeasuredDimension(resolveSize(w.toInt(), widthMeasureSpec), m.dpi(44f))
    }

    override fun onDraw(canvas: Canvas) {
        val down = pressedZone == 0
        box.set(m.border / 2, m.border / 2, width - m.border / 2, height - m.border / 2)
        if (down) canvas.drawRoundRect(box, m.radius, m.radius, fill) else canvas.drawRoundRect(box, m.radius, m.radius, border)
        val color = if (down) Ink.WHITE else Ink.BLACK
        textPaint.color = color
        var x = m.dp(14f)
        val cy = height / 2f
        leading?.let { Glyphs.draw(canvas, it, x + icon / 2, cy, icon, m.dp(2.4f), color); x += icon + m.dp(6f) }
        canvas.drawText(label, x, baseline(textPaint, cy), textPaint)
        x += textPaint.measureText(label) + m.dp(6f)
        trailing?.let { Glyphs.draw(canvas, it, x + icon / 2, cy, icon, m.dp(2.4f), color) }
    }
}

/** Quick-settings toggle: a 62dp circle (solid when on), the name, and its state underneath. */
class RoundToggle(context: Context, private val glyph: Glyph, private val name: String, state: String, active: Boolean) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val namePaint = Ink.textPaint(m.label, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val statePaint = Ink.textPaint(m.caption, Ink.body, Ink.GRAY).apply { textAlign = Paint.Align.CENTER }
    var state = state; set(v) { if (v != field) { field = v; invalidate() } }
    var active = active; set(v) { if (v != field) { field = v; invalidate() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(108f))

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val r = m.dp(31f)
        val cy = r
        val solid = active xor (pressedZone == 0)
        if (solid) canvas.drawCircle(cx, cy, r, fill) else canvas.drawCircle(cx, cy, r - m.border / 2, border)
        Glyphs.draw(canvas, glyph, cx, cy, m.dp(26f), m.dp(2f), if (solid) Ink.WHITE else Ink.BLACK)
        namePaint.typeface = if (active) Ink.bold else Ink.body
        canvas.drawText(name, cx, r * 2 + m.dp(22f), namePaint)
        canvas.drawText(state, cx, r * 2 + m.dp(40f), statePaint)
    }
}

/** "−  ▮▮▮▮▮▮▯▯▯▯  +" — a stepped level (front light, warmth). No slider: one redraw per tap. */
class StepBar(context: Context, private val steps: Int, value: Int, private val onChange: (Int) -> Unit) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val box = RectF()
    var value = value.coerceIn(0, steps); private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(44f))

    override fun zoneAt(x: Float, y: Float) = when {
        x < m.dp(44f) -> 0
        x > width - m.dp(44f) -> 1
        else -> 2 + (((x - m.dp(54f)) / ((width - m.dp(108f)) / steps)).toInt()).coerceIn(0, steps - 1)
    }

    override fun onZone(zone: Int) {
        val next = when (zone) { 0 -> value - 1; 1 -> value + 1; else -> zone - 1 }.coerceIn(0, steps)
        if (next != value) { value = next; invalidate(); onChange(next) }
    }

    override fun onDraw(canvas: Canvas) {
        val b = m.dp(44f)
        for ((i, x) in listOf(0f, width - b).withIndex()) {
            box.set(x + m.border / 2, m.border / 2, x + b - m.border / 2, b - m.border / 2)
            val down = pressedZone == i
            if (down) canvas.drawRoundRect(box, m.radius, m.radius, fill) else canvas.drawRoundRect(box, m.radius, m.radius, border)
            Glyphs.draw(canvas, if (i == 0) Glyph.MINUS else Glyph.PLUS, box.centerX(), box.centerY(), m.dp(18f), m.dp(2.2f), if (down) Ink.WHITE else Ink.BLACK)
        }
        val left = b + m.dp(10f)
        val right = width - b - m.dp(10f)
        val gap = m.dp(4f)
        val w = (right - left - gap * (steps - 1)) / steps
        val top = height / 2f - m.dp(10f)
        for (i in 0 until steps) {
            val x = left + i * (w + gap)
            box.set(x, top, x + w, top + m.dp(20f))
            if (i < value) canvas.drawRoundRect(box, m.dp(2f), m.dp(2f), fill)
            else { box.inset(m.border / 2, m.border / 2); canvas.drawRoundRect(box, m.dp(2f), m.dp(2f), border) }
        }
    }
}

/** Outlined segmented control; the selected cell is solid. Cells show a glyph or a word. */
class Segmented(context: Context, private val glyphs: List<Glyph?>, private val words: List<String?>, selected: Int, private val onChange: (Int) -> Unit) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val textPaint = Ink.textPaint(m.label, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val n get() = maxOf(glyphs.size, words.size)
    var selected = selected; private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.touch)
    override fun zoneAt(x: Float, y: Float) = (x / (width / n.toFloat())).toInt().coerceIn(0, n - 1)
    override fun onZone(zone: Int) { if (zone != selected) { selected = zone; invalidate(); onChange(zone) } }

    override fun onDraw(canvas: Canvas) {
        val w = width / n.toFloat()
        for (i in 0 until n) {
            val on = (i == selected) xor (i == pressedZone)
            if (on) {
                box.set(i * w, 0f, (i + 1) * w, height.toFloat())
                canvas.save(); canvas.clipRect(box)
                box.set(0f, 0f, width.toFloat(), height.toFloat())
                canvas.drawRoundRect(box, m.radius, m.radius, fill); canvas.restore()
            }
            val color = if (on) Ink.WHITE else Ink.BLACK
            val cx = i * w + w / 2
            glyphs.getOrNull(i)?.let { Glyphs.draw(canvas, it, cx, height / 2f, m.dp(22f), m.dp(2f), color) }
            words.getOrNull(i)?.let {
                textPaint.color = color; textPaint.typeface = if (i == selected) Ink.bold else Ink.body
                canvas.drawText(it, cx, baseline(textPaint, height / 2f), textPaint)
            }
        }
        box.set(m.border / 2, m.border / 2, width - m.border / 2, height - m.border / 2)
        canvas.drawRoundRect(box, m.radius, m.radius, border)
    }
}

/** Font picker cards: "Aa" set in each face, its name underneath. */
class FontCards(context: Context, private val faces: List<Pair<Typeface, String>>, selected: Int, private val onChange: (Int) -> Unit) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val sample = Ink.textPaint(m.sp(26f)).apply { textAlign = Paint.Align.CENTER }
    private val name = Ink.textPaint(m.caption, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    private val gap get() = m.dp(10f)
    var selected = selected; private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.dp(76f))
    override fun zoneAt(x: Float, y: Float) = (x / ((width + gap) / faces.size)).toInt().coerceIn(0, faces.size - 1)
    override fun onZone(zone: Int) { if (zone != selected) { selected = zone; invalidate(); onChange(zone) } }

    override fun onDraw(canvas: Canvas) {
        val w = (width - gap * (faces.size - 1)) / faces.size
        faces.forEachIndexed { i, (face, label) ->
            val x = i * (w + gap)
            val on = (i == selected) xor (i == pressedZone)
            box.set(x, 0f, x + w, height.toFloat())
            if (on) canvas.drawRoundRect(box, m.radius, m.radius, fill)
            else { box.inset(m.border / 2, m.border / 2); canvas.drawRoundRect(box, m.radius, m.radius, border) }
            val color = if (on) Ink.WHITE else Ink.BLACK
            sample.typeface = face; sample.color = color
            canvas.drawText("Aa", x + w / 2, m.dp(40f), sample)
            name.color = color; name.typeface = if (i == selected) Ink.bold else Ink.body
            canvas.drawText(label, x + w / 2, m.dp(62f), name)
        }
    }
}

/** Text size: small "A" button, ten bars rising left to right, large "A" button. */
class SizeBars(context: Context, private val steps: Int, value: Int, private val onChange: (Int) -> Unit) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val small = Ink.textPaint(m.sp(15f), Type.serif()).apply { textAlign = Paint.Align.CENTER }
    private val large = Ink.textPaint(m.sp(26f), Type.serif()).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    var value = value.coerceIn(0, steps - 1); private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.touch)
    override fun zoneAt(x: Float, y: Float) = when {
        x < m.touch -> 0
        x > width - m.touch -> 1
        else -> 2 + ((x - m.touch - m.dp(14f)) / ((width - 2 * m.touch - m.dp(28f)) / steps)).toInt().coerceIn(0, steps - 1)
    }
    override fun onZone(zone: Int) {
        val next = when (zone) { 0 -> value - 1; 1 -> value + 1; else -> zone - 2 }.coerceIn(0, steps - 1)
        if (next != value) { value = next; invalidate(); onChange(next) }
    }

    override fun onDraw(canvas: Canvas) {
        val b = m.touch
        for ((i, x) in listOf(0f, width - b).withIndex()) {
            box.set(x + m.border / 2, m.border / 2, x + b - m.border / 2, b - m.border / 2)
            val down = pressedZone == i
            if (down) canvas.drawRoundRect(box, m.radius, m.radius, fill) else canvas.drawRoundRect(box, m.radius, m.radius, border)
            val p = if (i == 0) small else large
            p.color = if (down) Ink.WHITE else Ink.BLACK
            canvas.drawText("A", box.centerX(), baseline(p, box.centerY()), p)
        }
        val left = b + m.dp(14f)
        val right = width - b - m.dp(14f)
        val step = (right - left) / steps
        val bottom = height / 2f + m.dp(15f)
        for (i in 0 until steps) {
            val cx = left + step * i + step / 2
            val h = m.dp(10f) + m.dp(20f) * i / (steps - 1)
            val bw = if (i == value) m.dp(10f) else m.dp(6f)
            box.set(cx - bw / 2, bottom - h, cx + bw / 2, bottom)
            if (i <= value) canvas.drawRoundRect(box, m.dp(1f), m.dp(1f), fill)
            else { box.inset(m.border / 2, m.border / 2); canvas.drawRoundRect(box, m.dp(1f), m.dp(1f), border) }
        }
    }
}

/** "−   Every 6 pages   +" in an outlined box. */
class BoxStepper(context: Context, private val range: IntRange, value: Int, private val format: (Int) -> String, private val onChange: (Int) -> Unit) : TapView(context) {
    private val fill = Ink.fillPaint()
    private val border = Ink.strokePaint(m.border)
    private val textPaint = Ink.textPaint(m.label, Ink.body).apply { textAlign = Paint.Align.CENTER }
    private val box = RectF()
    var value = value.coerceIn(range); private set

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = fixedHeight(widthMeasureSpec, m.touch)
    override fun zoneAt(x: Float, y: Float) = when { x < m.touch -> 0; x > width - m.touch -> 1; else -> -1 }
    override fun onZone(zone: Int) {
        val next = (value + if (zone == 0) -1 else 1).coerceIn(range)
        if (next != value) { value = next; invalidate(); onChange(next) }
    }

    override fun onDraw(canvas: Canvas) {
        box.set(m.border / 2, m.border / 2, width - m.border / 2, height - m.border / 2)
        canvas.drawRoundRect(box, m.radius, m.radius, border)
        for ((i, cx) in listOf(m.touch / 2, width - m.touch / 2).withIndex()) {
            val down = pressedZone == i
            if (down) canvas.drawRoundRect(cx - m.dp(20f), m.dp(4f), cx + m.dp(20f), height - m.dp(4f), m.radius, m.radius, fill)
            val enabled = if (i == 0) value > range.first else value < range.last
            Glyphs.draw(canvas, if (i == 0) Glyph.MINUS else Glyph.PLUS, cx, height / 2f, m.dp(16f), m.dp(2.4f), if (down) Ink.WHITE else if (enabled) Ink.BLACK else Ink.LIGHT)
        }
        canvas.drawText(format(value), width / 2f, baseline(textPaint, height / 2f), textPaint)
    }
}

/** A bold text link ("Done", "Library ›"). Inverts while pressed. */
class LinkText(context: Context, private val text: String, private val chevron: Boolean = false) : TapView(context) {
    private val paint = Ink.textPaint(m.body, Ink.bold)
    private val fill = Ink.fillPaint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText(text) + m.dp(16f) + (if (chevron) m.dp(18f) else 0f)
        setMeasuredDimension(w.toInt(), m.touch.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val down = pressedZone == 0
        if (down) canvas.drawRoundRect(0f, m.dp(6f), width.toFloat(), height - m.dp(6f), m.radius, m.radius, fill)
        paint.color = if (down) Ink.WHITE else Ink.BLACK
        canvas.drawText(text, m.dp(8f), baseline(paint, height / 2f), paint)
        if (chevron) Glyphs.draw(canvas, Glyph.FORWARD, width - m.dp(15f), height / 2f, m.dp(14f), m.dp(2.4f), paint.color)
    }
}

/** Ellipsize helper for custom-drawn single lines. */
internal fun fit(text: String, paint: Paint, width: Float): CharSequence =
    TextUtils.ellipsize(text, android.text.TextPaint(paint), width, TextUtils.TruncateAt.END)
