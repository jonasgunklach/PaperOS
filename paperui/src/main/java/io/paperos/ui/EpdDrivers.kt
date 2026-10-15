package io.paperos.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import java.lang.reflect.Method

/**
 * Picks the panel driver for the running device by probing for vendor APIs (capability, not
 * model strings). Every vendor call is reflective and wrapped: a missing or changed API degrades
 * to [GenericEpd] and the black-flash fallback, never to a crash.
 */
object EpdDrivers {
    fun detect(context: Context): EpdDriver =
        BigmeEpd.create() ?: RockchipEpd.create(context) ?: GenericEpd
}

/**
 * Bigme (MediaTek + "xrz" framework: B6 Color, HiBreak, ...).
 *
 * Bigme's SurfaceFlinger keeps a refresh mode per *layer*; `XrzEinkManager.setRefreshModeByView`
 * sets it for the layer that holds the view. For a clearing refresh we switch our layer to GC16
 * for one update and then hand it back to the vendor default (NORMAL), mirroring how KOReader
 * drives the comparable sunxi API.
 *
 * Reverse-engineered on a HiBreak Plus (Android 14) — see docs/RESEARCH.md. Unverified on the
 * B6; run tools/probe-epd.sh on the device before relying on it.
 */
class BigmeEpd private constructor(private val manager: Any, private val byView: Method) : EpdDriver {
    override val name = "bigme-xrz"

    override fun refresh(view: View, mode: Waveform): Boolean {
        when (mode) {
            Waveform.FULL -> {
                val target = view.rootView
                if (!setMode(target, GC16)) return false
                target.invalidate()
                target.postDelayed({ setMode(target, NORMAL) }, RESTORE_MS)
            }
            // The layer's vendor mode already does differential GL16-class updates.
            Waveform.QUALITY, Waveform.FAST -> view.invalidate()
        }
        return true
    }

    private fun setMode(v: View, mode: Int) = runCatching { byView.invoke(manager, v, mode) }.isSuccess

    companion object {
        const val DU = 2
        const val GC16 = 4
        const val A2 = 16
        const val GL16 = 32
        const val GU16 = 132
        const val CLEAN = 176
        const val HD = 177
        const val NORMAL = 178
        const val FAST = 179
        private const val RESTORE_MS = 700L

        @SuppressLint("PrivateApi")
        fun create(): BigmeEpd? = runCatching {
            val cls = Class.forName("xrz.framework.manager.XrzEinkManager")
            val byView = cls.getMethod("setRefreshModeByView", View::class.java, Int::class.javaPrimitiveType)
            // The constructor takes a Context, but the instance is stateless for our purposes.
            val ctx = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context
            BigmeEpd(cls.getConstructor(Context::class.java).newInstance(ctx), byView)
        }.getOrNull()
    }
}

/**
 * Rockchip RK3566/RK35xx (Meebook, InkBook, Xiaomi, ...): `android.os.EinkManager`, reached as
 * system service "eink". `sendOneFullFrame()` schedules a single GC16 frame; partial updates are
 * already diffed by the EBC driver.
 */
class RockchipEpd private constructor(private val manager: Any, private val oneFull: Method) : EpdDriver {
    override val name = "rockchip-eink"

    override fun refresh(view: View, mode: Waveform): Boolean {
        if (mode == Waveform.FULL) return runCatching { oneFull.invoke(manager) }.isSuccess.also { view.invalidate() }
        view.invalidate()
        return true
    }

    companion object {
        @SuppressLint("WrongConstant")
        fun create(context: Context): RockchipEpd? = runCatching {
            val m = context.getSystemService("eink") ?: return null
            RockchipEpd(m, m.javaClass.getMethod("sendOneFullFrame"))
        }.getOrNull()
    }
}
