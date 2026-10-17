# Build and test prettTV

[← prettTV](../README.md) · [Documentation](README.md) · [Installation](installation.md)

prettTV is a native Android TV application built with Kotlin and Android Views/Canvas. The public app ID is **`dev.zane.pretttv`**. The source namespace `fi.jommi.rivi` preserves its Rivi origin.

## Build requirements

| Tool | Version |
| --- | --- |
| JDK | **17** |
| Android SDK platform | **34** |
| Android SDK build-tools | **35.0.0** |
| Gradle | **8.11.1**, supplied by the checksum-pinned wrapper |
| Android Gradle Plugin | **8.7.3** |
| Kotlin | **2.1.0** |

Use the repository's Gradle wrapper; a separate Gradle installation is unnecessary. The app supports API 26 and newer, and targets API 34.

Set `JAVA_HOME` to your JDK 17 installation. Configure the SDK location in the ignored **`launcher/local.properties`** file. Use an absolute path, for example:

```properties
# Windows example; forward slashes avoid properties-file escaping.
sdk.dir=E:/Android/Sdk
```

On macOS or Linux, use your SDK's absolute path instead, such as `/home/you/Android/Sdk`. Install the required SDK platform and build-tools before running the build.

## Build from the repository root

### Windows · PowerShell

```powershell
.\launcher\gradlew.bat -p launcher :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

### macOS / Linux · Bash

```sh
./launcher/gradlew -p launcher :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

The commands run JVM tests, Android lint and the release build. Outputs:

| Output | Location |
| --- | --- |
| Release APK | `launcher/app/build/outputs/apk/release/app-release.apk` |
| JVM test report | `launcher/app/build/reports/tests/testReleaseUnitTest/index.html` |
| Android lint report | `launcher/app/build/reports/lint-results-release.html` |

To install the APK you just built, run the root installer with **`-NoBuild`** on Windows or **`--no-build`** in Bash. RBTV is included by default; add **`-LauncherOnly`** / **`--launcher-only`** to skip it. See [Installation](installation.md#installer-options) for device selection and all supported flags.

## Signing and updates

Local release builds use the local debug key unless private release signing is configured. The public GitHub workflow also produces a **debug-signed** artifact. Neither can update an installation signed with a different key.

For your own signing key, create the ignored **`launcher/signing.properties`**:

```properties
storeFile=E:/PrivateKeys/my-pretttv-key.p12
storePassword=YOUR_PRIVATE_PASSWORD
keyAlias=YOUR_KEY_ALIAS
```

Use a PKCS12 keystore. This build configuration uses the same password for the store and key. The equivalent environment variables are `RIVI_KEYSTORE`, `RIVI_KEYSTORE_PASSWORD` and `RIVI_KEY_ALIAS`; these names are retained in the inherited build configuration.

Keep signing material private and reuse your key for future updates. The signing properties and keystores are excluded from Git. Do not remove an installed app merely to work around a certificate mismatch if you need to preserve its data.

The published prettTV releases use this certificate SHA-256:

```text
46fecddaaff65d9500c6cfddee90e07c6ca3fe7941c8a405829258b5e7ff9ded
```

### Publish updater metadata

The in-app updater checks the latest stable release in **`draco1738/prettTV`**. It requires an **`update-manifest.json`** asset alongside the signed APK. Generate it from the final APK after choosing its release asset filename:

```powershell
./tools/write-update-manifest.ps1 `
    -Apk 'E:/Releases/prettTV-0.2.0.apk' `
    -BuildTools 'E:/Android/Sdk/build-tools/35.0.0' `
    -Output 'E:/Releases/update-manifest.json'
```

This Windows helper uses `aapt2` and `apksigner` to inspect the APK, verifies the production package and signature, rejects debug builds, and computes the checksum. It reads no signing keys. Its metadata fields are:

| Field | Required value |
| --- | --- |
| `schemaVersion` | `1` |
| `packageName` | `dev.zane.pretttv` |
| `versionCode` / `versionName` | The values inside the final signed APK; 0.2.0 uses code `12`. |
| `apkAsset` | Exact APK filename uploaded to the same release. |
| `apkSha256` | SHA-256 of that exact APK. |
| `signingCertificateSha256` | Its sole APK signing certificate's SHA-256. |

Upload both assets without renaming or modifying the APK afterward. The updater matches the manifest against the installed signing certificate and then the archive itself, including package, version code/name and digest. It requires a strictly newer version, rejects Android debug certificates and debuggable archives, and delegates final installation to Android with user confirmation. It never updates RBTV. The ZIP remains the initial-install option with the companion app.

## Tests and device validation

The [v0.1.10 validation record](validation.md) reports **139 JVM tests** and **10 Android fault tests** passed. Those are recorded results for that release, not a claim that every device or future build has been tested.

JVM tests cover ordering and filtering, watching chronology, row navigation, feed retention, artwork lifecycle, local ADB framing and staged recovery failures. Feature tests cover identity matching, Watchlist consumption, row preferences, recommendation feedback, episode policy, sports favorites, offline snapshots and update validation/cancellation. Android tests exercise real storage, decoding, bounded artwork queues, cancellation, cache recovery and recovery storage/dialog behavior in an isolated app ID.

Build the device-test APKs from the repository root:

```powershell
.\launcher\gradlew.bat -p launcher :app:assembleStability :app:assembleStabilityAndroidTest :testapp:assembleDebug
```

```sh
./launcher/gradlew -p launcher :app:assembleStability :app:assembleStabilityAndroidTest :testapp:assembleDebug
```

The `stability` variant uses `dev.zane.pretttv.stability`, separate from the installed launcher, and has no Home or app-launcher intent filter. Test fixtures and instrumentation are absent from the release APK.

Use [Repeatable stability checks](stability-testing.md) for the actual device-test, lifecycle, navigation and scrolling commands. They require an authorized device; navigation checks require an idle TV. Keep raw results local because they can include viewing history and installed-app details.

For the 0.2.0 release gate, review lint findings individually, run failure and migration checks, exercise all three layouts, and complete the navigation/lifecycle/soak protocol. Record outcomes only after running the final candidate; a successful build or an earlier version's results do not establish that gate has passed.

## What CI verifies

The [build workflow](../.github/workflows/build.yml) runs for pushes to `main` and pull requests. It uses Ubuntu 24.04 and JDK 17 to:

1. Run JVM tests and Android lint.
2. Build the release, isolated stability target, instrumentation APK and lifecycle fixture.
3. Upload the debug-signed release APK and test/lint reports.

CI **compiles** the Android test APKs; it does not run the Android fault tests on a TV. Release signing happens outside this public workflow. Device results and measured limitations belong in [Validation](validation.md).

## Native rendering and artwork

The home screen uses native Views/Canvas, two artwork workers and bounded caches. Visible cards request artwork progressively; off-screen cards release bitmap references. App tiles share cached artwork, and source images are contained rather than stretched.

Changed feeds replace their affected rows rather than rebuilding every card. Watchlist retains all saved entries within its 8 MiB personalization limit and displays 60 per page. Content snapshots use bounded atomic private-file writes; saved titles and follows are separate from disposable artwork/catalog caches. Recovery credentials and updater sessions are excluded from ordinary settings exports, and automatic backup/device extraction is disabled.

Twelve bundled [Dashboard Icons](https://github.com/homarr-labs/dashboard-icons) PNGs and an adapted Disney+ vector provide app marks on dark tiles with color accents. Other apps use their installed artwork. The [asset manifest](../launcher/app/src/main/assets/app-icons/sources.json) records source URLs, the pinned revision and hashes; the adjacent [license](../launcher/app/src/main/assets/app-icons/LICENSE.txt) preserves the supplied terms. See [third-party notices](../THIRD_PARTY_NOTICES.md) for attribution and derivative details.

## Code and reference map

| Area | Where to look |
| --- | --- |
| Native launcher implementation | [`launcher/app/src/main`](../launcher/app/src/main) |
| JVM tests | [`launcher/app/src/test`](../launcher/app/src/test) |
| Android fault tests | [`launcher/app/src/androidTest`](../launcher/app/src/androidTest) |
| Device validation scripts | [`tests`](../tests) |
| Stock recovery setup and constraints | [Stock recovery](stock-recovery.md) |
| Release evidence and measurements | [Validation](validation.md) |
| Original Rivi design | [Upstream architecture](ARCHITECTURE.md) — historical Rivi v0.15 documentation |

The upstream architecture document and original Rivi benchmarks describe the inherited project, not the current prettTV app. Keep that distinction when citing performance, permissions or Home-button behavior. Attribution and asset licensing live in [Third-party notices](../THIRD_PARTY_NOTICES.md).
