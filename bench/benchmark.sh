#!/usr/bin/env bash
# Rivi benchmark suite. Everything over adb, no root. Results -> results/runs/<LABEL>/
#
#   bench/benchmark.sh idle   LABEL HOME_PKG [KEY...]   idle: CPU / redraw / PSS of the Home app, N×60 s windows
#   bench/benchmark.sh launch LABEL                     cold+warm launch of streaming apps, Home return, ×TRIALS
#   bench/benchmark.sh switch LABEL                     Netflix↔YouTube switching + process residency
#   bench/benchmark.sh nav    LABEL HOME_PKG            D-pad navigation frame stats (jank, percentiles)
#   bench/benchmark.sh system LABEL                     RAM pressure, PSI, thermal, storage, ANR/crash dropbox
#   bench/benchmark.sh pressure LABEL                   the device as it is after days of use: swap, who holds it,
#                                                       then per app first frame, time to settle and swap read back
#   bench/benchmark.sh cycles LABEL HOME_PKG N          reliability: N × Home→app→Home cycles
#   bench/benchmark.sh shot   NAME                      screenshot -> results/shots/NAME.png
#
# Env: TV=<serial>  TRIALS=5  WINDOWS=5  SETTLE=45  APPS="pkg1 pkg2" (launch/switch)
# Idle methodology adapted from hungmi/just-a-launcher (MIT): /proc tick deltas, gfxinfo frame deltas,
# `dumpsys meminfo --package` (includes sub-processes and swap). Never use `dumpsys cpuinfo` (5-min stale).
source "$(dirname "$0")/../adb/common.sh"
BENCH=$ROOT/bench
TRIALS=${TRIALS:-5}; WINDOWS=${WINDOWS:-5}; SETTLE=${SETTLE:-45}
MODE=${1:?see header}; LABEL=${2:?label}; shift 2
OUT=$ROOT/results/runs/$LABEL; mkdir -p "$OUT" "$ROOT/results/shots"
STAMP=$(date +%Y%m%d-%H%M%S)
log() { echo "[$(date +%T)] $*" | tee -a "$OUT/log.txt"; }
shot() { sh_ "screencap -p /data/local/tmp/s.png" >/dev/null; "${A[@]}" pull /data/local/tmp/s.png "$1" </dev/null >/dev/null 2>&1; sh_ "rm -f /data/local/tmp/s.png" >/dev/null; }
gfx() { sh_ "dumpsys gfxinfo $1 | grep -E '^Uptime|^\*\*|Total frames rendered|Janky frames'"; }
key() { sh_ "input keyevent $1" >/dev/null; }
pid_of() { sh_ "pidof $1"; }
# am start -W prints ThisTime/TotalTime/WaitTime (ms). Coarse (activity-window-displayed), but real.
am_start_w() { sh_ "am start -W $* 2>&1"; }
total_ms() { grep -m1 TotalTime | awk '{print $2}'; }
home_cmd="-a android.intent.action.MAIN -c android.intent.category.HOME"
default_apps() {
  # resolve installed streaming apps in a stable order
  for p in com.netflix.ninja com.google.android.youtube.tv com.amazon.amazonvideo.livingroom com.apple.atve.androidtv.appletv; do
    sh_ "pm path $p" | grep -q package: && echo "$p"
  done
}
leanback_component() { sh_ "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER $1" | tail -1; }
go_home() { key KEYCODE_HOME; }
rec() { echo "$STAMP,$LABEL,$*" >> "$OUT/$MODE.csv"; }

case $MODE in
shot) shot "$ROOT/results/shots/$LABEL.png"; echo "$ROOT/results/shots/$LABEL.png" ;;

idle)
  PKG=${1:?home pkg}; shift
  echo "$PKG" > "$OUT/pkg"
  log "idle: force-stop $PKG, HOME, kill-all, nav keys: $*"
  # never force-stop a package whose accessibility service the owner has switched on: Android
  # would switch the service off (this is how the Rivi Home button was lost once)
  if sh_ "settings get secure enabled_accessibility_services" | grep -q "$PKG/"; then log "not force-stopping $PKG (its accessibility service is on)"
  else sh_ "am force-stop $PKG" >/dev/null; sleep 1; fi
  if current_home | grep -q "^$PKG/"; then go_home; START_MODE=home
  else START_MODE=app; comp=${START_COMP:-$(leanback_component "$PKG")}; sh_ "am start -n $comp" >/dev/null; fi
  echo "$START_MODE" > "$OUT/start_mode"; log "started as: $START_MODE"
  for _ in $(seq 30); do [ -n "$(pid_of "$PKG")" ] && break; sleep 1; done
  sleep 5; sh_ "am kill-all" >/dev/null; sleep 2
  for k in "$@"; do key "$k"; sleep 1.5; done
  sleep 3; shot "$OUT/idle_prep.png"
  t=$(sh_ "settings get system screen_off_timeout" | tr -dc 0-9); ago=$(sh_ "dumpsys power | grep -iE 'lastUserActivityTime='" | grep -oE '[0-9]+ ms ago' | head -1 | tr -dc 0-9)
  need=$((SETTLE + WINDOWS * 60 + 40)); left=$(( ${t:-600000} / 1000 - ${ago:-0} / 1000 ))
  [ "$left" -lt "$need" ] && die "screen timeout in ${left}s but the run needs ${need}s; press a key (prep sends keys) or raise the timeout"
  log "settling $SETTLE s (do not touch the remote); screen timeout budget ${left}s"; sleep "$SETTLE"
  sh_ "dumpsys power | grep -iE 'mWakefulness=|lastUserActivityTime='; dumpsys dreams | grep -m1 mCurrentDream=" > "$OUT/power_before.txt"
  gfx "$PKG" > "$OUT/gfxA.txt"
  "${A[@]}" push "$BENCH/snap.sh" /data/local/tmp/snap.sh </dev/null >/dev/null 2>&1
  "${A[@]}" shell "setsid sh /data/local/tmp/snap.sh /data/local/tmp/snap.out $WINDOWS 60 >/dev/null 2>&1 </dev/null &" </dev/null
  log "$WINDOWS × 60 s windows running"
  sleep $((WINDOWS * 60 + 8))
  for _ in $(seq 40); do sh_ "tail -n1 /data/local/tmp/snap.out" | grep -q DONE && break; sleep 3; done
  gfx "$PKG" > "$OUT/gfxB.txt"
  sh_ "dumpsys power | grep -iE 'mWakefulness=|lastUserActivityTime='; dumpsys dreams | grep -m1 mCurrentDream=" > "$OUT/power_after.txt"
  shot "$OUT/idle_after.png"
  sh_ "dumpsys meminfo --package $PKG" > "$OUT/meminfo_pkg.txt"
  sh_ "cat /proc/meminfo" > "$OUT/proc_meminfo.txt"
  sh_ "dumpsys meminfo" > "$OUT/meminfo_all.txt"
  "${A[@]}" pull /data/local/tmp/snap.out "$OUT/snap.out" </dev/null >/dev/null 2>&1
  sh_ "rm -f /data/local/tmp/snap.out /data/local/tmp/snap.sh" >/dev/null
  log "done -> python3 bench/analyze.py idle $OUT" ;;

launch)
  APPS=${APPS:-$(default_apps | paste -sd' ' -)}
  log "launch: apps=[$APPS] trials=$TRIALS"
  echo "stamp,label,app,kind,trial,total_ms,wait_ms,resident_after_home" > "$OUT/launch.csv.hdr"
  for p in $APPS; do
    comp=$(leanback_component "$p"); [ -z "$comp" ] && { log "skip $p (no leanback activity)"; continue; }
    for t in $(seq 1 "$TRIALS"); do
      # cold
      sh_ "am force-stop $p" >/dev/null; sleep 2
      o=$(am_start_w -n "$comp" -c android.intent.category.LEANBACK_LAUNCHER); c=$(echo "$o" | total_ms); w=$(echo "$o" | grep -m1 WaitTime | awk '{print $2}')
      sleep 8; go_home; sleep 3
      r=$([ -n "$(pid_of "$p")" ] && echo 1 || echo 0)
      rec "$p,cold,$t,${c:-NA},${w:-NA},$r"; log "$p cold #$t total=${c}ms resident_after_home=$r"
      # warm (process alive, activity re-shown)
      o=$(am_start_w -n "$comp" -c android.intent.category.LEANBACK_LAUNCHER); c=$(echo "$o" | total_ms); w=$(echo "$o" | grep -m1 WaitTime | awk '{print $2}')
      sleep 4
      # home return (warm)
      o=$(am_start_w $home_cmd); h=$(echo "$o" | total_ms)
      sleep 2
      rec "$p,warm,$t,${c:-NA},${w:-NA},1"; rec "$p,home_return,$t,${h:-NA},NA,NA"
      log "$p warm #$t total=${c}ms  home_return=${h}ms"
    done
  done
  log "done -> python3 bench/analyze.py launch $OUT" ;;

pressure)
  # `launch` empties the stage first (force-stop, then a first frame). This mode measures what a person
  # meets: whatever has piled up since the last restart, and how long an app keeps the device busy.
  # Nothing is stopped except the app being timed. Run it before and after a restart to compare.
  APPS=${APPS:-$(default_apps | paste -sd' ' -)}
  "${A[@]}" push "$BENCH/settle.sh" /data/local/tmp/rivi_settle.sh </dev/null >/dev/null 2>&1
  {
    echo "uptime_h=$(sh_ "cut -d. -f1 /proc/uptime" | awk '{printf "%.1f", $1/3600}')"
    sh_ "cat /proc/meminfo" | awk '/^MemAvailable|^SwapTotal|^SwapFree/{v[$1]=$2} END{printf "mem_available_mb=%d\nswap_used_mb=%d\nswap_used_pct=%d\n", v["MemAvailable:"]/1024, (v["SwapTotal:"]-v["SwapFree:"])/1024, v["SwapTotal:"] ? 100*(v["SwapTotal:"]-v["SwapFree:"])/v["SwapTotal:"] : 0}'
    echo "low_memory_kills_recorded=$(sh_ "dumpsys activity exit-info" | grep -c 'LOW_MEMORY')"
    echo "cached_app_processes=$(sh_ "dumpsys activity lru" | grep -cE ' cch')"
    echo "settings_screens_alive=$(sh_ "dumpsys activity activities" | grep -c 'Hist .*settings/')"
  } | tee "$OUT/pressure_state.txt" | sed 's/^/   /'
  log "largest holders of swap (MB swapped / MB resident):"
  sh_ 'for p in /proc/[0-9]*; do s=$(grep VmSwap $p/status 2>/dev/null | tr -dc 0-9); [ "${s:-0}" -gt 20000 ] && echo "$((s/1024)) $(($(grep VmRSS $p/status | tr -dc 0-9)/1024)) $(tr "\000" " " < $p/cmdline | cut -d" " -f1)"; done | sort -rn | head -10' | tee "$OUT/pressure_swap.txt" | sed 's/^/   /'
  echo "stamp,label,app,trial,first_ms,settle_ms,swapin_mb,majflt" > "$OUT/pressure.csv.hdr"
  for p in $APPS; do
    comp=$(leanback_component "$p"); [ -z "$comp" ] && { log "skip $p (no leanback activity)"; continue; }
    for t in $(seq 1 "$TRIALS"); do
      sh_ "am force-stop $p" >/dev/null; sleep 2
      set -- $(sh_ "sh /data/local/tmp/rivi_settle.sh $comp ${LIMIT:-25}")
      rec "$p,$t,${1:-NA},${2:-NA},${3:-NA},${4:-NA}"; log "$p #$t first frame ${1:-NA} ms, settled after ${2:-NA} ms, ${3:-NA} MB read back from swap, ${4:-NA} major faults"
      go_home; sleep 4
    done
  done
  sh_ "rm -f /data/local/tmp/rivi_settle.sh" >/dev/null
  log "done" ;;

switch)
  APPS=${APPS:-$(default_apps | head -2 | paste -sd' ' -)}
  set -- $APPS; A1=$1; A2=${2:-$1}
  c1=$(leanback_component "$A1"); c2=$(leanback_component "$A2")
  log "switch: $A1 <-> $A2, $TRIALS round trips"
  sh_ "am force-stop $A1; am force-stop $A2" >/dev/null; sleep 2
  am_start_w -n "$c1" -c android.intent.category.LEANBACK_LAUNCHER >/dev/null; sleep 8
  for t in $(seq 1 "$TRIALS"); do
    o=$(am_start_w -n "$c2" -c android.intent.category.LEANBACK_LAUNCHER); ms=$(echo "$o" | total_ms); sleep 6
    r1=$([ -n "$(pid_of "$A1")" ] && echo 1 || echo 0)
    rec "$A1->$A2,$t,${ms:-NA},resident_$A1=$r1"
    o=$(am_start_w -n "$c1" -c android.intent.category.LEANBACK_LAUNCHER); ms2=$(echo "$o" | total_ms); sleep 6
    r2=$([ -n "$(pid_of "$A2")" ] && echo 1 || echo 0)
    rec "$A2->$A1,$t,${ms2:-NA},resident_$A2=$r2"
    log "#$t to2=${ms}ms (1 resident=$r1)  to1=${ms2}ms (2 resident=$r2)  MemAvailable=$(sh_ 'grep MemAvailable /proc/meminfo' | awk '{print $2}')kB"
  done
  go_home; sleep 3
  rec "after,home,NA,resident_$A1=$([ -n "$(pid_of "$A1")" ] && echo 1 || echo 0);resident_$A2=$([ -n "$(pid_of "$A2")" ] && echo 1 || echo 0)"
  log "done" ;;

nav)
  PKG=${1:?home pkg}
  log "nav: 40 D-pad presses on $PKG, frame stats"
  if current_home | grep -q "^$PKG/"; then go_home; else comp=${START_COMP:-$(leanback_component "$PKG")}; sh_ "am start -n $comp" >/dev/null; fi; sleep 3
  sh_ "dumpsys activity activities | grep -m1 topResumedActivity" | grep -q "$PKG" || die "$PKG is not in the foreground"
  sh_ "dumpsys gfxinfo $PKG reset" >/dev/null; sleep 1
  for _ in 1 2 3 4 5; do for k in KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT; do key $k; sleep 0.35; done; done
  sleep 2
  sh_ "dumpsys gfxinfo $PKG" > "$OUT/nav_gfxinfo.txt"
  sh_ "dumpsys gfxinfo $PKG framestats" > "$OUT/nav_framestats.txt"
  grep -E "Total frames|Janky|percentile|Number (Missed|High|Slow)" "$OUT/nav_gfxinfo.txt" | tee -a "$OUT/log.txt"
  shot "$OUT/nav_after.png"
  log "done" ;;

navfast)
  # 20 × (RIGHT, LEFT) sent back-to-back: a sustained-navigation stress; reports frame stats + render stages
  PKG=${1:?home pkg}
  if current_home | grep -q "^$PKG/"; then go_home; else comp=${START_COMP:-$(leanback_component "$PKG")}; sh_ "am start -n $comp" >/dev/null; fi; sleep 3
  sh_ "dumpsys activity activities | grep -m1 topResumedActivity" | grep -q "$PKG" || die "$PKG is not in the foreground"
  sh_ "dumpsys gfxinfo $PKG reset" >/dev/null; sleep 1
  for _ in $(seq 1 20); do key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_LEFT; done; sleep 1.5
  sh_ "dumpsys gfxinfo $PKG" > "$OUT/navfast_gfxinfo.txt"
  sh_ "dumpsys gfxinfo $PKG framestats" > "$OUT/navfast_framestats.txt"
  grep -E "Total frames|Janky frames:|percentile:" "$OUT/navfast_gfxinfo.txt" | head -6 | tee -a "$OUT/log.txt"
  awk -F, '/^[0-9]+,/{ if (NF>20) { n++; i+=($16-$15)/1e6; g+=($17-$16)/1e6; t+=($17-$3)/1e6 } } END { if (n) printf "stages mean over %d frames: issue=%.1fms gpu=%.1fms total(vsync->done)=%.1fms\n", n, i/n, g/n, t/n }' "$OUT/navfast_framestats.txt" | tee -a "$OUT/log.txt"
  log "done" ;;

homekey)
  # Home-key latency as the system logs it: from the moment the system starts handling the Home
  # press ("START ... cat=[android.intent.category.HOME]" or the accessibility service's own START)
  # to the home screen's activity reporting resumed (events log wm_on_resume_called).
  # Usage: homekey LABEL HOME_PKG [FROM_PKG]   FROM_PKG default: Android Settings.  TRIALS trials.
  # Keys injected by adb do not pass through accessibility key filters, so with the Rivi
  # service on this measures its redirect path, not the direct key path of a physical remote.
  PKG=${1:?home pkg}; FROM=${2:-settings}
  log "homekey: $TRIALS × Home from $FROM, expecting $PKG"
  for t in $(seq 1 "$TRIALS"); do
    if [ "$FROM" = settings ]; then sh_ "am start -a android.settings.SETTINGS -f 0x10008000" >/dev/null 2>&1; else comp=$(leanback_component "$FROM"); sh_ "am start -n $comp -c android.intent.category.LEANBACK_LAUNCHER" >/dev/null 2>&1; fi
    sleep 4; sh_ "logcat -c; logcat -b events -c" >/dev/null 2>&1; sleep 0.3
    key KEYCODE_HOME; sleep 2.5
    sh_ "logcat -b main,system,events -d -v threadtime" > "$OUT/homekey_$t.txt"
    python3 - "$OUT/homekey_$t.txt" "$PKG" "$t" <<'PY' | tee -a "$OUT/homekey.csv" | tee -a "$OUT/log.txt"
import re,sys
f,pkg,t=sys.argv[1:4]
def ms(l):
    m=re.match(r'\d\d-\d\d (\d\d):(\d\d):(\d\d)\.(\d\d\d)',l); h,mi,se,x=map(int,m.groups()); return ((h*60+mi)*60+se)*1000+x
start=None; resumed=None; stock=None; via='stock-home-intent'
for l in open(f,errors='replace'):
    if not re.match(r'\d\d-\d\d ',l): continue
    if start is None and 'ActivityTaskManager' in l and 'START u0' in l and ('category.HOME' in l or pkg+'/' in l):
        start=ms(l); via='direct' if (pkg+'/' in l and 'category.HOME' not in l) else 'stock-home-intent'
    if start is not None and stock is None and 'wm_on_resume_called' in l and 'launcherx' in l and pkg not in l: stock=ms(l)
    if start is not None and resumed is None and 'wm_on_resume_called' in l and pkg in l: resumed=ms(l)
print(f"{t},{pkg},{via},{'' if resumed is None else resumed-start},{'' if stock is None else stock-start}")
PY
  done
  python3 - "$OUT/homekey.csv" <<'PY' | tee -a "$OUT/log.txt"
import csv,statistics as st,sys
rows=[r for r in csv.reader(open(sys.argv[1])) if len(r)==5]
v=[int(r[3]) for r in rows if r[3]]; s=[int(r[4]) for r in rows if r[4]]
print(f"home screen resumed after Home: n={len(v)}/{len(rows)} median={st.median(v) if v else 'NA'} ms max={max(v) if v else 'NA'} ms; paths={sorted(set(r[2] for r in rows))}; stock home screen resumed first in {len(s)} trials")
PY
  log "done" ;;

system)
  log "system snapshot"
  sh_ "cat /proc/meminfo" > "$OUT/sys_meminfo.txt"
  sh_ "cat /proc/pressure/memory /proc/pressure/cpu 2>/dev/null" > "$OUT/sys_psi.txt"
  sh_ 'for t in /sys/class/thermal/thermal_zone*; do echo "$(cat $t/type 2>/dev/null) $(cat $t/temp 2>/dev/null)"; done' > "$OUT/sys_thermal.txt"
  sh_ "df -h /data" > "$OUT/sys_df.txt"
  sh_ "dumpsys dropbox --print 2>/dev/null | grep -E '^Drop box|^[0-9-]+ [0-9:]+ (system_app_anr|system_app_crash|data_app_anr|data_app_crash|system_server_watchdog|lowmem)' | tail -60" > "$OUT/sys_dropbox.txt"
  sh_ "dumpsys dropbox 2>/dev/null | tail -80" > "$OUT/sys_dropbox_list.txt"
  sh_ "dumpsys meminfo" > "$OUT/sys_meminfo_all.txt"
  sh_ "dumpsys activity lru 2>/dev/null | head -80" > "$OUT/sys_lru.txt"
  sh_ "cat /sys/block/zram0/mm_stat 2>/dev/null" > "$OUT/sys_zram.txt"
  sh_ "top -b -n 1 -m 15 -o PID,%CPU,RSS,NAME" > "$OUT/sys_top.txt"
  grep -E "MemTotal|MemAvailable|SwapTotal|SwapFree" "$OUT/sys_meminfo.txt" | tee -a "$OUT/log.txt"
  cat "$OUT/sys_thermal.txt" | tee -a "$OUT/log.txt" | head -5
  log "done" ;;

cycles)
  PKG=${1:?home pkg}; N=${2:-20}
  APPS=${APPS:-$(default_apps | paste -sd' ' -)}; set -- $APPS
  log "cycles: $N × Home → app → Home on $PKG with apps [$APPS]"
  fail=0; i=0
  while [ $i -lt $N ]; do
    i=$((i+1)); idx=$(( (i-1) % $# + 1 )); app=${!idx}
    comp=$(leanback_component "$app")
    o=$(am_start_w -n "$comp" -c android.intent.category.LEANBACK_LAUNCHER); ms=$(echo "$o" | total_ms); sleep 5
    go_home; sleep 2.5
    top=$(sh_ "dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity'")
    ok=$(echo "$top" | grep -q "$PKG" && echo ok || echo FAIL)
    [ "$ok" = FAIL ] && fail=$((fail+1))
    key KEYCODE_DPAD_RIGHT; sleep 0.3; key KEYCODE_DPAD_LEFT; sleep 0.5
    hp=$(pid_of "$PKG")
    rec "$i,$app,${ms:-NA},$ok,homepid=${hp:-none}"
    log "#$i $app launch=${ms}ms home=$ok homepid=${hp:-none}"
  done
  sh_ "dumpsys dropbox 2>/dev/null | grep -E '$PKG' | tail -5" > "$OUT/cycles_dropbox.txt"
  log "done: $((N-fail))/$N ok, $fail failures; crashes logged: $(grep -c . "$OUT/cycles_dropbox.txt")" ;;

*) die "unknown mode $MODE" ;;
esac
