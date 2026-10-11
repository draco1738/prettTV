#!/usr/bin/env bash
# On-device UI tests for Rivi, driven over adb with real key events.
# State is read through the activity's dumpsys hook (`dumpsys activity fi.jommi.rivi` → "RIVI ..." lines).
# Uses a dummy leanback app (launcher/testapp) so no real streaming app is launched or modified.
#
#   tests/ui_test.sh            run everything
# Exit code = number of failed checks. Results are appended to results/tests/<timestamp>.log
source "$(dirname "$0")/../adb/common.sh"
TESTAPK=$ROOT/launcher/testapp/build/outputs/apk/debug/testapp-debug.apk
TPKG=fi.jommi.rivi.testapp; TKEY=$TPKG/.MainActivity
LOG=$ROOT/results/tests/$(date +%Y%m%d-%H%M%S).log; mkdir -p "$(dirname "$LOG")"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  PASS  $*" | tee -a "$LOG"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL  $*" | tee -a "$LOG"; }
check(){ local d=$1; shift; if "$@"; then ok "$d"; else bad "$d"; fi; }
t()    { echo; echo "== $*" | tee -a "$LOG"; }
key()  { sh_ "input keyevent $1" >/dev/null; sleep "${2:-0.5}"; }
keys() { local n=$1 i=0; while [ "$i" -lt "$n" ]; do key "$2" "${3:-0.35}"; i=$((i+1)); done; }   # keys N KEY [delay]; N may be 0
longpress() { sh_ "input keyevent --longpress KEYCODE_DPAD_CENTER" >/dev/null; sleep 1; }
# dumpsys can time out when the device is short of memory (seen with four streaming apps resident):
# retry instead of treating an empty answer as launcher state.
jd()   { local o i=0; while [ $i -lt 4 ]; do o=$(sh_ "dumpsys activity $PKG" | grep -E '^ *RIVI ' | sed 's/^ *RIVI //'); [ -n "$o" ] && { echo "$o"; return 0; }; i=$((i+1)); sleep 1.5; done; echo "  (no dumpsys answer after 4 tries)" >> "$LOG"; return 1; }
field(){ jd | grep -m1 "^$1=" | cut -d= -f2-; }          # favourites / rest / hidden / focus
kv()   { jd | tr ' ' '\n' | grep -m1 "^$1=" | cut -d= -f2; }   # version / reloads / tiles / withBitmap
top()  { sh_ "dumpsys activity activities" 2>/dev/null | grep -m1 -E 'topResumedActivity' ; }
top_is(){ top | grep -q "$1"; }
pidj() { sh_ "pidof $PKG"; }
front(){ sh_ "am start -n $HOME_ACT" >/dev/null 2>&1; sleep "${1:-2}"; }
in_list() { echo ",$2," | grep -q ",$1,"; }
nth()  { echo "$1" | tr ',' '\n' | sed -n "$2p"; }
idx_of(){ echo "$1" | tr ',' '\n' | grep -n -x -F "$2" | cut -d: -f1; }
wait_for() { local n=$1; shift; for _ in $(seq "$n"); do "$@" && return 0; sleep 1; done; return 1; }
focus_tile() {   # focus_tile KEY : navigate with the D-pad to a tile in the grid ("rest") or favourites
  local favs rest i
  key KEYCODE_BACK 0.6                                  # BACK = first favourite
  favs=$(field favourites); rest=$(field rest)
  if in_list "$1" "$favs"; then i=$(idx_of "$favs" "$1"); keys $((i-1)) KEYCODE_DPAD_RIGHT
  else
    i=$(idx_of "$rest" "$1"); [ -z "$i" ] && return 1
    local row=$(( (i-1) / 6 )) col=$(( (i-1) % 6 ))
    key KEYCODE_DPAD_DOWN 0.4; keys 6 KEYCODE_DPAD_LEFT 0.2
    keys "$row" KEYCODE_DPAD_DOWN 0.4
    keys "$col" KEYCODE_DPAD_RIGHT
  fi
  sleep 0.5; [ "$(field focus)" = "$1" ]
}
menu_pick() { longpress; keys "$1" KEYCODE_DPAD_DOWN 0.3; key KEYCODE_DPAD_CENTER 1; }   # 0-based item

# `am force-stop` makes Android switch off the package's accessibility service. When the owner has
# the Home-button service on, never force-stop: the cold-start checks are skipped instead.
SERVICE_ON=0; sh_ "settings get secure enabled_accessibility_services" | grep -q "$PKG/" && SERVICE_ON=1
restart_launcher() {   # returns 1 (and restarts nothing) when the service is on
  [ "$SERVICE_ON" = 1 ] && return 1
  sh_ "am force-stop $PKG"; sleep 1; front "${1:-3}"; return 0
}
skip() { echo "  SKIP  $*" | tee -a "$LOG"; }

echo "Rivi UI tests on $TV — $(date)  (Home-button service on: $SERVICE_ON)" | tee "$LOG"
sh_ "pm path $PKG" | grep -q package: || die "Rivi not installed"
[ -f "$TESTAPK" ] || die "build the test app first: (cd launcher && ./gradlew :testapp:assembleDebug)"
"${A[@]}" uninstall $TPKG </dev/null >/dev/null 2>&1

t "1. Startup: cold start shows tiles with artwork"
restart_launcher 1 || { skip "cold start not forced (service on); checking the running launcher"; front 1; }
has_pid() { [ -n "$(pidj)" ]; }
check "process started" wait_for 10 has_pid
check "Rivi is the top activity" wait_for 5 top_is "$PKG"
all_bitmaps() { local n b; n=$(kv tiles); b=$(kv withBitmap); [ -n "$n" ] && [ "$n" -gt 0 ] && [ "$n" = "$b" ]; }
check "every tile has artwork within 10 s ($(kv tiles) tiles)" wait_for 10 all_bitmaps
check "a tile has focus (focus=$(field focus))" test "$(field focus)" != none

t "2. App discovery matches the package manager"
want=$(sh_ "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER" | grep / | sed 's/^ *//' | cut -d/ -f1 | grep -v -x -e "$PKG" -e com.google.android.apps.tv.launcherx -e com.google.android.tvlauncher -e com.google.android.tungsten.setupwraith | sort -u)
got=$( (field favourites; field rest; field hidden) | tr ',' '\n' | grep / | cut -d/ -f1 | sort -u)
check "same set of packages ($(echo "$want" | grep -c .) expected, $(echo "$got" | grep -c .) shown/hidden)" test "$want" = "$got"
for p in com.netflix.ninja com.amazon.amazonvideo.livingroom com.google.android.youtube.tv com.apple.atve.androidtv.appletv; do
  if sh_ "pm path $p" | grep -q package:; then check "$p is on the home screen" bash -c "echo '$got' | grep -q -x '$p'"; else echo "  SKIP  $p not installed" | tee -a "$LOG"; fi
done
check "no duplicate tiles" test "$( (field favourites; field rest) | tr ',' '\n' | grep -c .)" = "$( (field favourites; field rest) | tr ',' '\n' | sort -u | grep -c .)"

t "3. Remote navigation"
key KEYCODE_BACK 0.6; favs=$(field favourites); rest=$(field rest)
check "BACK focuses the first favourite" test "$(field focus)" = "$(nth "$favs" 1)"
key KEYCODE_DPAD_RIGHT; check "RIGHT moves to the second favourite" test "$(field focus)" = "$(nth "$favs" 2)"
key KEYCODE_DPAD_LEFT;  check "LEFT moves back" test "$(field focus)" = "$(nth "$favs" 1)"
key KEYCODE_DPAD_LEFT;  check "LEFT at the edge stays put" test "$(field focus)" = "$(nth "$favs" 1)"
key KEYCODE_DPAD_UP;    check "UP at the top stays put" test "$(field focus)" = "$(nth "$favs" 1)"
key KEYCODE_DPAD_DOWN;  f=$(field focus); check "DOWN enters the app grid (focus=$f)" in_list "$f" "$rest"
for _ in 1 2 3 4 5 6 7; do key KEYCODE_DPAD_RIGHT 0.25; done
check "RIGHT ×7 stops at the end of the first grid row" test "$(field focus)" = "$(nth "$rest" 6)"
for _ in 1 2 3 4 5 6 7 8; do key KEYCODE_DPAD_DOWN 0.3; done; f=$(field focus)
check "DOWN ×8 ends on a tile in the grid (focus=$f)" in_list "$f" "$rest"
key KEYCODE_DPAD_UP; for _ in 1 2 3 4 5 6 7 8; do key KEYCODE_DPAD_UP 0.3; done; f=$(field focus)
check "UP returns to the favourites row (focus=$f)" in_list "$f" "$favs"
check "still the top activity, same process" top_is "$PKG"

t "4. New app appears while the launcher is visible; launching it"
p0=$(pidj); r0=$(kv reloads)
"${A[@]}" install -r "$TESTAPK" </dev/null >/dev/null 2>&1
has_test() { in_list "$TKEY" "$(field rest)"; }
check "installed test app shows up in the grid within 8 s" wait_for 8 has_test
check "launcher was not restarted (pid $p0)" test "$(pidj)" = "$p0"
check "navigate to the test app tile with the D-pad" focus_tile "$TKEY"
key KEYCODE_DPAD_CENTER 2.5
check "OK launches it" top_is "$TPKG"

t "5. Focus restoration"
key KEYCODE_BACK 2
check "BACK from the app returns to Rivi" top_is "$PKG"
check "focus is restored to the tile that was launched" test "$(field focus)" = "$TKEY"
check "recently launched app moved to the front of the grid" test "$(nth "$(field rest)" 1)" = "$TKEY"

t "6. Configuration: favourites, ordering, persistence"
focus_tile "$TKEY" >/dev/null; menu_pick 0            # Add to favourites
favs=$(field favourites); check "long-press → Add to favourites" in_list "$TKEY" "$favs"
n=$(echo "$favs" | tr ',' '\n' | grep -c .)
check "added at the end of favourites" test "$(idx_of "$favs" "$TKEY")" = "$n"
focus_tile "$TKEY" >/dev/null; menu_pick 1            # Move left
check "long-press → Move left" test "$(idx_of "$(field favourites)" "$TKEY")" = "$((n-1))"
before=$(field favourites)
if restart_launcher 3; then
  check "favourites order survives a process restart" test "$(field favourites)" = "$before"
  check "focus after restart is the last focused tile" test "$(field focus)" = "$TKEY"
else skip "process-restart persistence (service on)"; fi
focus_tile "$TKEY" >/dev/null; pos=$(idx_of "$(field favourites)" "$TKEY"); tot=$(field favourites | tr ',' '\n' | grep -c .)
hidemenu=1; [ "${pos:-1}" -gt 1 ] && hidemenu=$((hidemenu+1)); [ "${pos:-1}" -lt "$tot" ] && hidemenu=$((hidemenu+1))
menu_pick "$hidemenu"                                 # Hide: after Remove [, Move left] [, Move right]
check "long-press → Hide removes the tile" bash -c "! echo ',$(field favourites),$(field rest),' | grep -q ',$TKEY,'"
check "hidden list records it" in_list "$TKEY" "$(field hidden)"
check "focus moved to another tile" test "$(field focus)" != none
key KEYCODE_MENU 1; key KEYCODE_DPAD_DOWN 0.3; key KEYCODE_DPAD_CENTER 1     # menu: Android settings, Show hidden apps, …
check "MENU → Show hidden apps restores it" in_list "$TKEY" "$(field favourites)"

t "7. Missing apps"
p0=$(pidj)
"${A[@]}" uninstall $TPKG </dev/null >/dev/null 2>&1
gone() { ! echo ",$(field favourites),$(field rest)," | grep -q ",$TKEY,"; }
check "uninstalled favourite disappears within 8 s" wait_for 8 gone
check "launcher survived (pid $p0)" test "$(pidj)" = "$p0"
check "focus moved to a valid tile ($(field focus))" test "$(field focus)" != none
key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_LEFT
check "remote still navigates" test "$(field focus)" != none
# reinstall while another app is in front: the launcher must pick it up when it returns
sh_ "am start -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 2
"${A[@]}" install -r "$TESTAPK" </dev/null >/dev/null 2>&1; sleep 2
front 3
back_fav() { in_list "$TKEY" "$(field favourites)"; }
check "app reinstalled in the background returns to its favourite slot" wait_for 8 back_fav

t "8. Activity lifecycle"
p0=$(pidj); r0=$(kv reloads)
key KEYCODE_BACK 0.8; check "BACK never closes the home screen" top_is "$PKG"
for i in 1 2 3; do sh_ "am start -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 1.5; front 1.5; done
check "3× leave/return keeps the same process" test "$(pidj)" = "$p0"
check "returning does not rescan apps (reloads $r0 → $(kv reloads))" test "$(kv reloads)" = "$r0"
check "all tiles still have artwork" all_bitmaps

t "9. Unexpected exits"
focus_tile "$TKEY" >/dev/null; key KEYCODE_DPAD_CENTER 2.5
sh_ "am force-stop $TPKG"; sleep 2
check "foreground app killed → launcher process still alive" test -n "$(pidj)"
front 2; check "launcher comes back with focus" test "$(field focus)" != none
if restart_launcher 3; then
  check "launcher killed → restarts with all tiles" all_bitmaps
  check "…and restores focus" test "$(field focus)" = "$TKEY"
else skip "launcher kill/restart (service on)"; fi
crashes=$(sh_ "dumpsys dropbox --print 2>/dev/null" | grep -c "Process: $PKG\$")
check "no Rivi crash or ANR in dropbox ($crashes)" test "$crashes" = 0

t "10. Home button"
if [ "$SERVICE_ON" = 1 ] && [ "$(kv homeService)" = true ]; then
  # adb-injected keys bypass the accessibility key filter, so this exercises the redirect path
  # (stock home starts, the service covers it). The direct key path needs the physical remote.
  k0=$(( $(kv homeKeys) + $(kv redirects) ))
  sh_ "am start -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 2
  key KEYCODE_HOME 2
  check "Home from Settings opens Rivi" top_is "$PKG"
  check "handled by the Rivi service (key or redirect)" test "$(( $(kv homeKeys) + $(kv redirects) ))" -gt "$k0"
  focus_tile "$TKEY" >/dev/null; key KEYCODE_DPAD_CENTER 2.5; key KEYCODE_HOME 2
  check "Home from an app opens Rivi" top_is "$PKG"
  check "focus is on the app that was open" test "$(field focus)" = "$TKEY"
  key KEYCODE_HOME 1.5; check "Home on the home screen stays there" top_is "$PKG"
  check "stock launcher still enabled" bash -c "! adb -s $TV shell pm list packages -d </dev/null | grep -q launcherx"
elif current_home | grep -q "^$PKG/"; then
  sh_ "am start -a android.settings.SETTINGS" >/dev/null 2>&1; sleep 2; key KEYCODE_HOME 2
  check "Home from Settings opens Rivi (Rivi is the system Home app)" top_is "$PKG"
else
  echo "  SKIP  Rivi does not own the Home button yet (service off, stock launcher is Home)" | tee -a "$LOG"
fi

[ "$SERVICE_ON" = 1 ] && check "Home-button service still switched on after the tests" bash -c "adb -s $TV shell settings get secure enabled_accessibility_services </dev/null | grep -q '$PKG/'"

t "Cleanup"
focus_tile "$TKEY" >/dev/null && menu_pick 0          # Remove from favourites
check "test app removed from favourites" bash -c "! echo ',$(field favourites),' | grep -q ',$TKEY,'"
"${A[@]}" uninstall $TPKG </dev/null >/dev/null 2>&1; sleep 2
key KEYCODE_BACK
echo; echo "RESULT: $PASS passed, $FAIL failed  (log: $LOG)" | tee -a "$LOG"
exit $FAIL
