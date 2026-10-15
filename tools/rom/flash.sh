#!/usr/bin/env bash
# Flash a PaperOS ROM built by build_rom.py. WRITES TO THE TABLET.
#
#   tools/rom/flash.sh <backup-name>
#
# Steps (each is its own mtkclient session; the tablet is re-plugged in between):
#   1. unlock the bootloader (seccfg) — required to boot an edited system
#   2. write super (the ROM) and vbmeta_a with verification off
#   3. erase userdata + md_udc (metadata) so the first boot starts clean; both are "formattable"
#      in the stock fstab, so Android re-creates them itself
#
# Undo everything: tools/rom/restore.sh <backup-name>   (stock super, vbmeta, seccfg = locked)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NAME="${1:?backup name, e.g. b6-stock-20261008}"
OUT="$here/../../build/rom/$NAME/out"
MTK="$here/mtk"

[[ -f "$OUT/super.bin" && -f "$OUT/vbmeta_a-disabled.bin" ]] || { echo "build the ROM first: build_rom.py $NAME"; exit 1; }
echo "Verifying ROM checksums…"; (cd "$OUT" && shasum -a 256 -c --quiet SHA256SUMS)
[[ -f "$here/../../backups/$NAME/flash/SHA256SUMS" ]] || { echo "no backup $NAME — refusing to flash"; exit 1; }

attached() { ioreg -p IOUSB -l -w0 | grep -q '"idVendor" = 3725'; }
step() {
  echo; echo ">> $1"
  if attached; then echo "   UNPLUG the tablet now."; while attached; do sleep 1; done; fi
  echo "   Ready: with the tablet OFF, PLUG IT IN (no buttons)."
}

# Files named after their target partitions, for one write session.
STAGE="$OUT/flash-stage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
ln -s "$OUT/super.bin" "$STAGE/super.bin"
ln -s "$OUT/vbmeta_a-disabled.bin" "$STAGE/vbmeta_a.bin"

step "1/3  Unlock bootloader"
"$MTK" da seccfg unlock 2>&1 | tee "$OUT/flash-1-unlock.log"
grep -qiE "unlocked|success" "$OUT/flash-1-unlock.log" || { echo "Unlock did not report success — stopping before writing anything."; exit 1; }

step "2/3  Write the ROM (super, ~12 GB, about 15–25 min) and vbmeta"
"$MTK" wl "$STAGE" 2>&1 | tee "$OUT/flash-2-write.log"

step "3/3  Erase userdata and metadata (clean first boot)"
"$MTK" e userdata,md_udc 2>&1 | tee "$OUT/flash-3-erase.log"

echo
echo "Done. Unplug, then hold power to start. First boot takes a few minutes (it sets up storage);"
echo "the screen shows an 'orange state' / unlocked warning first — that is expected."
