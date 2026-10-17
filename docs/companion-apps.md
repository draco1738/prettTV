# RBTV+ companion installer

Included by default in the owner-requested prettTV 0.1.1 installation bundle and attached as an individual release asset. Both installers verify the pinned SHA-256 before a fresh install and preserve an existing RBTV installation. The explicit launcher-only flag opts out. It is not incorporated into prettTV's source/APK and is not covered by prettTV's MIT license.

- App/package: RBTV+ / `com.rblive.app`
- Version: 3.0.320 (version code 320)
- Minimum Android API: 22; target API: 34; native ABI: armeabi-v7a
- Download page: https://rbtvplus.tv/download-rbtv-apk1/
- Original file: https://app99.mcvd8p.cfd/apks/com.rblive.app-release-320-v3.0.320.apk
- File size: 13,063,867 bytes
- SHA-256: `5e7f0b4eb571d4c662678cad8cd18254ec8abd55ead230d2f8e7ab684ff8f279`
- Signing certificate SHA-256: `9dc7d4202e31b81fcef60937d02cfeac67e260559c299b47d82cc0c521f9117e`
- Signature inspection: APK v1/v2 verified. This identifies the downloaded build; it does not independently establish the publisher's identity or safety.
- Review/download date: 2026-10-17.

Manifest, signature and selected entry-point code were inspected. The manifest requests network access, legacy storage, package visibility, package-install requests and advertising identifiers/referrer access; cleartext traffic is allowed. No SMS, contacts, microphone, camera, accessibility-service or device-admin capability was found in the inspected manifest. Installation of other packages and other special privileges were not granted during setup.

The TV catalog opened. It advertised version 3.0.323 inside the app; that update was not installed or reviewed. Playback of a specific live stream was not verified. This was a limited review, not a malware certification. prettTV's privacy claims do not apply to the companion app.

No verified external game/channel interface was found in 3.0.320. Its launcher/home entries can open the app, but the TV player activity is not exported. prettTV’s optional accessibility helper instead navigates the visible RBTV catalog, matching both team names and kickoff/date in supported sport tabs. It leaves server choice to the user; it does not extract streams or bypass player controls. ESPN broadcast labels alone are never used as game identity.

HD Streamz and SportioTV were removed at the owner's request and are not included in this release. Their prior local installers are kept outside the repository; they are not silently reinstalled by prettTV.
