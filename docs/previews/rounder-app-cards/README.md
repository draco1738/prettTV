# Rounder app cards, tighter spacing

[← prettTV](../../../README.md) · [Earlier size and motion record](../premium-focus/README.md) · [Native design](../../../DESIGN.md) · [Validation history](../../validation.md)

The owner approved this follow-up refinement for **v0.2.1: 22-pixel app corners and 12-pixel gaps**. The implementation retains the 208-pixel app width and finite focus treatment. This page records that approval through a rendered comparison; [validation history](../../validation.md) records the scoped native checks and outcomes.

![Current and proposed app rows, with enlarged selected-card details](comparison.png)

[Open the full-size comparison](comparison.png)

| Your apps | Intermediate candidate | Approved final geometry |
| --- | ---: | ---: |
| Card width | 208 | 208 |
| C Balanced height | 94 | 94 |
| Corner radius | 15 | 22 |
| Gap between cards | 18 | 12 |

Dimensions are 1920-wide design-reference pixels, scaled to the TV's display width. The refinement applies only to **Your apps** in A/B/C: A retains its 116-pixel height, B its 176-pixel height and C its 94-pixel height. Library keeps its six-column geometry, 154-pixel height, 18-pixel gaps and 15-pixel corners. Other content cards and sports-app shortcuts retain their existing shapes.

Logo sizing, dark surfaces and the row's beginning/end are preserved. The clipped ivory spotlight still reaches approximately 18% peak opacity, with up to six reference pixels of internal artwork drift over 180 ms and the existing 140 ms focus scale. Each transition settles; there is no repeating idle animation. Disabled animations apply final focus states immediately.

Both rows use the same scale and the same selected app. The image's “Current” and “Proposed” labels refer to the intermediate and approved final geometry. The lower details are enlarged 2× to show the softer corners and one-third reduction in spacing. The adjacent card is intentionally cropped in those close-ups. These are browser-rendered concepts, not native captures or performance measurements.

App marks reuse existing prettTV assets from [Homarr Dashboard Icons](https://github.com/homarr-labs/dashboard-icons), with the [source manifest](../../../launcher/app/src/main/assets/app-icons/sources.json) and [Apache-2.0 license](../../../launcher/app/src/main/assets/app-icons/LICENSE.txt). Disney+ uses prettTV's existing adapted vector. Brand names and marks belong to their owners. No viewing history, account data or private playback links are included.
