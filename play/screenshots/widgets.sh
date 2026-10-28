#!/bin/bash
# Widget screenshots for the README, from the debug build's WidgetGallery (forecast loaded, e.g. after rain.sh):
#   ./play/screenshots/widgets.sh  -> play/screenshots/widgets/<type>-<size>.png
# The emulator's host GPU crashed on the gallery; start it with -gpu swiftshader_indirect.
A="$HOME/Android/platform-tools/adb -s ${SERIAL:-emulator-5600}"; O=$(cd "$(dirname "$0")" && pwd)/widgets; T=$(mktemp -d)
$A shell appwidget grantbind --package nowcast.app
for spec in "SUMMARY MEDIUM" "MINUTES MEDIUM" "HOURS MEDIUM" "RADAR MEDIUM" "RADAR LARGE" "COMBINED LARGE"; do
  set -- $spec
  $A shell am start -n nowcast.app/.WidgetGallery --es type $1 --es size $2 --es theme DARK >/dev/null
  sleep 6; $A exec-out screencap -p > $T/$1-$2.png
done
mkdir -p $O; $HOME/Projects/local_weather_model/.venv/bin/python -I - $T $O <<"PY"
# crop the widget (centred; WidgetGallery.SIZES in dp) plus a margin of the gallery background
import sys, os
from PIL import Image
src, dst = sys.argv[1:]
for f in os.listdir(src):
    t, s = f[:-4].split("-"); im = Image.open(f"{src}/{f}")
    d = im.width / 411.43                       # px per dp (1080 px at 420 dpi)
    W, H = (int(v * d) + 72 for v in {"MEDIUM": (370, 190), "LARGE": (370, 390)}[s])
    cx, cy = im.width // 2, im.height // 2
    im.crop((cx - W // 2, cy - H // 2, cx + W - W // 2, cy + H - H // 2)).save(f"{dst}/{t.lower()}-{s.lower()}.png", optimize=True)
PY
ls -la $O
