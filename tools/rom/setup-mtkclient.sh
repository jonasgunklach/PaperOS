#!/usr/bin/env bash
# Installs mtkclient (github.com/bkerler/mtkclient) into tools/rom/mtkclient with its own venv.
# mtkclient talks to the MediaTek boot ROM over USB: it can read/write every partition
# (including preloader and seccfg) without root and without an unlocked bootloader.
set -euo pipefail
cd "$(dirname "$0")"
command -v brew >/dev/null && { brew list libusb >/dev/null 2>&1 || brew install libusb; }
[[ -d mtkclient ]] || git clone --depth 1 https://github.com/bkerler/mtkclient.git
# mtkclient needs Python 3.10+ (macOS ships 3.9).
PY=""
for c in python3.13 python3.12 python3.11 python3.10 /opt/homebrew/bin/python3.12; do
  command -v "$c" >/dev/null && "$c" -c 'import sys; sys.exit(sys.version_info < (3,10))' && { PY=$c; break; }
done
[[ -n "$PY" ]] || { command -v brew >/dev/null && brew install python@3.12 && PY=/opt/homebrew/bin/python3.12; }
rm -rf mtkclient/.venv
"$PY" -m venv mtkclient/.venv
mtkclient/.venv/bin/pip install -q --upgrade pip
mtkclient/.venv/bin/pip install -q -r mtkclient/requirements.txt
mtkclient/.venv/bin/pip install -q -e mtkclient
# mtkclient only needs FUSE to mount the tablet as a drive, which we don't use. Without macFUSE
# its mfusepy dependency raises OSError, which mtkclient's guard (except ImportError) misses.
if [[ "$(uname)" == Darwin ]] && ! [[ -e /usr/local/lib/libfuse.dylib || -e /opt/homebrew/lib/libfuse.dylib ]]; then
  site=$(mtkclient/.venv/bin/python -c 'import site; print(site.getsitepackages()[0])')
  echo 'raise ImportError("libfuse not installed (mounting disabled)")' > "$site/mfusepy.py"
fi
# lpunpack.py (super image unpacker) for unpack.sh / build_rom.py.
mkdir -p bin
[[ -f bin/lpunpack.py ]] || curl -sfL -o bin/lpunpack.py https://raw.githubusercontent.com/unix3dgforce/lpunpack/master/lpunpack.py
echo "mtkclient ready: tools/rom/mtk --help"
