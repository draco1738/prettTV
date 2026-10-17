# Repeatable stability checks

[prettTV](../README.md) / [Documentation](README.md) / Stability testing

Use these checks to reproduce build validation, artwork failures and remote navigation behavior. For recorded outcomes, see the [validation record](validation.md).

| Check | What it covers | Device requirement |
|---|---|---|
| [Build checks](#build-checks) | Unit tests, lint and test APKs | Computer only |
| [Artwork fault tests](#artwork-fault-tests) | Decode failures, queue completion, cancellation and cache recovery | Authorized ADB device |
| [Navigation and lifecycle](#navigation-and-lifecycle) | Focus, process continuity, cold Home and stop/return | Idle TV on C Balanced |
| [Scrolling comparison](#comparing-scrolling-changes) | Frame statistics and row-navigation regression | Idle TV on C Balanced |

Passing these bounded tests does not establish indefinite uptime or a sustained frame rate. Keep raw reports local: they can contain content identities and installed-app names.

## Build checks

Use **JDK 17**, **Android SDK 34** and **build-tools 35.0.0**:

```sh
cd launcher
./gradlew :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease :app:assembleStability :app:assembleStabilityAndroidTest :testapp:assembleDebug
```

Use `gradlew.bat` on Windows. Tests do not add runtime libraries to the release APK; test fixtures and the instrumentation runner are absent from it.

## Artwork fault tests

From the repository root, with PowerShell 7 and an authorized ADB device:

```powershell
./tests/android-fault-tests.ps1 -Tv '<serial>' -OutputDirectory '<local-results-directory>'
```

The script temporarily installs an isolated target and test runner, executes **ten Android checks**, and removes both packages in `finally`. It refuses to replace pre-existing test installations.

| Fixture or check | Required behavior |
|---|---|
| Valid/large images, corrupted bytes, oversized streams, delayed responses | Exercise image loading and failure handling |
| Queue of 100 requests | Every accepted or rejected request finishes |
| Screen replacement and closure | Zero late callbacks |
| Cache recovery | Uses only the isolated target's cache |

<details>
<summary>How the fault tests stay isolated</summary>

The `stability` variant uses `dev.zane.pretttv.stability`, has no Home/app-launcher intent filter, and contains the generated artwork fixture provider. The owner's release package and preferences are not used by these tests.

The fixture provider runs inside the same sandbox because the TCL's cross-app autostart policy blocks an externally hosted test provider. No TV policy is changed.

</details>

## Navigation and lifecycle

Leave the TV idle with **C Balanced** selected and prettTV already configured as default Home. Do not run these tests while someone is watching.

```powershell
./tests/stress-home.ps1 -Tv '<serial>' -Cycles 15 -RestartCycles 5 -OutputDirectory '<local-results-directory>'
./tests/lifecycle-home.ps1 -Tv '<serial>' -FixtureApk './launcher/testapp/build/outputs/apk/debug/testapp-debug.apk' -Cycles 10 -OutputDirectory '<local-results-directory>'
```

All scripts accept `-Adb '<adb-path>'` and `-Port <adb-server-port>`.

| Script | Actions and checks |
|---|---|
| `stress-home.ps1` | Sends D-pad directions, Back and Home without opening media or selecting settings. Checks focus and process continuity each cycle. Cold-start checks force-stop only prettTV and verify return through the existing Home resolver. |
| `lifecycle-home.ps1` | Temporarily installs a do-nothing fixture app, verifies Home stops, returns with Back, and checks the same focus, viewport and process. Removes the fixture afterward and refuses to overwrite an existing installation. |

No launcher is disabled or uninstalled. Foreground guards stop further input when another app becomes active. Timing includes ADB overhead and measures a focusable Home, not complete artwork or playback readiness.

After a run:

- Check the crash buffer and Android exit reasons. Package-update and test-requested force-stop exits are expected; crashes and ANRs are not.
- Compare preserved preference values before and after temporary app tests, excluding transient focus.
- Keep raw device reports outside the repository.

## Comparing scrolling changes

With the TV idle on C Balanced, run the same route on the old and new signed release:

```powershell
./tests/measure-home-motion.ps1 -Tv '<serial>' -OutputDirectory '<local-before-directory>'
# Install the candidate release, then repeat with a separate after directory.
./tests/measure-home-motion.ps1 -Tv '<serial>' -OutputDirectory '<local-after-directory>'
./tests/verify-row-navigation.ps1 -Tv '<serial>' -OutputDirectory '<local-navigation-directory>'
```

The motion script warms the route once, resets only the app's graphics counters, then repeats two paced D-pad routes through apps and content shelves. It saves Android `gfxinfo` frame statistics and verifies that focus and the Home process survive. It does not clear caches or change animation settings.

For a meaningful comparison, keep content and device conditions the same. Consider **frame count and frame-time percentiles together**: enabling an animation adds frames. Cold startup, streaming playback and sustained 60 fps are outside this measurement. Follow [Android's rendering guidance](https://developer.android.com/topic/performance/issues/render) when investigating remaining slow frames.

The separate row-navigation check requires:

1. Left revisits the cards selected while moving right, in reverse order.
2. Held Left stops at the first card.
3. A fresh Left opens the sidebar.
4. Right returns to that same card.

This is a navigation regression check, not a frame-rate measurement. Both scripts require an idle TV already displaying prettTV.
