> Upstream Rivi reference, retained for provenance. This describes Rivi v0.15, not the modified Luma implementation. See the root README, THIRD_PARTY_NOTICES.md and docs/validation.md for Luma.

# Benchmark methodology

All measurements are taken over network adb on a real device, no root. The published numbers are
from a JMGO N1S (K80A / "holan").
Scripts: `bench/benchmark.sh` (collection) and `bench/analyze.py` (analysis). Raw data for every
run is written to `results/runs/<label>/` on your machine (git-ignored) so numbers can be
recomputed. Select the device with `export TV=<ip>:5555`.

## Idle (`benchmark.sh idle LABEL PKG [KEYS]`)
1. `am force-stop PKG`, then start the launcher: HOME key if it is the current Home app, otherwise
   `am start` of its leanback activity (recorded in `start_mode`).
2. `am kill-all` to clear background apps, press the navigation KEYS to park focus on an app tile,
   screenshot (`idle_prep.png`) to verify focus.
3. Settle 45 s. Read `dumpsys gfxinfo` and `dumpsys power` (user-activity timestamp).
4. A device-side shell script (`snap.sh`) snapshots `/proc/uptime`, `/proc/stat` and every
   `/proc/<pid>/stat` once per 60 s window, 5 windows. The host sends **no adb commands** during this.
5. Read gfxinfo/power again, then the heavy dumps (`dumpsys meminfo --package PKG`, `dumpsys meminfo`).

Metrics:
* **Process CPU** = Δ(utime+stime)/(Δt·100) summed over `PKG` and `PKG:*` → **share of one core**.
* **Whole-device CPU** = 1 − Δ(idle+iowait)/Δ(all) from `/proc/stat` → share of **all four cores**.
* **Redraws/s** = Δ"Total frames rendered" / Δ gfxinfo Uptime.
* **Memory** = Σ TOTAL PSS of the package's processes from `dumpsys meminfo --package` (PSS, includes
  swap-backed pages as reported by meminfo). PSS is never compared with RSS from `ps`.
* Runs where the user-activity timestamp changed or the device dozed are flagged by `analyze.py`.
`dumpsys cpuinfo` is never used (it is a 5-minute stale average).

## Launch (`benchmark.sh launch LABEL`)
For each installed streaming app, 5 trials of: force-stop → `am start -W` (cold) → 8 s → HOME →
3 s → check process still alive → `am start -W` (warm) → `am start -W` of the HOME intent (warm
return-to-Home). `am start -W` reports **TotalTime** = ms until the activity's first frame is
reported displayed by the system. This is the only latency the device exposes; it is coarse
(not input-to-photon) and is reported as such. Median and p90 over trials.

## Switch (`benchmark.sh switch LABEL`)
Netflix ↔ YouTube round trips, 5×, `am start -W` each way, plus whether the other app's process
survived (residency) and MemAvailable after each hop.

## Navigation (`benchmark.sh nav LABEL PKG`)
`dumpsys gfxinfo PKG reset`, 40 D-pad presses at 350 ms intervals, then `dumpsys gfxinfo PKG`
for janky-frame % and 50/90/95/99th-percentile frame times. Measures render-thread cost per
focus move, not remote-to-photon latency (no high-speed camera available).

## Reliability (`benchmark.sh cycles LABEL PKG N`)
N × (launch app → 5 s → HOME → verify top resumed activity is PKG → two D-pad presses). Counts
failures and checks the launcher's pid is stable (no crash/restart) and dropbox for crashes.

## Home key (`benchmark.sh homekey LABEL PKG`)
From the system starting to handle Home (first `START` of the HOME intent, or of the launcher by
its service) to the home activity's `wm_on_resume_called` in the events log. Keys injected by adb
bypass accessibility key filters, so with the Home-button service on this measures the redirect
path, not the direct path a physical remote takes.

## Lived-in state (`benchmark.sh pressure LABEL`)
The modes above start from a tidy device: the launcher is restarted, background apps are cleared,
and an app's start is timed to its first frame. That is the best case. A TV that has been on for
days is not in that state, and "first frame" is not when an app is usable. This mode measures
what a person meets and stops nothing except the app being timed:

1. State: uptime, available memory, how full the compressed swap is, how many low-memory kills
   Android has recorded, how many cached app processes and Settings screens are alive, and which
   processes hold the most swap.
2. Per app: force-stop, start, then (`bench/settle.sh`, on the device) **first frame**
   (`am start -W`), **time to settle** (until whole-device CPU has stayed under 30% for one second;
   an app that autoplays video never settles and reports the 25 s limit), **MB read back from
   swap** and **major page faults** in that window.

Run it as the device is, restart the device, run it again. The difference is what uptime costs.
To find out why one screen is slow, the same counters can be read per process around a burst of
key presses (major faults and CPU time from `/proc/<pid>/stat`); that is how the stacked Settings
screens in RESULTS.md were found.

## A/B (`bench/ab.sh N a=one.apk b=two.apk`)
Installs each APK in turn, interleaved, runs the sustained navigation test N times per APK and
prints medians. Used to keep or revert individual rendering changes.

## Things that bit us
* `am force-stop` (used to get a cold start) makes Android switch off the package's accessibility
  service. The scripts refuse to force-stop a package whose service is enabled.
* The screensaver starts at the screen timeout and is itself heavy; idle runs check the remaining
  timeout budget first and flag runs where a dream was active.
* `dumpsys` can time out when the device is short of memory; the UI tests retry.
* BSD `seq 1 0` counts down. Loops that may run zero times use a counter instead.
* Wireless debugging switches itself off whenever Wi-Fi drops. Keep "USB debugging" on as well,
  or adb disappears until someone re-enables it on the device.

* Opening Settings with `am start -a android.settings.SETTINGS` and leaving with Home stacks
  another Settings screen on the last one. The tests did this four times per run; eight
  screens were alive at one point (160 MB). They now pass `-f 0x10008000` (new task, clear task).
* The device shell (mksh) has 32-bit arithmetic: nanosecond timestamps overflow. `settle.sh`
  counts in hundredths of a second from `/proc/uptime`.
* `input keyevent --longpress` sends the key down and the long-press repeat at the same instant,
  so it cannot show the hold ring filling, and the shell may not write to `/dev/input`. A monkey
  script (`DispatchKey` down, `UserWait`, `DispatchKey` up) holds a key for a chosen time.

## Caveats
* `idle`, `launch` and `nav` describe a freshly tidied device. Use `pressure` for the other case.
* Thermal: the device reports no readable thermal zones to the shell user; CPU frequency is
  logged instead (`adb/profile.sh`).
* Launch times depend on network (Netflix/YouTube fetch content on start); trials are interleaved
  per app and medians are used.
* Remote-to-photon latency and focus latency are not measured; frame times are a proxy.
