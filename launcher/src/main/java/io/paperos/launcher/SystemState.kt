package io.paperos.launcher

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.provider.Settings
import kotlin.math.roundToInt

/**
 * The radios and the front light, switched directly. PaperOS is a system app in the PaperOS ROM,
 * which is what Android requires for turning Wi-Fi/Bluetooth/airplane mode on and off.
 *
 * Front light: the B6 has two LED strings, cold and warm, each driven through Bigme's framework
 * class `android.hwebook.Xrzebook` with a raw level 0–222 (37 steps, table below, taken from
 * Bigme's own control centre). PaperOS presents it Kindle-style as one brightness plus a warmth
 * mix: at warmth 0 only the cold string is lit, at 10 only the warm one.
 */
class SystemState(private val context: Context) {
    private val resolver = context.contentResolver
    private val prefs = context.getSharedPreferences("light", Context.MODE_PRIVATE)
    private val wifiManager get() = context.getSystemService(WifiManager::class.java)
    private val btAdapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val airplane get() = Settings.Global.getInt(resolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    val wifi get() = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)
    val bluetooth get() = runCatching { btAdapter?.isEnabled == true }.getOrDefault(false)

    /** Returns the state now requested (the radio follows within a second or two). */
    @Suppress("DEPRECATION")
    fun toggleWifi(): Boolean {
        val on = !wifi
        runCatching { wifiManager.setWifiEnabled(on) }
        return on
    }

    @Suppress("DEPRECATION", "MissingPermission")
    fun toggleBluetooth(): Boolean {
        val on = !bluetooth
        runCatching { btAdapter?.let { if (on) it.enable() else it.disable() } }
        return on
    }

    /** ConnectivityManager.setAirplaneMode is a system API; PaperOS holds NETWORK_AIRPLANE_MODE. */
    fun toggleAirplane(): Boolean {
        val on = !airplane
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            ConnectivityManager::class.java.getMethod("setAirplaneMode", Boolean::class.javaPrimitiveType).invoke(cm, on)
        }
        return on
    }

    /**
     * System-wide dark mode as e-ink readers do it: the whole display inverted (white on black),
     * which works in every app, designed for it or not. Uses Android's display inversion;
     * PaperOS holds WRITE_SECURE_SETTINGS as a privileged system app.
     */
    val dark get() = Settings.Secure.getInt(resolver, "accessibility_display_inversion_enabled", 0) == 1

    fun toggleDark(): Boolean {
        val on = !dark
        runCatching { Settings.Secure.putInt(resolver, "accessibility_display_inversion_enabled", if (on) 1 else 0) }
        return dark
    }

    // ---- Front light ----

    /** Whether Bigme's light driver is present (it is on the B6; not on other devices or the emulator). */
    val hasFrontLight: Boolean get() = ebook != null

    var lightLevel: Int
        get() = prefs.getInt("level", 5)
        set(v) { prefs.edit().putInt("level", v.coerceIn(0, STEPS)).apply(); applyLight() }

    var warmth: Int
        get() = prefs.getInt("warmth", 3)
        set(v) { prefs.edit().putInt("warmth", v.coerceIn(0, STEPS)).apply(); applyLight() }

    private fun applyLight() {
        val total = (lightLevel * MAX_INDEX / STEPS.toFloat())
        val warmIndex = (total * warmth / STEPS).roundToInt().coerceIn(0, MAX_INDEX)
        val coldIndex = (total * (STEPS - warmth) / STEPS).roundToInt().coerceIn(0, MAX_INDEX)
        val cold = LEVELS[coldIndex]
        val warm = LEVELS[warmIndex]
        val e = ebook ?: return
        runCatching { e.javaClass.getMethod("setColdlight", Int::class.javaPrimitiveType).invoke(e, cold) }
        runCatching { e.javaClass.getMethod("setWarmlight", Int::class.javaPrimitiveType).invoke(e, warm) }
        // Keep Bigme's own settings in step so its services agree with what is lit.
        runCatching {
            Settings.System.putInt(resolver, "ColdValue", coldIndex)
            Settings.System.putInt(resolver, "WarmValue", warmIndex)
            Settings.System.putInt(resolver, "screen_brightness_cold", cold)
            Settings.System.putInt(resolver, "screen_brightness_warm", warm)
        }
    }

    private val ebook: Any? by lazy {
        runCatching { Class.forName("android.hwebook.Xrzebook").getDeclaredConstructor().newInstance() }.getOrNull()
    }

    companion object {
        const val STEPS = 10
        private const val MAX_INDEX = 36
        /** Bigme's level table for the colour (Kaleido) panel: index 0–36 → LED value. */
        private val LEVELS = intArrayOf(
            0, 2, 20, 30, 40, 50, 60, 70, 80, 90, 102, 108, 114, 120, 126, 132, 138, 144, 150,
            156, 162, 168, 174, 180, 188, 192, 198, 204, 206, 208, 210, 212, 214, 216, 218, 220, 222,
        )
    }
}
