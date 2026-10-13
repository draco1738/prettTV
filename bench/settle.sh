# Device-side helper (pushed by benchmark.sh). Starts one activity and reports how the start felt:
#   first_ms   am start -W TotalTime: the app's first frame (what `launch` measures)
#   settle_ms  until the whole device has been below BUSY% CPU for one second: a proxy for "finished
#              loading". An app that keeps playing video never settles and reports the limit.
#   swapin_mb  memory read back from compressed swap meanwhile; majflt = page faults that had to wait
# Usage: sh settle.sh COMPONENT [LIMIT_S] [BUSY%]      Output: one line "first_ms settle_ms swapin_mb majflt"
C=$1; LIMIT=${2:-25}; BUSY=${3:-30}
vm() { awk -v k="$1" '$1==k{print $2}' /proc/vmstat; }
cpu() { read -r _ a b c d e f g h _ < /proc/stat; echo "$((a+b+c+d+e+f+g+h)) $((d+e))"; }
now() { read -r u _ < /proc/uptime; echo "${u%.*}${u#*.}"; }   # hundredths of a second: the device shell has 32-bit arithmetic
s0=$(vm pswpin); f0=$(vm pgmajfault); t0=$(now)
first=$(am start -W -n "$C" -c android.intent.category.LEANBACK_LAUNCHER 2>&1 | awk '/TotalTime/{print $2}')
set -- $(cpu); pt=$1; pi=$2; calm=0; settle=$((LIMIT * 1000))   # reported when it never settles
while [ $(( $(now) - t0 )) -lt $((LIMIT * 100)) ]; do
  sleep 0.2
  set -- $(cpu); dt=$(($1 - pt)); di=$(($2 - pi)); pt=$1; pi=$2
  [ "$dt" -gt 0 ] || continue
  if [ $(( 100 - 100 * di / dt )) -lt "$BUSY" ]; then calm=$((calm + 1)); else calm=0; fi
  if [ "$calm" -ge 5 ]; then settle=$(( ($(now) - t0 - 100) * 10 )); break; fi
done
echo "${first:-NA} $settle $(( ($(vm pswpin) - s0) * 4 / 1024 )) $(( $(vm pgmajfault) - f0 ))"
