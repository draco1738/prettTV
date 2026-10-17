# Credits and third-party notices

## Rivi — Jommi

Luma TV is derived from **[Rivi](https://github.com/jommi9/rivi)** by Jommi, version **0.15**, commit **e4ca2ea9be442e17aa54b94b96bca4762d9607c1**.

Copyright (c) 2026 Jommi. Used under the MIT License. The complete original copyright and license notice is retained in [LICENSE](LICENSE).

Rivi supplies the native Android project foundation, app discovery and banner caching, favorites/hidden-app/recency model, remote-key handling pattern and upstream tests. Luma replaces the main home composition and adapts the native focus/remote pattern in LumaCard. Rivi's original tile, shadow, side-menu and optional Home-key service sources remain in the inherited tree; the Home-key service is not registered in the Luma APK. New files and modifications are identified in the commit history; inherited history and source provenance are retained. The original [README](docs/upstream-rivi-readme.md) is preserved without transferring its benchmark claims to Luma.

## PLUI — Smartago

[Premium TV Launcher UI](https://github.com/smartago/plui-tv-launcher) informed the panel depth and composition study. It is a **design reference**, not a source-code dependency. No decompiled PLUI implementation or PLUI artwork is redistributed in this repository or intended for the Luma APK. Luma is independent and is not endorsed by Smartago.

## Google TV / Android TV — Google

Stock Google TV informed the useful content-row hierarchy. The app uses public Android TV APIs for content published by installed apps. Google TV's proprietary launcher implementation and branding are not part of Luma.

## Kotlin — JetBrains and contributors

Kotlin 2.1.0, retained Kotlin standard-library code and JetBrains annotations 13.0 are under the Apache License 2.0. Source: https://github.com/JetBrains/kotlin/tree/v2.1.0 and https://github.com/JetBrains/java-annotations . The complete Apache license is included in the APK's About menu. Android and Java system libraries are provided by the device; the Android SDK, Gradle, JDK and decompiler are development tools, not bundled TV features.

## TVmaze and show artwork

Optional general show discovery uses [TVmaze's public API](https://www.tvmaze.com/api). TVmaze licenses API data under [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/); show records retain their TVmaze source URLs. Luma selects and reformats title/genre information and selects episode stills for landscape artwork; any adapted TVmaze data remains under CC BY-SA 4.0, separate from the MIT application code. Hold OK on a show for its source, or use About → TVmaze. This is general discovery, not access to the user's streaming accounts. Show artwork, streaming-service names and app icons remain the property of their respective rights holders. Installed-app icons come from those apps; catalog imagery is fetched from its source rather than copied out of PLUI. There is no endorsement by these companies.

## Cinemeta / Stremio and Metahub

Optional Trending and New releases use Stremio's public [Cinemeta catalog](https://v3-cinemeta.strem.io/manifest.json), with artwork URLs supplied by the catalog (including Metahub). Luma reformats those records and links titles to the installed Stremio app. Cinemeta's Popular order supplies Trending; it is not an independently measured trend score. New releases uses supplied release dates within the past 120 days. These requests disclose the device's IP and requested catalog/artwork identifiers to those services. No account tokens or viewing history are sent by Luma. No Stremio code is bundled; third-party catalog content and artwork retain their respective rights. This project does not use WatchHub's restricted availability API. The purple S badge identifies Stremio destinations.

Installed Netflix, Prime Video and YouTube banners are transformed locally into dark tiles, keeping recognizable logo accents. Their trademarks and original art remain owned by their respective rights holders; transformed banners are not bundled in the repository or APK.

## Real TV previews

The A/B/C PNGs in `docs/screenshots` are unedited screenshots captured directly from the owner's TCL TV and published at the owner's explicit request. Content, network and app artwork remains owned by the relevant rights holders. These captures show the native implementation, not generated design renders. Original design comps are retained locally as review references.

## Test and build dependencies

The inherited JVM tests use JUnit 4.13.2 (Eclipse Public License 1.0). The Gradle wrapper is distributed under Apache 2.0. These are development dependencies. The production UI uses Android Views and adds no third-party UI framework.


## ESPN sports schedules

Live and upcoming fixtures use ESPN's public scoreboard endpoints, including https://site.api.espn.com/apis/site/v2/sports/football/nfl/scoreboard . Network logos come from URLs supplied by ESPN. These are runtime data/artwork requests; no ESPN implementation is bundled. The endpoint is not a contracted API and may change. Schedules, scores, team/network names and logos remain the property of their respective owners. There is no endorsement. Responses are cached briefly; stale entries are labeled and expire after 15 minutes. No stream URLs, account data, or playback entitlement is supplied by this integration.

## Separately installed companion app

RBTV+ is a separate third-party application, not part of Luma's MIT source or APK. Its installer is attached only to the owner's private release as requested. See docs/companion-apps.md for source, version, signature and SHA-256. No RBTV source code is copied into Luma. Installed version 3.0.320 has no verified external game/channel interface; Luma opens the app and leaves channel selection to the user.
