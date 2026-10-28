# Privacy and optional access

[Documentation](README.md) · [Project home](../README.md) · [Using prettTV](usage.md)

prettTV has no analytics SDK, advertising-identifier access or account collection. It does not ask for streaming credentials, use root operations or modify firmware. Its declared permissions cover Internet access, network-state checks, TV listings and user-confirmed package installation.

## Data stored on the TV

App order, hidden apps and titles, confirmed playback, launch recency, Watchlist, followed shows, episode-alert state, recommendation feedback, row preferences and favorite teams stay local. Weather preferences and a small recent-reading cache are stored privately on the TV; the selected location is sent to the lookup providers as described below. Hiding a title or correcting its viewing order changes prettTV on this TV; it does not change a shared streaming account. Undo is an in-memory session history.

Home stores a bounded private snapshot for offline use, separate from saved titles and recovery credentials. Cached recommendations expire after seven days; sports records have shorter expiry. Saved titles remain until removed or until supported playback confirms they were started. [Watchlist behavior](usage.md#save-titles-to-watchlist) describes the distinction.

Automatic cloud backup and device-to-device extraction are disabled explicitly, including Android 12's transfer rules. Explicit local settings exports include the allowlisted app, Home, playback and personalization preferences, including the configured weather location and units. They exclude recovery keys, device-recovery records, downloaded APKs and updater sessions. Treat an exported file as private because it can contain title history, location and preferences.

prettTV cannot inspect other apps' credentials or reliably determine whether you are signed in. Recommendation services are selected by you or inferred from the progress apps share with Android TV. Opening an app alone is not treated as proof of a login.

## Network requests

With discovery enabled, prettTV requests public catalogs, ESPN scoreboards and artwork from TVmaze, Cinemeta, Metahub and the relevant image services. Home, Blind Pick and Rabbit Hole share this content-loading path while their screen is active. App-published rows can also point to provider artwork. Those servers receive the TV's IP address and the requested URLs or catalog, show and league identifiers. Catalog requests do not include streaming credentials or an uploaded viewing-history list.

**Settings → Show discovery → Off** stops general discovery and scoreboard refreshes. Provider artwork, followed-show checks, explicitly requested team directories, weather and update checks are separate:

| Request | When it happens | Information sent |
| --- | --- | --- |
| Followed-show metadata | While Home or an original Library experience is visible; bounded checks at most daily per checked show | TVmaze/IMDb show IDs, or a title lookup when an exact year is available. Unfollow to stop checking a show. |
| Blind Pick / Rabbit Hole metadata | While either experience is open and Show discovery is enabled; bounded lookup batches | Selected title and peer IMDb/TVmaze IDs, or exact-title searches for series with a known original year. |
| Team directory | When the Favorite teams picker needs it; weekly cache | Requested league. Favorite team selections are applied locally. |
| Weather ZIP lookup | When you configure a US ZIP | The entered ZIP in an HTTPS request to `api.zippopotam.us`. |
| Weather observations | When enabled and Home is visible; about every 30 minutes | Approximate ZIP coordinates and nearby station requests to `api.weather.gov`, with an identifying prettTV user agent. |
| Official update check | At most daily while Home is visible, or when you choose Check now | GitHub release/asset requests and a prettTV updater user agent. |
| APK download | Only after choosing Download | The selected official GitHub release-asset URL. |

As with other HTTPS services, these servers receive the TV's IP address. No scheduled alarm wakes Home for episode or update checks. See [content sources](content-and-sports.md) and the [third-party notices](../THIRD_PARTY_NOTICES.md) for provider attribution.

## Blind Pick and Rabbit Hole

These Library experiences select from app listings, Watchlist and available catalogs. Filtering, preferred-service ordering, random selection and connection matching happen locally. They do not read streaming accounts or send a complete viewing-history list to a recommendation service.

Optional metadata lookups use only the fixed HTTPS hosts `api.tvmaze.com` and `v3-cinemeta.strem.io`. The providers receive the TV's IP and the requested identifiers or series search terms; these requests can reveal which titles are being explored. No account token or streaming credential is included.

A lookup batch considers a starting title and up to twelve identifiable peers, permits at most eight HTTP requests and uses a twelve-second request budget. Responses are limited to 600 KB. A private metadata file is capped at 900 KB and 96 records containing genres, cast/director names, runtime, year and source attribution. Records are fresh for seven days and may be reused during failures for up to 30 days. They are separate from Watchlist and playback history.

Leaving an experience cancels its metadata work. **Show discovery → Off** prevents new enrichment requests; existing local facts and available app listings can still be used. The experiences also share Home's ordinary content repository, so its bounded catalog, followed-show and sports refresh logic can run while they are open. No discovery alarm or persistent background service is added.

## Weather

Weather is off until you set a location. It uses only the US ZIP you enter, not GPS, IP-based location inference or a Google account address. Zippopotam.us receives that ZIP and the connection's IP address, then returns a place label and approximate coordinates. NWS receives the requested coordinates or station identifiers and the connection's IP address. These requests do not contain your streaming credentials, titles or viewing-history list. The providers operate their own services; prettTV does not claim that they avoid request logging or retain no IP/location data.

The TV privately stores the selected ZIP, resolved place and coordinates, enabled state and units. A separate bounded cache holds the recent station reading and its observation time. Cached readings become stale after 30 minutes and are no longer displayed after two hours. Network work runs only while Home is active; stopping Home or changing the location cancels the active request and rejects outdated results. Turning weather off stops its refreshes independently of the discovery setting; it retains the saved location, units and cache for later use.

There is no bundled personal ZIP or automatic location lookup on a new installation. Public gallery images retain their historical v0.2.2 captures rather than exposing the configured weather location. Provider and data-licence attribution is in [third-party notices](../THIRD_PARTY_NOTICES.md#zip-lookup--zippopotamus-and-geonames).

## Optional access

The installers do not grant Notification or Accessibility access. You enable these helpers in Android settings and can turn them off there.

| Feature | Access | What prettTV uses it for |
| --- | --- | --- |
| Shared viewing progress | TV listings | Read Watch Next and app-published program rows. |
| Playback tracking | Notification access | Observe active media-session metadata for supported titles. Notification text is not read or retained. |
| RBTV game handoff | Accessibility access | Navigate RBTV during an explicit, bounded game request. It does not intercept Home or choose a server. |
| Settings button | Accessibility key filtering | Handle the Settings key, including TCL's notification-key mapping, across apps. Subscribes only to prettTV's own window-state events and discards them. No window-content access, key logging or network requests. All unrelated keys pass through. |
| EarthCam idle handoff | Select prettTV's EarthCam service as the system screensaver | Open the already-installed official app once when Android starts the dream; no Accessibility, overlay or account access. |
| prettTV updates | Allow installation from prettTV, then Android's installation confirmation | Install a verified newer prettTV APK only after you choose Install. It does not update RBTV or other apps. |
| Full stock recovery | Authorized local TCP debugging and the private setup record | Restore the recorded Home, disabled-app states and DNS after two confirmations. |
| Force stop an app | Existing authorized local TCP debugging key and device record | After an explicit app-menu confirmation, stop only the selected launchable app and verify its stopped state. No storage clearing, uninstall, network reset or automatic relaunch. |

Stock recovery connects only to the TV itself at `127.0.0.1:5555`, using its own private key rather than the computer's ADB key. It does not enable debugging automatically. Keep the original device-specific setup records outside GitHub and public releases. Read the [recovery guide](stock-recovery.md) for prerequisites and failure behavior.

The app-menu Force stop action reuses that existing TV-local key. It does not generate or authorize a new key, import account information, or invoke stock recovery. It checks the saved TV identity, current Android user and selected app before issuing a bounded stop command. System controls and Home are excluded. If prerequisites are missing, it offers Android's App info screen. No background app-killing service or remote command endpoint is exposed.

After a successful stock restore, the optional playback, game and Settings-button helpers pause. **Resume prettTV helpers** in prettTV Settings reactivates them using their existing Android permissions if you return to the launcher later.

## Companion apps and scope

These statements describe prettTV. They do not apply to RBTV+ or any other installed streaming app. RBTV is a separate third-party application with its own terms and permissions; prettTV's MIT license does not cover it. The [companion-app review](companion-apps.md) records the inspected build and the limits of that review.

Optional TV Bro browsing, filter-list downloads and Google remote voice requests are handled by those apps and their service providers, outside prettTV. Browser permissions and any device-level Private DNS provider have their own scope; prettTV does not configure them or claim that their activity stays on the TV. See [Browser and remote voice](browser-and-voice.md).

The optional [EarthCam idle handoff](earthcam-idle.md) stores only a private last-attempt elapsed timestamp and a short launch-status value. It opens EarthCam's fixed app entry and does not request or embed EarthCam camera feeds. EarthCam's network traffic, advertisements, accounts and purchases remain governed by that app. The bridge starts only through Android's screensaver lifecycle; it adds no alarm or background polling.

prettTV does not remove advertising inside other apps. Its APK and installer do not disable system launchers. A separate device setup may have changed Google TV Home, other apps or DNS; [full troubleshooting recovery](stock-recovery.md) restores only the original recorded changes, while keeping installed apps and logins.
