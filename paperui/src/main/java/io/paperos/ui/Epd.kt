package io.paperos.ui

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * The three update classes every e-ink controller offers in some form.
 *
 *  - [FULL]    GC16-style: the panel flashes and rewrites every pixel. Clears ghosting. Slow (~450ms), costly.
 *  - [QUALITY] GL16/GU-style: only changed pixels move, 16 gray levels, no flash. The default.
 *  - [FAST]    A2/DU-style: 1-bit, ~120ms. For press feedback and pen/drag, never for content with gray.
 */
enum class Waveform { FULL, QUALITY, FAST }

/** Vendor-specific bridge to the panel controller. Every method must be safe to call on any device. */
interface EpdDriver {
    val name: String

    /**
     * Arrange for the next update of [view]'s window to use [mode] and invalidate it.
     * Returns false when the device has no way to honour the request.
     */
    fun refresh(view: View, mode: Waveform): Boolean
}

/** Fallback for unknown panels (and for emulators): no vendor API at all. */
object GenericEpd : EpdDriver {
    override val name = "generic"
    override fun refresh(view: View, mode: Waveform): Boolean {
        view.invalidate()
        return mode != Waveform.FULL
    }
}

/**
 * Counts partial updates and decides when a clearing full refresh is due.
 * Ghosting accumulates with each partial update; flushing it every N page turns is what
 * Kindle and Kobo do, and the user can tune N (0 = never automatically).
 */
class GhostBudget(var pageTurnsPerFull: Int = 6) {
    private var turns = 0

    /** Returns true when this page turn should be a full refresh. */
    fun onPageTurn(): Boolean {
        if (pageTurnsPerFull <= 0) return false
        turns++
        if (turns >= pageTurnsPerFull) { turns = 0; return true }
        return false
    }

    fun reset() { turns = 0 }
}

object Epd {
    var driver: EpdDriver = GenericEpd
        private set

    val ghosts = GhostBudget()

    /** Called once from the host Activity; picks the best driver for this hardware. */
    fun init(detected: EpdDriver) { driver = detected }

    /** Content of a large area changed (page turn, screen change). Partial, unless ghosting is due. */
    fun pageTurn(view: View) {
        if (ghosts.onPageTurn()) full(view) else driver.refresh(view, Waveform.QUALITY)
    }

    /** Count a change toward the ghosting budget without redrawing anything extra. */
    fun countTurn(view: View) {
        if (ghosts.onPageTurn()) full(view)
    }

    /** Explicit clean-slate refresh, e.g. from a "Refresh" button. */
    fun full(view: View) {
        ghosts.reset()
        // Let the new content draw first, then clear. Otherwise we would flash the old frame.
        view.post {
            if (!driver.refresh(view, Waveform.FULL)) flash(view)
        }
    }

    /**
     * Driver-agnostic clearing refresh: cover the window with black for one panel update, then
     * reveal the content again. The black→content transition drives every pixel through its full
     * range, which removes ghosting on any controller without vendor APIs.
     */
    fun flash(view: View) {
        val activity = view.context as? Activity ?: return
        val decor = activity.window.decorView as? ViewGroup ?: return
        val cover = View(activity).apply { setBackgroundColor(Ink.BLACK) }
        decor.addView(cover, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The panel needs the black frame to actually land before we remove it.
        cover.postDelayed({ decor.removeView(cover) }, FLASH_MS)
    }

    private const val FLASH_MS = 160L
}
