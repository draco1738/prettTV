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
