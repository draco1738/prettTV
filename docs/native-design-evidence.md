# Native design evidence and exceptions

C Balanced remains the selected design, with A Cinema and B Quiet retained. The original selection used seed 2a08e375. The owner accepted the rebuilt C and then supplied explicit changes: dark app tiles with color accents, real personal media, separate Shows and Movies rows, local title hiding, long discovery shelves, a finite app row, and Live sports below Movies. The two small side-panel cards remain discovery.

## Current owner override and source evidence

The sidebar must be hidden by default in every A/B/C layout. Current MainActivity source uses a 1808-pixel inner width and 56-pixel left and right insets. C uses a 1132 × 486 hero, a 20-pixel gap, and a 656 × 486 Discover panel containing two 608 × 184 compact rows. The rail is a sibling of the content ScrollView, starts at View.GONE, and appears as a fixed overlay when Left is pressed from a first card. Content alpha becomes 0.45 while open. Right, Back, choosing a destination, or returning focus to content dismisses the rail and restores alpha 1.0. All six destinations are on the left; there is no top navigation. Library opens the complete Library · All apps grid, while Your apps is a separate customizable horizontal row.

App hiding is scoped only to the horizontal Your apps row. Library retains every discoverable app, and search, suggestions, and viewing history are unaffected. Hold OK toggles Hide from Your apps row or Show in Your apps row. Settings → Restore apps to Your apps row clears all row-only hide choices. Search, suggestions, and history independence are source-verified; separate device sessions for those paths were not performed.

The exact display brand is prettTV, with branding and credits at the footer only. The application ID is dev.zane.pretttv. The implementation workflow reports successful export/import of the owner's preferences and removal of the previous application. Those exports remain local and excluded from publication.

PlaybackHistory freezes provider timestamps as an initial baseline. Provider refreshes alone do not promote a title. Optional supported active media sessions and the manual I watched this most recently action update local recency. Notification access is required for the optional tracker, which does not read or retain notification text. Missing or ambiguous title metadata stays unknown; complete personal watch history is not claimed.

Optional RBTV accessibility assistance runs only after a selected game, is limited to 30 seconds in supported RBTV 3.0.320 layouts, and matches both teams plus kickoff time. It stops before server selection. Unsupported or unmatched events use manual selection. Game availability, channel availability, a verified per-game deep link, and stream playback success are not guaranteed.

Installed v0.1.1 built with 52 tests passing. On the TCL TV, Prime Video was hidden through Hold OK → Hide from Your apps row: Your apps changed from 34 to 33. Prime remained in Library, reached through the sidebar, and offered Show in Your apps row; restoring it returned the count to 34. Cold Home after force-stop resolved dev.zane.pretttv to C with The Night Agent in the hero. The prior font-scale 1.3 capture review passed. Final scoped visual confirmation passed: Disney+ sizing is resolved across A/B/C, the expanded sidebar capture is valid, and no material regression was observed.

Optional playback tracking and RBTV accessibility assistance remain off. No end-to-end playback-tracking or RBTV matching test is claimed. Source behavior, confirmed device actions, and visual review scope remain distinct.

## Artwork provenance

The original generated PNGs illustrate layout with fictional content. Flattening those scenes into the interface would contradict the request for actual personal media. Runtime provider imagery and real app marks are intentional native substitutions. System sans is the implemented TV typeface; the automated browser-font guess of Salsa is not an accepted specification.

AppIconPack maps eleven unmodified Homarr Dashboard Icons PNGs to Netflix, Prime Video, YouTube, Apple TV+, Crunchyroll, Stremio, Paramount+, Peacock, Pluto TV, Hulu, and NordVPN. The PNGs remain unchanged. Native composition places them on a dark ground and scales them within the tile. launcher/app/src/main/assets/app-icons/sources.json records a pinned source URL, SHA-256 value, and modified:false for each image; the adjacent LICENSE.txt preserves the upstream Apache 2.0 license. Disney+ and HBO Max retain installed artwork because the pack candidates did not fit. Other unmapped apps also use installed artwork or label fallback.

Final actual 1920 × 1080 captures from the TCL Android TV were recorded as prettTV-final-A.png, prettTV-final-B.png, prettTV-final-C.png, and prettTV-final-sidebar.png under the E: tool root. Their repository destinations are docs/screenshots/A-cinema.png, B-quiet.png, C-balanced.png, and sidebar.png, replacing the prior A/B/C set during final packaging. Original comp references and prompts remain local for provenance. A native screenshot diff is a drift report, not a passed pixel-fidelity gate.

## Historical review scope

The earlier independent review accepted the composition, header, feature adaptation, shared side panel, app geometry, ground, native typography, focus, and hide menu inspected at that time. It requested a darker SHOW-card metadata scrim and an honest evidence record. The implemented scrim reaches alpha 0xE6 at 58% of card height and 0xF2 at the bottom, supplying a dark ground through the metadata band. The reviewer scored those two fixes resolved and returned ship at that scope, with no material regressions in the inspected changes.

That verdict is historical and scoped. It is not whole-surface certification. Current device evidence covers the hide/restore and cold-Home actions above; one bounded visual confirmation is in progress, and optional helpers remain unverified end to end. The local build-phase tool closed comps and spec but remains at plates; later automated phases are pending. Original static-plate/font prescriptions do not describe this native implementation. No native HTML/CSS detector was run, and unrun gates are not marked passed. See docs/validation.md for the recorded checks.

## Publication contract

The owner requested a public GitHub repository when the work is finished. Publishable files use current prettTV branding and the current package ID. Selected actual A/B/C launcher screenshots may be published; device dumps, account data, preference exports, and other-app screens remain excluded. This record states the publication target without claiming that repository visibility has already been changed. The current device proof is limited to the actions and capture provenance recorded above.
