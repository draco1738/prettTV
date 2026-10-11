#!/usr/bin/env python3
"""Analyse results from bench/benchmark.sh.

  python3 bench/analyze.py idle   results/runs/A [results/runs/B ...]   idle CPU / redraw / PSS, comparison table
  python3 bench/analyze.py launch results/runs/A [...]                  launch medians / p90 per app & kind
  python3 bench/analyze.py switch results/runs/A [...]
  python3 bench/analyze.py cycles results/runs/A

Idle maths (adapted from hungmi/just-a-launcher bench/analyze.py, MIT):
  process %  = Δ(utime+stime) / (seconds × USER_HZ)        -> share of ONE core
  device %   = 1 − Δ(idle+iowait) / Δ(all fields)          -> share of ALL cores
  redraw/s   = Δ(gfxinfo 'Total frames rendered') / Δ(gfxinfo Uptime)
  memory     = Σ 'TOTAL PSS' over the package's processes (dumpsys meminfo --package; includes swap)
"""
import csv, os, re, statistics as st, sys

HZ = 100

def parse_snap(path):
    names, snaps, cur, mode = {}, [], None, None
    for line in open(path, errors='replace'):
        line = line.rstrip('\n')
        if line.startswith('N '):
            p = line.split(' ', 2)
            if len(p) == 3 and p[2].strip(): names[p[1]] = p[2].strip()
        elif line.startswith('@@ '):
            cur = {'cpu': {}, 'procs': {}}; snaps.append(cur); mode = 'uptime'
        elif line == '@@procs': mode = 'procs'
        elif line == '@@end': cur['done'] = True; mode = None
        elif mode == 'uptime': cur['uptime'] = float(line.split()[0]); mode = 'stat'
        elif mode == 'stat' and line.startswith('cpu'):
            f = line.split(); cur['cpu'][f[0]] = list(map(int, f[1:]))
        elif mode == 'procs':
            m = re.match(r'(\d+) \((.*)\) (.*)', line)
            if m:
                rest = m.group(3).split()
                cur['procs'][(m.group(1), rest[19])] = (m.group(2), int(rest[11]) + int(rest[12]))
    return names, [s for s in snaps if s.get('done')]

def windows(names, snaps):
    rows = []
    for a, b in zip(snaps, snaps[1:]):
        dt = b['uptime'] - a['uptime']
        d = [y - x for x, y in zip(a['cpu']['cpu'], b['cpu']['cpu'])]
        procs = {}
        for key, (comm, t1) in b['procs'].items():
            t0 = a['procs'].get(key, (comm, 0))[1]
            procs[key] = (names.get(key[0], comm).split(' ')[0], (t1 - t0) / (dt * HZ))
        rows.append({'dt': dt, 'total': 1 - (d[3] + d[4]) / sum(d), 'procs': procs})
    return rows

def cpu(row, pred): return sum(v for n, v in row['procs'].values() if pred(n))

def gfx(path):
    t = open(path).read()
    up = re.search(r'Uptime: (\d+)', t); frames = re.findall(r'Total frames rendered: (\d+)', t)
    return (int(up.group(1)) if up else 0, sum(map(int, frames)), re.findall(r'Graphics info for pid (\d+)', t))

def pss_by_process(path):
    t = open(path).read()
    if 'Total PSS by process:' not in t: return {}
    sec = t.split('Total PSS by process:')[1].split('Total PSS by OOM adjustment:')[0]
    out = {}
    for kb, name in re.findall(r'^\s*([\d,]+)K: (\S+) \(pid', sec, re.M):
        out[name] = out.get(name, 0) + int(kb.replace(',', '')) / 1024
    return out

def mean(xs): return st.mean(xs) if xs else 0
def pct(v): v *= 100; return f'{v:.0f}%' if v >= 5 else (f'{v:.1f}%' if v >= 0.05 else '0%')

def analyze_idle(run):
    pkg = open(os.path.join(run, 'pkg')).read().strip()
    names, snaps = parse_snap(os.path.join(run, 'snap.out'))
    rows = windows(names, snaps)
    is_pkg = lambda n: n == pkg or n.startswith(pkg + ':')
    r = {'label': os.path.basename(run.rstrip('/')), 'pkg': pkg, 'warn': []}
    r['home'] = [cpu(w, is_pkg) for w in rows]
    r['sf'] = [cpu(w, lambda n: n.endswith('surfaceflinger')) for w in rows]
    r['hal'] = [cpu(w, lambda n: 'composer' in n) for w in rows]
    r['total'] = [w['total'] for w in rows]
    r['ncpu'] = max((sum(1 for k in s['cpu'] if k != 'cpu') for s in snaps), default=0)
    agg = {}
    for w in rows:
        for n, v in w['procs'].values(): agg[n] = agg.get(n, 0) + v / len(rows)
    r['top'] = sorted(((v, n) for n, v in agg.items()), reverse=True)[:12]
    (ua, fa, pa), (ub, fb, pb) = gfx(os.path.join(run, 'gfxA.txt')), gfx(os.path.join(run, 'gfxB.txt'))
    r['fps'] = (fb - fa) / ((ub - ua) / 1000) if ub > ua else 0; r['frames'] = fb - fa; r['fsec'] = (ub - ua) / 1000
    if pa != pb: r['warn'].append(f'home process changed during run (pid {pa} -> {pb}); redraw/s unreliable')
    txt = open(os.path.join(run, 'meminfo_pkg.txt')).read()
    r['mem'] = [(name, int(pss) / 1024, int(swap) / 1024) for name, pss, swap in re.findall(
        r'MEMINFO in pid \d+ \[(.*?)\] \*\*.*?TOTAL PSS:\s+(\d+).*?TOTAL SWAP[^:]*:\s+(\d+)', txt, re.S)]
    r['pss_all'] = pss_by_process(os.path.join(run, 'meminfo_all.txt'))
    pa_ = open(os.path.join(run, 'power_before.txt')).read(); pb_ = open(os.path.join(run, 'power_after.txt')).read()
    ga = re.search(r'lastUserActivityTime=(\d+)', pa_); gb = re.search(r'lastUserActivityTime=(\d+)', pb_)
    if ga and gb and ga.group(1) != gb.group(1): r['warn'].append('remote was pressed during the run; discard')
    if 'Asleep' in pa_ + pb_ or 'Dozing' in pa_ + pb_: r['warn'].append('device slept during run; discard')
    if 'DreamRecord' in pb_ or 'DreamRecord' in pa_: r['warn'].append('screensaver (dream) was active; whole-device numbers invalid')
    r['dts'] = [w['dt'] for w in rows]
    return r

def print_idle(r):
    print(f"== {r['label']} ({r['pkg']}) {len(r['dts'])} windows of {', '.join(f'{d:.0f}' for d in r['dts'])} s")
    for label, key in (('home proc (incl. sub-procs), 1 core', 'home'), ('surfaceflinger, 1 core', 'sf'), ('composer HAL, 1 core', 'hal'), (f"whole device ({r['ncpu']} cores)", 'total')):
        print(f'{label:38}' + ''.join(f'  {v * 100:5.1f}%' for v in r[key]) + f'  mean {mean(r[key]) * 100:5.1f}%')
    print(f"redraws/s {r['fps']:.2f} ({r['frames']} frames / {r['fsec']:.1f} s)")
    total = sum(p for _, p, _ in r['mem'])
    print(f"PSS {total:.1f} MB = " + ' + '.join(f'{n} {p:.1f} (swap {s:.1f})' for n, p, s in r['mem']))
    short = lambda n: os.path.basename(n) if n.startswith('/') else n
    print('top CPU (1 core): ' + ', '.join(f'{short(n)} {v * 100:.1f}%' for v, n in r['top']))
    for w in r['warn']: print(f'WARNING: {w}')
    print()

def print_idle_compare(rs):
    print('| idle | ' + ' | '.join(r['label'] for r in rs) + ' |'); print('|---' * (len(rs) + 1) + '|')
    print('| Home PSS (MB, incl. sub-processes & swap) | ' + ' | '.join(f"{sum(p for _, p, _ in r['mem']):.0f}" for r in rs) + ' |')
    print('| Home process CPU (of one core) | ' + ' | '.join(pct(mean(r['home'])) for r in rs) + ' |')
    print('| surfaceflinger CPU (of one core) | ' + ' | '.join(pct(mean(r['sf'])) for r in rs) + ' |')
    print(f"| Whole-device CPU (all cores) | " + ' | '.join(pct(mean(r['total'])) for r in rs) + ' |')
    print('| Redraws per second | ' + ' | '.join(f"{r['fps']:.0f}" for r in rs) + ' |')
    names = set().union(*(r['pss_all'] for r in rs))
    diff = sorted(((max(r['pss_all'].get(n, 0) for r in rs) - min(r['pss_all'].get(n, 0) for r in rs), n) for n in names), reverse=True)
    print('\nProcesses differing by > 4 MB PSS:'); print(f"{'':50}" + ''.join(f'{r["label"][:9]:>10}' for r in rs))
    for d, n in diff:
        if d < 4: break
        print(f'{n[:50]:50}' + ''.join(f"{r['pss_all'].get(n, 0):10.1f}" for r in rs))
    print(f"{'device total':46}" + ''.join(f"{sum(r['pss_all'].values()):10.0f}" for r in rs))

def stats(xs):
    xs = sorted(x for x in xs if x is not None)
    if not xs: return 'n=0'
    p90 = xs[min(len(xs) - 1, int(round(0.9 * (len(xs) - 1))))]
    return f'n={len(xs)} median={st.median(xs):.0f} p90={p90} min={xs[0]} max={xs[-1]}'

def analyze_launch(runs):
    for run in runs:
        p = os.path.join(run, 'launch.csv')
        if not os.path.exists(p): continue
        print(f'== {os.path.basename(run)}')
        by = {}
        for row in csv.reader(open(p)):
            if len(row) < 6: continue
            _, _, app, kind, _, total = row[:6]
            by.setdefault((app, kind), []).append(int(total) if total.isdigit() else None)
        for (app, kind), xs in sorted(by.items()):
            print(f'  {app:40} {kind:12} {stats(xs)} ms')
        print()

def analyze_switch(runs):
    for run in runs:
        p = os.path.join(run, 'switch.csv')
        if not os.path.exists(p): continue
        print(f'== {os.path.basename(run)}'); by = {}
        for row in csv.reader(open(p)):
            if len(row) >= 5 and row[4].isdigit(): by.setdefault(row[2], []).append(int(row[4]))
            elif len(row) >= 5: pass
        for k, xs in by.items(): print(f'  {k:50} {stats(xs)} ms')
        res = [row for row in csv.reader(open(p)) if len(row) >= 6]
        print('  resident flags: ' + ', '.join(sorted({r[5] for r in res})))
        print()

def analyze_cycles(runs):
    for run in runs:
        p = os.path.join(run, 'cycles.csv')
        if not os.path.exists(p): continue
        rows = [r for r in csv.reader(open(p)) if len(r) >= 6]
        ok = sum(1 for r in rows if r[5] == 'ok'); pids = {r[6] for r in rows if len(r) > 6}
        print(f'== {os.path.basename(run)}: {ok}/{len(rows)} cycles returned to Home; launch {stats([int(r[4]) for r in rows if r[4].isdigit()])} ms; home pids seen: {len(pids)}')

if __name__ == '__main__':
    if len(sys.argv) < 3: sys.exit(__doc__)
    mode, runs = sys.argv[1], sys.argv[2:]
    if mode == 'idle':
        rs = [analyze_idle(r) for r in runs]
        for r in rs: print_idle(r)
        if len(rs) > 1: print_idle_compare(rs)
    elif mode == 'launch': analyze_launch(runs)
    elif mode == 'switch': analyze_switch(runs)
    elif mode == 'cycles': analyze_cycles(runs)
    else: sys.exit(__doc__)
