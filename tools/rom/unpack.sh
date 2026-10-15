#!/usr/bin/env bash
# Unpack a backup's super partition into its logical images (system, vendor, product, …) and
# extract each EROFS image into a folder, so the ROM can be built from the real stock files.
# Read-only on the backup; output goes to build/rom/<name>/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
NAME="${1:?backup name, e.g. b6-stock-20261008}"
SUPER="$here/../../backups/$NAME/flash/partitions/super.bin"
OUT="$here/../../build/rom/$NAME"
[[ -f "$SUPER" ]] || { echo "no super.bin in backup $NAME"; exit 1; }
mkdir -p "$OUT/images" "$OUT/tree"
PY="$here/mtkclient/.venv/bin/python"
"$PY" "$here/bin/lpunpack.py" -p system_a,system_ext_a,product_a,vendor_a "$SUPER" "$OUT/images"
for img in "$OUT"/images/*.img; do
  n=$(basename "$img" .img)
  [[ -s "$img" ]] || continue
  if dump.erofs "$img" >/dev/null 2>&1; then
    echo "extract $n (erofs)"
    mkdir -p "$OUT/tree/$n"
    fsck.erofs --extract="$OUT/tree/$n" --preserve "$img" >/dev/null 2>&1 || echo "  (partial extract: $n)"
  else
    echo "$n: not erofs ($(file -b "$img" | cut -c1-60))"
  fi
done
du -sh "$OUT"/images/* 2>/dev/null
