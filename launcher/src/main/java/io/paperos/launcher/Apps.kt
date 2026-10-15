package io.paperos.launcher

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import java.text.Collator

data class App(val key: String, val label: String, val component: ComponentName, val user: UserHandle)

/**
 * The installed, launchable apps. Icons are never loaded: Apps shows lettered circles, which
 * stay crisp on e-ink where colourful launcher icons turn to gray mud.
 */
class AppStore(private val context: Context) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val infos = HashMap<String, LauncherActivityInfo>()

    var all: List<App> = emptyList()
        private set
    private var listener: (() -> Unit)? = null

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p: String, u: UserHandle) = reload()
        override fun onPackageAdded(p: String, u: UserHandle) = reload()
        override fun onPackageChanged(p: String, u: UserHandle) = reload()
        override fun onPackagesAvailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
        override fun onPackagesUnavailable(p: Array<out String>, u: UserHandle, r: Boolean) = reload()
    }

    fun start(onChange: () -> Unit) {
        listener = onChange
        launcherApps.registerCallback(callback)
        reload()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        listener = null
    }

    fun reload() {
        val collator = Collator.getInstance()
        val list = ArrayList<App>()
        infos.clear()
        for (user in launcherApps.profiles) {
            for (info in launcherApps.getActivityList(null, user)) {
                if (info.componentName.packageName == context.packageName) continue
                val key = keyOf(info.componentName, user)
                infos[key] = info
                list += App(key, info.label.toString(), info.componentName, user)
            }
        }
        list.sortWith { a, b -> collator.compare(a.label, b.label) }
        all = list
        listener?.invoke()
    }

    fun byKey(key: String) = all.firstOrNull { it.key == key }

    fun launch(app: App) {
        // No zoom/slide: an animated transition is a smear of partial frames on e-ink.
        val opts = ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle()
        runCatching { launcherApps.startMainActivity(app.component, app.user, null, opts) }
    }

    fun openInfo(app: App) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: App) {
        val i = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.component.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    companion object {
        fun keyOf(c: ComponentName, user: UserHandle) =
            if (user == Process.myUserHandle()) c.flattenToShortString() else "${c.flattenToShortString()}#${user.hashCode()}"

        fun openSystem(context: Context, action: String) {
            runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    ActivityOptions.makeCustomAnimation(context, 0, 0).toBundle())
            }.onFailure {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
