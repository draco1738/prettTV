# Results

Everything here was measured on one real device over adb, without root. Items that were not
measured are marked as such. Method: [METHODOLOGY.md](METHODOLOGY.md). Measured 2026-10-11 with
versions 0.2–0.12 as noted.

## Tested devices

Performance results from other devices are very welcome. Run `bench/benchmark.sh idle <label>
fi.jommi.rivi` (see [METHODOLOGY.md](METHODOLOGY.md)) and open a pull request adding a row here.
Add the summary numbers only: the raw files under `results/` can contain your network name, MAC
addresses and adb keys, which is why that folder is git-ignored.

| Device | Chip / RAM | OS | Rivi version | Idle CPU, whole device | Rivi memory | Home button | Reported by |
|---|---|---|---|---|---|---|---|
| JMGO N1S projector | 4× Cortex-A53 / 2 GB | Google TV, Android 14 | 0.12 | 11–18% (stock home: 41%) | 33 MB | works, direct | [@jommi9](https://github.com/jommi9) |

## Device

The rest of this page is from the first row.


JMGO N1S projector (model K80A, codename `holan`). MediaTek `m7332` platform, 4× Cortex-A53
(1.15–1.55 GHz), Mali-G52, 32-bit userspace, 1.8 GB RAM plus 800 MB zram swap, Google TV on
Android 14, 1920×1080 at 60 Hz. Stock home: `com.google.android.apps.tv.launcherx` 1.0.976298245.

## Why the stock home screen feels slow

It redraws 60 times a second even when nothing moves. That costs 55% of one core in the launcher,
33% of a core in SurfaceFlinger and 8% in the display HAL: 41% of the whole four-core CPU while
idle, against a floor of about 11% with a static screen. It also holds 196 MB on a device with
roughly 690 MB available. Every key press and every app start competes with that.

## Idle

Focus parked on an app tile, 5 × 60 s windows, remote untouched.

| | Google TV | Projectivy 4.72 (defaults) | just-a-launcher 0.9 | Rivi |
|---|---|---|---|---|
| Home memory, PSS incl. sub-processes (MB) | 196 | 127 | 32 | 33–34 |
| Home process CPU (% of one core) | 55 | 48 | 0.0 | 0.0 |
| SurfaceFlinger CPU (% of one core) | 33 | 34 | 0.0 | 0.0 |
| Whole-device CPU (% of all 4 cores) | 41 | 42 | 10 | 11–18 |
| Redraws per second | 60 | ~60 | 0 | 0 (clock: 1 per minute) |

Notes:
* Rivi: 11% in the first run (v0.2, started as an ordinary app), 18% in the final
  configuration (v0.11, Home-button service on). The difference in the second run is Bluetooth
  (`com.android.bluetooth` 7.9% plus its HAL 2.5%), active because the remote had just been used.
  Rivi itself was at 0.0% in both.
* The ~10% floor is the projector's own services (hardware manager, sensors, auto-keystone). No
  launcher removes it.
* Projectivy was measured as installed from Google Play with nothing changed; its animated
  wallpaper keeps it redrawing. Figures are from the first two of three windows, because the
  remote was used during the third. It is reported to be much lighter with wallpaper and focus
  animations switched off; that was not measured.
* Stock run: the remote was pressed once during the first window; windows 2–5 agree within 0.4
  points.
* The other launchers were run as ordinary apps with the stock launcher still cached in memory.

## App launch and switching

`am start -W` TotalTime, the time to the app's first frame (not to usable content). Medians of 5.

| app | cold, Google TV home | cold, Rivi home | warm, Google TV | warm, Rivi |
|---|---|---|---|---|
| Netflix | 984 ms | 796 ms | 164 | 108 |
| YouTube | 1262 | 965 | 229 | 160 |
| Prime Video | 1175 | 809 | 176 | 3099 (see note) |
| Apple TV | 928 | 715 | 628 | 332 |

Cold starts are 19–31% faster from Rivi, consistent with the home screen no longer competing
for CPU. Prime Video's warm start is bimodal on both home screens (about 0.15 s, or 3–6 s when the
app re-initialises itself): 2 of 5 slow trials under Google TV, 3 of 5 under Rivi. Too few
trials to call a difference.

Switching between two running apps (Netflix ↔ YouTube) is unchanged: 207 / 112 ms with Rivi,
231 / 117 ms with Google TV. Both stayed in memory through every round trip in both setups.

## Navigation

Paced test: 40 D-pad presses, one every 350 ms.

| | frames drawn | janky | p50 / p90 / p99 frame time (ms) |
|---|---|---|---|
| Google TV | 1306 | 5.0% | 24 / 38 / 65 |
| just-a-launcher 0.9 | 40 | 2.5% | 12 / 13 / 17 |
| Rivi 0.9 (two runs, identical) | 288 | 12% | 16 / 16 / 18 |

Rivi draws about seven frames per key press because the focus ring and scale animate for
110 ms; just-a-launcher draws one frame and has no animation. The first frame after a key press
completes a median 9 ms after its vsync on Rivi (p90 10 ms, n=15) and 12 ms on just-a-launcher
(n=40). The 12% "janky" frames are roughly one per key press inside the animation.

Remote-to-screen latency was not measured (no high-speed camera). Frame times are a proxy.

### What changed the numbers

Sustained test, 40 presses back-to-back:

| version | change | janky | p50 / p90 / p99 (ms) |
|---|---|---|---|
| 0.3 | rounded tiles drawn with a `BitmapShader` | 9.2% | 14 / 30 / 34 |
| 0.4 | corners baked into the cached bitmaps, plain `drawBitmap` | 0.6% | 13 / 13 / 19 |
| 0.5 | solid window background instead of a gradient | 0.3–3.1% | 10 / 13 / 16 |

The background change is less certain than the first: over five runs the GPU stage took
5.5–5.9 ms in three and 9.2–10.3 ms in two, against a steady 8.1–8.3 ms for the gradient.

## Tests and reliability

* JVM unit tests: 14 of 14.
* On-device UI tests (`tests/ui_test.sh`): 54 of 54 with the Home-button service on, 51 of 51
  with it off. They cover cold start, app discovery against the package manager, D-pad navigation
  and edges, a newly installed app appearing, launching, focus restoration, favourites add /
  reorder / hide / persistence, an uninstalled favourite, an app reinstalled in the background,
  leave / return, recovery after the foreground app or the launcher is killed, and the Home
  button. Three cold-start checks are skipped while the service is on, because forcing them
  would switch it off.
* Netflix, Prime Video, YouTube and Apple TV each opened from its tile, stayed in the foreground,
  and focus was on the same tile on return. Playback and DRM were not exercised.
* 20 of 20 "open a streaming app, press Home" cycles returned to Rivi with one launcher
  process throughout and no crash or ANR.
* Uninstalling leaves the stock home screen, all packages and all streaming apps as they were.

## Not measured or not tested

* Behaviour after a device reboot.
* Any device other than the JMGO N1S; any remote other than its own.
* Playback, DRM, casting, Bluetooth audio, keystone and autofocus with Rivi as home. Nothing
  they depend on is changed.
* Latency added by routing key presses through the accessibility service.
* Temperature (not readable without root on this device).
