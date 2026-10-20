# 7Movies TV shortcut

An optional, independent Android TV app entry that opens `https://7movies.ac/discover` in [TV Bro](https://github.com/truefedex/tv-bro). It appears in prettTV's app library and does not change the default Home app.

The shortcut hands off to TV Bro and immediately finishes. It requests no permissions, makes no network requests, and has no background services or URL logging. TV Bro supplies the website rendering, remote controls, cookies, and browser blocking settings. Its current tab is reused, so repeated launches do not continually add tabs; this also means launching the shortcut navigates the selected browser tab to the destination.

TV Bro must be installed and enabled. If Android cannot open it, the shortcut displays a dark error dialog with a Close button. The shortcut does not embed TV Bro or the website, and is not an official 7Movies app.

## Build

Use the parent launcher Gradle project with JDK 17, Android SDK 34 and Build Tools 35.0.0:

```sh
./gradlew :webshortcut:assembleRelease :webshortcut:lintRelease
```

On Windows, use `gradlew.bat`. The APK is written to `build/outputs/apk/release/webshortcut-release.apk`.

Release signing uses the parent project's untracked `signing.properties` or its existing `RIVI_KEYSTORE`, `RIVI_KEYSTORE_PASSWORD` and `RIVI_KEY_ALIAS` environment variables. Without a release key it uses the local debug key, matching the parent project's build convention. Keep the same signing key for subsequent updates.

The module has no external application dependencies. Its `app_icon` and `app_banner` drawables define the launcher artwork.

## Install and use

Install [TV Bro](../../docs/browser-and-voice.md) first, then install the built shortcut:

```sh
adb -s TV_SERIAL_FROM_ADB_DEVICES install -r webshortcut/build/outputs/apk/release/webshortcut-release.apk
```

Run this from the parent `launcher` folder. Replace the device placeholder with the authorized serial shown by `adb devices`. Open **7Movies** from prettTV's Your apps row or Library. Android's Home button continues to use your existing launcher.

The website address is fixed in `MainActivity.java`. Website availability, playback, remote interaction and browser filtering remain the responsibility of the website and TV Bro; this shortcut does not add a native player or bypass site access controls. TV Bro's [same-tab intent extra](https://github.com/truefedex/tv-bro/blob/v2.1.6/app/src/main/java/com/phlox/tvwebbrowser/activity/main/MainActivity.kt#L445-L459) was verified against version 2.1.6.

CI builds and lints the module, and keeps an optional debug-signed APK artifact. A CI APK cannot update a release-signed installation. The artwork is original to this shortcut, and does not imply endorsement by the website or TV Bro.

## Validation

Version 1.0 was built and installed on the TCL test TV on October 20, 2026. Release lint reported zero errors and zero warnings; the APK signature verified and its manifest requested no permissions. The tile appeared in prettTV's Your apps row, and two launches opened the configured Discover page in TV Bro 2.1.6. prettTV C remained the Home launcher. These checks cover the shortcut, not streaming playback or every website/browser feature.

If TV Bro's toolbar was already open, it can remain open after the handoff. Close the toolbar with the browser's Back control to view the page.
