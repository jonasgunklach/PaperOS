package io.paperos.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/** Global, user-tunable behaviour of the toolkit. */
object PaperUi {
    /** Invert a touched element while the finger is down. The cheapest feedback e-ink can give. */
    var invertOnPress = true
}

/**
 * Discrete gesture recogniser for e-ink: tap, long-press and a single "swipe = turn page" gesture.
 * There is no drag, fling or scroll: nothing moves under the finger, so nothing has to be redrawn
 * per touch-move event.
 *
 * Press feedback is deferred by [PRESS_DELAY_MS] so a swipe does not first invert and then
 * restore the cell under the finger (two wasted panel updates).
 */
class TapTracker(private val view: View, private val target: Target) {

    interface Target {
        /** Id of the pressable thing at (x, y), or -1. */
        fun hitTest(x: Float, y: Float): Int
        /** Redraw [id] in its pressed / normal state. Should invalidate only that element's rect. */
        fun showPressed(id: Int, pressed: Boolean)
        fun onTap(id: Int)
        fun onLongPress(id: Int): Boolean = false
        /** forward = swipe left or up. Return true if handled. */
        fun onSwipe(forward: Boolean): Boolean = false
    }

    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop * 1.5f
    private val swipeMin = Metrics.of(view.context).dp(56f)

    private var downX = 0f
    private var downY = 0f
    private var id = -1
    private var shown = false
    private var moved = false
    private var longPressed = false

    private val showPress = Runnable {
        if (id >= 0 && !moved && PaperUi.invertOnPress) { shown = true; target.showPressed(id, true) }
    }
    private val longPress = Runnable {
        if (id >= 0 && !moved && target.onLongPress(id)) {
            longPressed = true
            clearPress()
        }
    }

    fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                moved = false; longPressed = false; shown = false
                id = target.hitTest(e.x, e.y)
                if (id >= 0) {
                    view.postDelayed(showPress, PRESS_DELAY_MS)
                    view.postDelayed(longPress, LONG_PRESS_MS)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (abs(e.x - downX) > slop || abs(e.y - downY) > slop)) {
                    moved = true
                    cancelTimers()
                    clearPress()
                }
            }
            MotionEvent.ACTION_UP -> {
                cancelTimers()
                if (longPressed) return true
                val dx = e.x - downX
                val dy = e.y - downY
                if (moved) {
                    clearPress()
                    when {
                        abs(dx) > swipeMin && abs(dx) > abs(dy) * 1.5f -> target.onSwipe(dx < 0)
                        abs(dy) > swipeMin && abs(dy) > abs(dx) * 1.5f -> target.onSwipe(dy < 0)
                    }
                    return true
                }
                val tapped = id
                if (tapped < 0) return true
                if (!shown && PaperUi.invertOnPress) {
                    // Quick tap: make sure the press is actually seen before the (maybe slow) action.
                    shown = true
                    target.showPressed(tapped, true)
                }
                // Posted messages run after the pending traversal, so the inverted state is drawn first.
                view.post {
                    target.onTap(tapped)
                    clearPress(tapped)
                }
                id = -1
            }
            MotionEvent.ACTION_CANCEL -> { cancelTimers(); clearPress() }
        }
        return true
    }

    private fun cancelTimers() {
        view.removeCallbacks(showPress)
        view.removeCallbacks(longPress)
    }

    private fun clearPress(which: Int = id) {
        if (shown && which >= 0) {
            shown = false
            // Always reset: if the tap swapped screens, the view is detached and the invalidate is
            // free, but it must not come back inverted when its screen is shown again.
            target.showPressed(which, false)
        }
        if (which == id) id = -1
    }

    companion object {
        const val PRESS_DELAY_MS = 60L
        const val LONG_PRESS_MS = 550L
    }
}
