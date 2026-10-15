package io.paperos.launcher

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.paperos.reader.BookCell
import io.paperos.reader.BookEntry
import io.paperos.reader.HeroView
import io.paperos.reader.Library
import io.paperos.ui.ActionRow
import io.paperos.ui.BatteryView
import io.paperos.ui.CellRenderer
import io.paperos.ui.ChoiceRow
import io.paperos.ui.Epd
import io.paperos.ui.FilterTabs
import io.paperos.ui.Glyph
import io.paperos.ui.IconButton
import io.paperos.ui.Ink
import io.paperos.ui.LinkText
import io.paperos.ui.Metrics
import io.paperos.ui.NavBar
import io.paperos.ui.PageBar
import io.paperos.ui.PageHeader
import io.paperos.ui.Pageable
import io.paperos.ui.PagedColumn
import io.paperos.ui.PagedGrid
import io.paperos.ui.Panel
import io.paperos.ui.PrimaryButton
import io.paperos.ui.RoundToggle
import io.paperos.ui.Screen
import io.paperos.ui.SectionHeader
import io.paperos.ui.SectionLabel
import io.paperos.ui.StatusBar
import io.paperos.ui.StepBar
import io.paperos.ui.StepperRow
import io.paperos.ui.ToggleRow
import io.paperos.ui.Type
import io.paperos.ui.paperText
import java.util.Date

private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
private fun lp(w: Int = MATCH, h: Int = WRAP, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

private fun statusBar(home: HomeActivity) = StatusBar(home).apply { onTap = { showQuickSettings(home) } }

private fun hairline(home: HomeActivity, color: Int = Ink.RULE) = View(home).apply { setBackgroundColor(color) }

/** One tab of the main screen. Views are built once and swapped, never animated. */
abstract class Tab(val home: HomeActivity) {
    var view: View? = null
    abstract fun create(): View
    open fun onShow() {}
    open fun onHide() {}
    abstract fun pager(): Pageable?
}

/**
 * The shell: status bar, the current tab, and the bottom navigation. Switching tabs replaces only
 * the middle; the bars stay untouched on the panel.
 */
class MainScreen(private val home: HomeActivity) : Screen(home) {
    private val tabs: List<Tab> = listOf(HomeTab(home, this), LibraryTab(home), AppsTab(home))
    private var current = 0
    private var content: FrameLayout? = null
    private var nav: NavBar? = null

    override fun create(): View {
        val c = FrameLayout(home).also { content = it }
        nav = NavBar(home, listOf("Home" to Glyph.HOME, "Library" to Glyph.LIBRARY, "Apps" to Glyph.APPS)).apply {
            selected = current
            onSelect = { selectTab(it) }
        }
        tabs.forEach { it.view = null }
        attach(tabs[current])
        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBar(home), lp())
            addView(c, lp(h = 0, weight = 1f))
            addView(nav, lp())
        }
    }

    private fun attach(t: Tab) {
        val c = content ?: return
        c.removeAllViews()
        c.addView(t.view ?: t.create().also { t.view = it })
    }

    fun selectTab(i: Int) {
        val c = content
        if (c == null) { current = i; return }
        nav?.selected = i
        if (i == current) return
        tabs[current].onHide()
        current = i
        attach(tabs[i])
        tabs[i].onShow()
        Epd.pageTurn(c)
    }

    fun refresh() = tabs[current].onShow()

    override fun onShow() = tabs[current].onShow()
    override fun onHide() = tabs[current].onHide()
    override fun pager(): Pageable? = tabs[current].pager()
}

// ---------------------------------------------------------------- Home

/** Home: the book you're reading, big, and the ones you opened recently. */
class HomeTab(home: HomeActivity, private val main: MainScreen) : Tab(home) {
    private val library = Library.of(home)
    private lateinit var hero: HeroView
    private lateinit var recentLabel: SectionLabel
    private lateinit var recent: PagedGrid<BookEntry>

    override fun create(): View {
        val m = Metrics.of(home)
        hero = HeroView(home, library).apply {
            onClick = {
                val b = book
                when {
                    b != null -> Library.open(home, b.path)
                    !library.hasAccess() -> runCatching { home.startActivity(library.accessIntent()) }
                    else -> main.selectTab(1)
                }
            }
        }
        recentLabel = SectionLabel(home, "Recently opened", link = "Library").apply { onClick = { main.selectTab(1) } }
        recent = PagedGrid(home, BookCell(home, library, withAuthor = false, gapX = m.dp(16f), gapY = 0f)).apply {
            fixedColumns = 4; fixedRows = 1
            setPadding(-m.dpi(8f), 0, -m.dpi(8f), 0)
            onItemTap = { Library.open(home, it.path) }
        }
        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            val pad = m.gutter.toInt()
            setPadding(pad, m.dpi(18f), pad, 0)
            addView(SectionLabel(home, "Reading now"), lp())
            addView(hero, lp().apply { topMargin = m.dpi(14f) })
            addView(recentLabel, lp().apply { topMargin = m.dpi(30f) })
            addView(recent, lp(h = m.dpi(213f)).apply { topMargin = m.dpi(12f) })
        }
    }

    private fun refresh() {
        val recents = library.recent()
        val reading = recents.firstOrNull { library.progress(it.path)?.finished == false }
        hero.empty = if (library.hasAccess()) Triple("Nothing open yet", "Books you start reading appear here.", "Open Library")
        else Triple("Find your books", "Allow PaperOS to look for EPUB, PDF and TXT files on this device.", "Allow access")
        hero.book = reading
        val rest = recents.filter { it != reading }
        val v = if (rest.isEmpty()) View.INVISIBLE else View.VISIBLE
        recentLabel.visibility = v; recent.visibility = v
        recent.items = rest.take(8)
    }

    override fun onShow() {
        refresh()
        library.scan { refresh() }
    }

    override fun pager(): Pageable? = null
}

// ---------------------------------------------------------------- Library

/** All books as covers, three by two per page. Filter by state, sort by recent/title/author. */
class LibraryTab(home: HomeActivity) : Tab(home) {
    private val library = Library.of(home)
    private val sorts = listOf("Recent", "Title", "Author")
    private lateinit var header: PageHeader
    private lateinit var filters: FilterTabs
    private lateinit var grid: PagedGrid<BookEntry>
    private lateinit var access: View

    override fun create(): View {
        val m = Metrics.of(home)
        val pad = m.gutter.toInt()
        header = PageHeader(home, "Library").apply { setPadding(pad, 0, pad, 0) }
        filters = FilterTabs(home, listOf("All", "Reading", "Unread", "Finished")).apply {
            setPadding(pad, 0, pad, 0)
            action = sorts[home.prefs.sort]
            onSelect = { refresh(); grid.goTo(0) }
            onAction = { home.prefs.sort = (home.prefs.sort + 1) % sorts.size; action = sorts[home.prefs.sort]; refresh(); grid.goTo(0) }
        }
        grid = PagedGrid(home, BookCell(home, library, withAuthor = true, gapX = m.dp(22f), gapY = m.dp(18f))).apply {
            fixedColumns = 3; fixedRows = 2
            setPadding(pad - m.dpi(11f), m.dpi(20f), pad - m.dpi(11f), 0)
            onItemTap = { Library.open(home, it.path) }
            emptyText = "No books here yet.\nCopy EPUB, PDF or TXT files\ninto the Books folder."
        }
        access = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad * 2, 0, pad * 2, 0)
            setBackgroundColor(Ink.WHITE)
            addView(paperText(home, "PaperOS needs access to your files to find your books.", m.sp(18f), Type.serif(400)).apply { gravity = Gravity.CENTER }, lp())
            addView(PrimaryButton(home, "Allow access").apply { onClick = { runCatching { home.startActivity(library.accessIntent()) } } }, lp().apply { topMargin = m.dpi(20f) })
        }
        val body = FrameLayout(home).apply {
            addView(grid)
            addView(access)
        }
        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, m.dpi(10f), 0, 0)
            addView(header, lp())
            addView(filters, lp().apply { topMargin = m.dpi(6f) })
            addView(body, lp(h = 0, weight = 1f))
            addView(PageBar(home).apply { setPadding(pad - m.dpi(8f), 0, pad - m.dpi(8f), 0); bind(grid) }, lp())
        }
    }

    private fun refresh() {
        access.visibility = if (library.hasAccess()) View.GONE else View.VISIBLE
        val all = library.books
        header.meta = if (all.size == 1) "1 book" else "${all.size} books"
        val filtered = all.filter {
            val p = library.progress(it.path)
            when (filters.selected) {
                1 -> p != null && !p.finished
                2 -> p == null
                3 -> p?.finished == true
                else -> true
            }
        }
        grid.items = when (home.prefs.sort) {
            1 -> filtered.sortedBy { it.title.lowercase() }
            2 -> filtered.sortedWith(compareBy({ it.author.substringAfterLast(' ').lowercase() }, { it.title.lowercase() }))
            else -> filtered.sortedByDescending { library.progress(it.path)?.opened ?: 0L }
        }
    }

    override fun onShow() {
        refresh()
        library.scan { refresh() }
    }

    override fun pager(): Pageable = grid
}

// ---------------------------------------------------------------- Apps

/** Every other app as a lettered circle, three by four per page, A–Z. */
class AppsTab(home: HomeActivity) : Tab(home) {
    private lateinit var header: PageHeader
    private lateinit var grid: PagedGrid<App>
    private val onApps = { refresh() }

    override fun create(): View {
        val m = Metrics.of(home)
        val pad = m.gutter.toInt()
        header = PageHeader(home, "Apps").apply { setPadding(pad, 0, pad, 0) }
        grid = PagedGrid(home, MonogramCell(home) { it.key in home.prefs.hidden }).apply {
            fixedColumns = 3; fixedRows = 4
            setPadding(m.dpi(20f), m.dpi(18f), m.dpi(20f), 0)
            onItemTap = { home.apps.launch(it) }
            onItemLongPress = { appActions(home, it) }
        }
        val bar = PageBar(home).apply {
            setPadding(pad - m.dpi(8f), 0, pad - m.dpi(8f), 0)
            bind(grid)
            prefix = { p ->
                val r = grid.pageRange(p)
                if (r.isEmpty() || grid.pageCount < 2) "" else {
                    val a = grid.items[r.first].label.take(1).uppercase(); val b = grid.items[r.last].label.take(1).uppercase()
                    if (a == b) "$a · " else "$a – $b · "
                }
            }
        }
        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, m.dpi(10f), 0, 0)
            addView(header, lp())
            addView(hairline(home), lp(h = m.hairline.toInt()).apply { topMargin = m.dpi(14f); leftMargin = pad; rightMargin = pad })
            addView(grid, lp(h = 0, weight = 1f))
            addView(bar, lp())
        }
    }

    private fun refresh() {
        val hidden = home.prefs.hidden
        val visible = home.apps.all.filter { home.prefs.showHidden || it.key !in hidden }
        val nHidden = home.apps.all.count { it.key in hidden }
        header.meta = buildString {
            append("${home.apps.all.size - nHidden} apps")
            if (nHidden > 0) append(" · $nHidden hidden")
        }
        grid.items = visible
    }

    override fun onShow() { home.onAppsChanged(onApps); refresh() }
    override fun onHide() = home.removeAppsListener(onApps)
    override fun pager(): Pageable = grid
}

private fun appActions(home: HomeActivity, app: App) {
    val p = home.prefs
    home.showSheet(app.label, listOf(
        (if (app.key in p.hidden) "Show in Apps" else "Hide from Apps") to { p.toggleHidden(app.key); home.apps.reload() },
        "App info" to { home.apps.openInfo(app) },
        "Uninstall" to { home.apps.uninstall(app) },
    ))
}

/** An app: its initial in a ringed circle (solid while pressed), the name underneath. */
private class MonogramCell(home: HomeActivity, private val dimmed: (App) -> Boolean) : CellRenderer<App> {
    private val m = Metrics.of(home)
    private val ring = Ink.strokePaint(m.border)
    private val ink = Ink.fillPaint()
    private val initial = Ink.textPaint(m.sp(26f), Type.serif(400, opsz = 24)).apply { textAlign = Paint.Align.CENTER }
    private val name = Ink.textPaint(m.body, Ink.body).apply { textAlign = Paint.Align.CENTER }

    override fun draw(canvas: Canvas, item: App, bounds: RectF, pressed: Boolean) {
        val r = m.dp(29f)
        val cx = bounds.centerX()
        val cy = bounds.centerY() - m.dp(16f)
        val dim = dimmed(item)
        if (pressed) canvas.drawCircle(cx, cy, r, ink) else { ring.color = if (dim) Ink.LIGHT else Ink.BLACK; canvas.drawCircle(cx, cy, r - m.border / 2, ring) }
        initial.color = if (pressed) Ink.WHITE else if (dim) Ink.LIGHT else Ink.BLACK
        val letter = item.label.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "·"
        canvas.drawText(letter, cx, cy - (initial.ascent() + initial.descent()) / 2, initial)
        name.typeface = if (pressed) Ink.bold else Ink.body
        name.color = if (dim) Ink.MUTED else Ink.BLACK
        val label = android.text.TextUtils.ellipsize(item.label, android.text.TextPaint(name), bounds.width() - m.dp(8f), android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(label, cx, cy + r + m.dp(10f) - name.ascent(), name)
    }
}

// ---------------------------------------------------------------- Quick settings

/** Drops from the top bar: radios, a clearing refresh, the front light, and the way into Settings. */
fun showQuickSettings(home: HomeActivity) {
    val m = Metrics.of(home)
    val sys = SystemState(home)
    val pad = m.gutter.toInt()
    val panel = Panel(home, onTop = true)

    panel.addView(FrameLayout(home).apply {
        setPadding(pad, 0, pad, 0)
        val date = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEEdMMMM"), Date()).toString()
        addView(paperText(home, date, m.label, Ink.bold), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START))
        addView(BatteryView(home, m.label), FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.END))
    }, lp(h = m.dpi(40f)))

    panel.addView(LinearLayout(home).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(m.dpi(16f), m.dpi(14f), m.dpi(16f), 0)
        fun state(on: Boolean) = if (on) "On" else "Off"
        // Each tile switches the radio itself and shows the requested state at once.
        fun toggle(g: Glyph, name: String, on: Boolean, flip: () -> Boolean) =
            addView(RoundToggle(home, g, name, state(on), on).apply {
                onClick = { val now = flip(); active = now; this.state = state(now) }
            }, lp(0, WRAP, 1f))
        toggle(Glyph.WIFI, "Wi‑Fi", sys.wifi) { sys.toggleWifi() }
        toggle(Glyph.BLUETOOTH, "Bluetooth", sys.bluetooth) { sys.toggleBluetooth() }
        toggle(Glyph.AIRPLANE, "Airplane", sys.airplane) { sys.toggleAirplane() }
        toggle(Glyph.DARK, "Dark", sys.dark) { sys.toggleDark() }
    }, lp())

    if (sys.hasFrontLight) {
        fun level(title: String, value: Int, set: (Int) -> Unit) = LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, m.dpi(16f), pad, 0)
            val meta = paperText(home, "$value of ${SystemState.STEPS}", m.label, Ink.body, Ink.GRAY)
            addView(LinearLayout(home).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(paperText(home, title, m.body, Ink.bold), lp(0, WRAP, 1f))
                addView(meta)
            }, lp())
            addView(StepBar(home, SystemState.STEPS, value) { set(it); meta.text = "$it of ${SystemState.STEPS}" },
                lp().apply { topMargin = m.dpi(10f) })
        }
        panel.addView(level("Light", sys.lightLevel) { sys.lightLevel = it }, lp())
        panel.addView(level("Warmth", sys.warmth) { sys.warmth = it }, lp())
    }

    panel.addView(hairline(home), lp(h = m.hairline.toInt()).apply { topMargin = m.dpi(18f) })
    panel.addView(LinearLayout(home).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(pad - m.dpi(8f), m.dpi(4f), pad, m.dpi(6f))
        addView(LinkText(home, "Settings", chevron = true).apply { onClick = { home.dismissPanel(); home.push(SettingsScreen(home)) } })
        addView(View(home), lp(0, 1, 1f))
        addView(paperText(home, "Tap below to close", m.small, Ink.body, Ink.GRAY))
    }, lp())

    home.showPanel(panel, top = true)
}

// ---------------------------------------------------------------- Settings

/** PaperOS settings — a pushed screen: back chevron, serif title, paged rows. */
class SettingsScreen(private val home: HomeActivity) : Screen(home) {
    private lateinit var column: PagedColumn

    override fun create(): View {
        val m = Metrics.of(home)
        val p = home.prefs
        column = PagedColumn(home)
        listOf<View>(
            SectionHeader(home, "Display"),
            StepperRow(home, "Full refresh", "Flash to clear ghosting every N page turns", 0..20, 1, p.fullRefreshEvery,
                { if (it == 0) "off" else "$it" }) { p.fullRefreshEvery = it; home.applyPrefs() },
            ToggleRow(home, "Invert on touch", "Instant feedback where you tap", p.invertOnPress) { p.invertOnPress = it; home.applyPrefs() },
            ActionRow(home, "Refresh screen now") { Epd.full(home.root) },

            SectionHeader(home, "Lock screen"),
            ChoiceRow(home, "When locked, show", listOf("Cover", "Blank", "Bigme"),
                io.paperos.reader.LockScreen.mode(home).ordinal) { io.paperos.reader.LockScreen.setMode(home, io.paperos.reader.LockScreen.Mode.entries[it]) },

            SectionHeader(home, "Library and apps"),
            ActionRow(home, "Look for new books", "Scans this device for EPUB, PDF and TXT") { Library.of(home).scan { } },
            ToggleRow(home, "Show hidden apps", "Listed dimmed in Apps so you can unhide them", p.showHidden) { p.showHidden = it },

            SectionHeader(home, "Controls"),
            ToggleRow(home, "Hide Android bars", "Swipe from an edge to reveal them", p.hideSystemBars) { p.hideSystemBars = it; home.applySystemBars() },
            ToggleRow(home, "Volume keys turn pages", null, p.volumeKeysTurnPages) { p.volumeKeysTurnPages = it },

            SectionHeader(home, "Device"),
            sys("Wi‑Fi", Settings.ACTION_WIFI_SETTINGS),
            sys("Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
            sys("Display", Settings.ACTION_DISPLAY_SETTINGS),
            sys("Battery", Intent.ACTION_POWER_USAGE_SUMMARY),
            sys("Date and time", Settings.ACTION_DATE_SETTINGS),
            sys("Default home app", Settings.ACTION_HOME_SETTINGS),
            sys("All Android settings", Settings.ACTION_SETTINGS),

            SectionHeader(home, "About"),
            ActionRow(home, "PaperOS ${home.packageManager.getPackageInfo(home.packageName, 0).versionName}", "Panel driver: ${Epd.driver.name}") {},
        ).forEach { column.addView(it) }

        return LinearLayout(home).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBar(home), lp())
            addView(LinearLayout(home).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(m.dpi(8f), m.dpi(4f), 0, 0)
                addView(IconButton(home, Glyph.BACK, "Back").apply { onClick = { home.pop() } })
                addView(paperText(home, "Settings", m.sp(28f), Type.serif(600, opsz = 24), maxLines = 1), lp(0, WRAP, 1f))
            }, lp())
            addView(column, lp(h = 0, weight = 1f))
            addView(PageBar(home).apply { topRule = true; setPadding(m.gutter.toInt(), 0, m.gutter.toInt(), 0); bind(column) }, lp())
        }
    }

    private fun sys(title: String, action: String) = ActionRow(home, title) { AppStore.openSystem(home, action) }

    override fun pager(): Pageable = column
}
