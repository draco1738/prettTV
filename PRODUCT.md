# prettTV

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android Views and Kotlin, extending the existing MIT-licensed Rivi 0.15 source. No runtime UI dependencies. Android TV only.

## Users

The owner of a TCL Google TV, using a remote from viewing distance.

## Product Purpose

A modern, attractive, lightweight TV home that launches streaming apps smoothly and retains useful show suggestions.

## Operating Context

User-confirmed brief: combine PLUI, Rivi, and the useful parts of stock Google TV; remove unwanted ads and tracking; retain recommendations. Connected device previously identified as TCL G08, Android 14, 32-bit ARM, 1920x1080 UI with a 3840x2160 60 Hz panel. ADB is connected; a signed native build is installed. The first installed C was rejected. The replacement was praised by the owner, who then requested dark app icons, real watch history, longer content rows, a finite row containing all included apps and local title hiding.

## Capabilities and Constraints

Preserve app launching, favorites, hiding and ordering apps, HDMI input access, system settings, and safe Home-button handling. Use available Android TV content-provider rows for recommendations and Continue Watching; provider coverage and permissions must be tested on this TCL. No universal streaming-account integration is assumed. No measured frame-rate claim until hardware testing. All sources, tools, caches and builds stay on E:.

Version 0.2.0 extends the existing home with a local Watchlist, followed-show release alerts, configurable shelves, title details, recent-change Undo, recommendation feedback, favorite teams and optional kickoff reminders, cached offline Home content, and an explicit official-update screen. These features reuse the incumbent home and native control language. They do not introduce a new visual identity.

The default content order is Shows, Movies, Watchlist, conditional New episodes, Live sports, Suggestions, Discover, Trending, and New releases. Users may reorder or hide content shelves and select Small, Standard, or Large cards. Suggestions, Discover, Trending, and New releases offer 20/40/60 title limits. Watchlist remains local, uses 60-title pages for long lists, and does not imply streaming availability. Library continues to include apps hidden only from Your apps.

Followed-show alerts require verified release metadata and are checked while Home is open; released does not mean playable in a service. Favorite-team reminders are optional Home cues. Cached recommendations expire after seven days and show an offline age label. Updates are user-initiated, use official releases, and require Android installation confirmation; other apps are managed separately.

## Brand Commitments

User supplied PLUI and Rivi as explicit visual references and asked for the good parts of stock Google TV. The requested feel is progressive, lightweight and attractive. prettTV is the owner-selected project name; the Android package is dev.zane.pretttv. PLUI code and artwork are analysis references only; build original implementation on Rivi's reusable MIT base.

## Evidence on Hand

Pinned Rivi source and official APK; official PLUI APK decompiled for static analysis; PLUI repository screenshots; previous TV identity and display snapshots. Actual 1920x1080 ADB captures and user photographs of the TV are available in .impeccable/review. The user requires the appearance to match the C mockup.

The 0.4.0 device gallery uses docs/screenshots/A-cinema.png, B-quiet.png, C-balanced.png, top-navigation.png and app-actions.png. These show the current native layouts and contextual action panel. Rendered design proposals are separate from device evidence, and the versioned validation record holds actual checks and limitations.

Historical 0.2.0 functional validation reported 202 JVM tests and 35 Android tests passing. Its final functional gate and the user-approved one-hour soak checkpoint review passed; see docs/validation.md. The original two-hour run was stopped at the user's request and its native outcome remains UNRECORDED. That earlier one-hour review is not a 0.4.0 soak or certification of its new navigation and app-action surfaces.

## Product Principles

- Show real installed apps and real provider content; never invent a watch history.
- Let the screen rest when nothing changes.
- Load artwork progressively, off the UI thread, with bounded caches.
- Keep Home, Back, settings and inputs usable with a remote.
- No analytics, developer registration, advertising identifiers or promotional catalog.

## Accessibility & Inclusion

D-pad navigation, visible focus, readable text from a couch, labels for accessibility, system font scaling and disabled-animation support.

Latest owner direction, implemented in **0.4.0**: a persistent opaque horizontal navigation bar replaces the sidebar in all A/B/C layouts. Home, Shows, Library, Inputs, Search and Settings remain visible above the scrolling content, with location, temperature and time on the right. Up from the first content card reaches navigation; Down returns to the remembered content card. Left and Right stop at finite row boundaries. Header Home resets the page and every row to its default start, matching the remote Home action. Weather freshness remains available in Weather settings without a cached label beside the temperature.

The same version adds a clearer provider-backed resume hero, a compact anchored app-action panel, focused-card metadata in reserved space, 28-reference-pixel medium section headings, bounded hold/end feedback, and stable cached-content refresh. It retains existing content and artwork during refresh, distinguishes an unavailable service from offline connectivity, and never manufactures episode, progress, account or resume guarantees. Native visual and release outcomes are recorded by version in docs/validation.md.

Continue watching applies chronological changes immediately while preserving the selected title; the hero follows the newest eligible viewing record. Recommendation-row reordering can wait until focus leaves the row. Provider progress observations are stored separately from confirmed local viewing and do not consume Watchlist. Timestamp-only provider changes never promote a title; delayed or shared-account progress cannot identify who watched or establish an exact playback time.

Subtle card-edge contrast and footer-only branding remain. C stays selected. RBTV+ comes with the default installation bundle. Optional bounded RBTV game matching and local playback evidence replace reliance on stale provider timestamps; app coverage and game availability remain limited and must be described honestly. SportioTV and HD Streamz remain removed.
