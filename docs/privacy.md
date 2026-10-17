# Privacy and optional access

[Documentation](README.md) · [Project home](../README.md) · [Using prettTV](usage.md)

prettTV has no analytics SDK, advertising-identifier access or account collection. It does not ask for streaming credentials, use root operations or modify firmware. Its declared app permissions are Internet and TV-listings access.

## Data stored on the TV

App order, hidden apps and titles, confirmed playback and launch recency stay local. Hiding a title or correcting its viewing order changes prettTV on this TV; it does not change a shared streaming account.

prettTV cannot inspect other apps' credentials or reliably determine whether you are signed in. Recommendation services are selected by you or inferred from the progress apps share with Android TV. Opening an app alone is not treated as proof of a login.

## Network requests

With discovery enabled, prettTV requests public catalogs, ESPN scoreboards and artwork from TVmaze, Cinemeta, Metahub and the relevant image services. App-published rows can also point to provider artwork. Those servers receive the TV's IP address and the requested URLs or catalog, show and league identifiers. prettTV does not send them account tokens or viewing history.

**Settings → Show discovery → Off** stops public catalog requests. Provider artwork may still be requested for shared content. See [content sources](content-and-sports.md) and the [third-party notices](../THIRD_PARTY_NOTICES.md) for source details.

## Optional access

The installers do not grant Notification or Accessibility access. You enable these helpers in Android settings and can turn them off there.

| Feature | Access | What prettTV uses it for |
| --- | --- | --- |
| Shared viewing progress | TV listings | Read Watch Next and app-published program rows. |
| Playback tracking | Notification access | Observe active media-session metadata for supported titles. Notification text is not read or retained. |
| RBTV game handoff | Accessibility access | Navigate RBTV during an explicit, bounded game request. It does not intercept Home or choose a server. |
| Full stock recovery | Authorized local TCP debugging and the private setup record | Restore the recorded Home, disabled-app states and DNS after two confirmations. |

Stock recovery connects only to the TV itself at `127.0.0.1:5555`, using its own private key rather than the computer's ADB key. It does not enable debugging automatically. Keep the original device-specific setup records outside GitHub and public releases. Read the [recovery guide](stock-recovery.md) for prerequisites and failure behavior.

After a successful stock restore, the optional playback and game helpers pause. **Resume prettTV helpers** in prettTV Settings reactivates them using their existing Android permissions if you return to the launcher later.

## Companion apps and scope

These statements describe prettTV. They do not apply to RBTV+ or any other installed streaming app. RBTV is a separate third-party application with its own terms and permissions; prettTV's MIT license does not cover it. The [companion-app review](companion-apps.md) records the inspected build and the limits of that review.

prettTV does not remove advertising inside other apps. Its APK and installer do not disable system launchers. A separate device setup may have changed Google TV Home, other apps or DNS; [full troubleshooting recovery](stock-recovery.md) restores only the original recorded changes, while keeping installed apps and logins.
