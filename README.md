# prettTV

**A quieter home for Android TV.** Your apps, viewing progress and something new to watch, in three native layouts.

[**Download the latest release**](https://github.com/draco1738/prettTV/releases/latest) · [Installation](docs/installation.md) · [Documentation](docs/README.md) · [Credits](THIRD_PARTY_NOTICES.md)

[![Build](https://github.com/draco1738/prettTV/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/draco1738/prettTV/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/draco1738/prettTV?style=flat&color=adc1b6)](https://github.com/draco1738/prettTV/releases/latest)

![C Balanced on the TCL TV: a large recently watched title, two discovery cards, dark app tiles and a Continue watching row.](docs/screenshots/C-balanced.png)

*C · Balanced is the default. This is an unedited capture from the TCL TV, using real apps and provider content.*

## A home that stays out of the way

- **Pick your layout.** Cinema, Quiet or Balanced, with a hidden left sidebar in all three.
- **Put recent viewing first.** A large feature and separate Shows and Movies rows use supported app progress and locally confirmed playback.
- **Keep your apps close.** Dark tiles with color accents, favorites first, and a finite row with a clear start and end. Hide an app from the row while keeping it in Library.
- **Save something for later.** A dedicated Watchlist row keeps your saved titles and their app destinations. Follow shows separately for new-episode alerts.
- **Make the rows yours.** Reorder or hide content rows, choose card sizes, and browse 20, 40 or 60 discovery titles per row. Suggestions, Discover and Trending put preferred services first.
- **Follow your teams.** Favorite teams move up Live sports, with optional kickoff reminders while Home is visible.
- **Keep shared accounts personal.** Hide individual titles from the feature or Continue watching without changing anyone else's streaming account.

Built on [Rivi by Jommi](https://github.com/jommi9/rivi), using native Android Views/Canvas, progressive artwork loading and bounded caches. No third-party UI framework or analytics SDK.

> [!NOTE]
> Viewing progress and recommendations depend on what each app shares with Android TV. prettTV does not access a universal streaming history or detect every signed-in account. [How content works →](docs/content-and-sports.md)

Home also retains recent content during outages, offers undo for supported changes, and checks for official updates without installing anything automatically. [Explore the controls →](docs/usage.md)

## Get started

You need **Android TV 8.0 or newer** and a computer with an authorized **ADB** connection.

1. Download and extract the **`prettTV-…-with-RBTV.zip`** bundle from [Releases](https://github.com/draco1738/prettTV/releases/latest).
2. Open a terminal in the extracted folder and run the command for your computer, replacing `TV_SERIAL_OR_ADDRESS` with the device shown by `adb devices`.
3. In prettTV, allow **TV listings**, then use **Settings → Choose default Home app**. C Balanced is selected by default.

**Windows · PowerShell**

```powershell
.\install.ps1 -Tv 'TV_SERIAL_OR_ADDRESS'
```

**macOS / Linux · Bash**

```bash
TV='TV_SERIAL_OR_ADDRESS' bash ./install.sh
```

The default bundle includes **RBTV+** and keeps an existing RBTV installation. Add `-LauncherOnly` on Windows or `--launcher-only` in Bash to skip it. A standalone prettTV APK installs or updates only the launcher.

[Full installation guide](docs/installation.md) · [RBTV provenance and limits](docs/companion-apps.md)

## Three layouts, one library

Choose a layout in **prettTV Settings → Layout**. Your apps, viewing history and discovery feeds are shared across all three.

| Layout | Composition |
| :--- | :--- |
| **A · Cinema** | A wide feature puts the current title first. |
| **B · Quiet** | Apps first, with content rows below. |
| **C · Balanced** | A large feature, two discovery cards and a compact app row. **Default.** |

<details>
<summary><strong>View A · Cinema</strong></summary>

![A Cinema captured on the TCL TV.](docs/screenshots/A-cinema.png)

</details>

<details>
<summary><strong>View B · Quiet</strong></summary>

![B Quiet captured on the TCL TV.](docs/screenshots/B-quiet.png)

</details>

<details>
<summary><strong>Explore the content rows</strong></summary>

Continue watching · Shows → Continue watching · Movies → Watchlist → New episodes when available → Live sports → Suggestions → Discover → Trending → New releases.

This is the default order; **Settings → Customize rows** lets you change it. The capture below shows the existing discovery styling; it predates the additional rows.

![Suggestions, Discover and Trending on the TCL TV.](docs/screenshots/discover-row.png)

</details>

## Just a remote

| Control | Action |
| :--- | :--- |
| **Arrows / OK** | Move between cards / open the selected item. |
| **Hold OK** | Arrange apps; save, follow, dismiss or inspect a title; correct recent viewing. |
| **Left at the first card** | Reveal the sidebar; Right or Back closes it. |
| **Back from content** | Return to the top. |
| **Menu** | Open prettTV Settings. |

[App arrangement, title controls and settings →](docs/usage.md)

## Recovery, when you need it

**Settings → Return to stock Google TV** requires two confirmations. It restores the recorded TCL/Google app states, original Private DNS and Google TV Home while retaining streaming apps, logins and prettTV.

Full recovery needs the original private setup record and authorized local TCP debugging. It is available once those prerequisites are configured; it is not a factory reset.

[Set up stock recovery](docs/stock-recovery.md) · [Privacy and permissions](docs/privacy.md) · [Uninstall safely](docs/installation.md#restore-or-uninstall)

## Build, verify, contribute

Use **JDK 17**, **Android SDK 34** and **build-tools 35.0.0**. The [development guide](docs/development.md) covers source builds, signing and the test setup.

Release checks cover JVM tests, Android lint, isolated device tests, navigation, lifecycle, failure handling and measured scrolling. [Validation history](docs/validation.md) records completed checks and their limitations for each release; earlier results do not certify a newer build.

## Built with credit

prettTV is derived from **Rivi v0.15**, Copyright © 2026 Jommi, under the [MIT License](LICENSE). Its source history, original notices and [upstream README](docs/upstream-rivi-readme.md) are retained.

[PLUI by Smartago](https://github.com/smartago/plui-tv-launcher) and stock Google TV informed the design. Their implementation and artwork are not redistributed. App icons, data providers, artwork and the separate RBTV companion retain their respective rights and attribution.

[Full credits and third-party notices →](THIRD_PARTY_NOTICES.md)
