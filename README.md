# prettTV

**A quieter home for Android TV.** Your apps, recent viewing and something new to watch, in three native layouts built for a remote.

[**Download**](https://github.com/draco1738/prettTV/releases/latest) · [Install](#install-pretttv) · [Layouts](#three-layouts-one-home) · [User guide](docs/usage.md) · [Documentation](docs/README.md)

[![Release](https://img.shields.io/github/v/release/draco1738/prettTV?style=flat&color=adc1b6)](https://github.com/draco1738/prettTV/releases/latest) [![Build](https://github.com/draco1738/prettTV/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/draco1738/prettTV/actions/workflows/build.yml) [![License: MIT](https://img.shields.io/badge/code-MIT-adc1b6)](LICENSE)

![prettTV 0.4.0 in C Balanced: persistent top navigation, a recently watched title, two discovery cards, dark app tiles and Continue watching.](docs/screenshots/C-balanced.png)

*C · Balanced, running on the TCL TV. Every screenshot on this page is an unedited capture of the released 0.4.0 build.*

## Make Home yours

- **Return to your viewing.** A large resume feature in Cinema and Balanced follows the latest eligible viewing record. Separate Shows and Movies rows bring recent progress forward, with episode details and remaining time when an app supplies them.
- **Keep every app within reach.** A single finite Your apps row has a clear beginning and end, rounded dark cards and color accents. Arrange favorites or hide a shortcut from this row while keeping it in Library.
- **Find something worth watching.** Suggestions, Discover, Trending and New releases offer up to 60 titles per row. Choose the services to prioritize, hide shared-account titles and dismiss recommendations you do not want.
- **Save and follow.** Keep a dedicated local Watchlist, follow identifiable shows for new-episode alerts, and undo supported recent changes.
- **Choose your own layout.** Cinema, Quiet and Balanced share persistent Home, Shows, Library, Inputs, Search and Settings navigation. Optional location, weather and time sit together at the top right.
- **Move without losing your place.** Focus uses a subtle spotlight and brief artwork movement. Selected-title details occupy reserved space; refreshes retain usable content and artwork, and app actions return to the originating card.

Open **Settings → Customize rows** to reorder or hide content sections, choose card sizes, and set discovery limits to **20, 40 or 60**. The default order is:

**Continue watching · Shows → Continue watching · Movies → Watchlist → New episodes when available → Live sports → Suggestions → Discover → Trending → New releases.**

Library remains below the content rows. [Explore all controls →](docs/usage.md)

## Three layouts, one Home

Switch through **prettTV Settings → Layout**. Apps, saved titles, viewing records and row preferences are shared across layouts.

| Layout | What leads the screen |
| :--- | :--- |
| **A · Cinema** | A wide feature, followed by apps and content rows. |
| **B · Quiet** | Apps first, followed by content rows. |
| **C · Balanced** | A large feature, two discovery cards and a compact app row. **Default.** |

<details>
<summary><strong>A · Cinema</strong></summary>

![A Cinema on the TCL TV, showing the wide feature and app row.](docs/screenshots/A-cinema.png)

</details>

<details>
<summary><strong>B · Quiet</strong></summary>

![B Quiet on the TCL TV, with apps first and separate viewing rows below.](docs/screenshots/B-quiet.png)

</details>

<details>
<summary><strong>Navigation that stays visible</strong></summary>

The opaque top bar stays in place as content scrolls. Left and Right stop at row boundaries; Up from the first content card reaches navigation.

![Persistent navigation while browsing toward Library on the TCL TV.](docs/screenshots/top-navigation.png)

</details>

<details>
<summary><strong>App actions, right beside the selected card</strong></summary>

Hold OK to open, arrange or hide a shortcut, inspect App info, or request Force stop. Open is selected first; Force stop has its own Cancel-first confirmation.

![The contextual Netflix app-action panel on the TCL TV.](docs/screenshots/app-actions.png)

</details>

## Install prettTV

You need **Android TV 8.0 / API 26 or newer**, a computer with Android platform-tools (`adb`), and a debugging connection authorized on the TV. The release bundle needs no build tools.

1. Download **`prettTV-<version>-with-RBTV.zip`** from [GitHub Releases](https://github.com/draco1738/prettTV/releases/latest) and extract it.
2. Run `adb devices`, then use an entry listed as `device` in the command below. Run it from the extracted folder.
3. Allow **TV listings** when prettTV asks. Open **Settings → Choose default Home app** and select prettTV in Android's settings.

**Windows · PowerShell**

```powershell
.\install.ps1 -Tv 'TV_SERIAL_FROM_ADB_DEVICES'
```

**macOS / Linux · Bash**

```sh
TV='TV_SERIAL_FROM_ADB_DEVICES' bash ./install.sh
```

The default bundle includes **RBTV+ 3.0.320**. The installer verifies the pinned companion checksum before a fresh installation and preserves any existing RBTV installation and its data. To skip the companion, add **`-LauncherOnly`** in PowerShell or **`--launcher-only`** in Bash. The standalone prettTV APK installs only the launcher.

The installers keep other launchers in place and leave Home selection and optional special access to you. RBTV is separate third-party software with its own terms and permissions; prettTV's MIT license does not cover it.

[Installation and connection help](docs/installation.md) · [RBTV provenance and limits](docs/companion-apps.md)

### Already using prettTV?

Open **Settings → Updates** to check release notes, download and install a newer official build. The updater verifies the APK's package, version, checksum and signing certificate before Android asks for installation confirmation. Compatible signed upgrades preserve settings; updating prettTV does not update RBTV.

Use the signed APK from [Releases](https://github.com/draco1738/prettTV/releases/latest) for an in-place release update. Ordinary source builds and GitHub CI artifacts use debug certificates and cannot replace a differently signed installation. [Update and signing details →](docs/installation.md#updates)

## Just a remote

| Control | Action |
| :--- | :--- |
| **Arrows / OK** | Move between cards / open the selected item. |
| **Hold OK on an app** | Open its action panel: launch, arrange, hide from Your apps, Force stop or App info, as applicable. |
| **Hold OK on a title** | View details, save, follow, hide, dismiss or correct recent viewing, as applicable. |
| **Left / Right at a row boundary** | Stop at the beginning or end; rows do not wrap. |
| **Up from the first content card** | Reach the persistent navigation bar. |
| **Down from navigation** | Return to the remembered content card. |
| **Home** | Reset the page and horizontal rows to their default start. |
| **Back** | Close the current panel or return content to the top. |
| **Menu** | Open prettTV Settings. |

Hiding an app from **Your apps** does not remove it from Library. Hiding a title from the hero or Continue watching stays local to this TV and does not change anyone else's streaming account. [App and title controls →](docs/usage.md)

## Viewing history, Watchlist and discovery

prettTV uses program rows that apps publish to Android TV, supported playback evidence and explicit local corrections. It does not ask for streaming credentials or have access to a universal account-wide viewing history.

Meaningful progress and episode changes can move a title forward; timestamp-only refreshes do not. Continue watching applies new ordering while keeping the selected title focused, and the hero follows the newest eligible record. Delayed or shared-account updates cannot establish exactly when or who watched. If a record is stale, hold OK and choose **I watched this most recently**.

**Watchlist is local to prettTV. It does not synchronize with Google account / Google TV Watchlist.** Hold OK on a title to save it. Supported confirmed playback can remove a started title, with Undo available; a provider progress observation alone does not consume a saved entry.

Choose **Settings → Recommendation services** to prioritize your services explicitly, or use inference from shared viewing activity. Android does not reliably expose which streaming accounts are signed in. Provider coverage, subscriptions and regional availability vary; a listed title does not guarantee playback.

[Content sources and ordering](docs/content-and-sports.md) · [Watchlist and episode follows](docs/usage.md#save-titles-to-watchlist)

## More to explore

| Feature | How it works |
| :--- | :--- |
| **Blind Pick** | Reveal mystery movie/show choices filtered by type, mood and available runtime. Open a result or save it. |
| **Rabbit Hole** | Explore published cast/director, genre and release connections from a starting title. Missing metadata stays unavailable. |
| **Live sports** | Browse live/upcoming fixtures, prioritize favorite teams and enable optional Home-only kickoff reminders. The optional RBTV helper matches supported games and leaves server selection to you. |
| **Weather** | Enter a US ZIP and choose °F or °C. Nearby NWS observations appear beside the clock; freshness remains in Weather details. No GPS permission is needed. |
| **Offline Home** | Reuse recent content and available artwork during outages, with age/error information and expiry rules. Streaming still needs the app's connection. |
| **EarthCam idle handoff** | Optionally open the separately installed EarthCamTV app when Android starts the selected screensaver. EarthCam owns camera playback. |

**Blind Pick and Rabbit Hole live at the bottom of Library**, after installed apps. They are built into prettTV and stay out of the upper Your apps row. EarthCam and other optional apps are not part of the release bundle.

[Original Library experiences](docs/original-apps.md) · [Sports handoff](docs/content-and-sports.md) · [Weather](docs/usage.md#weather-beside-the-clock) · [EarthCam](docs/earthcam-idle.md)

## Troubleshooting and recovery

**Restart one app:** Hold OK on an eligible app → **Force stop** → confirm. After a verified stop, choose **Done** or **Open app**. Saved data and logins are kept. Direct stopping requires the existing authorized TV-local recovery connection; **App info** provides Android's controls when that setup is unavailable. Home and essential TV services are protected. This closes an app's connections; it does not reset the TV network or guarantee a fix for playback problems.

**Repair the remote Settings shortcut:** The optional Settings-button helper opens TCL's native quick-settings panel on supported devices and uses standard Android Settings elsewhere. Its Accessibility key filter leaves unrelated buttons alone and does not read screen content. [Setup and limits →](docs/usage.md#remote-settings-button)

**Return to stock:** The button at the bottom of prettTV Settings requires **two confirmations**. With the original private setup record and authorized local TCP debugging configured, it restores recorded TCL/Google app states, Private DNS and Google TV Home while retaining installed streaming apps, logins and prettTV. It is a troubleshooting restore, not a factory reset; a fresh install cannot reconstruct an unknown baseline.

[Configure stock recovery](docs/stock-recovery.md) · [Select another Home or uninstall](docs/installation.md#restore-or-uninstall)

## Privacy and implementation

prettTV uses **Kotlin and native Android Views/Canvas**, progressive artwork loading and bounded caches. Focus effects finish and settle instead of animating continuously. There is no third-party UI framework or analytics SDK.

Personalization and viewing records are stored locally. Catalog, artwork, weather and update requests contact their respective providers, which receive normal connection/request information. Optional playback, game-handoff and Settings-button access is enabled separately in Android settings. prettTV does not remove advertising inside other apps, and its privacy statements do not cover those apps.

[Privacy, stored data and optional permissions →](docs/privacy.md)

## Build, verify and contribute

Use **JDK 17**, **Android SDK platform 34**, **build-tools 35.0.0** and the included Gradle wrapper. See [Development](docs/development.md) for build commands, signing and the source map; [Stability testing](docs/stability-testing.md) describes isolated device checks.

The released **0.4.0** build has the following recorded results:

| Check | Result |
| :--- | :--- |
| JVM tests | **334 passed** across 29 suites. |
| Targeted Android checks on the TCL TV | **51 passed**, including navigation, refresh, viewing order, focus and normal/enlarged-text dialog checks. |
| Android lint | **0 errors**, 32 existing warnings. |
| Installed release artifact | Pulled back from the TV and matched to the signed release SHA-256. |
| GitHub build | [Passed on the release commit](https://github.com/draco1738/prettTV/actions/runs/40660510276). |

These are bounded release checks. The historical one-hour soak and frame-time benchmark were **not repeated for 0.4.0**; no universal app coverage or sustained frame-rate guarantee is implied. CI builds the Android test APKs but does not run them on a TV. [Full versioned evidence →](docs/validation.md)

For a bug report, include the prettTV version, TV model/Android version, layout, reproduction steps and expected behavior. Keep account details, private recovery records and raw preference exports out of public issues. For contributions, describe the behavior changed and the checks actually run; preserve existing source and asset attribution.

[Report an issue](https://github.com/draco1738/prettTV/issues) · [Source](launcher/app/src/main) · [Tests](launcher/app/src/test) · [Design reference](DESIGN.md)

## Built with credit

prettTV is derived from **[Rivi v0.15 by Jommi](https://github.com/jommi9/rivi)**, Copyright © 2026 Jommi, under the [MIT License](LICENSE). Rivi supplied the native Android foundation, app discovery/caching and remote-navigation patterns. Its original notices, source history and [upstream README](docs/upstream-rivi-readme.md) are retained.

**[PLUI by Smartago](https://github.com/smartago/plui-tv-launcher)** and stock Google TV informed the design. Their implementations and artwork are not redistributed. App icons, catalog data, show artwork and companion software retain their own rights and licenses.

[Full credits and third-party notices](THIRD_PARTY_NOTICES.md) · [App-icon sources](launcher/app/src/main/assets/app-icons/sources.json) · [Documentation index](docs/README.md)
