#!/usr/bin/env bash
# Launch each installed streaming favourite from its Rivi tile with the D-pad + OK and verify
# that the right app comes to the foreground, then return and check focus. Starts real apps.
source "$(dirname "$0")/../adb/common.sh"
LOG=$ROOT/results/tests/streaming-$(date +%Y%m%d-%H%M%S).log; mkdir -p "$(dirname "$LOG")"
key() { sh_ "input keyevent $1" >/dev/null; sleep "${2:-0.5}"; }
field() { sh_ "dumpsys activity $PKG" | grep -E '^ *RIVI ' | sed 's/^ *RIVI //' | grep -m1 "^$1=" | cut -d= -f2-; }
top() { sh_ "dumpsys activity activities" 2>/dev/null | grep -m1 topResumedActivity; }
fail=0
for p in com.netflix.ninja com.amazon.amazonvideo.livingroom com.google.android.youtube.tv com.apple.atve.androidtv.appletv; do
  sh_ "pm path $p" | grep -q package: || { echo "SKIP $p (not installed)" | tee -a "$LOG"; continue; }
  sh_ "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 2; key KEYCODE_BACK 0.6
  favs=$(field favourites); i=$(echo "$favs" | tr ',' '\n' | grep -n "^$p/" | cut -d: -f1)
  [ -z "$i" ] && { echo "FAIL $p is not in favourites" | tee -a "$LOG"; fail=$((fail+1)); continue; }
  n=1; while [ "$n" -lt "$i" ]; do key KEYCODE_DPAD_RIGHT 0.35; n=$((n+1)); done
  f=$(field focus); t0=$(python3 -c 'import time; print(time.time())')
  key KEYCODE_DPAD_CENTER 0
  ok=0; for _ in $(seq 1 20); do top | grep -q " $p/" && { ok=1; break; }; sleep 0.5; done
  t1=$(python3 -c 'import time; print(time.time())')
  sleep 6; still=$(top | grep -q " $p/" && echo yes || echo no); alive=$([ -n "$(sh_ pidof $p)" ] && echo yes || echo no)
  sh_ "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 2; fb=$(field focus)
  if [ "$ok" = 1 ] && [ "$still" = yes ] && [ "${fb%%/*}" = "$p" ]; then r=PASS; else r=FAIL; fail=$((fail+1)); fi
  printf '%s %-38s focused=%s foreground_within=%.1fs still_foreground_after_6s=%s process_alive=%s focus_on_return=%s\n' "$r" "$p" "${f%%/*}" "$(python3 -c "print($t1-$t0)")" "$still" "$alive" "${fb%%/*}" | tee -a "$LOG"
done
echo "failures: $fail" | tee -a "$LOG"; exit $fail
