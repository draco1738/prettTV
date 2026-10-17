# Validation history

[← prettTV](../README.md) · [Documentation](README.md) · [Run the checks](stability-testing.md)

Recorded results for each release, newest first. Device observations apply to the named build and session; they are not guarantees for every TV. Historical measurements and unverified paths are retained with their original limits.

| Release | Scope | Recorded |
| :--- | :--- | :--- |
| [0.2.1 candidate](#pretttv-021-card-focus-candidate--2026-10-17) | Narrower app cards and finite focus treatment; interim checks | 2026-10-17 |
| [0.2.0](#pretttv-020-personal-rows-and-release-hardening--2026-10-17) | Personal rows and release hardening | 2026-10-17 |
| [0.1.10](#pretttv-0110-stock-troubleshooting-recovery--2026-10-17) | Stock troubleshooting recovery | 2026-10-17 |
| [0.1.9](#pretttv-019-scrolling-and-row-navigation--2026-10-17) | Scrolling and row navigation | 2026-10-17 |
| [0.1.8](#pretttv-018-stability-and-failure-recovery--2026-10-17) | Stability and failure recovery | 2026-10-17 |
| [0.1.7](#pretttv-017-stability-fixes--2026-10-17) | Stability fixes | 2026-10-17 |
| [0.1.6](#pretttv-016-pluto-recommendation-priority--2026-10-17) | Pluto recommendation priority | 2026-10-17 |
| [0.1.5](#pretttv-015-app-hide-focus-fix--2026-10-17) | App-hide focus fix | 2026-10-17 |
| [0.1.4](#pretttv-014-service-first-discovery--2026-10-17) | Service-first discovery | 2026-10-17 |
| [0.1.3](#pretttv-013-discover-row--2026-10-17) | Discover row | 2026-10-17 |
| [0.1.2](#pretttv-012-dark-tile-correction--2026-10-17) | Dark-tile correction | 2026-10-17 |
| [0.1.1](#pretttv-011-validation--2026-10-17) | Validation | 2026-10-17 |

## prettTV 0.2.1 card-focus candidate — 2026-10-17

These interim results apply to the implemented candidate with **208-reference-pixel Your apps widths**, existing A/B/C heights of **116/176/94**, **15-pixel corners** and **18-pixel app gaps**. Library grid geometry is unchanged. A clipped ivory spotlight reaches approximately 18% opacity while artwork moves up to six reference pixels over a finite 180 ms focus transition. The existing 140 ms focus scale remains. Hidden or detached cards cancel and settle their focus motion; disabled animations apply final states immediately.

The owner subsequently requested rounder app cards and less space between them. That revision is awaiting mockup review. The results below do not validate the proposed 22-pixel corners or 12-pixel gaps, and publication remains on hold.

The tested candidate is versionCode 13. Candidate APK SHA-256: `35fa2d0375159353ceb83346ebdf2929a132f68de73891ad6e576b5259f8cffa`. Its verified signing-certificate SHA-256 is `46fecddaaff65d9500c6cfddee90e07c6ca3fe7941c8a405829258b5e7ff9ded`, matching the established release identity. The installed APK was pulled back from the TV and matched the candidate hash.

**202 JVM tests and 40 Android fault/UI checks passed.** Android lint reports zero errors and 31 existing warnings. The five additional isolated card checks cover interrupted focus transitions, settling, hidden/detached cards and reattachment, immediate unattached focus state, fixed overlays, landscape edge coverage and contained portrait bounds. Raster stability demonstrates that tested card pixels settle; it is not a measurement of zero idle frames throughout Home. These tests do not directly toggle the TV's system animation setting.

### Completed device checks

All three layouts passed three navigation cycles each: **423 guarded inputs, six forced cold starts and nine leave-and-return cycles** in total.

| Layout | Guarded inputs | Cold-start times | Leave/return cycles | Strict position checks with unchanged content |
| --- | ---: | --- | ---: | ---: |
| A · Cinema | 141 | 697 ms, 557 ms | 3 passed | 2 |
| B · Quiet | 141 | 535 ms, 510 ms | 3 passed | 3 |
| C · Balanced | 141 | 572 ms, 531 ms | 3 passed | 2 |

Cold-start times of **510–697 ms** include ADB overhead and measure a focusable Home, not completed network artwork or playback readiness. All nine lifecycle cycles preserved Home process continuity and usable focus. Seven cycles also verified the same focus and viewport against unchanged content. Content signatures changed during the first A and first C cycle, so those two runs do not claim strict position equivalence.

The separate C row-navigation check passed reversal across nine app cards, held-Left containment, intentional sidebar entry and return to the original card. Actual A/B/C native Home captures were recorded and visually reviewed for this candidate. They remain private pending the rounder-card/tighter-spacing design decision; the public gallery still shows the earlier release.

An initial A setup used taps at observed control bounds to select the layout, leaving Android in touch mode. The stress harness failed its focus-readiness precondition before sending any test inputs (`inputActions: 0`). Returning to remote navigation restored the expected focus, and the subsequent A run passed. No app-code change was required for that setup failure; the initial result remains a failed precondition rather than a passed test.

### Bounded C motion comparison

The same warmed C navigation route was measured before and after the card update:

| Measure | Before | Card-focus candidate |
| --- | ---: | ---: |
| Rendered frames | 200 | 200 |
| Janky frames | 54 (27.0%) | 53 (26.5%) |
| Median frame time | 16 ms | 17 ms |
| 90th percentile | 27 ms | 28 ms |
| 95th percentile | 28 ms | 29 ms |
| 99th percentile | 28 ms | 29 ms |
| Slow UI-thread frames | 0 | 0 |

Jank count decreased by one while measured frame-time percentiles increased by one millisecond. These short samples do not establish a performance improvement, sustained frame rate or broad-device result. No large change is apparent in this route; remaining missed frame deadlines are retained in the record.

Final private exports matched the pre-install baseline exactly across all four groups: Home, apps, playback and personalization. The crash buffer was byte-for-byte unchanged. Retained exit records contained no CRASH or ANR reason; requested force stops and the retained system invalid-start record were accounted for. No temporary test packages remained. Read-only recovery diagnostics reported configured, unpaused and IDLE; prettTV remained the default Home with C Balanced and the feature focused. No stock restore was executed.

This is a bounded patch check, not a repetition of the complete 0.2.0 feature-release gate. The one-hour soak was **not rerun** for this candidate, and the earlier release's broader A/B/C results do not certify it. Raw device traces, settings and viewing data remain private.

## prettTV 0.2.0 personal rows and release hardening — 2026-10-17

This release adds the separate Watchlist row, configurable row order/visibility/card sizes, title details, recommendation feedback, session Undo, explicit episode follows, favorite teams and optional Home-only kickoff reminders. A bounded persisted snapshot supports offline Home. Settings now offers verified official updates with an explicit download and Android installation confirmation. C Balanced remains the default; A and B retain their native dark layouts.

The final signed candidate is versionCode 12, using the existing release certificate. APK SHA-256: `fbd54e4d7489cc421bfaa5ef072db3124bae07a5e24deafeff2c4c6eb8fe61f3`. It was installed in place without uninstalling prettTV or clearing the owner's data.

202 JVM tests passed. All 35 Android fault and UI checks passed on the TCL in the isolated test package. They cover corrupt/oversized artwork and snapshots, interrupted work, strict settings migration/import, Watchlist playback consumption and Undo, row controls, 72-entry Watchlist pagination, rapid refresh races, focus visibility, provider-row identity reuse, episode and sports policies, recovery failures, and opening/closing Updates. Android lint reports zero errors and 31 individually reviewed warnings: 22 retained upstream unused resources, four programmatic-view constructors, two TV orientation locks, two original vector raster warnings, and the Disney vector path warning. No blanket suppression was added. The test harness itself passes 57 offline assertions, including multiple retained Home activities, transient no-focus settling, persistent focus loss, timeouts, duration and coverage checks.

Testing found and fixed an Updates startup crash on the TCL caused by requesting its window insets controller before the decor view existed. A regression now opens the real activity. Other fixes preserve focus through consecutive partial/full refreshes, keep replacement cards visible, distinguish Watchlist identities when provider row IDs are reused, retain episode-specific destinations, prioritize favorite teams before fixture limits, validate all restored settings before mutation, and bound background work and storage. Earlier-candidate failures remain in the private evidence; they are not counted as final-candidate passes.

### Final device gate

Each layout passed 12 navigation cycles and 564 guarded inputs: 1,692 inputs total. The final build passed 25 forced cold starts across A/B/C; a focusable Home returned in 521–878 ms including ADB overhead, without waiting for all network artwork. All 50 real leave-and-return cycles preserved the same focus and viewport with unchanged content. Temporary test packages were removed and independently checked absent.

The owner shortened the requested soak to one hour while it was running. Navigation stopped after the first saved checkpoint beyond that duration: **3,639.46 seconds, 31 samples and at least 1,278 guarded inputs**. All samples used the same process and C Balanced, with valid focus, populated feeds, no reported refresh/shared-provider errors and saved snapshots. The largest diagnostic sample gap was 121.78 seconds. Post-stop read-only captures confirmed the same process and usable Home.

This is a **separate passed review of the approved one-hour checkpoints**, not a completed two-hour harness run. Host Ctrl-C ended the original 7,200-second process before its final result file was written; that native outcome remains unrecorded. The review derives duration and continuity from saved samples and final captures, and does not invent a final observation-gap aggregate. The revised repeatable harness accepts a one-hour minimum. Its duration tests still reject incomplete requested runs.

Soak PSS ranged from 74,733 to 85,745 KiB (73.0–83.7 MiB), ending at 81,756 KiB. First- and last-quarter means were 81,238.71 and 81,264.86 KiB, a 26.14 KiB difference: no sustained growth was apparent in this window. At the last checkpoint, cumulative rendering showed 3,666 frames, 769 janky frames (20.98%) and p99 27 ms. This lighter mixed navigation/idle route is separate from the before/after comparison below and is not evidence of a locked frame rate or indefinite leak-free operation.

The retained crash buffer was unchanged from the final-candidate baseline. The old Updates crashes remain historical evidence; no new crash or ANR was recorded. Retained exit records contained only the requested cold-start force stops preceding the soak. The installed APK was pulled back and matched the release hash. Home/app preferences and all stored playback timestamps were unchanged. All 21 cached viewing records were retained; their serialization gained empty IMDb/year fields, and two Netflix launch payloads refreshed without changing their destination. C, the default Home resolver and idle recovery state were retained.

The final row-navigation check passed reversal across nine app cards, held-Left containment, intentional sidebar entry and return to the original card. Actual A/B/C Home screenshots were refreshed from the TV. The feature-dialog test evidence is functional; the Home screenshots are not a visual certification of every new dialog.

Both stock-recovery confirmations were opened and canceled on the final build. Read-only preflight recognized the saved ten-package setup. Recovery returned to its idle state and prettTV remained Home. The full restore was deliberately not performed on the owner's TV; success and interruption cases use isolated/fake state.

Updates opened on the real TV and returned normally with Back. Its live check safely rejected the older GitHub release, which lacked the new verification manifest. Policy/transfer tests cover invalid checksums, identity/certificate/version rejection, cancellation and interrupted downloads. A future-version installation through Android's installer has not been exercised end to end on this TV. The standalone signed APK remains the installation route for this release.

### Bounded motion comparison

The same paced C route was warmed once and measured twice on each build. Android `gfxinfo` reported:

| Measure | Previous 0.1.10 | Final 0.2.0 |
| --- | ---: | ---: |
| Rendered frames | 190 | 161 |
| Janky frames | 68 (35.79%) | 53 (32.92%) |
| Median frame time | 17 ms | 17 ms |
| 95th percentile | 28 ms | 27 ms |
| 99th percentile | 28 ms | 31 ms |
| Slow UI-thread frames | 0 | 1 |

The measurements are mixed: jank share and p95 decreased slightly, while p99 increased. Added rows change the content traversed despite the same input script. These short hardware samples do not establish a broad performance improvement or sustained 60 fps. Remaining frame-deadline misses are reported rather than hidden. Raw frame traces, device logs, settings and viewing data remain private.

## prettTV 0.1.10 stock troubleshooting recovery — 2026-10-17

The final Settings item now opens two separate confirmation screens. Both initially focus Cancel and consume held/repeated OK input. A read-only connection check separates them; the final confirmation expires after two minutes. Restoration repeats the checks before writing. The saved baseline and newly generated RSA key stay in private TV storage and are excluded from backups and releases. No external intent or diagnostic command can execute the restore.

The engine restores only recorded, allowlisted package states and the two Private DNS settings, verifies every write, then selects and opens the saved Google TV Home. Missing packages/components, explicit component disabling, wrong identity/firmware/user, and conflicting changes stop recovery. Interrupted attempts preserve already-restored apps and report partial progress; they do not disable a working Home to roll back. No apps, logins or media data are erased. Optional prettTV helpers pause after successful restoration.

139 JVM tests passed, including 27 ADB protocol/authentication/failure tests and 17 restore-engine tests. Engine tests simulate connection loss before and after every mutation, silent write failures, state drift and retry. Four Android recovery tests joined the six existing decoder/worker tests: all ten passed on the TCL in the isolated test package. The importer passed 29 synthetic cases without TV changes. Android lint reports zero errors and 33 warnings. The signed release retains the existing certificate and installs as versionCode 11.

On the actual TV, the private original setup record was imported and the owner's separate local recovery key was authorized. The read-only check successfully verified all ten recorded packages, DNS, original stock Home, current Home, user and device identity. The second confirmation was displayed and canceled with the remote. The first confirmation's default Cancel path was also checked. Real screenshots are included in [recovery documentation](stock-recovery.md). The TCL sends late close packets for completed ADB streams; the client now ignores only bounded, validated packets from known completed streams, with a regression reproducing the observed sequence.

After cancellation, the disabled package list, AdGuard DNS and prettTV default Home were unchanged. The full exported home/app/playback preferences matched before the update, including C Balanced. Temporary test packages were removed. **The full restore was deliberately not executed on the owner's TV.** System-changing success and interruption paths were exercised with fake shell state; live testing covered authorization, preflight and cancellation. This button requires prettTV to run and authorized TCP debugging to remain available; it is not recovery for a device that cannot boot.

## prettTV 0.1.9 scrolling and row navigation — 2026-10-17

Scroll events now coalesce artwork visibility work into at most one scan every 80 ms, with a trailing scan for the settled viewport. Stop and rebuild cancel pending scans. Assigning an unchanged bitmap no longer invalidates a card, and offscreen cards are cleared only when they actually hold artwork. Canvas drawing reuses geometry and cached typefaces. The existing 140 ms focus animation and visual design are preserved.

Horizontal navigation now explicitly selects the adjacent card rather than relying on framework scrolling that can leave focus behind. The sidebar opens only from the logical first card at scroll position zero, on a fresh Left press; held Left cannot spill into it. A stale first-card focus in a scrolled row reveals the start before accepting another Left for the sidebar. Nested sports-app cards and fixed Library rows use the same boundary rules. Horizontal reveal animations last 180 ms, retarget from the current offset and respect disabled system animations. Stop/rebuild completes movement before preserving the viewport.

The sidebar captures its return card before hiding the rail. Hiding the focused rail can synchronously move focus to the hero; capturing first prevents that callback from replacing the intended destination. The on-TV test exposed this issue in the initial candidate, and the final build passed after correction.

The signed release and isolated test APKs build. All 95 JVM tests pass, including seven new row-navigation regressions; Android lint reports zero errors and 32 warnings. Scoped code reviews found no remaining changed-path regressions. v0.1.9 installed in place on the TCL as versionCode 10 with the established signing certificate. The final release APK SHA-256 is `60eaa08664b04d6b6788fa9d6c9455b98609dca11404bef8aafe1dc34340574b`.

The row-navigation test reproduced held Left spilling into the sidebar on v0.1.8. On final v0.1.9 it passed reversal across nine app cards, the held-Left boundary, a fresh Left opening the sidebar, and Right returning to the original app. Two additional rapid-navigation loops and two forced cold starts passed. Three real leave-and-return cycles preserved the selected scrolled app, viewport and Home process. The temporary lifecycle fixture was removed. C Balanced and its artwork were visually checked on the TCL, then Home was returned to its initial position.

The same paced C navigation script warmed the route once and measured two routes on each signed build. Android `gfxinfo` reported:

| Measure | v0.1.8 | v0.1.9 |
| --- | ---: | ---: |
| Rendered frames | 179 | 188 |
| Janky frames | 126 (70.39%) | 60 (31.91%) |
| Median frame time | 46 ms | 17 ms |
| 95th-percentile frame time | 73 ms | 28 ms |
| 99th-percentile frame time | 93 ms | 28 ms |
| Slow UI-thread frames | 109 | 0 |

These are bounded hardware samples with the same scripted input and existing device settings, not sustained 60 fps or broad-device benchmarks. The navigation fix changes focus/scroll behavior, and animations can change frame counts. Remaining frame-deadline misses are visible in the results; no locked-frame-rate claim is made. Raw frame data remains local.

Final diagnostics reported C Balanced, 36 apps, 21 watching records, 60 entries per discovery/catalog row and no feed/app refresh errors. The retained crash buffer contains no prettTV crash; retained exit reasons show package updates and requested force stops, with no crash or ANR reasons. The default Home resolver remains prettTV, and no temporary test packages remain. Every exported app and playback preference matched the pre-test export; Home preferences differed only in transient focus.

## prettTV 0.1.8 stability and failure recovery — 2026-10-17

Failed shared TV-provider reads retain the last successful watching, preview and channel snapshots; valid empty results still clear them. Retained content is filtered against currently installed apps, and cancelled refreshes cannot replace the snapshot. Diagnostics distinguish failed shared-provider reads from an empty feed. App discovery failures retain existing apps and retry while Home is visible. Individual label/icon failures no longer abort the remaining app worker, and cached app-tile dimensions and file size are checked before bitmap allocation.

88 JVM tests pass, including seven new regressions for shared-feed retention, empty responses, recovery, removed apps, individual icon failures, cancellation and app-discovery recovery. The release and isolated test APKs build successfully. Android lint reports zero errors and 38 existing warnings. Builds now pin Ubuntu 24.04 and the Gradle distribution checksum; CI compiles the test APKs and retains unit-test/lint reports. CI does not execute the Android device tests.

Six Android fault tests passed on the TCL using a separate application ID: real large-image downsampling, corrupt/oversized artwork rejection followed by recovery, completion of all 100 requests under queue saturation, cancellation suppressing stale callbacks, loader-close suppression and corrupt app-tile cache recovery. The generated fixture provider runs inside the isolated target's process because the TCL blocks externally hosted test-provider autostart. No TV policy was changed. Both test packages were removed, and release APK inspection found no fixture-provider or instrumentation-runner class markers.

The signed v0.1.8 release installed in place as versionCode 9 with the established release certificate. On the final build, 15 remote-navigation cycles preserved process continuity and valid focus. Ten real leave-and-return cycles preserved the same selected title, vertical/horizontal offsets and Home process; the temporary do-nothing app was removed afterward. Five forced cold starts returned to a focusable Home in 696–788 ms including ADB overhead. Those timings do not measure fully loaded artwork or playback readiness. Navigation PSS samples ranged from 61,801 to 69,789 KiB; this is a bounded sample, not a long-term memory-growth result.

Final diagnostics show C Balanced, 36 apps, 21 watching records, 60 entries in each discovery/catalog row and no refresh/app-discovery errors. The default Home resolver still selects prettTV. No prettTV crash appears in the retained crash buffer; retained exit reasons are package updates and the requested force stops, with no crash or ANR reasons. All temporary test packages are absent. Every exported app and playback preference matched the pre-test export; Home preferences differed only in transient focus. No sustained-frame-rate or indefinite-uptime claim is made.

See [repeatable stability checks](stability-testing.md) for the fault, navigation and lifecycle scripts. Raw device dumps remain local because they may contain viewing history and installed-app identities.

## prettTV 0.1.7 stability fixes — 2026-10-17

Catalog refreshes reuse the last successful results instead of briefly removing catalog cards before loading them again. Failed catalog requests retain previous results; a valid empty response clears them. Disabled, hidden and uninstalled sources remain filtered, and retained new releases still age out. Cancelled refreshes cannot deliver obsolete results; unexpected refresh exceptions are recorded by class name and leave the next refresh scheduled.

Artwork requests cancelled when Home stops are retryable on return. Failed or rejected requests complete explicitly and retry after a 30-second backoff. Compressed image cache writes use temporary files and atomic replacement, with reads/writes serialized across loader instances; decoder-rejected entries are discarded. Cache-write failures do not prevent delivery of downloaded artwork. Worker counts, memory/disk limits and HTTPS-only remote downloads remain bounded. Sidebar focus and its underlying content destination survive screen rebuilds; sports rows now participate in horizontal-position restoration.

81 JVM tests pass, including 11 new regressions covering catalog retention/filtering/expiry, artwork lifecycle/backoff and concurrent cache replacement/recovery. The signed release builds successfully. Android lint reports zero errors and 38 existing warnings; CI now runs lint explicitly alongside unit tests and the release build. The APK retains the established release certificate and installed in place on the TCL as versionCode 8. App preferences and playback history matched the pre-update export; only the saved focus key changed.

Remote-navigation verification hid Prime Video from Your apps, moved focus to its next neighbor YouTube, and retained Prime in Library. Restoring it from Library retained focus on Prime there. The temporary hide was undone. The initial touchscreen-driven probe is not used as evidence of D-pad focus behavior.

A 135-second on-device sample crossed an automatic refresh (completed count 2 → 3). All four discovery/catalog rows retained 60 cards; the selected New releases identity, vertical offset 2186 and horizontal offset 1692 were unchanged in every sample. C subsequently returned to Home with artwork populated. Additional navigation occurred during the Settings-return probe, so that probe does not establish an isolated stop/resume artwork test; cancellation and retry are covered by the regression tests. No prettTV crash was found in the retained crash buffer. These are bounded checks, not a sustained frame-rate or long-duration reliability claim.

### Debugging a future regression

With an authorized ADB connection, `adb shell dumpsys activity dev.zane.pretttv --feed-diagnostics --viewport-diagnostics` reports layout, card counts, focused identity, row offsets, loaded artwork count and completed refresh count. `REFRESH error` identifies unexpected refresh exceptions; provider/network fallbacks can still occur without an unexpected exception. Capture a before/after pair around the failing interaction. For an app crash, inspect `adb logcat -b crash -d` for the prettTV process. Unit-test and lint reports are under `launcher/app/build/reports/` after the README build command.

## prettTV 0.1.6 Pluto recommendation priority — 2026-10-17

Pluto TV now has a separate fallback tier in Suggestions and Discover, after other app/catalog sources and before Stremio. The tier applies before deduplication, allocation and the 60-title limits, and is not overridden by launch recency or a selected-service entry. Trending, app availability and personal viewing-history rules are unchanged.

70 JVM tests pass. Two new regressions cover a large Pluto feed that must not displace other apps, and sparse rows where Pluto remains available after other sources but before Stremio. The signed v0.1.6 APK installed in place on the TCL.

## prettTV 0.1.5 app-hide focus fix — 2026-10-17

Hiding a focused Your apps tile now selects the next surviving neighbor, the previous neighbor at the end, or the empty-row chooser when none remain. Library and sports shortcuts keep their own focus identity because hiding affects only Your apps. Favorite rearrangement follows the same app. The app menu captures its originating focus and scroll positions and applies its action after dismissal. Screen rebuilds restore saved horizontal positions after layout; a pending restoration survives another feed-triggered rebuild. Local app arrangement no longer restarts recommendation fetching.

68 JVM tests pass, including three new focus-policy regressions for middle/first/last removal, multiple missing neighbors, an empty row and reordered apps. The signed update installed in place on the TCL. Navigation reached a scrolled app row and the app options menu, but the hide operation did not apply before unrelated TV navigation resumed. The TV subsequently entered Stremio; remote input was stopped. The hidden-app set remained unchanged at 24 entries. This pass does not claim a completed on-device hide/restore or Library-focus check. Earlier visual captures still describe the unchanged design.

## prettTV 0.1.4 service-first discovery — 2026-10-17

65 JVM tests pass. Eleven new policy tests cover large early free feeds, allocating multiple providers to both Suggestions and Discover, explicit service priority, conservative viewing-activity inference, app intent preservation, unknown/ambiguous popularity matches, cross-row exclusions, 60-title bounds and empty input. The signed v0.1.4 update installed in place on the TCL; C Balanced, the local hero and dark icons were retained.

On-device diagnostics report 60 Suggestions, 60 Discover and 60 Trending entries. Suggestions and Discover start with Disney+ and Prime Video, with Apple TV also present before general/free feeds. Trending starts with Apple TV and Crunchyroll's explicit popular channel; YouTube's published Trending and Cinemeta fill later slots. The observed Netflix and HBO apps publish watching records but no preview recommendation cards. Their account catalogs were not scraped or fabricated. Genre fields were empty in the observed preview feeds, so provider balancing supplies variety without inventing genre classifications.

The native service selector displays the inferred choices and supports a local explicit override or a return to viewing-activity inference. Its UI was captured on the TV. A signed-in state is not claimed: shared progress is only a default signal. During remote checks, unexpected navigation was observed; inputs were paused to avoid overlapping with the owner. This pass does not claim an isolated save/restart interaction test. The new row capture at docs/screenshots/discover-row.png shows non-anime films and series, provider labels, focus treatment and the Suggestions → Discover → Trending order in C. All layouts use the same row builder and policy; this pass did not repeat the earlier A/B/C layout checks.

The shared-provider scan remains bounded at 5,000 scanned records, 2,000 accepted cards and 300 per app, with 60 visible entries per row. Public channel labels and weights are read; opaque provider-internal identifiers are no longer decoded. Existing cached catalogs are reused. Optional Android special-access settings and viewing-history ordering were not changed.

## prettTV 0.1.3 Discover row — 2026-10-17

Added Discover immediately below Suggestions and above Trending through the shared row builder used by A/B/C. The row reuses the current cards, D-pad behavior, horizontal scrolling and artwork limits. It selects up to 60 additional titles from already-fetched provider/catalog data, excluding normalized titles in watching, Suggestions, Trending and New releases. Preferred app sources precede Stremio; duplicate titles retain the preferred source. No additional network endpoint or request was added.

54 JVM tests pass, including cross-source duplicate/exclusion behavior and applying the row limit after exclusions. The signed update installed in place. The TV supplied 60 Discover entries; UI hierarchy checks verified Suggestions → Discover → Trending in all three layouts. Moving six cards right in Discover reached an additional title and scrolled the row. C and the Home position were restored afterward. The new screenshot is docs/screenshots/discover-row.png. Dark app icons, viewing-history behavior and special-access settings were unchanged.

## prettTV 0.1.2 dark-tile correction — 2026-10-17

The reported rollback was checked against the installed package: v0.1.1 and C Balanced were still selected. The certificate reinstall had removed cached artwork; unmapped Disney+ and HBO then rendered their installed bright/gradient banners. Version 0.1.2 uses an attributed Disney+ foreground vector on the common dark ground and an unmodified bundled white HBO logo. Cache generation icons4 invalidates the former tiles. Neither mapped app now depends on installed artwork.

The signed v0.1.2 update installed in place without uninstalling or resetting preferences. The new cache generation rendered dark tiles on the TV; A/B/C captures were refreshed, with C restored and The Night Agent still the hero. The same 52 JVM tests pass. An additional Android cache-only clear command did not return, so its client was stopped; explicit completion of that command is not claimed. Source-level cache independence and the new-generation TV renders are the evidence. No launcher behavior, viewing-history policy or special-access state changed.

## prettTV 0.1.1 validation — 2026-10-17

Signed release built with JDK 17, Android SDK 34 and build-tools 35.0.0. All 52 JVM tests pass: 16 app-model, 14 content-policy, 7 sports-policy, 7 playback-policy and 8 RBTV match-policy tests. The PowerShell and Bash installers passed eight isolated checks: fresh installs include both APKs; existing RBTV is preserved; launcher-only installs one APK; a bad companion checksum installs neither. The restore script passed syntax checks but was not run against the configured Home.

On the owner's TCL Android 14 / 32-bit ARM TV, package dev.zane.pretttv was installed and selected as default Home. Local settings were backed up, migrated and compared before the previous package was removed. Title filters, favorites and C selection survived. After force-stop and Home, C returned with The Night Agent as the hero. That title was corrected using the owner's explicit report and the local “I watched this most recently” control; it is not evidence of automatic playback tracking.

All three native layouts were captured on the TV at its 1920×1080 UI resolution. The sidebar is hidden initially, appears on Left at the first card, and closes on Right, Back or a destination. Library opens the full app grid. A temporary Prime Video hide test reduced Your apps from 34 to 33, left Prime accessible in Library, and offered “Show in Your apps row” there. Restoring it returned the row to 34. Search and content-history independence are verified in source; no streaming account state was changed. C and normal font scale 1.0 were restored. The prior 1.3 font-scale capture also passed the scoped visual review. Disney's fine wordmark now receives appropriate logo bounds instead of showing as a tiny square.

The default Home resolver points to dev.zane.pretttv/fi.jommi.rivi.MainActivity. Observed sources supplied 21 watching records, 59 app suggestions, 59 Trending entries, 60 New releases and 34 sports fixtures. Providers may publish stale or shared-account records. Provider timestamp refreshes alone cannot displace locally confirmed playback; initial provider ordering is retained until there is local confirmation. Missing usable metadata is not guessed.

Optional Notification access for identifiable active media sessions and Accessibility access for bounded RBTV game matching remain disabled on the owner's TV pending approval. Their policies are unit-tested, but end-to-end automatic recency and matching-game handoff are **not hardware-verified**. The ordinary RBTV app-opening fallback was verified. No stream playback or server-selection success is claimed.

The screenshots in docs/screenshots are fresh, unedited captures of A, B, C and the expanded sidebar, explicitly authorized for public publication. Private settings exports, device dumps, signing keys and raw traces remain outside Git. Source art and provider content retain their owners' rights.

### Performance evidence limits

The panel is 60 Hz; sustained 60 fps is not claimed. A historical 0.1.0 navigation sample rendered 262 frames with 13 janky frames (4.96%), median 25 ms, p95 36 ms and p99 42 ms. That single scripted sample is not a benchmark of 0.1.1. No new performance claim is inferred from the UI screenshots or unit tests. The native renderer retains bounded artwork workers/caches and avoids a web UI or continuous animation.

Final scoped visual confirmation: Disney+ sizing resolved across A/B/C; the expanded sidebar capture includes Library and focused Home; no material regression observed. The final release was re-signed with a prettTV-named certificate and reinstalled with every backed-up preference value compared successfully. Signing-certificate SHA-256: `46fecddaaff65d9500c6cfddee90e07c6ca3fe7941c8a405829258b5e7ff9ded`.

Publication checks: GitHub Actions passed for release source bff82461576d81404589a63ab51acd85ad42ae51. Public repository access and an unauthenticated download of prettTV-0.1.1-with-RBTV.zip were verified; the downloaded SHA-256 matches e7f02cc4bb0f7ea955cd2382b823ebd19d6850d3b874091efb0fecd70743fae3. The retired release/tag and old-named branch were removed after local backups. Historical source commits remain intact.
