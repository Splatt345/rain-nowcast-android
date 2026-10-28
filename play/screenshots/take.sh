#!/bin/bash
# Store screenshots from a running app (radar mode, a forecast loaded): ./play/screenshots/take.sh
# Best taken while it rains. SERIAL picks the device.
# tap(text): taps the node whose text or content-desc matches, via uiautomator
A="$HOME/Android/platform-tools/adb -s ${SERIAL:-emulator-5600}"; S=$(mktemp -d)
tap() { $A shell uiautomator dump /sdcard/u.xml >/dev/null; $A pull /sdcard/u.xml $S/u.xml >/dev/null
  xy=$(python3 -c '
import re,sys
for m in re.finditer(r"<node [^>]*>", open(sys.argv[2]).read()):
    n=m.group(0)
    if re.search(r"(text|content-desc)=\"%s\""%re.escape(sys.argv[1]), n):
        a,b,c,d=map(int,re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"",n).groups()); print((a+c)//2,(b+d)//2); break' "$1" $S/u.xml)
  [ -n "$xy" ] && $A shell input tap $xy; [ -n "$xy" ]; }

O=$(cd "$(dirname "$0")" && pwd)
demo() { $A shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
$A shell settings put global sysui_demo_allowed 1
demo enter; demo clock -e hhmm $(date +%H%M); demo battery -e level 100 -e plugged false
demo network -e wifi show -e level 4 -e fully true; demo network -e mobile hide; demo notifications -e visible false
tap Now >/dev/null; sleep 2; $A exec-out screencap -p > $O/1-now.png
tap "Next 48 h" >/dev/null; sleep 3; $A exec-out screencap -p > $O/2-next-48h.png
tap Nowcast >/dev/null; tap Map >/dev/null; sleep 5; $A exec-out screencap -p > $O/3-map.png
$A shell input tap 396 720; sleep 4; $A exec-out screencap -p > $O/4-map-place.png
$A shell input keyevent KEYCODE_BACK; sleep 1
tap Settings >/dev/null; sleep 2; $A exec-out screencap -p > $O/5-settings.png
demo exit
ls -la $O
