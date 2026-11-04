# Globe TV shortcut

An optional, independent Android TV app entry that opens [Globe TV's United States page](https://globetv.app/tv?country=united-states) in [TV Bro](https://github.com/truefedex/tv-bro). It appears as **Globe TV** in the TV app library. This is an unofficial website shortcut, not an app provided by Globe TV and not a standalone streaming player.

The shortcut requests no permissions, makes no network requests itself, and has no background services or URL logging. It opens TV Bro and immediately finishes. TV Bro supplies the website rendering, remote controls, cookies and browser filtering. The shortcut does not change the default Home app or replace the existing 7Movies shortcut.

## Use

Install and enable [TV Bro](../../docs/browser-and-voice.md), then open **Globe TV** from the app library. The shortcut requests the current browser tab instead of creating a new tab on each launch. This navigates the selected tab to the configured United States page; it does not edit TV Bro's saved preferences or clear browser data.

Use the website's country selector to browse other countries. Each fresh launch from the Globe TV app tile returns to the configured United States URL. Country selection, channel availability, playback and remote interaction are handled by the website and TV Bro; the shortcut does not change them or bypass access controls. If the browser toolbar remains open, use TV Bro's Back control to close it.

If TV Bro is unavailable or Android blocks the handoff, the shortcut shows a dark error dialog with a **Close** button.

## Build and install

Use JDK 17, Android SDK 34 and Build Tools 35.0.0. From the parent `launcher` folder:

```sh
./gradlew :globetv:assembleRelease :globetv:lintRelease
```

On Windows, use `gradlew.bat`. The output is `globetv/build/outputs/apk/release/globetv-release.apk`.

```sh
adb -s TV_SERIAL_FROM_ADB_DEVICES install -r globetv/build/outputs/apk/release/globetv-release.apk
```

Replace the device placeholder with the authorized serial from `adb devices`. This module uses package **`dev.zane.pretttv.globetv`**, version **1.0 / code 1**, and has no external application dependencies.

Signing reuses the parent project's untracked `signing.properties` or its existing `RIVI_KEYSTORE`, `RIVI_KEYSTORE_PASSWORD` and `RIVI_KEY_ALIAS` environment variables. Without a release key it uses the local debug key. Keep signing material private and reuse the same key for later updates; a debug-signed CI APK cannot replace a release-signed installation.

## Validation scope

The November 3, 2026 build passed lint with zero errors and warnings. Its signature and permission-free manifest were verified. The signed 21,444-byte APK installed on the TCL and its pulled-back copy matched SHA-256 `29420f2876f08d15f4e6360f275ca62d05dcd88a41f8f62113eee572273e1eaf`. The tile launched from Library and Your apps into the correct United States page in TV Bro. The browser address and existing three-tab strip were inspected after both launches; no extra tab appeared. Home returned to prettTV with the hero focused. The website page rendered, but individual channel playback was not tested.

Launching the website alone does not verify channel playback, every country or all browser features. The shortcut's same-tab handoff follows the existing TV Bro 2.1.6 integration used by the [7Movies shortcut](../webshortcut/README.md). See the [versioned validation record](../../docs/validation.md) for current results and limitations.

Globe TV and TV Bro names belong to their respective owners. This shortcut does not imply their endorsement.
