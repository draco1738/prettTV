# Home controls · 0.6.4

This version is installed and verified on the owner’s TCL. Public release publication is separate and remains subject to exact-source CI gates. [Validation record](validation.md).

## Reclaimed Home space

C Balanced retains the black horizontal-navigation identity, 1000-pixel hero, 40-pixel gutter and three 240×360 Discover pictures. Browse shows and Different picks share the heading. At normal system text size the stage is 502 reference pixels rather than 567. Named app shortcuts retain the finite row and its original 208×94 icon cards. The first standard Continue watching row is intended to fit the normal 1920×1080 viewport; larger interface/text settings may scroll lower rows rather than clip or reduce portraits. A/B remain available.

## Local viewing choice

Settings → Shows & recommendations → Viewing history offers Combined (existing default) and This TV only. The latter uses supported local media-session observations and explicit recent-viewing corrections for the hero and Continue watching. It does not identify people, enable Notification access, or change streaming accounts. Unsupported/missing app metadata may need Hold OK → I watched this most recently. With no local record, the hero stays empty rather than substituting a recommendation. Switching modes retains the histories.

## Different picks

The heading action deliberately selects another available batch in existing service-priority order among unseen titles. It uses the full bounded local recommendation pool, not just the six-title artwork lookup window, and creates no additional network enrichment pass. The initial batch can fill and adopt final service ranking while the ordinary catalog loads. After it settles, or an explicit rotation occurs, rebinds preserve the batch and resolve fresh metadata. Removed recommendations disappear immediately. Exhaustion says no more unseen picks rather than cycling or inventing titles.

Undo in the top bar restores the previous still-eligible picks. It never resurrects a removed/dismissed title. State is bounded and session-local; restarting Home or intentionally changing source settings can reset it. A short catalog produces fewer than three cards. The long Browse shows shelves remain accessible.

## Portable settings

Portable copies contain private preferences, title history and configured weather location, not streaming credentials or Android recovery keys. A readable validated preview and fresh confirmation are required before any import. Keep an off-TV copy on E: to survive uninstall. USB/document-picker capability is firmware-dependent: this TCL currently resolves Android document intents only to a framework stub and has no mounted USB volume. Do not describe that as working USB support. A real owner settings export has been copied off-TV and readback-verified on E:. That is export evidence, not proof of a user-confirmed production restore.

## Verification scope

The final integrated source passed 607 JVM tests across 48 suites and 202 native Android checks, with zero lint errors and 29 warnings. The signed in-place upgrade and pulled-back APK hash were verified, with Home/app/personalization preferences retained except navigation focus and automatic playback observations. Actual production navigation and sleep/wake checks passed. A real off-TV backup was staged, read back and shown in the native validated preview; Cancel was selected and used, with no production restore. Host fault injection and isolated native restores do not prove power-loss atomicity or USB support. Scoped suites overlap these totals.
