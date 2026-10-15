# PaperOS design rules

Every PaperOS screen follows these rules. They come from waveform documentation (E Ink, IT8951),
vendor guidance (GoodDisplay), the CHI '26 e-paper design-system paper, and the KOReader and
Bigme reverse-engineering work. Sources are listed in [RESEARCH.md](RESEARCH.md).

## Why e-ink is different

A panel update is a *waveform*: a timed sequence of voltages that pushes pigment particles. The
main types:

| Class | Typical waveforms | Time | Use |
|---|---|---|---|
| Full / clearing | GC16 | ~450 ms, up to 1.6 s when cold; flashes | Remove ghosting |
| Quality partial | GL16, GU16 | ~450 ms, no flash | Text and UI changes |
| Fast | A2, DU | 120–260 ms; black and white only | Press feedback, pen strokes |

Every changed frame costs a waveform, power, and a little ghosting. **An unchanged pixel costs
nothing.** That one fact drives everything below.

## Visual language

The approved screens live on a design canvas: https://claude.ai/artifact/HWhQy65vs4VSHzGb8gnfDJ
(drawn at the B6's 572×772 dp, so canvas px = Android dp).

- Type: **Literata** (book text and titles; variable weight + optical size) and **Atkinson
  Hyperlegible Next** (UI labels). Both SIL OFL, subset to Latin, in `paperui/src/main/assets/fonts`.
- Ink #111 on white; grays only for static metadata. 1.5 dp rules, 6 dp radii, 48 dp targets, 28 dp gutter.
- Covers are typographic black-and-white plates (`reader/Covers.kt`), not grayscaled publisher art.
- Apps are lettered circles, not launcher icons.

The layout borrows from Kindle, the most refined e-ink UI there is:
- a thin top bar with the time centred (tap it for quick settings) and the battery on the right
- a bottom bar with Home and Library tabs
- bold section titles with a "Library ›" style link on the right
- covers in a grid
- "Page 1 of 3" pagination
- monoline icons that turn solid when active
- black and white, and plenty of white space

## The rules, and how the toolkit enforces them

1. **Nothing animates.** Window, activity and transition animations are off in the theme. App
   launches pass `makeCustomAnimation(0, 0)`. `tools/debloat.sh tune` sets the system animation
   scales to 0. There are no ripples, no `LayoutTransition`s and no item animators.
2. **Paginate; never scroll.** `PagedGrid` derives `rows × columns` from the space available, so a
   page is always exactly full. `PagedColumn` flows forms onto pages. You turn pages by swiping
   (left/up = next), with the `PageBar` arrows, or with page/D‑pad/volume keys. There is no drag,
   fling or overscroll, so a touch-move event never causes a redraw.
3. **Only what changed is redrawn.** HWUI sends the compositor one damage rectangle per frame, built
   from the RenderNodes that changed. So anything that updates on its own is its own View:
   - each grid cell
   - the clock and the date (`LiveText`)
   - the battery indicator (`BatteryView`)
   - each settings control

   Measured on the emulator: **1 frame per minute** while idle on Home, and **1 frame** per page
   turn.

   We do **not** use `hardwareAccelerated="false"`. In AOSP, a CPU-locked surface reports the
   *whole buffer* as damaged.
4. **Live data uses system broadcasts and only redraws on real change.**
   - The clock listens to `TIME_TICK` and shows no seconds.
   - The battery listens to `BATTERY_CHANGED`, rounded to whole percent plus charging state.
   - Both re-render only when their text changes, and listen only while visible.
   - Tabular digits keep the width fixed, so the neighbouring views never re-layout.

   There are no timers, alarms or wake locks.
5. **Clearing refreshes happen at meaningful moments, not on a timer.** `GhostBudget` counts page
   turns and screen changes and asks for a full refresh every N (user setting, default 6; 0 =
   manual). There is also a "Refresh" button. On Bigme, the vendor has its own auto‑clean, so set
   N to 0 if you see double flashes.
6. **Touch feedback = inversion.**
   - The pressed element is filled black with white text, a black-and-white change that fast
     waveforms handle well.
   - Feedback is delayed 60 ms so the start of a swipe doesn't invert‑then‑restore a cell for
     nothing.
   - A quick tap still shows it before the action runs.
   - Toggles, steppers and choices skip inversion: the state change *is* the feedback.
7. **Only black and white for anything interactive.** Gray (`Ink.GRAY` and `Ink.LIGHT`) is only for
   static secondary text and hairlines, because fast 1‑bit modes threshold grays away.
8. **Heavier type, thicker lines.** Body text uses weight 500 (regular weight is too thin at small
   sizes). Borders are at least 2 dp, and focus and selection use fill, not thin outlines.
9. **Big targets.** Touch targets are at least 56 dp. A mis-tap costs a slow refresh plus an undo.
10. **Colour is opt-in and coarse.** The Kaleido 3 colour layer is 150 ppi, half the B/W
    resolution. App icons default to grayscale with extra contrast; "Color" and "Text" tiles are
    settings. Never encode meaning in colour alone.
11. **No overlapping UI.** Popups and menus leave ghosts when they close. Sheets are solid white,
    anchored at the bottom and undimmed (dimming would be a full-screen gray update), and they
    count toward the ghost budget when they close.
12. **Steppers, not sliders.** A slider redraws on every move event; `−  5  +` redraws once per tap.
13. **Show controls instead of hidden gestures.** Swipe-to-page is always mirrored by visible
    arrows, and long-press menus have an "App info" route in system Settings.
14. **Never dark mode.** A black background means more pixels change on every update, and Battery
    Saver's forced dark is disabled in the theme.

## Toolkit map (`paperui/`)

| File | What it contains |
|---|---|
| `Ink.kt` | Palette, weights, and `Metrics` (border, touch, type scale) |
| `Epd.kt` | `Waveform`, `EpdDriver`, `GhostBudget`, `Epd.pageTurn/full/flash` |
| `EpdDrivers.kt` | Bigme `XrzEinkManager` and Rockchip `EinkManager` drivers, with the generic fallback |
| `Tap.kt` | `TapTracker`: tap, long-press and swipe-to-page, with deferred inversion |
| `PagedGrid.kt` | `Pageable` and `PagedGrid` (a fixed pool of cell views) |
| `PageBar.kt` | `PageBar` and `PagedColumn` |
| `Live.kt` | `LiveText` (clock and date) and `BatteryView` |
| `Controls.kt` | Button, rows (toggle, stepper, choice, action) and section header |
| `Host.kt` | `PaperActivity` (screen stack, bars, keys), `PaperRoot`, `Panel` and `Sheet` |
| `Glyphs.kt` | Monoline icons drawn in code: outlined when idle, solid when active |
| `Chrome.kt` | `TapView`, `StatusBar`, `NavBar`, `SectionLabel`, `PageHeader`, `FilterTabs`, buttons, `RoundToggle`, `StepBar`, `Segmented`, `FontCards`, `SizeBars`, `BoxStepper`, `LinkText` |

Everything is plain framework Android with no AndroidX. The release launcher APK is about 47 KB.
