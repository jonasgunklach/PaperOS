#!/usr/bin/env bash
# Step 1 of the ROM path, BEFORE anything is unlocked or flashed.
# Over plain adb (no root, nothing written to the device) this saves:
#   - every system property, package list, partition layout, settings tables
#   - the Bigme e-ink framework/HAL pieces we'll need to port: xrz jars, HWC/gralloc libs,
#     vendor init/configs, firmware listing
#   - your own files from /sdcard (books, notes, screenshots)
# Output: backups/<serial>-<date>/inventory/ (gitignored).
set -euo pipefail
ADB="${ADB:-adb}"; command -v "$ADB" >/dev/null || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
SKIP_SDCARD=0; [[ "${1:-}" == "--no-sdcard" ]] && SKIP_SDCARD=1

n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
[[ "$n" == 1 ]] || { echo "connect exactly one device with USB debugging (found $n)"; exit 1; }
SERIAL=$("$ADB" get-serialno)
OUT="$(cd "$(dirname "$0")/../.." && pwd)/backups/$SERIAL-$(date +%Y%m%d)/inventory"
mkdir -p "$OUT"/{system,vendor,sdcard}
s() { "$ADB" shell "$@" </dev/null 2>&1 | tr -d '\r' || true; }
pull() { "$ADB" pull "$1" "$2" >/dev/null 2>&1 && echo "  pulled $1" || echo "  (unreadable) $1"; }

echo "Saving device inventory to $OUT"
s getprop > "$OUT/getprop.txt"
s cat /proc/cpuinfo > "$OUT/cpuinfo.txt"
s cat /proc/meminfo > "$OUT/meminfo.txt"
s uname -a > "$OUT/uname.txt"
s cat /proc/cmdline > "$OUT/cmdline.txt"
s ls -l /dev/block/by-name/ /dev/block/platform/*/by-name/ > "$OUT/partitions-by-name.txt"
s cat /proc/partitions > "$OUT/proc-partitions.txt"
s df -h > "$OUT/df.txt"
s mount > "$OUT/mounts.txt"
s pm list packages -f -u > "$OUT/packages.txt"
s pm list packages -d > "$OUT/packages-disabled.txt"
s service list > "$OUT/services.txt"
s dumpsys xrz_display_policy_service > "$OUT/xrz-display-policy.txt"
for t in global secure system; do s settings list $t > "$OUT/settings-$t.txt"; done
s getprop ro.boot.verifiedbootstate > "$OUT/verifiedbootstate.txt"
s getprop ro.boot.flash.locked > "$OUT/flash-locked.txt"

echo "Pulling e-ink stack (framework, HAL, configs)"
for f in $(s ls /system/framework | grep -i -E "xrz|eink"); do pull "/system/framework/$f" "$OUT/system/"; done
for d in /vendor/lib64/hw /vendor/lib/hw; do
  for f in $(s ls $d 2>/dev/null | grep -i -E "hwcomposer|gralloc|eink|epd|xrz|lights"); do pull "$d/$f" "$OUT/vendor/"; done
done
for f in $(s ls /system/lib64 | grep -i -E "xrz|eink|epd|libgui.so|libsurfaceflinger"); do pull "/system/lib64/$f" "$OUT/system/"; done
pull /vendor/etc/init "$OUT/vendor/init"
pull /vendor/build.prop "$OUT/vendor/"
pull /system/build.prop "$OUT/system/"
s ls -lR /vendor/firmware > "$OUT/vendor-firmware-listing.txt"
s ls -l /system/app /system/priv-app /product/app /product/priv-app /system_ext/app /system_ext/priv-app > "$OUT/preinstalled-apps.txt"

if [[ $SKIP_SDCARD == 0 ]]; then
  echo "Pulling /sdcard (your files)…"
  "$ADB" pull /sdcard/ "$OUT/sdcard/" | tail -1
fi

(cd "$OUT/.." && find inventory -type f -print0 | xargs -0 shasum -a 256 > inventory.sha256)
echo "Done: $OUT"
