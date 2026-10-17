> Upstream Rivi reference, retained for provenance. This describes Rivi v0.15, not the modified prettTV implementation. See the root README, THIRD_PARTY_NOTICES.md and docs/validation.md for prettTV.

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

### After three days without a restart

Everything above was measured on a tidy device: background apps cleared, launcher restarted.
The same projector after 74 hours of ordinary use (and of this project's test runs) felt slow
again, and it was. `benchmark.sh pressure`, nothing cleared first:

* Compressed swap 78–83% full (630–670 MB), 340–540 MB available, 389 low-memory kills on
  record. Nearly every process had 30–100 MB swapped out, including the system UI, Bluetooth and
  the remote-control service, so any action first had to read memory back.
* Cold first frames were slower than three days earlier and uneven, the first try of each app
  being the worst: Netflix 1115 ms (was 796), YouTube 1246 (965), Prime Video 1096 (809),
  Apple TV 764 (715). Medians of 3, range 635–1889 ms.

| app (one trial each) | first frame | settled after | read back from swap | major faults |
|---|---|---|---|---|
| Netflix | 1.8 s | over 25 s (keeps playing previews) | 8 MB | 3 900 |
| YouTube | 2.2 s | 13.7 s | 50 MB | 16 600 |
| Disney+ | 1.4 s | 15.0 s | 27 MB | 9 900 |
| Viaplay | 3.7 s | 15.2 s | 42 MB | 14 000 |
| MUBI | 2.3 s | over 25 s | 34 MB | 11 000 |
| Kanopy | 5.5 s | 12.5 s | 12 MB | 5 100 |

"First frame" is what the launch table measures; "settled" is when the device stopped being
busy, which is closer to when the app can be used.

The same six apps one minute after a restart (swap 24% full, 675 MB available):

| app (one trial each) | first frame | settled after | read back from swap |
|---|---|---|---|
| Netflix | 0.9 s (was 1.8) | over 25 s (same) | 6 MB (8) |
| YouTube | 1.3 s (2.2) | 8.5 s (13.7) | 5 MB (50) |
| Disney+ | 1.0 s (1.4) | 14.6 s (15.0) | 11 MB (27) |
| Viaplay | 2.5 s (3.7) | 8.4 s (15.2) | 3 MB (42) |
| MUBI | 1.9 s (2.3) | over 25 s (same) | 41 MB (34) |
| Kanopy | 4.6 s (5.5) | 14.0 s (12.5) | 10 MB (12) |

A restart took 0.4 to 1.2 s off every first frame and about 40% off the settle time of YouTube
and Viaplay. Disney+, MUBI and Kanopy were as slow to become usable either way: that part is the
apps and the hardware, not the state of the device. One trial each, so treat small differences
as noise. After the restart Rivi came back as the home screen with its Home-button service on.

Rivi itself was not slower in this state: returning Home took 165–330 ms (as before), and the
navigation frame times above were taken in it. A launcher cannot free other apps' memory on
Android 14, and the stock home screen stays cached at about 190 MB even while Rivi is in front.

One cause was found and fixed. Android's Settings showed 100 MB read back from swap and frame
times of 26 / 69 / 200 ms (p50 / p90 / p99) for twelve key presses, at 160 MB resident. It was
Settings screens stacked on top of each other (five when first looked at, eight after more
test runs): opening Settings with a plain intent and
leaving with Home leaves the old screen alive and puts a new one on it. This project's own test
suite did that four times per run, and Rivi's "Android settings" menu entry did it once per use.
Both now open Settings in a cleared task, and the count stays at one.

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

### Motion in 0.13

0.13 adds four refinements (settle with overshoot, ring and label fade, lift shadow, hold ring
and side menu). Same APK with them off (`--es motion none`) and on, two runs each, interleaved:

| | paced: frames / p50 / p90 / p99 (ms) | back-to-back: p50 / p90 / p99 (ms) | missed vsync |
|---|---|---|---|
| off | 320 / 16 / 16 / 17 (both runs) | 10 / 11 / 15–16 | 0 |
| on | 370–530 / 14–15 / 16–17 / 17–28 | 11–12 / 13 / 16 | 0 |

The longer settle and the shadow fade mean more frames per key press (9 to 13, was 8), each
1–2 ms dearer when keys arrive back-to-back. No frame missed its vsync in any run. One of the two
paced runs had a 28 ms 99th percentile; the other matched "off". At rest with the refinements on:
2 frames and 20 ms of CPU in 30 s.

### Cinema theme in 0.14

Cinema draws every unfocused tile through a brightness filter. Against Midnight, same APK, two
runs each, interleaved: paced 15 / 17 / 17 ms against 14–15 / 16 / 17; back-to-back 13 / 13–14 / 17
against 12 / 13 / 16; GPU stage 4 / 5 ms against 4 / 4 (p50 / p90). No missed vsync. At rest:
1 frame and 10 ms of CPU in 30 s. On-device tests in Cinema: 54 of 54.

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

* JVM unit tests: 16 of 16.
* On-device UI tests (`tests/ui_test.sh`): 54 of 54 with the Home-button service on, 51 of 51
  with it off (0.12 and 0.13). They cover cold start, app discovery against the package manager, D-pad navigation
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

* Sound by ear. On the test device the audio output stayed in standby through six key presses on
  Rivi, with and without `sound` (Android's Settings did wake it), so the opt-in click could not
  be confirmed there.
* The hold ring with a real remote. It was checked with a scripted key hold and a screen recording.
* Behaviour after a device reboot beyond one restart (0.13: Rivi was on screen and the service on).
* Any device other than the JMGO N1S; any remote other than its own.
* Playback, DRM, casting, Bluetooth audio, keystone and autofocus with Rivi as home. Nothing
  they depend on is changed.
* Latency added by routing key presses through the accessibility service.
* Temperature (not readable without root on this device).
