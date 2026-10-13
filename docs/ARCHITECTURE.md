# Architecture

## Launcher (`launcher/`)
One Activity and one optional service, eight Kotlin files, no AndroidX, no third-party libraries.
Release APK ≈ 66 KB. No internet permission.

* `MainActivity.kt` builds the view tree in code (no XML inflation): a `ScrollView` → vertical
  `LinearLayout` with a header (wordmark + clock), the **Favourites** strip (`HorizontalScrollView`),
  and the **All apps** grid (rows of `LinearLayout`, 6 tiles per row). Sizes are designed on a
  1920×1080 grid and scaled by `displayWidth / 1920`, so the layout is identical on any TV density.
* `TileView.kt` is a single custom `View` per app: rounded artwork via `BitmapShader`, a focus ring
  drawn *outside* the artwork (always visible), and the label drawn only under the focused tile.
  Focus = a scale animation on the hardware layer (190 ms in with a slight overshoot, 130 ms out)
  while the ring and label fade for 140 ms, which redraws only the two tiles involved. Holding OK
  fills a ring on the tile until the menu opens. Nothing redraws at rest; the only idle redraw is
  the clock, once per minute (`ACTION_TIME_TICK`).
* `ShadowView.kt` is the soft shadow under the focused tile. It is one view behind the whole page,
  so it never shades a neighbouring tile. The blur is drawn once per tile size into a small bitmap;
  after that the view only changes translation and alpha, which are render properties (no redraw).
* `SideMenu.kt` is the menu for a tile (hold OK) and for Rivi (MENU): a sheet that slides in from
  the right. It does not take focus, so the tile underneath stays lit; the activity hands it the
  remote's keys while it is open. It is `GONE` when closed.
* `Motion.kt` says which of these four refinements are on (`settle`, `fade`, `shadow`, `hold`;
  all by default). `adb shell am start -n fi.jommi.rivi/.MainActivity --es motion none` switches
  them off, which gives the 0.12 behaviour. They were designed in `tools/motion-study`, a browser
  simulation of this screen, and the timings there and here are the same numbers.
* `Apps.kt`: `AppRepository` queries `LEANBACK_LAUNCHER` activities, renders each banner/icon to a
  bitmap of the exact tile size once, and caches it as PNG under `cacheDir/tiles/` keyed by
  component + versionCode + size. Later cold starts never open other packages' resources.
  `Prefs` is a few `SharedPreferences` keys: favourites order, hidden apps, last focus, and
  launch timestamps written only when *we* launch an app (no background tracking).

* `Model.kt`: `HomeModel`, the pure ordering logic (favourites, grid order, focus fallback, recents),
  unit-tested on the JVM. `MainActivity.dump()` prints the current arrangement and focus under
  `dumpsys activity fi.jommi.rivi`, which is how `tests/ui_test.sh` reads state.

Rounded corners are baked into the cached bitmaps, so a tile is one textured quad; the window
background is a solid colour. Both choices come from measurements (see RESULTS.md).

Threads: one `HandlerThread` for discovery and decoding; results are posted to the UI thread per
tile so the screen fills progressively on first run and in a single burst from the disk cache.

Manifest: `HOME` + `LEANBACK_LAUNCHER` intent filters, `singleTask`, `stateNotNeeded`,
`excludeFromRecents`, `clearTaskOnLaunch`, and `configChanges` covering everything so the
activity is never recreated. `queries` declares the package visibility it needs.

Input: D-pad focus is Android's default focus search, constrained with `nextFocusLeft/Right` at
row edges so Left/Right never jump rows. OK launches, long-press opens a tile menu (favourite,
reorder, hide, app info), MENU opens the launcher menu, BACK returns to the first favourite.

## Why native Views
On this SoC (4× A53, 2 GB, 32-bit) a Flutter/Compose engine costs tens of MB and warm-up time.
The stock launcher's cost is dominated by continuous redraw; the design goal is zero redraws
when nothing changes, which plain Views deliver trivially.

## Home handling on Google TV
Google TV resolves HOME by intent-filter priority (its launcher 2, its setup app 1) and caps
third-party apps at 0, so declaring a HOME activity is not enough. `HomeKeyService.kt` is an
accessibility service, off until the owner enables it, that consumes the Home key and starts the
launcher, and also covers the stock home screen when it appears by itself. It disables nothing.
See [HOME_BUTTON.md](HOME_BUTTON.md). Without the service, Rivi runs as an ordinary app.

## Scripts
`adb/common.sh` (device selection), `adb/profile.sh` (device profile), `bench/benchmark.sh` +
`bench/analyze.py` + `bench/ab.sh` (see METHODOLOGY.md), `tests/ui_test.sh`,
`tests/launch_streaming.sh`, `install.sh`, `restore.sh`. Scripts write to `results/`, which is
git-ignored because raw device dumps can contain network names, MAC addresses and adb keys.
