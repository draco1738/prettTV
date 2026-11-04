# Lightweight UI research for prettTV

[← Documentation](README.md) · [Current design](../DESIGN.md) · [Recorded validation](validation.md)

Research date: **November 4, 2026**. Implementation reviewed: **prettTV 0.5.1**, commit [`8da88c2`](https://github.com/draco1738/prettTV/tree/8da88c2478f44df76f9bfdfc852c979c99a44063). This document proposes future work; it does not describe a newly installed build.

Implementation follow-up: [A lighter Home in 0.5.2](lightweight-home.md), with actual results in the [validation history](validation.md). The research observations below remain tied to the reviewed 0.5.1 source.

**Recommendation: retain the native Views/Canvas foundation, make long rows cheaper, and treat focus as durable state.** The most useful references are Rivi and Luncher for restrained architecture, Projectivy for progressive loading, Jellyfin for focus restoration, and LTvLauncher/Pegasus for large collections. A framework replacement has no demonstrated benefit for this TV.

## What was researched

Exa returned **60 search results across four research areas**, reduced to **55 distinct URLs** after URL deduplication. Those are discovery counts, not 55 independently verified implementations. Follow-up reads covered official documentation, release notes, original source files and repository metadata. Four areas: minimal launchers; richer TV home screens and their failure modes; adjacent media/controller interfaces; platform performance guidance.

Primary sources carry the conclusions below. Search-only leads, third-party roundups, unofficial Jellyfin forks, APK mirrors and unauditable speed claims do not establish findings. No alternative launcher was installed or benchmarked on the TCL. Statements about likely benefits are hypotheses until measured on the same TV and content.

“Lightweight” needs several measurements: installed/download size, first usable screen, warm Home return, frame pacing, total process and graphics memory, background work, and correct remote behavior. A tiny APK does not establish low runtime memory or low input latency. Android's TV guidance explicitly describes memory pressure delaying Home and affecting system controls and media threads. [Android TV memory guidance](https://developer.android.com/training/tv/playback/memory).

## The interfaces worth learning from

| Reference | Verified observation | Lesson for prettTV | Boundary |
| :--- | :--- | :--- | :--- |
| **Rivi** | Its architecture uses native Views, one custom-drawn tile per app, prepared tile images and event-driven updates. It documents device-specific benchmarks and their limitations. | Keep a small rendering surface and make idle work exceptional. Preserve its useful distinction between first frame, warm return and physical remote latency. | Its simpler app launcher and projector measurements do not predict our content-heavy home screen. [Architecture](https://github.com/jommi9/rivi/blob/e4ca2ea9be442e17aa54b94b96bca4762d9607c1/docs/ARCHITECTURE.md), [methodology](https://github.com/jommi9/rivi/blob/e4ca2ea9be442e17aa54b94b96bca4762d9607c1/docs/METHODOLOGY.md). |
| **Luncher** | Platform Views and plain Kotlin; domain decisions are separated from Android adapters. Its image cache validates size, app/launcher version and displayed name, and defers writes until idle/background work. | Keep UI decisions testable and invalidate only the changed app's image. Schedule nonessential cache writes behind visible work. | The inspected cache reads on the main thread; that is not a pattern to import into our remote artwork pipeline. “Instant” is the author's description, not our measurement. [Architecture](https://github.com/CVasilakis/luncher/blob/89e7939e1a00dc181e036d23799feb1fc888e392/docs/ARCHITECTURE.md), [cache source](https://github.com/CVasilakis/luncher/blob/89e7939e1a00dc181e036d23799feb1fc888e392/platform/src/main/java/com/luncher/launcher/apps/CachedBannerImages.kt). |
| **FLauncher / LTvLauncher** | FLauncher offers categories, manual order, row/grid choices and sideloaded-app support. LTvLauncher's inspected row uses a lazy child builder, package-based keys and a finite prefetch extent. | Retain the full collection while creating fewer visible controls. Stable item identity matters when hiding, moving or refreshing apps. | These are Flutter implementations. Borrow the behavior; measure before considering any framework change. LTvLauncher remains excluded from this TV's app lists. [FLauncher](https://gitlab.com/flauncher/flauncher), [LTv row source](https://github.com/LeanBitLab/LtvLauncher/blob/5c079e555927c55d32115ad57cb0e4da46ef009e/lib/widgets/category_row.dart). |
| **Projectivy** | Release 4.51 describes loading uncommon custom-channel data in a second pass. Release 4.72 caps the image cache and adds per-channel program limits. Release 4.73 fixes animations that failed to stop. | First make the useful screen interactive, then enrich it. Put explicit limits on caches and work queues; stop effects when their screen is no longer visible. | These are developer release notes, not source-level performance proof. Do not copy its numeric cache limit as our target. [4.51](https://github.com/spocky/miproja1/releases/tag/4.51), [4.72](https://github.com/spocky/miproja1/releases/tag/4.72), [4.73](https://github.com/spocky/miproja1/releases/tag/4.73). |
| **PLUI** | Its official FAQ describes a configurable widget dashboard and says the launcher is closed source. It describes a separate MIT focus toolkit with explicit arrow rules and a traveling focus ring. | A clear, consistent focus treatment and deliberate transitions between navigation zones are useful ideas. | Both the old launcher repository URL and advertised toolkit returned 404 through GitHub during this review; Exa also could not fetch them. Toolkit internals, current license file and performance were not verified. Widget-heavy dashboards and live cameras are outside our lightweight goal. [Official FAQ](https://pluitv.com/us/faq/). |
| **Jellyfin Android TV** | The inspected focus manager stores a selected key; a navigation decorator saves/restores keys per screen. | Restore by stable content identity rather than a raw view or numerical index. Preserve a clear contract for Back, Home, dialogs and refresh. | The inspected files are from a pinned development commit, not a claim about the current released app. It is a media client, not evidence that prettTV needs a media server. [Focus manager](https://github.com/jellyfin/jellyfin-androidtv/blob/3b00c167a5ae6929dde624ce7b2998989ae583e9/app/src/main/java/org/jellyfin/androidtv/ui/navigation/focus/FocusPersistManager.kt), [navigation decorator](https://github.com/jellyfin/jellyfin-androidtv/blob/3b00c167a5ae6929dde624ce7b2998989ae583e9/app/src/main/java/org/jellyfin/androidtv/ui/navigation/focus/FocusRestorationNavEntryDecorator.kt), [branch policy](https://github.com/jellyfin/jellyfin-androidtv). |
| **Pegasus / Qt Quick** | The official Flixnet example assigns rows and items to two distinct navigation axes. Qt documents reusable list items, offscreen animation suspension, asynchronous images and sizing decoded images for display. | Keep movement predictable and separate collection data from the small set of rendered cards. Use a measured, bounded prefetch window. | Qt warns that larger caches trade memory for smoothness and may make performance worse. This is an architectural analogy, not a recommendation to port prettTV to Qt. [Pegasus example](https://pegasus-frontend.org/docs/themes/example-flixnet-ch1/), [Qt lists](https://doc.qt.io/qt-6/qml-qtquick-listview.html#reusing-items), [Qt performance](https://doc.qt.io/qt-6/qtquick-performance.html). |
| **Kodi skin engine** | Its engine can skip drawing clean regions, while still processing control state every frame. | “Nothing visibly changes” does not establish “nothing costs CPU.” Measure both redraws and background/state work. | This is an engine lesson, not a performance ranking of Estuary or a proposal to adopt Kodi's skin system. [Official engine explanation](https://kodi.wiki/view/Skinning_Engine). |
| **Apple TV focus model** | Apple's documentation describes one focused action target and directionally coherent navigation; its reduced-motion guidance treats depth/parallax and scaling as effects that need alternatives. | Communicate focus immediately. Small, optional motion can finish afterward; the interface must remain understandable without it. | tvOS hardware, input and rendering differ. Apple-style parallax is not evidence of faster performance on TCL hardware. [Focus model](https://developer.apple.com/library/archive/documentation/General/Conceptual/AppleTV_PG/WorkingwiththeAppleTVRemote.html), [reduced motion](https://developer.apple.com/help/app-store-connect/manage-app-accessibility/reduced-motion-evaluation-criteria/). |

Primal TV Launcher and text-only Lite Launcher were additional discovery leads, not implementation recommendations. Primal's own listing describes layouts, Watch Next and profiles but does not establish an inspectable native implementation. Lite Launcher's text-first approach is an interesting Library search reference, but removing artwork would conflict with this home's purpose. [Primal listing](https://play.google.com/store/apps/details?id=dxidev.primaltvlauncher), [Lite Launcher](https://github.com/icaroharry/lite-launcher).

## What prettTV already does well

These findings come from the reviewed prettTV source, not competitor claims:

- The release has no third-party runtime library declared in the app build; JUnit is test-only. The signed 0.5.1 APK is **701,300 bytes**. That is a useful packaging baseline, not a RAM measurement. [Build configuration](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/build.gradle.kts).
- Content artwork uses two workers, a bounded 64-item queue, sampled decoding and a **12 MiB** memory cache. Remote image work is outside the UI thread. [ArtworkLoader](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/ArtworkLoader.kt).
- Home reuses its hierarchy on unchanged warm returns; status-only refreshes preserve cards. Content art loads according to viewport visibility, with throttled scans, while app icons use a disk cache. Home stops its content/weather work and cancels queued artwork work when it stops. [MainActivity](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt), [app images](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/Apps.kt).
- Finite rows, compact actions, dark tiles, grouped settings, anchored app actions and retained refresh focus already exist. Reimplementing these under a new name would not be an improvement. [Current usage](usage.md).

The recorded 0.5.1 injected warm reset took roughly **7.3–8.7 ms of UI-thread work**. This excludes physical remote transport, composition and panel display time; it is not an input-to-photon measurement or a sustained frame-rate claim. [Validation](validation.md#pretttv-051-oled-controls-and-audio-output--2026-11-04).

## Concrete opportunities in the current source

### 1. Long rows create every card, even when images are lazy

`buildShowRow()` iterates through its entire supplied list. `buildAllApps()` creates the entire Library. Recommendation rows default to 60 items each, so four fully populated discovery rows can create **240 card views** before Continue watching, apps and other rows. This is a capacity example, not a count measured on the current TV. [Row construction](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt#L773), [row limits](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/PersonalizationPolicy.kt#L28).

**Proposal:** prototype windowed rendering for one discovery row. Keep all titles available, while rendering the viewport and a small neighboring buffer. Compare a native custom window with an isolated RecyclerView prototype; choose using measurements and navigation reliability. Android documents RecyclerView's creation/reuse model, but changing the container is not enough to guarantee correct TV focus. [RecyclerView](https://developer.android.com/develop/ui/views/layout/recyclerview), [TV navigation](https://developer.android.com/training/tv/get-started/navigation).

Required invariants: selected content ID survives reordering; hidden cards choose a sensible adjacent item; held Left/Right never escapes a finite row; pending layout cannot lose focus; Back restores the originating card. The full catalog length must remain unchanged.

### 2. Artwork loading can follow the direction of travel

The current scan begins loading an image after its card is visibly intersecting the viewport. This bounds memory well but can leave an entering card waiting for work. The queue is FIFO and the loader has no explicit in-flight map keyed by URL and dimensions. Scans also walk the complete registered artwork list. These are code observations; no new trace established which currently dominates. [Visible-art scan](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt#L810), [loader](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/ArtworkLoader.kt).

**Proposal:** visible cards first, then one or two neighbors in the current direction; cancel obsolete queued work and coalesce identical in-flight requests. Bound prefetch by decoded bytes, not only item count. Keep current placeholders and preserve artwork during refresh. Hero and thumbnail requests legitimately need different resolutions; they must not be incorrectly merged.

### 3. Prioritize the first useful screen

App reload publishes discovered apps before filling images, which is already progressive. It then prepares Library marks and Home images for every discovered app. Rendering also constructs offscreen Library and content rows. [Reload path](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt#L916).

**Proposal:** schedule visible favorite icons before offscreen Library marks; mount lower sections in bounded batches while preserving their navigation anchors. Measure first **usable** content separately from first frame. Projectivy's phased loading is the relevant precedent; Android also advises keeping startup independent of blocking network requests. [Projectivy 4.51](https://github.com/spocky/miproja1/releases/tag/4.51), [startup guidance](https://developer.android.com/topic/performance/appstartup/best-practices).

### 4. Motion has a real cost, but the remaining animation is not dead code

App and Library spotlights are disabled in the OLED theme. Their `focusAmount` animation still drives a **6-reference-pixel icon drift**, while a separate property animation scales eligible cards to 1.025. The 180 ms redraw animation therefore still changes the picture. Row scrolling also uses a 180 ms animation that is canceled/restarted for the next target. [Drawing and focus](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/PrettTvCard.kt#L150), [row movement](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt#L261).

**Proposal:** benchmark the existing treatment against immediate focus indication plus restrained property-only motion. Check rapid reversal and held keys before changing timings. Preserve the current design unless a rendered comparison earns approval. Do not debounce valid D-pad presses to conceal slow rendering, and do not assume all motion is harmful.

### 5. Make memory retention deliberate

The 12 MiB artwork LRU is only one owner of memory. App entries retain tile/Library bitmaps, visible cards can retain artwork, and graphics allocations add another category. `onStop()` cancels artwork jobs but does not evict its cache; eviction happens on loader close. The inspected Home/loader has no explicit memory-trim policy. Warm retention can be useful and is not itself a leak. [Lifecycle](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt#L156), [loader ownership](https://github.com/draco1738/prettTV/blob/8da88c2478f44df76f9bfdfc852c979c99a44063/launcher/app/src/main/kotlin/fi/jommi/rivi/ArtworkLoader.kt#L18).

**Proposal:** measure foreground and background memory, then introduce a bounded warm-retention policy with pressure-aware release of replaceable artwork. Keep lightweight data and focus state. Account for card references as well as cache entries. Compare subsequent Home returns so memory savings do not create repeated blank screens. Use actual device capability and supported lifecycle signals; do not adopt a generic “free RAM” button or kill other apps automatically.

### 6. Compilation profiles are a later experiment

Android's Baseline Profiles can optimize selected startup/navigation paths. They require correct capture, release transformation and installation behavior. Google's general improvement figures are not a forecast for this small app, and sideloaded signed upgrades need separate verification. This should follow traces showing compilation/startup cost, not precede the row and image work. [Official overview](https://developer.android.com/topic/performance/baselineprofiles/overview).

## Visible UX refinements worth prototyping

1. **A precise focus contract.** Keep Home as the deliberate reset to the beginning. Back/dismiss returns to the exact card and scroll position. Refresh preserves content identity. Header selection and current focus remain distinct.
2. **A quiet position cue for long rows.** Keep the partial next card; optionally show a small “12 / 60” only while browsing. No wrapping or extra permanent chrome. This is a proposed design, not an observed competitor feature.
3. **One consistent focus treatment.** White outline, black surface, no competing full-screen glow. Optional small motion follows the selection without delaying input. A shared moving ring is worth a mockup, but it must not make the selected target ambiguous in transit.
4. **Stable images and text.** Reserve artwork bounds, keep the previous valid image during refresh, and load neighbors before they enter. Do not let late metadata resize rows.
5. **Keep the hero's meaning.** It remains the newest eligible watched item. Hovering recommendations must not replace it or trigger video previews. Information about the focused recommendation stays with that row.

These extend the current design. The pinned choices remain: one finite app row, persistent horizontal navigation, OLED black/white controls, long content rows, separate Shows/Movies Continue watching, and Blind Pick/Rabbit Hole in Library.

## Recommended sequence and release gates

| Order | Work | Decision gate |
| :--- | :--- | :--- |
| 1 | Capture a repeatable 0.5.1 baseline and traces using fixed content, text scale and resolution. | Identify layout, decode/upload, animation and idle costs separately. |
| 2 | Prioritize visible icons, deduplicate/cancel image work and try a very small directional prefetch buffer. | Fewer entering-card blanks without worse memory, navigation or Home return. |
| 3 | Prototype one windowed recommendation row behind a development flag. | Fewer live views/lower layout cost, all titles available, zero focus failures in scripted stress paths. |
| 4 | Compare restrained motion variants with rendered mockups, then on-device timing. | Adopt only an approved appearance with measured benefit. |
| 5 | Tune background retention; consider Baseline Profiles only if traces justify them. | Better memory behavior without worse warm return or sideload/update regressions. |

For each experiment, interleave baseline/candidate runs on the same TCL instead of comparing a freshly restarted candidate with an old baseline. Include short and long populated rows, repeated direction reversal, hide/unhide, metadata updates, offline images, enlarged text, Settings and app-return transitions. Preserve production preferences and use isolated test content where possible.

Record cold and warm launch timing separately; median and tail frame timing; missed-frame/overrun metrics where available; PSS and graphics memory with consistent definitions; live view count; decoded image bytes; decode/network queue depth; and idle CPU/redraws. At 60 Hz, 16.7 ms is the frame budget, not a promised end-to-end input latency. A first-frame timestamp, injected input or screenshot cannot establish physical remote-to-panel response. [Android benchmark metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics), [Rivi measurement caveats](https://github.com/jommi9/rivi/blob/e4ca2ea9be442e17aa54b94b96bca4762d9607c1/docs/METHODOLOGY.md).

Promotion requires preserved navigation semantics, no new crashes/ANRs, stable memory across repeated paths and improvement in the metric the change targets. Report unsuccessful experiments too. Keep the user's one-hour upper preference for any later full soak; this research did not run a soak or interrupt TV playback.

## What should stay out

- A Compose, Flutter, Qt or web rewrite justified only by appearance or a competitor's “lightweight” claim.
- Autoplay trailers, live wallpaper, animated logo loops or continuous blur added to simulate polish.
- Larger unbounded caches, smaller content catalogs disguised as optimization, or automatic app killing.
- Re-enabling Google launcher/accessibility key filtering to obtain an unrelated UI feature. Those choices previously conflicted with the owner's Home and native volume requirements.
- Claims that studying Jellyfin requires Plex/Jellyfin server setup, or that a launcher can fabricate streaming account progress it cannot access.

## Provenance and limits

No third-party code or assets were imported. Rivi and Luncher repository metadata identify MIT licenses; LTvLauncher identifies GPL-3.0 and Jellyfin Android TV GPL-2.0. Existing prettTV attribution remains intact. Any future code reuse needs the actual file/repository license and notices reviewed at that revision; inspiration alone is not permission to copy closed-source internals.

Pinned source revisions used for implementation reads:

| Project | Revision |
| :--- | :--- |
| Rivi | `e4ca2ea9be442e17aa54b94b96bca4762d9607c1` |
| Luncher | `89e7939e1a00dc181e036d23799feb1fc888e392` |
| LTvLauncher | `5c079e555927c55d32115ad57cb0e4da46ef009e` |
| Jellyfin Android TV, development branch | `3b00c167a5ae6929dde624ce7b2998989ae583e9` |

Projectivy evidence is tied to named release notes, not an accessible launcher codebase. PLUI evidence is limited to its official FAQ and observed repository-access failures. Platform documentation is a living source. The report deliberately makes no cross-launcher speed ranking and no claim that the proposed changes are already implemented.
