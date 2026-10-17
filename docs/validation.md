# prettTV 0.1.6 Pluto recommendation priority — 2026-10-17

Pluto TV now has a separate fallback tier in Suggestions and Discover, after other app/catalog sources and before Stremio. The tier applies before deduplication, allocation and the 60-title limits, and is not overridden by launch recency or a selected-service entry. Trending, app availability and personal viewing-history rules are unchanged.

70 JVM tests pass. Two new regressions cover a large Pluto feed that must not displace other apps, and sparse rows where Pluto remains available after other sources but before Stremio. The signed v0.1.6 APK installed in place on the TCL.

# prettTV 0.1.5 app-hide focus fix — 2026-10-17

Hiding a focused Your apps tile now selects the next surviving neighbor, the previous neighbor at the end, or the empty-row chooser when none remain. Library and sports shortcuts keep their own focus identity because hiding affects only Your apps. Favorite rearrangement follows the same app. The app menu captures its originating focus and scroll positions and applies its action after dismissal. Screen rebuilds restore saved horizontal positions after layout; a pending restoration survives another feed-triggered rebuild. Local app arrangement no longer restarts recommendation fetching.

68 JVM tests pass, including three new focus-policy regressions for middle/first/last removal, multiple missing neighbors, an empty row and reordered apps. The signed update installed in place on the TCL. Navigation reached a scrolled app row and the app options menu, but the hide operation did not apply before unrelated TV navigation resumed. The TV subsequently entered Stremio; remote input was stopped. The hidden-app set remained unchanged at 24 entries. This pass does not claim a completed on-device hide/restore or Library-focus check. Earlier visual captures still describe the unchanged design.

# prettTV 0.1.4 service-first discovery — 2026-10-17

65 JVM tests pass. Eleven new policy tests cover large early free feeds, allocating multiple providers to both Suggestions and Discover, explicit service priority, conservative viewing-activity inference, app intent preservation, unknown/ambiguous popularity matches, cross-row exclusions, 60-title bounds and empty input. The signed v0.1.4 update installed in place on the TCL; C Balanced, the local hero and dark icons were retained.

On-device diagnostics report 60 Suggestions, 60 Discover and 60 Trending entries. Suggestions and Discover start with Disney+ and Prime Video, with Apple TV also present before general/free feeds. Trending starts with Apple TV and Crunchyroll's explicit popular channel; YouTube's published Trending and Cinemeta fill later slots. The observed Netflix and HBO apps publish watching records but no preview recommendation cards. Their account catalogs were not scraped or fabricated. Genre fields were empty in the observed preview feeds, so provider balancing supplies variety without inventing genre classifications.

The native service selector displays the inferred choices and supports a local explicit override or a return to viewing-activity inference. Its UI was captured on the TV. A signed-in state is not claimed: shared progress is only a default signal. During remote checks, unexpected navigation was observed; inputs were paused to avoid overlapping with the owner. This pass does not claim an isolated save/restart interaction test. The new row capture at docs/screenshots/discover-row.png shows non-anime films and series, provider labels, focus treatment and the Suggestions → Discover → Trending order in C. All layouts use the same row builder and policy; this pass did not repeat the earlier A/B/C layout checks.

The shared-provider scan remains bounded at 5,000 scanned records, 2,000 accepted cards and 300 per app, with 60 visible entries per row. Public channel labels and weights are read; opaque provider-internal identifiers are no longer decoded. Existing cached catalogs are reused. Optional Android special-access settings and viewing-history ordering were not changed.

# prettTV 0.1.3 Discover row — 2026-10-17

Added Discover immediately below Suggestions and above Trending through the shared row builder used by A/B/C. The row reuses the current cards, D-pad behavior, horizontal scrolling and artwork limits. It selects up to 60 additional titles from already-fetched provider/catalog data, excluding normalized titles in watching, Suggestions, Trending and New releases. Preferred app sources precede Stremio; duplicate titles retain the preferred source. No additional network endpoint or request was added.

54 JVM tests pass, including cross-source duplicate/exclusion behavior and applying the row limit after exclusions. The signed update installed in place. The TV supplied 60 Discover entries; UI hierarchy checks verified Suggestions → Discover → Trending in all three layouts. Moving six cards right in Discover reached an additional title and scrolled the row. C and the Home position were restored afterward. The new screenshot is docs/screenshots/discover-row.png. Dark app icons, viewing-history behavior and special-access settings were unchanged.

# prettTV 0.1.2 dark-tile correction — 2026-10-17

The reported rollback was checked against the installed package: v0.1.1 and C Balanced were still selected. The certificate reinstall had removed cached artwork; unmapped Disney+ and HBO then rendered their installed bright/gradient banners. Version 0.1.2 uses an attributed Disney+ foreground vector on the common dark ground and an unmodified bundled white HBO logo. Cache generation icons4 invalidates the former tiles. Neither mapped app now depends on installed artwork.

The signed v0.1.2 update installed in place without uninstalling or resetting preferences. The new cache generation rendered dark tiles on the TV; A/B/C captures were refreshed, with C restored and The Night Agent still the hero. The same 52 JVM tests pass. An additional Android cache-only clear command did not return, so its client was stopped; explicit completion of that command is not claimed. Source-level cache independence and the new-generation TV renders are the evidence. No launcher behavior, viewing-history policy or special-access state changed.

# prettTV 0.1.1 validation — 2026-10-17

Signed release built with JDK 17, Android SDK 34 and build-tools 35.0.0. All 52 JVM tests pass: 16 app-model, 14 content-policy, 7 sports-policy, 7 playback-policy and 8 RBTV match-policy tests. The PowerShell and Bash installers passed eight isolated checks: fresh installs include both APKs; existing RBTV is preserved; launcher-only installs one APK; a bad companion checksum installs neither. The restore script passed syntax checks but was not run against the configured Home.

On the owner's TCL Android 14 / 32-bit ARM TV, package dev.zane.pretttv was installed and selected as default Home. Local settings were backed up, migrated and compared before the previous package was removed. Title filters, favorites and C selection survived. After force-stop and Home, C returned with The Night Agent as the hero. That title was corrected using the owner's explicit report and the local “I watched this most recently” control; it is not evidence of automatic playback tracking.

All three native layouts were captured on the TV at its 1920×1080 UI resolution. The sidebar is hidden initially, appears on Left at the first card, and closes on Right, Back or a destination. Library opens the full app grid. A temporary Prime Video hide test reduced Your apps from 34 to 33, left Prime accessible in Library, and offered “Show in Your apps row” there. Restoring it returned the row to 34. Search and content-history independence are verified in source; no streaming account state was changed. C and normal font scale 1.0 were restored. The prior 1.3 font-scale capture also passed the scoped visual review. Disney's fine wordmark now receives appropriate logo bounds instead of showing as a tiny square.

The default Home resolver points to dev.zane.pretttv/fi.jommi.rivi.MainActivity. Observed sources supplied 21 watching records, 59 app suggestions, 59 Trending entries, 60 New releases and 34 sports fixtures. Providers may publish stale or shared-account records. Provider timestamp refreshes alone cannot displace locally confirmed playback; initial provider ordering is retained until there is local confirmation. Missing usable metadata is not guessed.

Optional Notification access for identifiable active media sessions and Accessibility access for bounded RBTV game matching remain disabled on the owner's TV pending approval. Their policies are unit-tested, but end-to-end automatic recency and matching-game handoff are **not hardware-verified**. The ordinary RBTV app-opening fallback was verified. No stream playback or server-selection success is claimed.

The screenshots in docs/screenshots are fresh, unedited captures of A, B, C and the expanded sidebar, explicitly authorized for public publication. Private settings exports, device dumps, signing keys and raw traces remain outside Git. Source art and provider content retain their owners' rights.

## Performance evidence limits

The panel is 60 Hz; sustained 60 fps is not claimed. A historical 0.1.0 navigation sample rendered 262 frames with 13 janky frames (4.96%), median 25 ms, p95 36 ms and p99 42 ms. That single scripted sample is not a benchmark of 0.1.1. No new performance claim is inferred from the UI screenshots or unit tests. The native renderer retains bounded artwork workers/caches and avoids a web UI or continuous animation.

Final scoped visual confirmation: Disney+ sizing resolved across A/B/C; the expanded sidebar capture includes Library and focused Home; no material regression observed. The final release was re-signed with a prettTV-named certificate and reinstalled with every backed-up preference value compared successfully. Signing-certificate SHA-256: `46fecddaaff65d9500c6cfddee90e07c6ca3fe7941c8a405829258b5e7ff9ded`.

Publication checks: GitHub Actions passed for release source bff82461576d81404589a63ab51acd85ad42ae51. Public repository access and an unauthenticated download of prettTV-0.1.1-with-RBTV.zip were verified; the downloaded SHA-256 matches e7f02cc4bb0f7ea955cd2382b823ebd19d6850d3b874091efb0fecd70743fae3. The retired release/tag and old-named branch were removed after local backups. Historical source commits remain intact.
