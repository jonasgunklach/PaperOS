#!/usr/bin/env bash
# Write only some edited logical partitions (e.g. system_a) into `super` on the tablet, at their
# exact byte offsets — minutes instead of rewriting all 12 GB. WRITES TO THE TABLET.
#
#   tools/rom/flash_part.sh <backup-name> [--wipe] system_a [product_a …]
#
# Offsets: super's start on the flash (from the backup's GPT) + the partition's extent inside
# super (from the super metadata). Same layout as stock, so nothing else moves.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NAME="${1:?backup name}"; shift
WIPE=0; [[ "${1:-}" == "--wipe" ]] && { WIPE=1; shift; }
[[ $# -gt 0 ]] || { echo "name at least one partition, e.g. system_a"; exit 1; }
OUT="$here/../../build/rom/$NAME/out"
GPT="$here/../../backups/$NAME/flash/gpt.txt"
PY="$here/mtkclient/.venv/bin/python"

super_off=$((16#$(grep -E '^super:' "$GPT" | sed -E 's/.*Offset 0x([0-9a-f]+).*/\1/')))
info=$("$PY" "$here/bin/lpunpack.py" --info "$OUT/super.bin")

attached() { ioreg -p IOUSB -l -w0 | grep -q '"idVendor" = 3725'; }

for part in "$@"; do
  img="$OUT/$part.img"
  [[ -f "$img" ]] || { echo "no $img"; exit 1; }
  line=$(grep -E "^super: [0-9]+ \.\. [0-9]+: $part " <<<"$info" | head -1)
  start=$(sed -E 's/^super: ([0-9]+) .*/\1/' <<<"$line")
  sectors=$(sed -E 's/.*\(([0-9]+) sectors\).*/\1/' <<<"$line")
  size=$(stat -f %z "$img")
  [[ "$size" -eq $((sectors * 512)) ]] || { echo "$part: size $size != extent $((sectors * 512))"; exit 1; }
  off=$((super_off + start * 512))
  printf ">> %s: %d MB at flash offset 0x%x\n" "$part" $((size / 1048576)) "$off"
  if attached; then echo "   UNPLUG the tablet now."; while attached; do sleep 1; done; fi
  echo "   Ready: with the tablet OFF, PLUG IT IN (no buttons)."
  log=$(mktemp)
  "$here/mtk" wo "$(printf '0x%x' "$off")" "$(printf '0x%x' "$size")" "$img" 2>&1 | tee "$log"
  grep -q "^Wrote " "$log" || { echo "!! $part was NOT written (no connection). Nothing else was touched — run this again."; exit 1; }
done
if [[ $WIPE == 1 ]]; then
  echo ">> Wipe userdata + metadata (clean first boot)"
  if attached; then echo "   UNPLUG the tablet now."; while attached; do sleep 1; done; fi
  echo "   Ready: with the tablet OFF, PLUG IT IN (no buttons)."
  log=$(mktemp)
  "$here/mtk" e userdata,md_udc 2>&1 | tee "$log"
  grep -q "All partitions formatted" "$log" || { echo "!! Wipe did not complete — run again."; exit 1; }
fi
echo "Done. Unplug and hold power to start."
