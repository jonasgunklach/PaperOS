package io.paperos.launcher

import android.content.Context

/** All launcher settings. Small enough that plain SharedPreferences is the right tool. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("paper", Context.MODE_PRIVATE)

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    /** List hidden apps in Apps (dimmed) so they can be unhidden. */
    var showHidden: Boolean
        get() = sp.getBoolean("showHidden", false)
        set(v) = sp.edit().putBoolean("showHidden", v).apply()

    /** Full (flashing) refresh after this many page turns; 0 = only when asked. */
    var fullRefreshEvery: Int
        get() = sp.getInt("fullEvery", 6)
        set(v) = sp.edit().putInt("fullEvery", v).apply()

    var invertOnPress: Boolean
        get() = sp.getBoolean("invert", true)
        set(v) = sp.edit().putBoolean("invert", v).apply()

    var hideSystemBars: Boolean
        get() = sp.getBoolean("hideBars", true)
        set(v) = sp.edit().putBoolean("hideBars", v).apply()

    var volumeKeysTurnPages: Boolean
        get() = sp.getBoolean("volPages", false)
        set(v) = sp.edit().putBoolean("volPages", v).apply()

    /** Library sort: 0 recent, 1 title, 2 author. */
    var sort: Int
        get() = sp.getInt("sort", 0)
        set(v) = sp.edit().putInt("sort", v).apply()

    fun toggleHidden(key: String) {
        hidden = if (key in hidden) hidden - key else hidden + key
    }
}
