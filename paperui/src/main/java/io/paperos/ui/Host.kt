package io.paperos.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.window.OnBackInvokedDispatcher

/**
 * One screen of an app. Screens are plain view trees swapped in place: no fragments, no
 * transitions, no back-stack animation. The view is built lazily and kept while on the stack.
 */
abstract class Screen(val host: PaperActivity) {
    internal var view: View? = null
    val context: Context get() = host

    abstract fun create(): View
    open fun onShow() {}
    open fun onHide() {}
    /** The pageable content hardware page keys should drive, if any. */
    open fun pager(): Pageable? = null
    /** Return true if the screen consumed back (e.g. left a sub-mode). */
    open fun onBack(): Boolean = false

    /** Drop the view so it is rebuilt the next time this screen is shown (e.g. after a layout setting changed). */
    fun rebuild() { view = null }
}

/**
 * Root container: holds the current screen and an optional bottom sheet. Tapping outside the
 * sheet dismisses it without a full-screen overlay view (which would turn every sheet into a
 * full-screen redraw).
 */
class PaperRoot(context: Context) : FrameLayout(context) {
    private var content: View? = null
    private val sheets = ArrayList<View>()

    init {
        setBackgroundColor(Ink.WHITE)
        setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    fun setContent(v: View) {
        dismissSheet()
        content?.let { removeView(it) }
        (v.parent as? ViewGroup)?.removeView(v)
        content = v
        addView(v, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    val hasSheet get() = sheets.isNotEmpty()

    /** Show [v] anchored to the bottom (action sheets, display settings) or the top (menus, quick settings). */
    fun showSheet(v: View, top: Boolean = false) {
        dismissSheet()
        addSheet(v, top)
    }

    /** Show a top and a bottom panel together (the reader menu). Tapping between them closes both. */
    fun showSheets(top: View, bottom: View) {
        dismissSheet()
        addSheet(top, true)
        addSheet(bottom, false)
    }

    private fun addSheet(v: View, top: Boolean) {
        sheets += v
        addView(v, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, if (top) Gravity.TOP else Gravity.BOTTOM))
    }

    fun dismissSheet(): Boolean {
        if (sheets.isEmpty()) return false
        sheets.forEach { removeView(it) }
        sheets.clear()
        // Whatever the sheets covered is redrawn now; overlays are a classic ghosting source.
        Epd.countTurn(this)
        return true
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (sheets.isEmpty() || ev.actionMasked != MotionEvent.ACTION_DOWN) return false
        val inside = sheets.any { ev.x >= it.left && ev.x < it.right && ev.y >= it.top && ev.y < it.bottom }
        if (!inside) {
            dismissSheet()
            return true // swallow the rest of this gesture
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent) = true
}

/**
 * A sheet: solid white, a heavy rule on the edge facing the content, no dimming, no slide-in.
 * Bottom sheets hold actions; top sheets hold quick settings.
 */
open class Panel(context: Context, private val onTop: Boolean = false) : LinearLayout(context) {
    protected val m = Metrics.of(context)
    private val rule = Ink.fillPaint()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        setBackgroundColor(Ink.WHITE)
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val t = m.border * 2
        if (onTop) canvas.drawRect(0f, height - t, width.toFloat(), height.toFloat(), rule)
        else canvas.drawRect(0f, 0f, width.toFloat(), t, rule)
    }
}

/** Bottom sheet of actions. */
class Sheet(context: Context, title: String?, actions: List<Pair<String, () -> Unit>>, onPicked: () -> Unit) : Panel(context) {
    init {
        setPadding(0, m.dpi(14f), 0, m.dpi(16f))
        if (title != null) addView(SectionLabel(context, title).apply { setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0) })
        for ((label, action) in actions) {
            addView(ActionRow(context, label) { onPicked(); action() })
        }
    }
}

/**
 * Base activity for every PaperOS app.
 *
 * - Keeps HWUI on: it hands the compositor one tight damage rectangle per frame. Each element
 *   that changes independently is its own View (own RenderNode), so that rectangle is small.
 * - Owns a stack of [Screen]s. Changing screens counts as a page turn for ghosting.
 * - Hides the system bars by default so the status bar never triggers refreshes of its own.
 */
abstract class PaperActivity : Activity() {

    lateinit var root: PaperRoot
        private set
    private val stack = ArrayList<Screen>()
    val current: Screen? get() = stack.lastOrNull()

    /** Rebuild every screen below the current one when it is next shown. */
    fun rebuildBelow() { for (i in 0 until stack.size - 1) stack[i].rebuild() }

    /** Whether to keep the system bars hidden (swipe from the edge reveals them transiently). */
    open val hideSystemBars: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        Type.init(this)
        super.onCreate(savedInstanceState)
        Epd.init(EpdDrivers.detect(this))
        android.util.Log.i("PaperOS", "EPD driver: ${Epd.driver.name}")
        root = PaperRoot(this)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applySystemBars()
    }

    fun applySystemBars() {
        val hide = hideSystemBars
        if (Build.VERSION.SDK_INT >= 30) {
            val c = window.insetsController ?: return
            if (hide) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                c.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hide)
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    fun push(screen: Screen) {
        current?.onHide()
        stack += screen
        show(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1).apply { onHide(); view = null }
        show(stack.last())
        return true
    }

    /** Replace the whole stack with [screen]. */
    fun resetTo(screen: Screen) {
        root.dismissSheet()
        while (stack.isNotEmpty()) stack.removeAt(stack.size - 1).apply { onHide(); view = null }
        push(screen)
    }

    /** Drop everything above the bottom screen (e.g. the Home button was pressed). */
    fun popToRoot() {
        if (stack.size <= 1) { root.dismissSheet(); return }
        while (stack.size > 1) stack.removeAt(stack.size - 1).apply { onHide(); view = null }
        show(stack.last())
    }

    private fun show(screen: Screen) {
        val v = screen.view ?: screen.create().also { screen.view = it }
        root.setContent(v)
        screen.onShow()
        Epd.pageTurn(root)
    }

    fun showSheet(title: String?, actions: List<Pair<String, () -> Unit>>) {
        root.showSheet(Sheet(this, title, actions) { root.dismissSheet() })
    }

    fun showPanel(panel: View, top: Boolean) = root.showSheet(panel, top)
    fun showPanels(top: View, bottom: View) = root.showSheets(top, bottom)
    fun dismissPanel() = root.dismissSheet()

    /** Back at the bottom of the stack. Launchers override this to do nothing. */
    open fun onRootBack() = finish()

    private fun handleBack() {
        if (root.dismissSheet()) return
        if (current?.onBack() == true) return
        if (!pop()) onRootBack()
    }

    @Deprecated("Pre-33 path; 33+ uses OnBackInvokedDispatcher")
    override fun onBackPressed() = handleBack()

    /** Page keys, D-pad and (optionally) volume keys turn pages; there is nothing to scroll. */
    open val volumeKeysTurnPages: Boolean get() = false

    /** A page key was pressed. By default it turns the current screen's pager. */
    open fun onPageKey(forward: Boolean): Boolean {
        val pager = current?.pager() ?: return false
        if (forward) pager.next() else pager.previous()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val forward = when (keyCode) {
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> true
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> false
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysTurnPages) true else null
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysTurnPages) false else null
            else -> null
        }
        if (forward != null && onPageKey(forward)) return true
        return super.onKeyDown(keyCode, event)
    }
}
