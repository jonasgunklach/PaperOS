package io.paperos.launcher

import android.content.Intent
import android.os.Bundle
import io.paperos.ui.Epd
import io.paperos.ui.PaperActivity
import io.paperos.ui.PaperUi

/**
 * The PaperOS shell. Replaces the stock launcher (it is a HOME activity) and hosts the
 * Home, Apps and Settings screens.
 */
class HomeActivity : PaperActivity() {

    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppStore
        private set
    private val appListeners = mutableListOf<() -> Unit>()
    private lateinit var main: MainScreen

    override val hideSystemBars get() = prefs.hideSystemBars
    override val volumeKeysTurnPages get() = prefs.volumeKeysTurnPages

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        super.onCreate(savedInstanceState)
        applyPrefs()
        apps = AppStore(this)
        main = MainScreen(this)
        push(main)
        apps.start { appListeners.forEach { it() } }
    }

    fun onAppsChanged(l: () -> Unit) { if (l !in appListeners) appListeners += l }
    fun removeAppsListener(l: () -> Unit) { appListeners -= l }

    fun applyPrefs() {
        PaperUi.invertOnPress = prefs.invertOnPress
        Epd.ghosts.pageTurnsPerFull = prefs.fullRefreshEvery
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home button while already home: return to the Home screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { popToRoot(); main.selectTab(0) }
    }


    override fun onRestart() {
        super.onRestart()
        // Coming back from another app: Recents may have changed, and its ghosting is still on the panel.
        appListeners.toList().forEach { it() }
        if (current === main) main.refresh()
        Epd.pageTurn(root)
    }

    /** The launcher is the bottom of everything; back does nothing here. */
    override fun onRootBack() {}

    override fun onDestroy() {
        apps.stop()
        super.onDestroy()
    }
}
