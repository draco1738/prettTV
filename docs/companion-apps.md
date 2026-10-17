# RBTV+ companion installer

[prettTV](../README.md) / [Documentation](README.md) / Companion apps

The prettTV installation bundle includes RBTV+ by default, introduced at the owner's request in v0.1.1. It is also attached as an individual release asset. Both installers verify the pinned SHA-256 before a fresh install and preserve an existing RBTV installation.

To install only prettTV, use `-LauncherOnly` with `install.ps1` or `--launcher-only` with `install.sh`.

> [!NOTE]
> RBTV+ is a separate app, not part of prettTV's source or APK. It is not covered by prettTV's MIT license or privacy claims. The review below is limited and is not a malware certification.

## Pinned build

| Detail | Recorded value |
|---|---|
| App / package | RBTV+ / `com.rblive.app` |
| Version | **3.0.320** · version code 320 |
| Android API | Minimum 22 · target 34 |
| Native ABI | `armeabi-v7a` |
| File size | 13,063,867 bytes |
| Review / download date | October 17, 2026 |
| Source page | [RBTV+ download page](https://rbtvplus.tv/download-rbtv-apk1/) |
| Downloaded file | [Original 3.0.320 APK](https://app99.mcvd8p.cfd/apks/com.rblive.app-release-320-v3.0.320.apk) |

**APK SHA-256**

```text
5e7f0b4eb571d4c662678cad8cd18254ec8abd55ead230d2f8e7ab684ff8f279
```

**Signing certificate SHA-256**

```text
9dc7d4202e31b81fcef60937d02cfeac67e260559c299b47d82cc0c521f9117e
```

APK v1/v2 signatures were verified. This identifies the downloaded build; it does not independently establish the publisher's identity or safety.

## Review scope

| Inspected or observed | Result |
|---|---|
| Manifest, signature and selected entry-point code | Inspected for the pinned 3.0.320 build |
| Requested capabilities | Network access, legacy storage, package visibility, package-install requests, advertising identifiers/referrer access; cleartext traffic allowed |
| Absent from the inspected manifest | SMS, contacts, microphone, camera, accessibility-service and device-admin capabilities |
| Extra privileges during setup | Installation of other packages and other special privileges were not granted |
| TV catalog | Opened |
| Advertised 3.0.323 update | Not installed or reviewed |
| Specific live-stream playback | Not verified |

## Live sports handoff

No verified external game/channel interface was found in 3.0.320. Its launcher/home entries can open the app, but the TV player activity is not exported.

prettTV's optional accessibility helper navigates the visible RBTV catalog. In supported sport tabs, it matches **both team names and kickoff/date**, then leaves server choice to the user. It does not extract streams or bypass player controls. ESPN broadcast labels alone are never used as game identity.

## Removed companions

HD Streamz and SportioTV were removed at the owner's request and are not included in this release. Their prior local installers are kept outside the repository; they are not silently reinstalled by prettTV.
