# Narrower app cards — current versus proposed

Rendered design previews for prettTV's **C Balanced** layout. These are proposals, not screenshots of an installed update. The Android UI has not changed.

The revised comparison makes the change easier to judge: the current and proposed cards are shown at the same scale, with enlarged views of the selected card.

![Current and proposed app rows and enlarged selected cards](comparison.png)

[Open the full-size comparison](comparison.png)

The proposal uses **236 × 94** app cards instead of **286 × 94**: about **17.5% narrower**, with the existing height and corner shape. Dark logos retain their color accents. The app row keeps its beginning and end.

| Treatment | Appearance and motion |
| --- | --- |
| **Current** | Flat selected surface, with the existing 1.025 focus scale. |
| **Proposed** | A localized spotlight at up to 18% opacity, plus 6 pixels of internal logo movement over 180 ms. The existing focus scale remains. |

## See the movement

![Synchronized current versus proposed focus movement](focus-motion.gif)

[Open the animated comparison](focus-motion.gif) · [Download the MP4](focus-motion.mp4)

The clip repeats the demonstration. Each proposed focus transition settles completely; the launcher would have no idle animation. The selected-card close-ups are enlarged 2× so the internal motion is visible. The spotlight is intentionally stronger than the first set of nearly identical stills.

These are browser-rendered concepts, not Android performance measurements. The baseline uses the current documented dimensions and focus scale. Native implementation and hardware checks follow design selection.

## Credits

App marks reuse prettTV's existing assets from [Homarr Dashboard Icons](https://github.com/homarr-labs/dashboard-icons) under [Apache-2.0](../../../launcher/app/src/main/assets/app-icons/LICENSE.txt). Exact source files, revision, and modifications are listed in [the icon source manifest](../../../launcher/app/src/main/assets/app-icons/sources.json). Disney+ uses the existing adapted foreground paths with a cyan arc and no square background. Brand names and trademarks belong to their respective owners.

Only this app-row comparison is published here; the local playback export, private playback links, and full personalized screen are not included.
