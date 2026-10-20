# EarthCam as the idle screen

[Documentation](README.md) · [Aerial Views alternative](aerial-and-moonlight.md#aerial-views) · [Privacy](privacy.md#companion-apps-and-scope)

The optional prettTV EarthCam handoff opens the installed **[EarthCamTV 3](https://www.earthcam.com/apps/)** app when Android starts its screensaver. EarthCam then controls playback, camera selection, accounts and settings. prettTV remains the Home launcher.

> [!NOTE]
> Verified on the TCL with v0.3.0: normal launch, repeated system **Start now** activation and automatic activation after ten minutes without input all reached EarthCam playback. The idle trial showed advancing playback and two visibly different live camera frames; **Home** returned to prettTV. These are bounded checks on one TV. [Validation details](validation.md#pretttv-030-library-experiences-and-earthcam--2026-10-20)

## What to expect

The inspected EarthCamTV 3.0.6 build does not register an Android screensaver service. prettTV supplies a small system screensaver that hands off to EarthCam's ordinary launch activity. It does not embed, extract or redistribute camera streams.

The inspected app's normal startup loads its camera list and starts playback; returning to it resumes the current camera. A first-run guide, network failure or EarthCam's own settings can affect that flow. Complete its normal first-run setup and check playback before relying on automatic activation.

**Home** returns to prettTV. **Back** follows EarthCam's own controls and navigation. Other remote buttons operate EarthCam; this is not the native screensaver behavior where any key dismisses the screen.

EarthCam is installed separately from its official store listing. Its [official app page](https://www.earthcam.com/apps/) describes an optional All Access Pass for custom watchlists and archives. The handoff neither purchases a subscription nor changes existing entitlements. No EarthCam APK, proprietary code or stream URL is included in prettTV.

## Select the handoff

Use prettTV v0.3.0 or newer, with `fi.jommi.rivi.EarthCamDreamService`. First open EarthCam normally and verify that the cameras you want play. Keep the TV's current idle and sleep timers unless you deliberately want to change them.

If the TV has a screensaver picker, select the prettTV EarthCam handoff there. On devices without that picker, an authorized ADB connection can select the component. Record the current settings privately before changing them:

```text
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings get secure screensaver_components
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings get secure screensaver_enabled
adb -s TV_SERIAL_FROM_ADB_DEVICES shell cmd package query-services --brief -a android.service.dreams.DreamService -p dev.zane.pretttv
```

Only continue when the query lists the new service:

```text
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings put secure screensaver_components dev.zane.pretttv/fi.jommi.rivi.EarthCamDreamService
adb -s TV_SERIAL_FROM_ADB_DEVICES shell settings put secure screensaver_enabled 1
```

These commands change the screensaver selection, not Home. They do not change EarthCam's data or login. Before removing Aerial Views or another selected screensaver, select the replacement and verify its normal idle activation.

## Failure behavior and recovery

The handoff attempts one launch after its own window becomes visible. A two-minute cooldown survives process restarts to limit repeated attempts. If EarthCam is missing, disabled or cannot be launched, the bridge attempts to return to prettTV and releases its screensaver window. A startup timeout also releases the window if it never receives focus.

Android or TV firmware can reject an activity launch without reporting an exception. A dispatched launch therefore does not prove that EarthCam started or that a camera is playing. Verify the complete idle-to-playback flow, Home exit, Back behavior and a second activation on the actual device.

To revert, select another installed screensaver or restore the exact component and enabled value recorded before setup. If a setting was originally absent, delete that setting rather than writing the literal value `null`. Keep **[Aerial Views](aerial-and-moonlight.md#aerial-views)** as an optional native video-screensaver alternative.
