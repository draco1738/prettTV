# Narrower app cards — intermediate design record

[← prettTV](../../../README.md) · [Historical v0.2.1 geometry](../rounder-app-cards/README.md) · [Latest approved geometry](../rounder-36/README.md) · [Native design](../../../DESIGN.md) · [Validation history](../../validation.md)

These browser-rendered **C Balanced** comparisons record the intermediate **208-width / 15-radius / 18-gap** design for v0.2.1. That release used the later approved [22-pixel corners and 12-pixel gaps](../rounder-app-cards/README.md), retaining the widths, heights and focus treatment shown here. The subsequent [v0.2.2 refinement](../rounder-36/README.md) changes only the app-row radius to 36 pixels. These assets remain design previews, not screenshots of the installed Android app. Their “Current” and “Proposed” labels describe the earlier v0.2.0 baseline and the intermediate direction.

The comparison shows both rows at the same scale, with enlarged views of the selected card.

![Current and proposed app rows and enlarged selected cards](comparison.png)

[Open the full-size comparison](comparison.png)

This C comparison uses **208 × 94** app cards instead of **286 × 94**: about **27.3% narrower**, with the then-existing 15-pixel corners and 18-pixel gaps. This followed a 236-pixel proposal. Dark logos retain their color accents, and the finite app row keeps its beginning and end. The final approved width and height remain the same; only the later geometry comparison shows the accepted rounder corners and tighter spacing.

| Treatment | Appearance and motion |
| --- | --- |
| **Earlier baseline** | Flat selected surface, with the existing 1.025 focus scale. |
| **Retained focus direction** | A localized ivory spotlight at up to about 18% opacity, plus 6 reference pixels of internal artwork movement over 180 ms. The existing 140 ms focus scale remains. |

## Retained dimensions and focus treatment

Your apps retains the same **208-reference-pixel width** in all three layouts. This intermediate preview shows an 18-pixel gap and 15-pixel radius; the final values for **v0.2.1** were **12-pixel gaps and 22-pixel corners**. The later v0.2.2 design keeps these dimensions and gaps with a 36-pixel radius:

| Layout | App-card dimensions |
| --- | --- |
| **A · Cinema** | 208 × 116 |
| **B · Quiet** | 208 × 176 |
| **C · Balanced** | 208 × 94 |

These are 1920-wide design-reference pixels, scaled to the display width, not Android dp. App-row heights are unchanged. The radius and gap refinement applies only to Your apps; the Library grid retains its six-column geometry, 154-reference-pixel height, 18-pixel gaps and 15-pixel corners.

The native card component applies the clipped spotlight and bounded artwork drift to app and content cards, including Library tiles. The spotlight's peak alpha is `0x2E` (about 18%), fading across an ivory elliptical gradient. Artwork moves up to 6 reference pixels rightward as focus settles over 180 ms; text and progress do not independently drift. The existing 140 ms scale reaches 1.025 for app, show and sports cards; hero and compact cards keep their original scale.

Focus reversal retargets the finite transition from its current position. Hidden or detached cards cancel and settle their focus animations; disabled system animations apply the final state immediately. Landscape artwork reserves enough overscan for the movement, while contained portraits limit movement to their available side space. See the [native design specification](../../../DESIGN.md#elevation--depth) for the source-derived details.

## See the movement

![Synchronized current versus proposed focus movement](focus-motion.gif)

[Open the animated comparison](focus-motion.gif) · [Download the MP4](focus-motion.mp4)

The clip repeats the demonstration; the native transition runs only when focus changes and then settles. The selected-card close-ups are enlarged 2× so the internal motion is visible. The spotlight is intentionally stronger than the first set of nearly identical stills.

These rendered concepts preserve the accepted focus treatment and intermediate geometry. They are not native captures or performance measurements; [validation history](../../validation.md) records the scoped native checks and outcomes.

## Credits

App marks reuse prettTV's existing assets from [Homarr Dashboard Icons](https://github.com/homarr-labs/dashboard-icons) under [Apache-2.0](../../../launcher/app/src/main/assets/app-icons/LICENSE.txt). Exact source files, revision, and modifications are listed in [the icon source manifest](../../../launcher/app/src/main/assets/app-icons/sources.json). Disney+ uses the existing adapted foreground paths with a cyan arc and no square background. The Paramount+ mark is displayed in white in these mockups. Brand names and trademarks belong to their respective owners.

Only this app-row comparison is published here; the local playback export, private playback links, and full personalized screen are not included.
