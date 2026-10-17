> Upstream Rivi reference, retained for provenance. This describes Rivi v0.15, not the modified Luma implementation. See the root README, THIRD_PARTY_NOTICES.md and docs/validation.md for Luma.

# Third-party code and licences

* **just-a-launcher** (hungmi, MIT) — https://github.com/hungmi/just-a-launcher
  The idle-measurement methodology and the device-side `/proc` sampler (`bench/snap.sh`) and the
  CPU/redraw/PSS maths in `bench/analyze.py` are adapted from its `bench/` directory.
  Copyright (c) 2026 hungmi. MIT License.
* **lightweight_androidtv_launcher** (wiiguy, GPL-3.0), **LtvLauncher** (leanbitlab-org, GPL-3.0),
  **FLauncher** (etienn01, GPL-3.0) — studied for architecture only; no code copied.
* Streaming-service artwork shown in the launcher is loaded at runtime from the apps installed on
  the device (their own leanback banners); nothing is bundled.
* Rivi itself: MIT, see [LICENSE](../LICENSE).

## just-a-launcher licence text

```
MIT License

Copyright (c) 2026 hungmi

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
