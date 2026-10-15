# Research notes

These notes were collected 2026‑10‑08. Statements marked **unverified** have not been tested on a
B6 yet; run `tools/probe-epd.sh` with the device connected to check them.

## Bigme B6 Color

| Item | Value |
|---|---|
| SoC | MediaTek Helio P35 (MT6765), **not Rockchip** |
| Android | 14 (Bigme "xrz" framework, "xSettings") |
| Screen | 6" Kaleido 3; B/W 1448×1072 at 300 ppi; colour 724×536 at 150 ppi |
| Memory | 4 GB RAM, 64 GB storage, microSD |
| Battery | 2100 mAh |
| Google Play | Preinstalled |
| Root / ROM | None public for the B6. The HiBreak (same MT6765) was rooted via mtkclient + Magisk, so the B6 is probably reachable but unconfirmed. |

## Refresh APIs

**Bigme: `xrz.framework.manager.XrzEinkManager`** (on the boot classpath; reflection only).
- `setRefreshModeByView(View, int)`: Bigme's SurfaceFlinger keeps one mode per *layer*.
- `static forceGlobalRefresh(int)`: semantics **unverified**. It may set a persistent forced
  mode, so PaperOS deliberately doesn't use it.
- Modes:

| Group | Values |
|---|---|
| Base waveforms | `DU=2`, `GC16=4`, `A2=16`, `GL16=32`, `GU16=132` |
| Bigme presets | `CLEAN=176`, `HD=177`, `NORMAL=178` (system default), `FAST=179`, `REGAL=180` |

- What PaperOS does for a full refresh: switch our layer to GC16, invalidate, then restore NORMAL
  after 700 ms. That works only if the setter is allowed from an unprivileged app (**unverified**
  on B6; Bigme's own unprivileged notes app calls it on HiBreak).

**Rockchip RK35xx:** `getSystemService("eink")` returns `android.os.EinkManager`;
`sendOneFullFrame()` does a full refresh.

**Others** (KOReader's controllers): Onyx `View.refreshScreen`, NTX `postInvalidateDelayed(…, mode)`,
sunxi `ViewRootImpl.setRefreshMode`. None of these exist on Bigme.

## Stock launcher caveat

The eLauncher and ThinkLauncher projects report that the stock Bigme launcher
(`com.xrz.ebook.launcher`) drives system gestures. They call
`contentResolver.call("com.xrz.LauncherProvider", "custom_key", "false", null)` when the launcher is
entered. PaperOS doesn't do this yet. **Test the gestures on the B6** before considering disabling
the stock launcher; `debloat.sh` never disables it.

## Rendering and damage (AOSP 14)

- HWUI calls `eglSwapBuffersWithDamageKHR` with the union of changed RenderNodes. Two far-apart
  changes in one frame merge into one big rectangle, so avoid updating them in the same frame.
- With software rendering (`Surface::queueBuffer` when `mConnectedToCpu`), the damage is the
  **whole buffer**.
- Whether Bigme's MTK panel driver also diffs pixels is unknown. Rockchip's EBC driver does.

## Power (no root)

The `tune` step in `tools/debloat.sh` covers:
- animations off
- Wi‑Fi and BLE "always scanning" off
- location off
- an earlier light/deep doze, set through `cmd device_config put device_idle …` and pinned with
  `set_sync_disabled_for_tests persistent`
- `RUN_ANY_IN_BACKGROUND ignore` for every third-party app
- a 60 s screen timeout (the e-ink image stays up while the SoC sleeps)

To measure the effect: `dumpsys batterystats --reset`, then `dumpsys batterystats --charged` after
a day.

## Sources

- B6 hardware and packages:
  - omgmog.net B6 review, and their debloat gist (gist.github.com/omgmog/2c0987faac4d7e4bc907af4e6830168c)
  - parkablogs.com and root-nation.com reviews
- Bigme refresh API:
  - github.com/imedwei/inksdk (`docs/bigme-sdk-reverse-engineered.md`)
  - github.com/awong-dev/hibreak-pro-color
- KOReader controllers: github.com/koreader/android-luajit-launcher (`device/epd/*`)
- Rockchip: aosp-rockchip `frameworks/base` `EinkManager.java`
- Launcher shims: github.com/thypon/eLauncher (`BigmeShims`), github.com/MatiasDesuu/ThinkLauncher
- Waveforms:
  - E Ink VB3300 manual
  - IT8951 waveform docs
  - good-display.com/news/79.html
  - LKML Rockchip EBC diff mode
- UI guidance:
  - CHI '26, "A Dedicated E-Paper Design System for Mobile Phones" (doi 10.1145/3772318.3791459)
  - HN threads 49213660 and 31396797
  - worrydream.com/MagicInk
- AOSP: `libs/gui/Surface.cpp`, `View.java` (`invalidate(Rect)` javadoc),
  `hwui/renderthread/EglManager.cpp`, `DeviceIdleController.java`
