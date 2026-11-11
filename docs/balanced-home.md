# C Balanced: resume, discover, open

[← prettTV](../README.md) · [User guide](usage.md) · [Validation](validation.md)

Version 0.6.3 enlarges the three Discover portraits beside the resume feature while retaining the owner's approved C identity. It builds on 0.6.1's genuine portrait metadata and 0.6.2's reversible sizing and compact metadata cleanup. This is native Android Views and Canvas, not a WebView, static mockup or new catalog. Cinema and Quiet remain available through **Settings → Home & apps → Layout**.

![C Balanced 0.6.3 installed on the TCL, with enlarged Discover portraits and existing preferences.](screenshots/C-balanced-0.6.3.png)

*Actual, unedited installed-TV capture. Nocturne, Frieren: Beyond Journey's End and Awkwafina: The Unlikely Cook have full-height portraits beside the Game of Thrones feature. Titles, supplied metadata and launch destinations come from the TV's available feeds, not concept sample content. Later gallery images retain their explicit historical versions.*

## Larger Discover

Each picture area grows from 202×303 to 240×360 reference pixels. Three cards and Browse shows remain; the feature is 1000 pixels wide, the gutter stays 40 pixels and Discover occupies 752 pixels. Overall content width is unchanged. The stage grows to 567 pixels at normal text scale to preserve title clearance; lower rows retain their sizes and scroll normally. Both Standard and Larger sizes retain all three portraits without changing Android display or font settings. The full native suite and twelve A/B/C size/text captures verified the new geometry before installation.

## What changed

### Consistent interface size

**Settings → Home & apps → Interface size** offers **Standard · 100%** (default) and **Larger · 104%**. Cards, text, icons, gaps and controls scale together, using some outer margin so the hero and three portraits still fit side by side. Version 0.6.3 extends the same setting to Discovery, Search, native dialogs and prettTV utility screens. Lower shelves scroll; no screenshot or root-view bitmap is enlarged/cropped. Selection is retained when changing sizes, and the setting is included in local backup/restore.

This changes prettTV-owned rendering, not Android display density, other apps or system screens. System text scaling remains independent. The modest limit keeps the full-width composition usable without cropping or restructuring it. See [Validation](validation.md) for versioned evidence and limits.

The 0.6.2 compact Continue watching cleanup remains: service plus a deduplicated metadata/time line, rather than repeating it after the original provider detail. Only exact repeated fields are removed; unique descriptions and ambiguous episode labels are not guessed away. Original provider detail and synopsis remain available in title details, and other card modes retain their formatting.

### Direct local controls

Undo appears in Home's top bar immediately after a supported change without taking focus or expiring on a short timer. Discovery's session-local Undo sits beside Back. Applicable recommendation menus offer Why this recommendation? and Not interested, based only on observable source and preference evidence. The explicit TV-local settings workflow is under **Settings → Support → Local settings backup**. [Controls, recovery boundaries and exclusions](usage.md#local-settings-backup).

### Approved v0.6.1 composition

A larger feature occupies the left of the first screen. Its two-line title, actual metadata, progress when supplied, dark Resume/Open action and service label remain distinct. The existing chronology and hidden-title policy choose the feature; a refresh timestamp does not become evidence of recent viewing.

Three portrait picks sit to the right of the large feature. C now considers a bounded six-title pool, preferring series and verified portraits within the existing service-priority tiers. Sports stay in their ordinary shelves rather than occupying these show-poster slots; duplicate titles and the feature are excluded. Optional exact-match TVmaze metadata supplies real portrait URLs instead of assuming that a provider's `poster_art_uri` is actually portrait-oriented. A short feed remains short, and an empty feed offers an honest route to apps. Artwork updates preserve the selected title where it remains available; removal and empty-state transitions recover usable focus.

**Browse shows** goes to an existing visible discovery shelf, without launching a provider or promising streaming availability. If those shelves are hidden, it returns to available content or apps.

The single Your apps row keeps its **208 × 94** reference-pixel cards, **36-pixel corners** and **12-pixel gaps**. Standard-size Continue watching rows use compact landscape-thumbnail cards with title and actual supplied metadata. Custom Small/Large choices, separate Shows/Movies shelves, ordering and hiding remain supported. Library, local Watchlist and the remaining rows stay below.

## What you see

**Top navigation.** Home, Shows, Library, Inputs, Search and Settings stay in one opaque horizontal bar. There is no sidebar. Existing optional location/weather, clock and Bluetooth status stay on the right. The bar remains visible when lower shelves scroll underneath it.

**The resume feature.** The largest area belongs to the next viewing action, with a two-line title, episode metadata when available, supplied remaining time and a restrained progress line. A dark overlay keeps the text readable over artwork. The compact outlined action says **Resume** only when supported progress exists; otherwise it opens the relevant app/title. The source label sits beside it. A stale or unavailable feed is not replaced with invented progress.

**Discovery.** Up to three tall frames sit alongside the feature, each with a 2:3 picture area and its title below. Series and verified portraits are preferred only within service tiers, so a fallback service cannot jump ahead of preferred-service content merely because it has a poster. Other provider titles can remain as fallbacks. These are recommendations, not proof of a subscription or playback entitlement.

**Artwork limits.** A provider field named “poster” can contain a wide banner; this caused the empty bands in 0.6.0. Version 0.6.1 prefers dimension-checked portrait artwork for an exact series match. Every poster frame now uses one scale for both axes and a centered crop, never independent stretching. If no verified portrait exists, the original provider image is cropped rather than flattened; this can omit the sides of a wide banner and is not equivalent to a genuine poster. Ambiguous titles, movies, untyped items and sports are not guessed into a TV series. Missing art keeps a usable title/action. No episode still or URL is fabricated.

**Apps and continuation.** Recognizable colored streaming marks remain on black rounded tiles. The first screen gives the hero and discovery breathing room while keeping Your apps and the start of Continue watching visible at normal text size. Continuation cards place a small landscape image beside the title, service/episode information and valid progress. Enlarged text can push lower shelves farther down the page; the screen scrolls rather than squeezing text into the same height.

## Remote behavior

- Right from the feature reaches the first available poster. Right stops at the last poster; Left returns through the posters to the feature.
- Down from a poster reaches Browse shows; Down again reaches Your apps.
- Up from the first-stage content reaches the permanent top navigation. Down from navigation returns to remembered content.
- Home resets navigation and row positions without discarding the unchanged native hierarchy. Hold OK retains the existing title/app actions.

OK on the feature or a program follows its existing launch destination. OK on an app opens that app. **Browse shows** only moves focus into Home's existing discovery shelves; it does not start playback. Hold OK on a title still opens details and the applicable save, follow, hide, dismiss or viewing-order correction actions. Back closes panels or returns content toward the top. Menu opens Settings.

When a provider refresh removes the selected discovery item, focus stays within the replacement discovery area. Empty-to-populated transitions also select a usable replacement instead of an invisible old view. Lower windowed shelves still retain every configured title while mounting only nearby views.

<details>
<summary><strong>See Browse shows on the installed TV</strong></summary>

![Browse shows in 0.6.1 moves to native discovery shelves while the top navigation remains visible.](screenshots/C-browse-shows-0.6.1.png)

The existing row order is retained. This TV has sports above Suggestions and Discover; Browse shows moves to an available discovery shelf rather than rearranging those preferences.

</details>

## What remains unchanged

- A Cinema and B Quiet keep their own compositions. Selecting C does not replace the other layouts.
- App ordering, hidden shortcuts, row order/visibility/card-size choices, preferred services, weather, focus-motion preference and local title preferences remain supported.
- Continue watching uses existing chronology and explicit local corrections. It cannot identify who watched on a shared account or establish account-wide viewing history.
- Watchlist stays local. Following shows, recommendation feedback, original Library experiences and the existing sports features remain available.
- Audio controls, app first aid, native settings routes, updates and recovery controls are not redesigned or silently enabled.
- The update does not clear streaming-app data, change sign-ins, enable accessibility helpers, alter audio output or select a different default Home.

## Rendering and data

At 1920 × 1080 reference size, C has 64-pixel side insets, a 1000 × 567 feature/stage, a 40-pixel gutter and a 752-pixel discovery column containing three 240×360 picture areas. The stage grows for enlarged system text rather than letting poster labels collide with Browse shows. Text is native sans, surfaces stay black, and focus uses white outlines. Feature/poster/continuation artwork stays still; there is no autoplay, blur, added background polling or new runtime UI dependency.

Original provider portrait metadata remains backward compatible with saved titles and offline snapshots. C's new presentation-only map does not overwrite those fields, viewing history, progress or destinations. With discovery enabled and a verified network, an optional TVmaze pass can send the visible title names or supplied IDs for at most six candidates and the feature. It permits eight metadata requests with a twelve-second network-work budget and independent connection-disconnect timer, delivers all normal feeds first, rejects stale-generation callbacks and retains a separate bounded cache. Cache I/O and platform transport cleanup mean this is not a strict end-to-end elapsed-time guarantee. A/B do not opt in. [Network disclosures and cache limits](privacy.md#c-balanced-presentation-artwork). Image bytes still use the bounded, prioritized loader introduced in 0.5.2.

The new native feature, poster and continuation modes use static focus outlines: no card scaling, spotlight, artwork drift or autoplay. Existing card modes retain their existing behavior, including the Classic/Quiet focus-motion choice. There is no added UI framework or production runtime dependency.

Portrait fields survive saved/followed titles, playback records, local settings transfer and offline data. New binary snapshots use version 2; version 1 snapshots remain readable with an empty optional poster field. Existing landscape art remains separate and unchanged for legacy layouts and shelves.

## Verification and credits

Version 0.6.3 passed **529 JVM tests across 44 suites** and **184 isolated native Android checks** on the TCL, with **zero Android lint errors and 29 warnings**. Coverage includes enlarged C geometry, twelve A/B/C size/text captures, immediate Undo, truthful contextual actions, a real Home/backup save-change-restore-return flow, writer protection and Android loopback deadline/cancellation controls. Repeated focused runs overlap the full totals and are not added together.

The signed **0.6.3 / code 32** APK was installed in place and pulled back with the tested hash. Home, app and personalization settings matched the pre-install export except navigation focus; automatic playback observations were outside that comparison. The [validation history](validation.md) records production navigation and lifecycle results separately. These are bounded checks, not a new hour-long soak, physical remote-latency certification or streaming-playback benchmark. The image below is retained as the earlier 0.6.1 focus reference.

![The third Discover poster focused on the installed 0.6.1 TV.](screenshots/C-discovery-focused-0.6.1.png)

prettTV remains derived from **Rivi by Jommi**, with the original MIT notice retained. PLUI and Google TV remain design references, not copied proprietary implementations. Native app-logo assets, catalog data and provider artwork retain their respective credits and terms in [Third-party notices](../THIRD_PARTY_NOTICES.md).

The concept used illustrative content. The installed launcher uses the TV's actual available data, so titles, progress and services will differ. It does not infer subscriptions or add Google account Watchlist synchronization.
