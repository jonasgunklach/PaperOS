#!/usr/bin/env bash
# Step 2: a complete raw backup of the flash, via the MediaTek boot ROM. Reads only.
#
# What gets saved (backups/<name>/flash/):
#   gpt.txt                  partition table as the device reports it
#   boot1.bin, boot2.bin     eMMC boot areas = preloader (the first code the SoC runs)
#   partitions/*.bin         every GPT partition, one file each, except userdata
#                            (userdata is encrypted with device keys; your files come from inventory.sh)
#   SHA256SUMS               checksums of everything above
#
# The device-unique partitions are in there: nvram/nvdata/nvcfg/protect1/protect2 (MAC addresses,
# calibration), seccfg (lock state), proinfo, and the Bigme panel/waveform data. Without them a
# "restore" from someone else's firmware would leave Wi-Fi/the display broken. Keep 2 copies.
#
# How to connect: power the B6 OFF, start this script, then plug in USB (if it doesn't catch it,
# hold Volume Up / both volume keys while plugging in). It waits for the preloader/BROM port.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NAME="${1:-b6-$(date +%Y%m%d-%H%M)}"
OUT="$here/../../backups/$NAME/flash"
mkdir -p "$OUT/partitions"
MTK="$here/mtk"

# Each mtkclient run is its own session; between runs the tablet must be re-plugged
# (unplug, hold power ~10 s, plug in again with no button pressed).
# mtkclient only handshakes cleanly when it is already running *before* the tablet is plugged
# in (otherwise it tries to resume an old session). So: wait until no MediaTek device
# (USB vendor 0x0E8D = 3725) is attached, then start the step, then ask for the plug-in.
attached() { ioreg -p IOUSB -l -w0 | grep -q '"idVendor" = 3725'; }
step() {
  echo; echo ">> $1"
  if attached; then
    echo "   UNPLUG the tablet now."
    while attached; do sleep 1; done
  fi
  echo "   Ready: hold power ~10 s (fully off), then PLUG IT IN with no button pressed."
}

echo "Backup target: $OUT"
if [[ ! -s "$OUT/gpt.txt" ]]; then step "Partition table"; "$MTK" printgpt 2>&1 | tee "$OUT/gpt.txt"; fi
expected=$(grep -cE '^[a-z_0-9]+: +Offset' "$OUT/gpt.txt")
if [[ $(ls "$OUT/partitions" 2>/dev/null | wc -l) -lt $((expected - 1)) ]]; then
  step "All partitions except userdata (the long one)"; "$MTK" rl "$OUT/partitions" --skip userdata
fi
if [[ ! -s "$OUT/boot1.bin" ]]; then step "Preloader 1/2"; "$MTK" rf "$OUT/boot1.bin" --parttype boot1; fi
if [[ ! -s "$OUT/boot2.bin" ]]; then step "Preloader 2/2"; "$MTK" rf "$OUT/boot2.bin" --parttype boot2; fi

got=$(ls "$OUT/partitions" 2>/dev/null | wc -l | tr -d ' ')
if [[ $got -lt $((expected - 1)) ]]; then
  echo "INCOMPLETE: $got of $((expected - 1)) partitions saved. Re-run this script with the same name to continue."
  exit 1
fi
(cd "$OUT" && find . -type f -name '*.bin' -print0 | xargs -0 shasum -a 256 > SHA256SUMS)
echo
echo "Saved $(find "$OUT" -name '*.bin' | wc -l | tr -d ' ') images, $(du -sh "$OUT" | cut -f1)."
echo "Now copy backups/$NAME to a second disk before unlocking anything."
