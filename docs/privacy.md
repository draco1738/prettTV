# Privacy and optional access

[Documentation](README.md) · [Project home](../README.md) · [Using prettTV](usage.md)

prettTV has no analytics SDK, advertising-identifier access or account collection. It does not ask for streaming credentials, use root operations or modify firmware. Its declared permissions cover Internet access, network-state checks, TV listings, user-confirmed package installation and optional notification posting for shortcut status messages.

## Data stored on the TV

App order, hidden apps and titles, confirmed playback, observed provider-progress recency, launch recency, Watchlist, followed shows, episode-alert state, recommendation feedback, row preferences and favorite teams stay local. Weather preferences and a small recent-reading cache are stored privately on the TV; the selected location is sent to the lookup providers as described below. Hiding a title or correcting its viewing order changes prettTV on this TV; it does not change a shared streaming account. Undo is an in-memory session history.

Home stores a bounded private snapshot for offline use, separate from saved titles and recovery credentials. Cached recommendations expire after seven days; sports records have shorter expiry. Saved titles remain until removed or until supported playback confirms they were started. [Watchlist behavior](usage.md#save-titles-to-watchlist) describes the distinction.

Automatic cloud backup and device-to-device extraction are disabled explicitly, including Android 12's transfer rules. Explicit local settings exports include the allowlisted app, Home, playback and personalization preferences, including the configured weather location and units. They exclude recovery keys, device-recovery records, downloaded APKs and updater sessions. Treat an exported file as private because it can contain title history, location and preferences.

prettTV cannot inspect other apps' credentials or reliably determine whether you are signed in. Recommendation services are selected by you or inferred from the progress apps share with Android TV. Opening an app alone is not treated as proof of a login.

## Network requests

With discovery enabled, prettTV requests public catalogs, ESPN scoreboards and artwork from TVmaze, Cinemeta, Metahub and the relevant image services. Home, Blind Pick and Rabbit Hole share this content-loading path while their screen is active. App-published rows can also point to provider artwork. Those servers receive the TV's IP address and the requested URLs or catalog, show and league identifiers. Catalog requests do not include streaming credentials or an uploaded viewing-history list.

**Settings → Show discovery → Off** stops general discovery and scoreboard refreshes. Provider artwork, followed-show checks, explicitly requested team directories, weather and update checks are separate:

| Request | When it happens | Information sent |
| --- | --- | --- |
| Followed-show metadata | While Home or an original Library experience is visible; bounded checks at most daily per checked show | TVmaze/IMDb show IDs, or a title lookup when an exact year is available. Unfollow to stop checking a show. |
| Blind Pick / Rabbit Hole metadata | While either experience is open and Show discovery is enabled; bounded lookup batches | Selected title and peer IMDb/TVmaze IDs, or exact-title searches for series. A known year must match; a missing year permits only one unambiguous exact series match. |
| Team directory | When the Favorite teams picker needs it; weekly cache | Requested league. Favorite team selections are applied locally. |
| Weather ZIP lookup | When you configure a US ZIP | The entered ZIP in an HTTPS request to `api.zippopotam.us`. |
| Weather observations | When enabled and Home is visible; about every 30 minutes | Approximate ZIP coordinates and nearby station requests to `api.weather.gov`, with an identifying prettTV user agent. |
| Official update check | At most daily while Home is visible, or when you choose Check now | GitHub release/asset requests and a prettTV updater user agent. |
| APK download | Only after choosing Download | The selected official GitHub release-asset URL. |

As with other HTTPS services, these servers receive the TV's IP address. No scheduled alarm wakes Home for episode or update checks. See [content sources](content-and-sports.md) and the [third-party notices](../THIRD_PARTY_NOTICES.md) for provider attribution.

## C Balanced presentation artwork

When C Balanced opts into artwork enrichment and **Show discovery** is on, Home can send public title names or supplied TVmaze/IMDb show IDs to `api.tvmaze.com` for the visible feature and a ranked pool of at most six discovery candidates. These requests can reveal the individual titles being displayed, including the feature title. No progress, timestamps, account identifiers, launch destinations, preferences or complete viewing-history list are uploaded. Matching and service/feedback ranking stay local. Movies, untyped titles and sports are not searched as series.

Home delivers its normal catalog, sports and episode content before the optional lookups. One pass is armed when the content repository starts; ordinary refresh timers and provider notifications do not arm further artwork passes. The pass stops after three verified discovery posters, then may try the visible feature, with a shared eight-request limit (including redirects) and twelve-second network-work budget. An independent timer disconnects an active metadata connection at that deadline; private-cache I/O, connection creation and platform cleanup mean this is not a strict end-to-end timing guarantee. Discovery off prevents new lookups and display enrichment; a known offline or unverified network prevents new lookups. A/B do not opt in. Leaving Home or changing its content generation cancels the request and rejects obsolete deliveries. No artwork alarm, background service, periodic polling or Stremio installation is added.

Title matching requires an exact normalized series name and any supplied original year. Missing-year matches require one unambiguous exact name across the complete bounded search response; remake ambiguity, fuzzy names, mismatched IDs and malformed explicit IDs are rejected. The resolver uses supplied, dimension-checked poster/background image URLs, not guessed image or episode URLs. It does not infer an episode from viewing progress or synthesize a still. Image requests go to TVmaze's image CDN through the existing artwork loader, exposing the IP and requested image URL.

Responses are capped at 600 KB. A separate private presentation cache holds at most 96 records / 500 KB: hashed matching evidence, timestamps, positive or negative results, artwork URLs and attribution. Positive matches expire after seven days, unmatched titles after one day, and transport/parser failures after one hour; rate limiting pauses requests for one hour. Cached artwork metadata can be reused offline while valid. This cache never replaces provider artwork fields, saved titles, launch destinations or playback history. TVmaze attribution and data licence remain linked under **About prettTV · Credits → TVmaze**.

## Blind Pick and Rabbit Hole

These Library experiences select from app listings, Watchlist and available catalogs. Filtering, preferred-service ordering, random selection and connection matching happen locally. They do not read streaming accounts or send a complete viewing-history list to a recommendation service.

Optional metadata lookups use only the fixed HTTPS hosts `api.tvmaze.com` and `v3-cinemeta.strem.io`. The providers receive the TV's IP and the requested identifiers or series search terms; these requests can reveal which titles are being explored. No account token or streaming credential is included.

Series title searches require one exact matching result after applying any known original year. When the year is missing, the exact name alone must be unambiguous among the returned matches. Ambiguous names and malformed explicit IMDb IDs do not become loose title matches. The fetched full TVmaze record is checked again for its ID, name and any known year before its facts are used.

A lookup batch considers a starting title and up to twelve identifiable peers, permits at most eight HTTP requests and uses a twelve-second request budget. Rabbit Hole may run **one additional automatic batch** while visible, only when the first adds facts but still produces no connections: at most two automatic batches for a starting-title selection. It does not automatically retry unchanged or offline results. **Refresh connections** remains a manual action, with the same per-batch limits.

Responses are limited to 600 KB. A private metadata file is capped at 900 KB and 96 records containing genres, cast/director names, runtime, year and source attribution. Records are fresh for seven days and may be reused during failures for up to 30 days. They are separate from Watchlist and playback history.

Leaving an experience cancels its metadata work. **Show discovery → Off** prevents new enrichment requests; existing local facts and available app listings can still be used. The experiences also share Home's ordinary content repository, so its bounded catalog, followed-show and sports refresh logic can run while they are open. No discovery alarm or persistent background service is added.

## Weather

Weather is off until you set a location. It uses only the US ZIP you enter, not GPS, IP-based location inference or a Google account address. Zippopotam.us receives that ZIP and the connection's IP address, then returns a place label and approximate coordinates. NWS receives the requested coordinates or station identifiers and the connection's IP address. These requests do not contain your streaming credentials, titles or viewing-history list. The providers operate their own services; prettTV does not claim that they avoid request logging or retain no IP/location data.

The TV privately stores the selected ZIP, resolved place and coordinates, enabled state and units. A separate bounded cache holds the recent station reading and its observation time. Cached readings become stale after 30 minutes and are no longer displayed after two hours. Network work runs only while Home is active; stopping Home or changing the location cancels the active request and rejects outdated results. Turning weather off stops its refreshes independently of the discovery setting; it retains the saved location, units and cache for later use.

There is no bundled personal ZIP or automatic location lookup on a new installation. The owner-approved 0.4.0 gallery contains actual TV captures, including the displayed weather location; it is not a preset for new installations. Provider and data-licence attribution is in [third-party notices](../THIRD_PARTY_NOTICES.md#zip-lookup--zippopotamus-and-geonames).

## Optional access

The installers do not grant Notification or Accessibility access. You enable these helpers in Android settings and can turn them off there.

| Feature | Access | What prettTV uses it for |
| --- | --- | --- |
| Shared viewing progress | TV listings | Read Watch Next and app-published program rows. |
| Playback tracking | Notification access | Observe active media-session metadata for supported titles. Notification text is not read or retained. |
| Shortcut status messages | Optional notification posting permission on Android 13+ | Let Android show a brief Bluetooth result while another app is foreground. This is separate from notification-reading access. **Inputs → Audio devices → Enable shortcut messages** offers the normal Android permission request; no background prompt or overlay bypass is used. |
| RBTV game handoff | Accessibility access | Navigate RBTV during an explicit, bounded game request. It does not intercept Home or choose a server. |
| Home compatibility | Accessibility window-state events | Observe only stock launcher window names and return recognized Home screens to prettTV. No screen-content access, key filtering, microphone access or event logging. Gemini results and other activities stay open. |
| Remote buttons | Accessibility key filtering | Handle Settings, documented TCL app shortcuts and the speaker-Mute double-press gesture across apps. Subscribes only to prettTV's own window-state events and discards them. No window-content access or key logging. Home, volume, navigation, controllers and unknown keys pass through. |
| EarthCam idle handoff | Select prettTV's EarthCam service as the system screensaver | Open the already-installed official app once when Android starts the dream; no Accessibility, overlay or account access. |
| prettTV updates | Allow installation from prettTV, then Android's installation confirmation | Install a verified newer prettTV APK only after you choose Install. It does not update RBTV or other apps. |
| Full stock recovery | Authorized local TCP debugging and the private setup record | Restore the recorded Home, disabled-app states and DNS after two confirmations. |
| Bluetooth controls | Existing authorized TV-local recovery connection | Read Bluetooth state and change it after choosing the on/off action in Inputs → Audio devices or quickly pressing speaker Mute twice with the Remote buttons helper enabled. |
| Force stop an app | Existing authorized local TCP debugging key and device record | After an explicit app-menu confirmation, stop only the selected launchable app and verify its stopped state. No storage clearing, uninstall, network reset or automatic relaunch. |

Stock recovery connects only to the TV itself at `127.0.0.1:5555`, using its own private key rather than the computer's ADB key. It does not enable debugging automatically. Keep the original device-specific setup records outside GitHub and public releases. Read the [recovery guide](stock-recovery.md) for prerequisites and failure behavior.

The app-menu Force stop action reuses that existing TV-local key. It does not generate or authorize a new key, import account information, or invoke stock recovery. It checks the saved TV identity, current Android user and selected app before issuing a bounded stop command. System controls and Home are excluded. If prerequisites are missing, it offers Android's App info screen. No background app-killing service or remote command endpoint is exposed.

**Inputs → Audio devices** also reuses the existing authorized TV-local key. Opening the panel does not create a key, request a new authorization, change Bluetooth, or invoke stock recovery. A requested on/off action is followed by a state check; **Manage devices** opens the TV's own controls and remains the fallback when direct switching is unavailable. Switching Bluetooth off disconnects Bluetooth audio devices and remotes. The control does not send device information to a remote server.

On supported TCL firmware, Bluetooth off selects and verifies the built-in speaker route using TCL's existing audio-output setting. A private local marker remembers a previous Bluetooth-only selection so it can be restored on Bluetooth on while the output is still prettTV's speaker selection. A different manual selection wins. The marker contains only the known numeric output mode, stays outside settings exports, and holds no Bluetooth address or device name. Speaker and headphone volumes remain under TCL's own control.

The enabled Remote buttons helper can request the same Bluetooth change after a quick double press of the TCL speaker-Mute key (`VOLUME_MUTE`, 164). It leaves both native mute events untouched, with no delay or replay, and shows a brief checked result without navigating away from the current app. The microphone-Mute key (91) does not trigger this action. Gesture detection does not record a key history or send input to a server, and it does not create or authorize a new debugging key. The Inputs panel remains available when the helper or local setup is unavailable.

After a successful stock restore, the optional playback, game, remote-button and Home compatibility helpers pause. Home compatibility also suspends its redirect while the confirmed restore is running. **Resume prettTV helpers** in prettTV Settings reactivates them using their existing Android permissions if you return to the launcher later.

## Companion apps and scope

These statements describe prettTV. They do not apply to RBTV+ or any other installed streaming app. RBTV is a separate third-party application with its own terms and permissions; prettTV's MIT license does not cover it. The [companion-app review](companion-apps.md) records the inspected build and the limits of that review.

Optional TV Bro browsing, filter-list downloads and Google remote voice requests are handled by those apps and their service providers, outside prettTV. Browser permissions and any device-level Private DNS provider have their own scope; prettTV does not configure them or claim that their activity stays on the TV. See [Browser and remote voice](browser-and-voice.md).

The optional [EarthCam idle handoff](earthcam-idle.md) stores only a private last-attempt elapsed timestamp and a short launch-status value. It opens EarthCam's fixed app entry and does not request or embed EarthCam camera feeds. EarthCam's network traffic, advertisements, accounts and purchases remain governed by that app. The bridge starts only through Android's screensaver lifecycle; it adds no alarm or background polling.

The owner's TCL now uses its stock screensaver and no longer has EarthCam installed. The optional bridge remains in prettTV for users who choose it; an upgrade does not install EarthCam or select its screensaver.

prettTV does not remove advertising inside other apps. Its APK and installer do not disable system launchers. A separate device setup may have changed Google TV Home, other apps or DNS; [full troubleshooting recovery](stock-recovery.md) restores only the original recorded changes, while keeping installed apps and logins.
