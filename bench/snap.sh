# Device-side sampler (pushed by benchmark.sh). Snapshots /proc every I seconds, N windows (N+1 snapshots).
# Adapted from hungmi/just-a-launcher bench/snap.sh (MIT).  Usage: sh snap.sh OUT N I
OUT=$1; N=$2; I=$3
names() { for p in /proc/[0-9]*; do echo "N ${p#/proc/} $(tr '\0' ' ' < $p/cmdline 2>/dev/null)"; done; }
{
  names
  k=0
  while [ $k -le $N ]; do
    echo "@@ $k"; cat /proc/uptime; cat /proc/stat
    echo "@@procs"; cat /proc/[0-9]*/stat 2>/dev/null; echo "@@end"
    [ $k -lt $N ] && sleep $I
    k=$((k+1))
  done
  names
  echo DONE
} > $OUT 2>/dev/null
