# Install prettTV

[← prettTV](../README.md) · [Documentation](README.md) · [Build from source](development.md)

The release bundle installs **prettTV + RBTV+** and opens the launcher. **C · Balanced** is the default layout; A and B are available in prettTV Settings.

## 1. Get the bundle

Download **`prettTV-<version>-with-RBTV.zip`** from [GitHub Releases](https://github.com/draco1738/prettTV/releases/latest) and extract it on your computer.

You need:

- An Android TV running **Android 8.0 / API 26 or newer**.
- Android platform-tools, with `adb` available in your terminal.
- A debugging connection authorized on the TV.

Connect using the USB or network debugging method supported by your TV, accept its debugging prompt, then run:

```sh
adb devices
```

Use a serial listed with the state **`device`**. If it says `unauthorized`, accept the TV's prompt before continuing. The examples below use `TV_SERIAL_FROM_ADB_DEVICES` as a placeholder for that serial or network address.

## 2. Install

Open a terminal in the extracted bundle folder.

### Windows · PowerShell

```powershell
.\install.ps1 -Tv 'TV_SERIAL_FROM_ADB_DEVICES'
```

If ADB is outside your `PATH`, pass its location:

```powershell
.\install.ps1 -Tv 'TV_SERIAL_FROM_ADB_DEVICES' -Adb 'E:\Android\platform-tools\adb.exe'
```

### macOS / Linux · Bash

```sh
TV='TV_SERIAL_FROM_ADB_DEVICES' bash ./install.sh
```

You can omit the device selection when exactly one authorized device is connected.

### What gets installed

| Component | Installer behavior |
| --- | --- |
| **prettTV** | Installs or updates the launcher, then opens it. |
| **RBTV+ 3.0.320** | Verifies its pinned SHA-256 before a fresh install. If RBTV is already installed, keeps that version and its data. |
| **Home selection** | Leaves the existing selection and other launchers in place until you choose prettTV. |
| **Optional helpers** | Leaves Notification and Accessibility access for you to enable. |

To install only the launcher:

```powershell
.\install.ps1 -Tv 'TV_SERIAL_FROM_ADB_DEVICES' -LauncherOnly
```

```sh
TV='TV_SERIAL_FROM_ADB_DEVICES' bash ./install.sh --launcher-only
```

RBTV is a separate third-party app with its own terms. Its provenance, checksum and review limits are in [Companion apps](companion-apps.md); prettTV's MIT license does not cover it.

## 3. Choose Home and allow shared TV rows

In prettTV, open **Settings → Choose default Home app** and select prettTV through Android's Home settings. The installers do not disable system launchers or intercept the Home button.

Allow **TV listings** when prompted so prettTV can read the viewing progress and program rows that apps share with Android TV. prettTV does not ask for streaming credentials. You still sign in to each streaming app normally; not every app publishes usable progress or recommendations.

Optional setup:

| Setting | What it does |
| --- | --- |
| **Recommendation services** | Lets you choose your signed-in services explicitly. Android does not provide a reliable cross-app login flag. |
| **Playback tracking** | Explains optional Notification access for recognizing supported active media sessions. prettTV does not read notification text. |
| **Game handoff settings** | Opens setup for the optional Accessibility helper that matches supported games in RBTV. Server selection stays with you. |
| **Return to stock Google TV** | Provides the double-confirmed troubleshooting restore after its private recovery record and local debugging access are configured. |

See [Stock recovery](stock-recovery.md) before relying on the last option. A fresh installation cannot infer your TV's original app and DNS settings.

For an optional browser, see [TV Bro, ad blocking and remote voice](browser-and-voice.md). TV Bro is installed separately from the release bundle. Keep your working Home selection when troubleshooting voice; enabling a stock launcher can change what the Home button opens.

## Updates

Starting with **prettTV 0.2.0**, open **Settings → Updates**:

1. Choose **Check now** to fetch the latest stable official release and read its notes. Home also makes at most one automatic check per day while visible; it does not open the update screen or download an APK automatically.
2. Choose **Download**. The updater checks the official asset location, APK checksum, package ID, newer version and matching signing certificate. You can cancel the download.
3. Choose **Install**. If Android asks, allow prettTV to install from this source, return, and choose Install again. Confirm or cancel in Android's installer.

The updater rejects debug builds, certificate mismatches and releases without verification metadata. Download or installation failure leaves the installed app in place. No uninstall is used to bypass a mismatch. A compatible signed update preserves the app's preferences.

You can still run a new release bundle's installer or install the signed **`prettTV-<version>.apk`** through ADB. Use this route to upgrade from versions that predate the in-app updater. The application ID remains **`dev.zane.pretttv`**.

An existing RBTV installation is preserved by the bundle installer, including when it differs from the bundled version. Updating prettTV alone does not update RBTV.

GitHub CI builds and ordinary local builds use debug certificates. They cannot update an installation signed with the project's release key. Use the [published release](https://github.com/draco1738/prettTV/releases/latest) for an in-place release update; see [Signing and updates](development.md#signing-and-updates) for source builds.

## Restore or uninstall

Choose the operation that matches your intent:

| Operation | What changes | prettTV remains installed? |
| --- | --- | :---: |
| **Settings → Return to stock Google TV** | Restores the recorded stock Home, disabled apps and original Private DNS settings after two confirmations. Requires the private baseline and authorized local TCP debugging. | Yes |
| **Select another default Home** | Changes which launcher opens when you press Home. | Yes |
| **`restore.sh`** | Verifies and opens another enabled Home, then uninstalls prettTV. | No |
| **`restore.sh --stock-home`** | Enables the two Google TV Home packages used by this TCL setup, verifies and opens stock Home, then uninstalls prettTV. | No |

**Select and verify a working replacement Home before uninstalling prettTV.** The Bash uninstall helper is included in the release bundle and source checkout:

```sh
# Use another Home that is already enabled.
TV='TV_SERIAL_FROM_ADB_DEVICES' bash ./restore.sh

# Or restore the supported Google TV Home packages before uninstalling.
TV='TV_SERIAL_FROM_ADB_DEVICES' bash ./restore.sh --stock-home
```

These scripts do **not** restore the full disabled-app or DNS baseline. On Windows, you can select a replacement in Android's Home settings, confirm that the Home button opens it, then uninstall prettTV through Android's Apps settings. If stock Home is disabled, follow [Stock recovery](stock-recovery.md) first.

## Installer options

| Purpose | PowerShell | Bash |
| --- | --- | --- |
| Select a connected TV | `-Tv '<serial>'` | `TV='<serial>'` |
| Reuse an already built APK | `-NoBuild` | `--no-build` |
| Skip RBTV | `-LauncherOnly` | `--launcher-only` |
| Supply the pinned RBTV APK | `-CompanionApk '<path>'` | `RBTV_APK='<path>'` |
| Choose the ADB executable | `-Adb '<path>'` | Put `adb` on `PATH` |
| Choose the computer's ADB server port | `-AdbPort 5037` | Uses the ADB default |

The bundle already contains its APKs and needs no build tools. From a source checkout, the installer builds prettTV unless `-NoBuild` / `--no-build` is set, and downloads the pinned RBTV release asset if needed. Bash requires `curl` for that download and `sha256sum` or `shasum` for verification. See [Development](development.md) for source prerequisites.
