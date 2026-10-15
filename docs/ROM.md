# The PaperOS ROM for the Bigme B6 Color

PaperOS replaces Bigme's launcher, apps and Google services. Bigme's display drivers, e-ink
waveforms and kernel stay exactly as they ship.

Everything below ran on a real B6 Color: backup, build, flash, boot into PaperOS, and the full
restore to factory state.

**Rule: nothing is written to the tablet until a full backup exists**, ideally in two places.

## The device

| | |
|---|---|
| SoC | MediaTek Helio P35 (MT6765); USB vendor 0x0E8D; reports itself as "HiBreak" |
| Android | 14, A/B slots with `init_boot` (GKI); slot A active |
| Storage | 64 GB eMMC; `super` is 12 GB |
| `super` contents | `system_a` 3.0 GB, `product_a` 0.84 GB, `system_ext_a` 0.66 GB, `vendor_a` 0.34 GB. All plain **ext4** (no shared blocks), so they can be edited in place |
| Device-unique partitions | `nvram`, `nvdata`, `nvcfg`, `protect1/2`, `proinfo`, `seccfg`, plus `waveform` (the e-ink panel's waveforms) |
| `userdata` / metadata | `/data` is f2fs with file-based encryption; metadata lives on `md_udc`. Both are `formattable`, so Android recreates them on a boot after they've been wiped |

## Pipeline

```bash
tools/rom/setup-mtkclient.sh             # once: mtkclient (Python 3.10+), libusb, lpunpack
tools/rom/backup.sh <name>               # tablet OFF, start, then plug in: reads all partitions (~25 min)
tools/rom/unpack.sh <name>               # super → system/system_ext/product/vendor images
tools/rom/build_rom.py <name>            # applies tools/rom/rom.txt → build/rom/<name>/out/
tools/rom/flash.sh <name>                # unlock + write super + vbmeta + wipe (~40 min)
tools/rom/flash_part.sh <name> [--wipe] system_a vendor_a   # rewrite only changed parts
tools/rom/readlogs.sh                    # after a failed boot: dump expdb (kernel log) + md_udc
tools/rom/restore.sh <name>              # factory system + bootloader lock + wipe
```

How to connect for every mtkclient step:
1. Start the script with the tablet **unplugged**.
2. The tablet must be **off**.
3. Plug in USB without pressing any button. The preloader handshake only works if mtkclient is
   already waiting when the device appears.
4. If the tablet is still running, press power once while it's plugged in. It restarts, and the
   tool catches the preloader.

Each mtkclient run is its own session, and the tablet has to be re-plugged between them. The
scripts wait for the unplug.

## Recipe: `tools/rom/rom.txt`

- `remove <image>:<path>` deletes an app directory.
- `app <image>:<dir> <apk>` installs an APK as a system app, with its native libraries extracted
  to `lib/arm64`.
- `prop <image>:<file> key=value` sets a build property.
- `file <image>:<path> <local>` adds a file.

Every file written gets mode 0644/0755, owner root, and the SELinux label
`u:object_r:system_file:s0\0`, which is byte-identical to Bigme's own files. Replaced files keep
their original label.

Partition sizes never change, so the new `super` has the stock layout. The edited images are
written into it at their extents. `vbmeta_a` gets flags `|= 3` (verification and verity off),
which an unlocked bootloader accepts.

## What we learned on the hardware

- **`GoogleExtShared` is not optional.** It provides the required system library
  `android.ext.shared`. Without it, `system_server` dies right after `PMS_READY` and the device
  ends up in "Can't load Android system". `expdb` holds the kernel log of the failed boot, which is
  how we found it.
- **USB debugging.** Setting `persist.sys.usb.config=mtp,adb` in `vendor/build.prop` wasn't enough,
  and toggling USB debugging in Bigme's settings didn't add the adb interface either (only MTP
  enumerated). Next: start adbd from an init `.rc` in the ROM while keeping authorization
  (`ro.adb.secure=1`).
- **Language.** `ro.product.locale` is already `en-US`; Bigme's own apps and first-boot flow
  decide the language. The ROM also sets `persist.sys.locale=en-US`.
- **Front light:** `android.hwebook.Xrzebook().setColdlight(v)` / `.setWarmlight(v)`, with
  `v` from 0 to 222.
  - There are 37 steps; the colour-panel table is `0, 2, 20, 30 … 222`.
  - Bigme stores the step index in `Settings.System` as `ColdValue`/`WarmValue`, and the raw value
    as `screen_brightness_cold`/`screen_brightness_warm`.
  - Source: Bigme's control centre, `com.xrz.sys.control` / `LightUtils`.
- **Sleep and power-off screens:** Bigme's `com.xrz.standby` shows `/data/xrz/standby.png` and
  `/data/xrz/poweroff.png`.
- **Per-app display policy:** `xrz.framework.manager.DisplayPolicyManager` sets, per package:
  - colour mode (default, magazine, comic, video, custom)
  - refresh mode and frequency, contrast, colour enhancement
  - bleach colours, anti-alias, text enhancement, DPI

  New apps get colour processing, which is the likely cause of soft text on Kaleido 3.
  `dumpsys xrz_display_policy_service` shows the current policy.
- **Privileged permissions.** A priv-app that requests a privileged permission not allowlisted in
  `/system/etc/permissions` stops the device from booting. PaperOS ships
  `privapp-permissions-paperos.xml`, `default-permissions-paperos.xml` and a hidden-API
  allowlist (`sysconfig/paperos.xml`).
- **Speed.** Reading through BROM runs at about 15 MB/s and writing at about 5.7 MB/s. A full
  `super` write takes about 35 minutes; `flash_part.sh` writes only what changed.
- **Restore** writes `super`, `vbmeta_a`, `seccfg` (lock), `frp`, `para`, `boot_para` and `expdb`
  from the backup, then wipes `userdata` and `md_udc`. The backed-up `md_udc` must *not* be
  written back: its keys wouldn't match the wiped `userdata`. The result boots into Bigme's
  factory setup with no unlock warning.

## Options considered

| Option | Verdict |
|---|---|
| **Stripped stock** (this pipeline) | Keeps Bigme's patched SurfaceFlinger/libgui, HWC, kernel and waveforms. Done, and it boots. |
| GSI on Bigme's vendor | Loses Bigme's system-side e-ink patches (per-layer refresh modes live in libgui/SurfaceFlinger). |
| Own AOSP/Lineage | Needs Bigme's kernel and HWC sources; weeks to months. |
