#!/bin/bash
# Puts an archived rainy hour into the debug app's demo mode (Repo.kt Demo.replay), for promo screenshots:
#   ./play/screenshots/rain.sh [day.npz] [index of the newest frame]
# Default: 3 May 2026 15:45 UTC, a convective line reaching Brussels. Then: First run ->
# "Try it with the bundled sample radar (debug)", wait for the forecast, and run take.sh.
# The screens show real OPERA radar from that day, replayed as if it were now.
L=$HOME/Projects/local_weather_model
A="$HOME/Android/platform-tools/adb -s ${SERIAL:-emulator-5600}"; O=$(cd "$(dirname "$0")" && pwd); T=$(mktemp -d)
$L/.venv/bin/python -I $O/demo_frames.py $O/../../engine/src/test/resources/opera_sample.h5 \
  ${1:-$L/data/opera_archive_5min/20260503.npz} ${2:-189} $T/demo_frames.bin || exit 1
$A push $T/demo_frames.bin /data/local/tmp/ >/dev/null
$A shell "run-as nowcast.app mkdir -p files; run-as nowcast.app cp /data/local/tmp/demo_frames.bin files/"
