#!/usr/bin/env bash
# A/B a launcher change: install each APK in turn (interleaved), run `navfast` N times per APK,
# and print the per-run janky %, p50/p90/p99 frame time and mean GPU stage, plus medians.
#   bench/ab.sh N nameA=a.apk nameB=b.apk
source "$(dirname "$0")/../adb/common.sh"
N=${1:?N}; shift
for round in $(seq 1 "$N"); do
  for spec in "$@"; do
    name=${spec%%=*}; apk=${spec#*=}
    "${A[@]}" install -r "$apk" </dev/null >/dev/null 2>&1 || die "install $apk failed"
    sh_ "am start -n $HOME_ACT" >/dev/null 2>&1; sleep 4
    out=$("$ROOT/bench/benchmark.sh" navfast "ab-$name" "$PKG" 2>&1)
    j=$(echo "$out" | grep -m1 "Janky frames:" | sed 's/.*(\(.*\)%).*/\1/')
    pct() { echo "$out" | grep -m1 "^$1th percentile" | sed 's/.*: *\([0-9]*\)ms.*/\1/'; }
    p50=$(pct 50); p90=$(pct 90); p99=$(pct 99)
    gpu=$(echo "$out" | grep -m1 "stages mean" | sed 's/.*gpu=\([0-9.]*\)ms.*/\1/'); tot=$(echo "$out" | grep -m1 "stages mean" | sed 's/.*done)=\([0-9.]*\)ms.*/\1/')
    [ -z "$j" ] && die "navfast produced no data for $name (round $round): $(echo "$out" | tail -2)"
    echo "$name,$round,$j,$p50,$p90,$p99,$gpu,$tot" | tee -a "$ROOT/results/runs/ab-$name/ab.csv"
  done
done
python3 - "$ROOT" "$@" <<'PY'
import csv,statistics as st,sys,os
root=sys.argv[1]
print(f"{'variant':12} {'n':>2} {'janky% med':>10} {'p50':>5} {'p90':>5} {'p99':>5} {'gpu ms':>7} {'total ms':>8}")
for spec in sys.argv[2:]:
    name=spec.split('=')[0]; rows=[r for r in csv.reader(open(os.path.join(root,'results/runs',f'ab-{name}','ab.csv'))) if len(r)==8]
    col=lambda i:[float(r[i]) for r in rows if r[i]]
    print(f"{name:12} {len(rows):>2} {st.median(col(2)):>10.2f} {st.median(col(3)):>5.0f} {st.median(col(4)):>5.0f} {st.median(col(5)):>5.0f} {st.median(col(6)):>7.1f} {st.median(col(7)):>8.1f}")
PY
