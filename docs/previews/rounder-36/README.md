# 36-pixel app-card corners

[← prettTV](../../../README.md) · [Previous 30-pixel preview](../rounder-30/README.md) · [Installed geometry](../rounder-app-cards/README.md)

A rendered comparison of **30-pixel and 36-pixel corners**. This is a visual proposal; the TV still uses **22-pixel corners**.

![30-pixel preview compared with 36-pixel corners](comparison.png)

[Open the full-size comparison](comparison.png)

Both rows use **208 × 94** C Balanced app cards with **12-pixel gaps**. Logo sizes, dark surfaces, selected-card spotlight and focus scale are identical. Only the corner radius changes. The lower details are enlarged 2×, with the neighboring card intentionally cropped to emphasize the shape.

The proposal applies to Your apps in A/B/C, retaining their existing heights. Library and content-card geometry stay unchanged. Values are 1920-wide design-reference pixels, scaled to display width. This browser-rendered concept is not a native TV capture or a performance test. No application code or installed build changes are included.

App marks reuse prettTV's existing [Homarr Dashboard Icons](https://github.com/homarr-labs/dashboard-icons) assets under [Apache-2.0](../../../launcher/app/src/main/assets/app-icons/LICENSE.txt); exact provenance is in the [source manifest](../../../launcher/app/src/main/assets/app-icons/sources.json). Disney+ uses the existing adapted vector. Brand names and marks belong to their owners. No account data, viewing history or private playback links are included.