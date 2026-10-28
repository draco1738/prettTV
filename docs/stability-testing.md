# Repeatable stability checks

[prettTV](../README.md) / [Documentation](README.md) / Stability testing

Use these checks to reproduce build validation, artwork failures and remote navigation behavior. For recorded outcomes, see the [validation record](validation.md).

| Check | What it covers | Device requirement |
|---|---|---|
| [Build checks](#build-checks) | Unit tests, lint and test APKs | Computer only |
| [Artwork fault tests](#artwork-fault-tests) | Decode failures, queue completion, cancellation and cache recovery | Authorized ADB device |
| [Force-stop app-menu tests](#force-stop-app-menu-tests) | Eligibility, confirmation, cancellation, recreation and recovery-busy handling | Idle authorized test device |
| [Navigation and lifecycle](#navigation-and-lifecycle) | Focus, process continuity, cold Home and stop/return | Idle TV on the selected layout |
| [Scrolling comparison](#comparing-scrolling-changes) | Frame statistics and row-navigation regression | Idle TV on the selected layout |
| [Final feature-release gate](#final-feature-release-gate) | All-layout stress, lifecycle, failure checks and a one-hour soak | Idle TV and isolated fixtures |

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

The script temporarily installs an isolated target and test runner, executes the Android fault suite, and removes both packages in `finally`. It refuses to replace pre-existing test installations. Read the reported test count from the current run rather than carrying forward an older release's count.

| Fixture or check | Required behavior |
|---|---|
| Valid/large images, corrupted bytes, oversized streams, delayed responses | Exercise image loading and failure handling |
| Queue of 100 requests | Every accepted or rejected request finishes |
| Screen replacement and closure | Zero late callbacks |
| Cache recovery | Uses only the isolated target's cache |
| Attached card focus and lifecycle | Rapid reversal cancels stale motion; hidden/detached cards settle and recover |
| Native card raster checks | Artwork moves within covered bounds while text, progress and outlines stay fixed; contained portraits remain visible |

<details>
<summary>How the fault tests stay isolated</summary>

The `stability` variant uses `dev.zane.pretttv.stability`, has no Home/app-launcher intent filter, and contains the generated artwork fixture provider. The owner's release package and preferences are not used by these tests.

The fixture provider runs inside the same sandbox because the TCL's cross-app autostart policy blocks an externally hosted test provider. No TV policy is changed.

</details>

## Force-stop app-menu tests

After the build command above, use an **idle authorized test device** and PowerShell 7. Set `ANDROID_HOME` to the SDK root (or pass `-Aapt '<path-to-aapt2.exe>'`), then run from the repository root:

```powershell
./tests/android-force-stop-tests.ps1 -Tv '<serial>' -OutputDirectory 'E:/TV-validation/force-stop'
```

The runner verifies the APK package IDs, temporarily installs the isolated stability target, instrumentation runner and `fi.jommi.rivi.testapp` fixture, and runs only `forceStopOnly` checks. It requires an explicit `FORCE STOP PASS` result with at least seven tests, including the recovery-busy regression. It refuses to overwrite any existing test installation and removes only packages this invocation installed, with removal verified in `finally`.

These tests open native screens and exercise long-press menus, confirmation, held-key protection, cancellation, activity recreation and missing-setup fallback. **They do not force-stop production apps, configure debugging or execute stock recovery.** Positive-action tests have no recovery plan in their isolated sandbox and must fail before connecting. Keep the TV and remote idle; results are written to the chosen E: directory. `-Adb` and `-Port` select an existing authorized ADB connection.

## Navigation and lifecycle

Leave the TV idle with the selected layout visible and prettTV already configured as default Home. Use `-Layout CINEMA`, `QUIET` or `BALANCED` to assert the expected layout. The stress, lifecycle and soak harnesses require an absolute output directory on E:. Do not run these tests while someone is watching.

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

## Final feature-release gate

After integration, run the final candidate through the following checks and record results in [Validation](validation.md):

| Area | Required evidence |
| --- | --- |
| Static checks | Successful build and full tests; no lint errors or new unexplained warnings. Review existing warnings individually. |
| Failure handling | Malformed/restored preferences, duplicate identities, corrupt snapshots/artwork, interrupted writes/downloads, cancellation and rejected updates. Use isolated fixtures for simulated low storage and corruption. |
| Remote navigation | At least 500 scripted actions per layout, including held-key boundaries, direction reversals, sidebar return, options, hiding, arranging, undo and Watchlist paging. |
| Lifecycle | 25 cold starts and 50 leave/return cycles across A, B and C; preserved settings and usable focus. |
| Soak | At least one hour with navigation and idle periods; record process continuity, memory trend, frame statistics and refresh diagnostics. |
| Performance | Identical warmed before/after routes, frame-time percentiles, janky-frame counts, startup timing and memory. Investigate repeatable regressions. |
| Recovery/update | Verify recovery readiness and both cancellation paths. Test destructive restore with isolated/fake state; do not execute it on the owner's TV. Installation rejection must preserve the existing app. |

The guarded soak script requires an absolute local output directory on the connected E: drive:

```powershell
./tests/soak-home.ps1 -Tv '<serial>' -Layout BALANCED `
    -DurationSeconds 3600 -OutputDirectory 'E:/TV-validation/soak'
```

The default and minimum are 3,600 seconds; longer runs remain supported. It stops if another app becomes active or Home unexpectedly loses focus/process continuity. ADB commands have a 30-second limit. Long observation gaps or insufficient diagnostic samples invalidate the soak, so a suspended computer cannot count toward the requested coverage. At the default two-minute sampling interval, a one-hour run needs at least 26 diagnostic samples, including initial and final observations. The harness selects the visible Home instance when Android retains older stopped instances; `tests/harness-diagnostics.ps1` tests that parsing and duration limits without contacting a TV. It measures a Home session; separate lifecycle tests cover leaving for the fixture and returning.

If the owner shortens a running test after it started, preserve that run's original requested duration and interrupted result. A separate review may establish the approved shorter duration from recorded monotonic samples, continuous coverage, unchanged process identity, valid focus, refresh diagnostics and a final read-only observation. That review must identify the owner's revised requirement; it must not relabel an incomplete longer run as a harness pass.

A crash, ANR, data-loss case, stuck navigation or unresolved critical/high finding blocks release. Fix defects, add regression coverage, and rerun affected checks before reporting the candidate as ready.
