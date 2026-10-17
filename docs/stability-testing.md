# Repeatable stability checks

Build with JDK 17, Android SDK 34 and build-tools 35.0.0:

```sh
cd launcher
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleStability :app:assembleStabilityAndroidTest :testapp:assembleDebug
```

Use `gradlew.bat` on Windows. Tests do not require new runtime libraries in the release APK. The `stability` variant uses the separate `dev.zane.pretttv.stability` application ID, has no Home/app-launcher intent filter, and contains the generated artwork fixture provider. The owner’s release package and preferences are not used by those fault tests. Test fixtures and the instrumentation runner are absent from the release APK.

From the repository root, with PowerShell 7 and an authorized ADB device:

```powershell
./tests/android-fault-tests.ps1 -Tv '<serial>' -OutputDirectory '<local-results-directory>'
```

This temporarily installs the isolated target and test runner, executes ten Android checks, and removes both packages in `finally`. It refuses to replace pre-existing test installations. Fixtures include valid/large images, corrupted bytes, oversized streams and delayed responses. The queue test submits 100 requests and requires every accepted or rejected request to finish. Cancellation tests require zero late callbacks into replaced/closed screens. Cache recovery uses only the isolated target’s cache. The fixture provider runs inside that same sandbox because the TCL’s cross-app autostart policy blocks an externally hosted test provider; no TV policy is changed.

For navigation tests, leave the TV idle with **C Balanced** selected and prettTV already configured as default Home:

```powershell
./tests/stress-home.ps1 -Tv '<serial>' -Cycles 15 -RestartCycles 5 -OutputDirectory '<local-results-directory>'
./tests/lifecycle-home.ps1 -Tv '<serial>' -FixtureApk './launcher/testapp/build/outputs/apk/debug/testapp-debug.apk' -Cycles 10 -OutputDirectory '<local-results-directory>'
```

All scripts accept `-Adb '<adb-path>'` and `-Port <adb-server-port>`. Navigation uses D-pad directions, Back and Home without opening media or selecting settings. Each navigation cycle checks focus and process continuity. Cold-start checks force-stop only prettTV and verify it returns via the existing Home resolver; no launcher is disabled or uninstalled. Timing includes ADB overhead and only measures a focusable Home, not complete artwork or playback readiness.

The lifecycle script temporarily installs the do-nothing fixture app, verifies Home actually stops, returns with Back, and checks the same focus, viewport and process. It removes that fixture afterward and refuses to overwrite an existing fixture installation. Foreground guards stop further input when another app becomes active. These tests require an idle TV; do not run them while someone is watching.

Keep raw device reports local: they can include content identities and installed-app names. Check the crash buffer and Android exit reasons after a run. Package-update and test-requested force-stop exits are expected; crashes and ANRs are not. Compare preserved preference values before/after temporary app tests, excluding transient focus. Passing these bounded tests does not establish indefinite uptime or a sustained frame rate.

## Comparing scrolling changes

With the TV idle on C Balanced, run the same route on the old and new signed release:

```powershell
./tests/measure-home-motion.ps1 -Tv '<serial>' -OutputDirectory '<local-before-directory>'
# Install the candidate release, then repeat with a separate after directory.
./tests/measure-home-motion.ps1 -Tv '<serial>' -OutputDirectory '<local-after-directory>'
./tests/verify-row-navigation.ps1 -Tv '<serial>' -OutputDirectory '<local-navigation-directory>'
```

This warms the route once, resets only the app's graphics counters, then repeats two paced D-pad routes through apps and content shelves. It saves Android `gfxinfo` frame statistics and verifies that focus and the Home process survive. It does not clear caches or change animation settings. Keep the same content and device conditions when comparing results; frame count and frame-time percentiles must be considered together because enabling an animation adds frames. Cold startup, streaming playback and sustained 60 fps are outside this measurement. Follow [Android's rendering guidance](https://developer.android.com/topic/performance/issues/render) when investigating remaining slow frames.

The row-navigation check records the selected cards while moving right, then requires Left to visit them in reverse order. It also checks that held Left stops at the first card, a fresh Left opens the sidebar, and Right returns to that same card. This is a separate regression check, not a frame-rate measurement. Both scripts require an idle TV already displaying prettTV.
