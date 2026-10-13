# Motion study

A browser simulation of the Rivi home screen, for trying motion, themes and sounds before
building them into the app. Open `index.html` in a browser. Nothing to install or build.

* **Motion and themes.** Two screens side by side that follow the same key presses: Rivi 0.12
  on the left, and on the right the same screen with a theme and any mix of 15 refinements.
  Slow motion, a cold-start replay and three ready-made combinations are one click away.
* **Sounds.** Five navigation sounds (move, edge, open, back, menu) with sliders for pitch,
  length, volume, tone and bend. Each can be saved as a WAV file, and the whole set is one
  line of JSON you can copy and paste back.

Keys: arrows move, Enter opens, hold Enter for the tile menu, Esc goes back.

## What is in the app

Four refinements are built into Rivi since 0.13 and are on by default. They are marked
"in Rivi 0.13" on the page.

| name | what it does | where |
|---|---|---|
| `settle` | the focused tile overshoots a little and settles (190 ms in, 130 ms out) | `TileView.kt` |
| `fade` | the focus ring and the label fade instead of appearing | `TileView.kt` |
| `shadow` | a soft shadow under the focused tile, behind every tile | `ShadowView.kt` |
| `hold` | holding OK fills a ring, then the menu slides in from the right | `TileView.kt`, `SideMenu.kt` |

The card "Use it in the app" on the page prints a command for the mix you have ticked. It
switches a running Rivi over without rebuilding, and Rivi remembers the choice:

```bash
adb shell am start -n fi.jommi.rivi/.MainActivity --es motion settle,fade
```

`--es motion none` gives the 0.12 behaviour (including the stock dialog for menus) and
`--es motion default` goes back to all four. The defaults are in `Motion.kt`.

Everything else on the page is a proposal: themes, ambient glow, spotlight, tilt, press and
launch, sounds and the rest. Each was checked to be possible with the Android APIs Rivi already
uses, with no libraries. None of them has been measured on a device.

## Rules for moving something from here into the app

1. Nothing may move at rest. The home screen draws zero frames when idle; an effect that
   breathes, drifts or loops ends that.
2. Use the same numbers in both places. The durations and curves in the Kotlin files are the
   ones on this page.
3. A browser on a laptop proves nothing about speed. Measure on the device before and after
   with `bench/benchmark.sh nav` and `navfast`, and put the numbers in `docs/RESULTS.md`.

## Your own artwork

The page draws neutral placeholder tiles. To see your own apps, put images in `tiles/favs`
and `tiles/grid` and run `python3 make-tiles.py` (details at the top of that file). Both are
git-ignored, because app banners belong to their owners.

## Recording a clip

Four scripted clips are built in (tour, themes, labelled comparisons, the three combinations).
The buttons in the toolbar play them live. To film one frame by frame at exactly 60 frames per
second (needs Node 22, Google Chrome and ffmpeg):

```bash
node record.mjs --cut 14 --out frames
```

```bash
ffmpeg -framerate 60 -i frames/f%05d.jpg -i frames/audio.wav -c:v libx264 -crf 18 -pix_fmt yuv420p -c:a aac -movflags +faststart clip.mp4
```

Cuts: 11 tour, 12 themes, 13 comparisons, 14 combinations. The page runs on a virtual clock
while recording, so the result does not depend on how fast the machine is.
