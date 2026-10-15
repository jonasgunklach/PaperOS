#!/usr/bin/env bash
# After a failed boot: read MediaTek's exception/log partition (expdb) and the metadata partition
# (md_udc) to see how far the boot got. Read-only.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
OUT="$here/../../build/rom/logs/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
attached() { ioreg -p IOUSB -l -w0 | grep -q '"idVendor" = 3725'; }
if attached; then echo ">> UNPLUG the tablet now."; while attached; do sleep 1; done; fi
echo ">> Ready: with the tablet OFF, PLUG IT IN (no buttons)."
"$here/mtk" r expdb,md_udc "$OUT/expdb.bin,$OUT/md_udc.bin"
echo "Saved to $OUT"
