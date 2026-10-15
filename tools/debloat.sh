#!/usr/bin/env bash
# PaperOS debloat: strip a stock e-ink Android tablet down to what an e-reader/notebook needs.
#
# No root needed. Everything is reversible:
#   - packages are *disabled for user 0* (pm disable-user), not removed from /system
#   - every change is logged to tools/debloat-state/<serial>.log and `restore` undoes it
#
# Usage:
#   tools/debloat.sh scan                 # what's installed, what each profile would touch
#   tools/debloat.sh apply [profile]      # profile: lean (default) | degoogle
#   tools/debloat.sh tune                 # e-ink & battery settings (animations, scanning, ...)
#   tools/debloat.sh home                 # install PaperOS and make it the home app
#   tools/debloat.sh restore              # re-enable everything this script disabled
#
# Add --dry-run to apply/tune to print commands without running them.

set -euo pipefail
cd "$(dirname "$0")"

ADB="${ADB:-adb}"
command -v "$ADB" >/dev/null || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
DRY=0
for a in "$@"; do [[ "$a" == "--dry-run" ]] && DRY=1; done

die() { echo "error: $*" >&2; exit 1; }
sh_() { "$ADB" shell "$@" </dev/null 2>&1 | tr -d '\r'; }

need_device() {
  local n; n=$("$ADB" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  [[ "$n" == "1" ]] || die "connect exactly one device with USB debugging enabled (found $n)"
  SERIAL=$("$ADB" get-serialno)
  STATE="debloat-state/$SERIAL.log"
  mkdir -p debloat-state
}

run() {
  if [[ $DRY == 1 ]]; then echo "  [dry] adb shell $*"; else sh_ "$@"; fi
}

# Read a package list file, dropping comments/blank lines.
list() { sed -e 's/#.*//' -e 's/[[:space:]]//g' "lists/$1.txt" | grep -v '^$' || true; }

installed() { sh_ pm list packages | sed 's/^package://' | sort; }
enabled()   { sh_ pm list packages -e | sed 's/^package://' | sort; }

profile_lists() {
  case "${1:-lean}" in
    lean)     echo "oem google-apps aosp-extras" ;;
    degoogle) echo "oem google-apps aosp-extras google-services" ;;
    *) die "unknown profile '$1' (lean | degoogle)" ;;
  esac
}

cmd_scan() {
  need_device
  echo "Device: $(sh_ getprop ro.product.manufacturer) $(sh_ getprop ro.product.model)  Android $(sh_ getprop ro.build.version.release)  SoC: $(sh_ getprop ro.board.platform)"
  local inst; inst=$(installed)
  for l in oem google-apps aosp-extras google-services; do
    echo; echo "== $l =="
    for p in $(list "$l"); do
      if grep -qx "$p" <<<"$inst"; then echo "  present  $p"; fi
    done
  done
  echo; echo "== other enabled third-party apps (review by hand) =="
  sh_ pm list packages -e -3 | sed 's/^package:/  /' | sort
  echo; echo "Full package list: adb shell pm list packages -f"
}

cmd_apply() {
  local profile="${1:-lean}"; [[ "$profile" == --* ]] && profile=lean
  need_device
  local en; en=$(enabled)
  local home; home=$(sh_ cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1 | cut -d/ -f1)
  echo "Profile: $profile   (current home app: $home)"
  if [[ "$profile" == degoogle ]]; then
    echo "WARNING: degoogle disables Play Store and Google Play services. Apps that rely on them"
    echo "(push notifications, Google sign-in, some banking apps) stop working. Use F-Droid/Obtainium instead."
    [[ $DRY == 1 ]] || { read -r -p "Continue? [y/N] " ok; [[ "$ok" == y ]] || exit 0; }
  fi
  local n=0
  for l in $(profile_lists "$profile"); do
    for p in $(list "$l"); do
      grep -qx "$p" <<<"$en" || continue
      # Never disable the current home app, or the device is left without a launcher.
      [[ "$p" == "$home" ]] && { echo "  skip     $p (current home app)"; continue; }
      echo "  disable  $p"
      run pm disable-user --user 0 "$p" >/dev/null
      [[ $DRY == 1 ]] || echo "$p" >> "$STATE"
      n=$((n + 1))
    done
  done
  if [[ $DRY == 1 ]]; then echo "Would disable $n packages."; else echo "Disabled $n packages. Undo with: tools/debloat.sh restore"; fi
}

cmd_tune() {
  need_device
  echo "Applying e-ink + battery settings"
  # No animations: on e-ink every animation frame is a smeared partial refresh.
  run settings put global window_animation_scale 0
  run settings put global transition_animation_scale 0
  run settings put global animator_duration_scale 0
  # Background radio scanning that runs even when Wi-Fi/Bluetooth are "off".
  run settings put global wifi_scan_always_enabled 0
  run settings put global ble_scan_always_enabled 0
  run settings put global network_recommendations_enabled 0
  run settings put global wifi_wakeup_enabled 0
  # Don't keep mobile data / Wi-Fi alive while asleep where the OS honours it.
  run settings put global wifi_sleep_policy 0
  # Turn off location; e-readers don't need it.
  run settings put secure location_mode 0
  # No Nearby / auto-sync churn.
  # Doze sooner (Android 12+ keeps these in DeviceConfig). Pin them so Play services can't reset them.
  run cmd device_config put device_idle light_after_inactive_to 30000
  run cmd device_config put device_idle inactive_to 120000
  run cmd device_config put device_idle idle_after_inactive_to 0
  run cmd device_config set_sync_disabled_for_tests persistent
  # Short screen timeout: an e-ink image stays on screen anyway while the SoC sleeps.
  run settings put system screen_off_timeout 60000
  # Restrict background execution for every user app except PaperOS.
  for p in $(sh_ pm list packages -e -3 | sed 's/^package://'); do
    [[ "$p" == io.paperos.* ]] && continue
    run cmd appops set "$p" RUN_ANY_IN_BACKGROUND ignore
  done
  echo "Done. Revert animations with: adb shell settings put global window_animation_scale 1 (etc.)"
}

cmd_home() {
  need_device
  local apk=../launcher/build/outputs/apk/release/launcher-release.apk
  [[ -f "$apk" ]] || (cd .. && ./gradlew -q :launcher:assembleRelease)
  "$ADB" install -r "$apk"
  run cmd package set-home-activity io.paperos.launcher/.HomeActivity
  echo "PaperOS is now the home app. The stock launcher is still installed as a fallback."
}

cmd_restore() {
  need_device
  [[ -f "$STATE" ]] || die "nothing to restore for $SERIAL"
  sort -u "$STATE" | while read -r p; do
    echo "  enable   $p"
    run pm enable --user 0 "$p" >/dev/null || run cmd package install-existing "$p" >/dev/null
  done
  [[ $DRY == 1 ]] || mv "$STATE" "$STATE.restored-$(date +%s)"
}

case "${1:-}" in
  scan) cmd_scan ;;
  apply) shift; cmd_apply "${1:-lean}" ;;
  tune) cmd_tune ;;
  home) cmd_home ;;
  restore) cmd_restore ;;
  *) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
