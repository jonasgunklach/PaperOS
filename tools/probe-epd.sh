#!/usr/bin/env bash
# Collect what PaperOS needs to know about this device's e-ink stack. Read-only.
ADB="${ADB:-adb}"; command -v "$ADB" >/dev/null || ADB="$HOME/Library/Android/sdk/platform-tools/adb"
s() { "$ADB" shell "$@" 2>&1 | tr -d '\r'; }
echo "== device";      for p in ro.product.manufacturer ro.product.brand ro.product.model ro.board.platform ro.build.version.release ro.build.display.id; do echo "$p=$(s getprop $p)"; done
echo "== display";     s wm size; s wm density
echo "== xrz props";   s getprop | grep -i -E "xrz|eink|epd|refresh" | head -40
echo "== services";    s service list | grep -i -E "xrz|eink|epd|handwrit"
echo "== framework";   s ls /system/framework | grep -i -E "xrz|eink"
echo "== xrz packages"; s pm list packages | grep -i -E "xrz|bigme|mediatek" | sed 's/package:/  /'
echo "== display policy for PaperOS"; s dumpsys xrz_display_policy_service | grep -i -A3 paperos | head -20
echo "== PaperOS driver";  s "logcat -d -s PaperOS | tail -5"
