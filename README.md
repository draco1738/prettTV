# Luma TV

A small native Android TV launcher built on [Rivi](https://github.com/jommi9/rivi), by Jommi. Three working layouts share the same apps, viewing progress and discovery feeds. **C — Balanced** is the default.

- **A · Cinema:** wide feature with a left navigation rail.
- **B · Quiet:** apps first, followed by content rows.
- **C · Balanced:** large feature, two discovery cards and a compact app row.

Both Continue watching rows are sorted newest first. The large feature chooses the most recent **published playback timestamp across apps**, including Stremio. Below the apps are **Continue watching · Shows**, **Continue watching · Movies**, **Live sports**, **Suggestions**, **Trending** and **New releases**. Content rows scroll horizontally with up to 60 titles. The finite app row contains every included launchable app, favorites first, with a clear beginning and end. Home resets it to the first app.

Hold OK on a title to hide it from the large feature, Continue watching, or both. **Settings → Hidden titles** restores titles individually. Filters stay on this TV and do not alter shared streaming accounts. Matching uses normalized titles across sources; identically named remakes share a filter.

## Install and use

Download the APK from [Releases](https://github.com/draco1738/luma-tv/releases). Android TV 8.0 / API 26 or later is required. Install with `adb install -r Luma-TV-0.1.0.apk`, then launch `dev.zane.lumatv/fi.jommi.rivi.MainActivity` or select Luma in the apps screen. Allow **TV listings** when prompted so Luma can read shared program rows. Luma needs no streaming account login.

Settings → Choose apps one by one walks through every launchable app, including sideloaded apps with ordinary Android launch entries. Projectivy and LTvLauncher are excluded. Settings → Live sports apps chooses shortcuts for that row. RBTV+ is the default sports app; SportioTV and HD Streamz were removed at the owner's request.

Use arrows and OK; hold OK for app arrangement or title controls. Back returns to the top. Menu opens settings: layout, discovery, HDMI inputs, system settings, hidden titles and credits. Home selection uses Android's normal Home mechanism without an accessibility interception service.

## What the feeds mean

Continue watching reads Android TV Watch Next and app-published continuation channels. These are shared data sources used by TV launchers, not a copy of Google's private account database. Coverage varies, shared profiles can contribute other people's titles, and missing or stale timestamps cannot be reconstructed. Unknown content types are not guessed into Shows or Movies. If no eligible watching item exists, the hero falls back to discovery.

Suggestions prefer app recommendations, with optional TVmaze discovery. Priority uses published progress and local app launch recency, not access to login credentials. Stremio appears after other sources in mixed discovery rows and has a purple **S** badge. Trending is Cinemeta's Popular ordering; New releases uses supplied dates within the past 120 days. Catalog presence does not guarantee a playable stream or subscription entitlement. Cinemeta cards link to Stremio; app-published cards use their provider's intent.

Live sports pulls live/upcoming NFL, college football, NBA, NHL, MLB and Premier League fixtures from ESPN's public scoreboard feed. Cards use supplied broadcast logos and kickoff times; live cards include scores. Requests run while Home is visible, with a two-minute cache. Data older than five minutes loses its LIVE label/score, and records expire after fifteen minutes. These are uncontracted endpoints and may become unavailable.

**A network logo is not a stream link.** RBTV+ 3.0.320 does not expose a verified external game/channel link. Clicking a game opens RBTV+ and identifies the broadcast to find manually; channel availability is not guaranteed. Hold OK for details. App-published live cards still use the provider's own intent when supplied. Discovery off disables the public sports feed too.

The [private release](https://github.com/draco1738/luma-tv/releases/tag/v0.1.0) also contains the requested RBTV+ companion installer and checksums. See [version, provenance and review limits](docs/companion-apps.md). The companion app is separate from Luma and its MIT license.

The UI uses native Views/Canvas, two artwork workers and bounded caches. Visible cards request artwork; off-screen cards release bitmap references. Each app appears once in the finite horizontal row, with shared artwork. Netflix, YouTube and Prime banners are transformed locally into dark tiles with colored logo accents.

## Privacy and recovery

Luma has no analytics SDK, advertising identifier access, account collection, root operation or firmware modification. It requests Internet and TV-listings access. Catalog and provider artwork requests reveal your IP address and requested URLs to their servers. Discovery can be disabled. App order, hidden titles and launch recency stay local. Luma does not remove ads inside other apps.

The APK and installer do not disable system launchers. The owner's separate TCL setup disabled Google TV Home and its setup component so Home resolves to Luma. **Restore a working Home before uninstalling Luma.** With ADB:

```sh
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Select/open the returned Google Home component and verify it before uninstalling. `./restore.sh --stock-home` performs checks before removing Luma. Plain `./restore.sh` requires another enabled Home. Private device rollback records are excluded from Git. These scripts do not undo other app or DNS changes.

## Build

Use JDK 17, Android SDK platform 34 and build-tools 35.0.0:

```sh
cd launcher
./gradlew :app:testReleaseUnitTest :app:assembleRelease
```

On Windows use `gradlew.bat`. Output: `launcher/app/build/outputs/apk/release/app-release.apk`. Configure `launcher/local.properties` with your SDK path. Private release signing uses ignored `launcher/signing.properties` with `storeFile`, `storePassword`, and `keyAlias`; otherwise builds use the local debug key. CI artifacts cannot update the owner's release-signed installation. Signing secrets are never committed.

37 JVM tests cover app ordering, content filtering, progress, chronology, release dates, long-row bounds and independent title hiding. See [validation notes](docs/validation.md). Upstream Rivi benchmarks are not Luma benchmarks.

## Captured on the TCL TV

These are unedited screenshots of the actual native launcher on the owner's TV, published at the owner's explicit request. C remains selected on the TV. The Luma wordmark/credits are at the bottom of the page; subtle borders separate cards from the dark background.

### A · Cinema

![A — Cinema on the TCL TV](docs/screenshots/A-cinema.png)

### B · Quiet

![B — Quiet on the TCL TV](docs/screenshots/B-quiet.png)

### C · Balanced

![C — Balanced on the TCL TV](docs/screenshots/C-balanced.png)

## Credits

Derived from **Rivi v0.15**, Copyright (c) 2026 Jommi, under the [MIT License](LICENSE). Source history and the [upstream README](docs/upstream-rivi-readme.md) remain intact. [PLUI](https://github.com/smartago/plui-tv-launcher) and stock Google TV informed the design; their proprietary implementation and artwork are not redistributed. TVmaze, Cinemeta, Metahub, ESPN, Kotlin and installed-app artwork are credited in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), also available inside the APK.
