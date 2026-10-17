---
version: 1
slug: "app-src-main-kotlin-fi-jommi-rivi-mainactivity-kt"
primary_target: "launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt"
related_targets: ["launcher/app/src/main/kotlin/fi/jommi/rivi/PrettTvCard.kt"]
---
## Direction contract

THESIS: A calm television home that puts a useful feature, apps, and suggestions within one view.

OWN-WORLD: Sage-black ground, ivory type, subtle card outlines, real landscape stills, and colored app marks. System sans-serif preserves native TV legibility. The exact brand is prettTV; branding and credits appear only at the footer.

STORY: Find a show or app with a remote, see the focused action, and open its provider.

FIRST VIEWPORT: All A/B/C layouts use a 1808 px content width with 56 px left and right margins at the 1920 × 1080 reference size. C has a 1132 × 486 hero, a 20 px gap, and a 656 × 486 shared Discover panel with two 608 × 184 compact rows. Your apps follows as a finite row of 208 × 94 px dark tiles with 18 px gaps; up to eight complete tiles fit at the reference width. A and B also use 208 px widths, retaining heights of 116 and 176 px respectively. The Library grid remains six columns with integer (inner - 5 × 18) / 6 widths and 154 px heights. The hero action sits at lower left.

NAVIGATION: The left sidebar is HIDDEN BY DEFAULT in every layout. Left from the first app or content card reveals the fixed overlay rail and dims content to alpha 0.45. The six destinations are Home, Shows, Library, Inputs, Search, and Settings. Right, Back, choosing a destination, or returning focus to content hides it and restores alpha 1.0. Library opens the complete Library · All apps grid; Your apps remains a separate customizable horizontal row. No top navigation, top wordmark, or rail monogram.

FORM: C Balanced remains selected; A Cinema and B Quiet remain alternatives. The original choice used seed 2a08e375. The owner retains the hidden sidebar and full-width content. The approved 0.2.1 patch narrows Your apps cards and adds a finite focus spotlight with bounded artwork drift, preserving the rest of the native composition.

FINISH: This update documents the 0.2.1 native card source without claiming new screenshots or device validation. Historical 0.2.0 ADB A/B/C captures show that release's home and B shows Watchlist below Movies. Its validation reports 202 JVM tests and 35 Android tests passing, with a passed final functional gate and user-approved one-hour soak checkpoint review; see docs/validation.md. The original two-hour run was stopped at the user's request and its native outcome remains UNRECORDED. Those results do not validate this card patch. The narrower app row, new focus motion, feature dialogs, details, and updater are not certified by the retained captures.

## Runtime constraints

Operate mode; TV remote from couch distance. Geometry scales from a 1920-wide reference. Hero type is 58 reference pixels, section type 24, and card titles 29; system font scale also applies. Full accessible labels accompany visual truncation. Card focus scale lasts 140 ms; spotlight opacity and up to 6 reference pixels of rightward artwork drift use a separate 180 ms transition. Horizontal row travel remains 180 ms. Disabled animations apply final states immediately. Changed-row refreshes preserve unaffected cards and the viewport where possible. Historical 0.2.0 functional validation and its one-hour soak review retain their scope in docs/validation.md.

The native spotlight is a cached ivory RadialGradient clipped inside every card mode, including Library tiles. It lies beneath text and HERO/SHOW scrims, with no external glow, idle pulse, or looping motion. Focus drift moves only artwork within the card: landscape images reserve 12 reference pixels of overscan, portraits cap movement to available side space, app logos preserve their existing fit, and fallback app artwork reserves 6 pixels at each side. Focus reversals retarget from the current amount. Window hiding, aggregated visibility loss, and detachment cancel both focus animators and settle the current focus state; animation starts only while attached, shown, and in a visible window. These constraints are source-derived, not new measured results.

Use real Android TV content and attributed app artwork. General discovery is labeled and does not imply personal history or subscription availability. No fictional scenes, invented progress, or episode state are introduced.

The application ID is dev.zane.pretttv. The implementation workflow reports that owner preferences were exported and imported successfully and the previous application was removed. Preference exports remain local and excluded from publication. Device-specific Home setup and rollback records remain separate from the launcher design.

## Accepted content and artwork requirements

The finite Your apps row contains each included app once, resets to its beginning on Home, and has a clear end. Hold OK toggles Hide from Your apps row or Show in Your apps row. Hiding affects only this horizontal row: Library retains every discoverable app, and search, suggestions, and viewing history are unaffected. Settings → Restore apps to Your apps row clears all row-only hide choices. An empty row offers Choose apps for this row. Shows and Movies have separate Continue watching shelves. The default order after Movies is Watchlist, conditional New episodes, Live sports, Suggestions, Discover, Trending, and New releases. User row preferences may reorder or hide these content shelves. Small, Standard, and Large show 5, 4, or 3 cards at heights of 190, 218, or 270 reference pixels. Suggestions, Discover, Trending, and New releases have 20/40/60 title limits, defaulting to 60. Two compact side-panel cards remain discovery. Stremio-last ordering applies only to discovery; a purple S identifies Stremio.

Personal rows and the hero use local playback evidence. Provider timestamps seed a frozen baseline; later timestamp refreshes alone do not prove playback. Optional supported media sessions can update local recency. Hold OK → I watched this most recently supplies a manual correction. Missing or ambiguous metadata remains unknown. Playback tracking requires Android Notification access but does not read or store notification text.

Hold OK can hide a title from the hero, Continue watching, or both. Settings → Hidden titles restores individual titles. Filters stay local and do not change shared accounts. Discover launchable sideloaded apps, exclude unwanted launchers, and retain app-by-app inclusion controls.

AppIconPack uses twelve unmodified Homarr Dashboard Icons PNGs on a native dark tile: Netflix, Prime Video, YouTube, Apple TV+, Crunchyroll, Stremio, Paramount+, Peacock, Pluto TV, Hulu, NordVPN, and HBO. Sources, SHA-256 values, and modification flags are recorded in launcher/app/src/main/assets/app-icons/sources.json; LICENSE.txt is adjacent. HBO uses its attributed light PNG for mapped packages; Disney+ uses the native ic_disney_dark drawable composed on the same dark ground. Unmapped apps also retain installed artwork or native label fallback.

Live sports uses authentic ESPN events and broadcast logos or names, with Opens RBTV+ when selected. Optional accessibility assistance matches both teams and kickoff time in supported RBTV 3.0.320 layouts for NFL, college football, NBA, and Premier League. It runs only after an explicit game selection, for at most 30 seconds, and stops before server selection. Unsupported or unmatched events open the app for manual selection. No game, channel, server, stream availability, or verified deep link is promised.

## Historical version 0.2.0 extension

Watchlist is a local saved-title shelf immediately after Movies by default, including its empty Save something for later state. Hold OK adds/removes titles; long lists page in groups of 60 through newer/older saved-title actions. Confirmed later local viewing may remove a saved title with an Undo entry. Library and viewing history retain their separate purposes.

New episodes follows Watchlist only when verified release alerts exist. Follow/unfollow actions for series, check status, and alert dismissal use native menus. The shelf says Episode released · availability unconfirmed. Checks run while Home is open and do not promise streaming availability.

Settings → Customize rows controls ordering, visibility, card sizes, eligible title limits, and Restore default rows. About this title / source uses a scrollable native details dialog with synopsis, actual progress, availability wording, provider actions, and Watchlist save/remove. Undo in Settings identifies the latest recorded action; it is bounded session history. Not interested applies to recommendation shelves without removing Watchlist or history, and feedback can be reset.

Favorite teams use native league and checkbox dialogs. Optional kickoff reminders reuse a tonal, focusable banner below the header with Starting soon and OK to dismiss. Offline Home reuses cached content and shows its age in muted header text; cached recommendations expire after seven days. These states preserve the current palette, native type, card shapes, and remote focus language.

Settings → Updates opens a separate native scrollable screen with explicit check, download, install, cancel, and back controls. It reuses sage-black/ivory/pale-sage colors, uses native dp/sp controls, and requires user action plus Android installation confirmation. Home never opens it automatically. New native menus, details, reminder states, and updater dialogs are not visually certified by the current A/B/C captures.

Historical evidence: docs/screenshots/A-cinema.png, B-quiet.png, and C-balanced.png are actual 1920 × 1080 ADB captures for 0.2.0. They show preserved home styling; B visibly includes Watchlist below Movies. Existing sidebar and other captures retain their earlier provenance. Screenshot pixels are preserved. That release's functional test totals are 202 JVM and 35 Android; its final functional gate and user-approved one-hour soak checkpoint review passed as recorded in docs/validation.md. This evidence does not cover the 0.2.1 card changes. Uncaptured feature surfaces remain outside visual certification; the original two-hour run's native outcome remains UNRECORDED.

## Publication and evidence

The owner requested a public GitHub repository when this work is finished, with current prettTV branding and the current package ID throughout publishable files. Actual selected A/B/C launcher captures may be published. Device dumps, account data, preference exports, and other-app screens remain excluded. Retained 0.2.0 A/B/C actual ADB captures occupy docs/screenshots/A-cinema.png, B-quiet.png, and C-balanced.png; this documentation update adds no captures. Prior hide/Library/restore, cold-Home, and sidebar evidence keeps its historical scope; optional helpers remain untested end to end. See docs/native-design-evidence.md for the historical review scope and evidence limits.
