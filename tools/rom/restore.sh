#!/usr/bin/env bash
# Put the tablet back to factory state from a backup made by backup.sh. WRITES TO THE TABLET.
#
#   tools/rom/restore.sh <backup-name>
#
# 1. One session writes back everything PaperOS changed, from the backup:
#      super (system/vendor/product), vbmeta_a, seccfg (= bootloader locked again), frp,
#      and the boot bookkeeping partitions (para, boot_para, expdb).
# 2. One session wipes userdata + md_udc (metadata), so the next boot is a factory first boot.
#    (The backed-up md_udc is NOT written back: its encryption keys would not match a wiped
#    userdata and the tablet would refuse to boot.)
# The preloader (boot1/boot2) was never touched and is left alone.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NAME="${1:?backup name, e.g. b6-stock-20261008}"
P="$here/../../backups/$NAME/flash/partitions"
MTK="$here/mtk"
[[ -f "$here/../../backups/$NAME/flash/SHA256SUMS" ]] || { echo "no backup $NAME"; exit 1; }

echo "Verifying backup checksums…"
(cd "$here/../../backups/$NAME/flash" && shasum -a 256 -c --quiet SHA256SUMS)

STAGE="$here/../../build/rom/restore-stage"; rm -rf "$STAGE"; mkdir -p "$STAGE"
for part in super vbmeta_a seccfg frp para boot_para expdb; do
  [[ -f "$P/$part.bin" ]] || { echo "backup lacks $part.bin"; exit 1; }
  ln -s "$P/$part.bin" "$STAGE/$part.bin"
done

attached() { ioreg -p IOUSB -l -w0 | grep -q '"idVendor" = 3725'; }
step() {
  echo; echo ">> $1"
  if attached; then echo "   UNPLUG the tablet now."; while attached; do sleep 1; done; fi
  echo "   Ready: with the tablet OFF, PLUG IT IN (no buttons)."
}

step "1/2  Write factory system, verification and bootloader lock (~12 GB, about 35–40 min)"
log=$(mktemp)
"$MTK" wl "$STAGE" 2>&1 | tee "$log"
for part in super vbmeta_a seccfg; do
  grep -q "Wrote .*/$part.bin" "$log" || { echo "!! $part was NOT written — run this again."; exit 1; }
done

step "2/2  Wipe userdata and metadata (factory first boot)"
log=$(mktemp)
"$MTK" e userdata,md_udc 2>&1 | tee "$log"
grep -q "All partitions formatted" "$log" || { echo "!! Wipe did not complete — run this again."; exit 1; }

echo
echo "Restored to factory state. Unplug, hold power ~15 s, then press power to start."
