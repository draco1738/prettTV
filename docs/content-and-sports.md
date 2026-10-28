# Content and live sports

[Documentation](README.md) · [Project home](../README.md) · [Using prettTV](usage.md)

prettTV combines content that apps publish to Android TV with optional public catalogs. It can only display the progress, recommendations and launch actions those sources make available.

## At a glance

| Area | Source and ordering |
| --- | --- |
| **Large feature** | Most recent eligible playback evidence, using provider timestamps as an initial baseline and later progress observations when available. Falls back to discovery when no eligible watching item exists. |
| **Continue watching · Shows / Movies** | Android TV Watch Next and app-published continuation channels, newest first within each row. |
| **Watchlist** | Titles saved locally by you, newest additions first; 60 per page, with all saved entries retained within the storage limit. |
| **New episodes** | Unread TVmaze releases for explicitly followed shows, after the initial baseline. Appears only when alerts exist. |
| **Suggestions** | App feeds prioritized by your chosen services or shared viewing activity, then fallback sources. |
| **Discover** | A balanced provider mix that excludes titles already in watching, Suggestions, Trending and New releases. |
| **Trending** | Labeled popular/trending app channels and conservative Cinemeta Popular matches, then general catalog entries. |
| **New releases** | Entries with supplied release dates within the past 120 days. |
| **Live sports** | Live and upcoming ESPN fixtures, favorite teams first within each group, plus sports-app shortcuts and shared live-event cards. |

Discovery rows offer limits of 20, 40 or 60 titles. Watchlist uses independent 60-entry pages. Catalog listings and episode releases do not guarantee a playable stream, subscription entitlement or availability in your region.

## Continue watching and the large feature

Allow **TV listings** so prettTV can read shared program rows. No streaming login is needed in prettTV. Watch Next and continuation channels are shared Android TV data sources; they are not a copy of Google's private account database. Coverage varies by app, and shared profiles can contribute someone else's titles.

The large feature and both Continue watching rows use the newest eligible playback evidence, including Stremio. Local session or manual confirmations count, and meaningful changes in an app's published progress or explicit episode metadata can advance recency when session tracking is unavailable. A timestamp, artwork or description refresh alone does not count. Initial imports and unstarted next episodes do not become newly watched simply because Home loads them. Unknown content types are not guessed into Shows or Movies.

Continue watching reorders immediately when new viewing evidence arrives, even if that row has focus, while keeping the selected title reachable. Recommendation rows can defer reordering while you browse them. Provider progress is observed on this TV; delayed or shared-account updates cannot establish the exact playback time or who watched. It does not automatically remove titles from Watchlist as confirmed local playback.

For supported apps, **Settings → Playback tracking** explains optional Notification access. Android requires that access to observe active media sessions; prettTV does not read notification text. A viewing record is confirmed only when all of these conditions hold:

- Identifiable session metadata matches a known shared title from that app.
- The session has been playing for at least 15 seconds and its playback position is at least 15 seconds.
- The title's duration is at least three minutes.

Apps without usable metadata remain unsupported. This is not a universal or perfect watch history. If the order is stale, hold OK on the correct title → **I watched this most recently** to correct it locally. To remove a shared-account title from the feature or watching rows, use the [title-hiding controls](usage.md#keep-shared-account-titles-out-of-your-home).

## Prioritize your services

**Settings → Recommendation services** lets you choose your signed-in services. **Use viewing activity** restores automatic inference from shared progress.

Android does not expose a reliable login flag across apps. A shared progress record does not prove that you are still signed in, and simply opening an app does not mark it as signed in. Some apps share Continue watching but no recommendations; prettTV cannot fill their private catalogs automatically.

Suggestions, Discover and Trending prioritize services with shared viewing activity or your explicit selection. Providers are balanced before the 60-title limit, reserving cards from each service for Suggestions and Discover. This gives Discover a broader mix rather than letting one anime feed occupy the row.

The general priority is selected services, other app feeds, known free services, then general catalog fallback. Two rules refine that order:

- **Stremio stays last**, identified by a purple **S** badge beside its content.
- **Pluto TV is lower priority in Suggestions and Discover**, filling remaining space after other sources and before Stremio. Opening Pluto or selecting it as a recommendation service does not override this rule. Its viewing history and Trending ranking are unchanged.

When multiple sources share a normalized title, the preferred app source is retained. Discover also excludes titles already in watching, Suggestions, Trending and New releases. Provider balancing and Discover do not add extra catalog requests.

**Not interested** removes the identified title from recommendation rows without removing it from Watchlist or Continue watching. Supplied genre metadata contributes a small, bounded ranking penalty within existing service tiers. Missing genres do not produce an inferred preference. Reset feedback or undo a supported recent change in Settings.

## Trending, new releases and destinations

Trending uses explicitly labeled popular/trending app channels. It also ranks app cards that uniquely match the same title and content type in Cinemeta's Popular catalog. Unknown types and ambiguous matches are skipped. This matching changes ranking only: the provider's title, artwork and launch action stay intact.

Remaining slots follow Cinemeta's Popular order, alternating series and films. This is the catalog's ordering, not an independently measured trend score. New releases uses supplied dates within the past 120 days.

App-published cards open the action supplied by their provider. Cinemeta cards link to Stremio. Hold OK → **About this title / source** to inspect a title's origin. General TVmaze discovery is also credited with its source links; see the [third-party notices](../THIRD_PARTY_NOTICES.md) for the catalog and artwork sources.

## New-episode alerts

Following a show is explicit and independent of saving it to Watchlist. prettTV matches a supplied TVmaze or IMDb identifier, or requires a unique exact title-and-premiere-year match. A title alone is insufficient. Shows without enough matching information remain followed with an unavailable-source status.

The first check establishes a baseline rather than presenting an entire back catalog. Later checks use TVmaze's supplied episode timestamps, suppress duplicate and dismissed alerts, and never convert an unknown app destination into a promised episode link. An alert opens the known show destination; availability in that streaming service remains unconfirmed.

Checks run only while Home is visible, at most once daily per checked show, with a bounded request budget per content refresh. Offline state retains previously available alerts. Manage follows and inspect check status through **Settings → Followed shows**; dismiss individual alerts through Hold OK.

## Offline content

The last successful shared-feed and catalog snapshot is saved privately for offline Home startup. Home displays an offline label and update age when the network is unavailable. Recommendation snapshots expire after seven days; sports use the shorter limits below. Empty or unavailable providers do not become fabricated content.

Watchlist and followed-show preferences are stored separately and survive artwork-cache clearing. Available cached artwork can be reused during outages; unavailable artwork uses the normal card fallback. App shortcuts still launch, but their playback and login behavior depend on the individual app and connection.

## Live sports

ESPN's public scoreboard feed supplies live and upcoming fixtures for NFL, college football, NBA, NHL, MLB and the Premier League. Cards use supplied broadcast logos and kickoff times; live cards include scores.

Choose **Settings → Favorite teams**, then a league and teams. Favorite fixtures move first within the live group and within the upcoming group. The team directory is loaded when requested and cached for a week; league-specific IDs avoid collisions between sports.

**Kickoff reminders** are optional. While Home is visible, fresh schedule data can show a dismissible reminder in the ten minutes before a favorite team's kickoff. Acknowledged reminders do not repeat for the same event/time; a changed kickoff can qualify again. No alarm opens Home, no other app is interrupted, and no stream starts automatically.

| Data behavior | Limit |
| --- | --- |
| Requests | Run while Home is visible. |
| Cache | Two minutes. |
| Stale live data | After five minutes, the LIVE label and score are removed. |
| Expiry | Records expire after fifteen minutes. |
| Availability | These are uncontracted endpoints and may change or become unavailable. |

**A network logo is not a stream link.** Schedule data does not provide stream URLs or viewing rights.

### Open a matching game in RBTV+

The optional **prettTV game handoff** helper navigates RBTV's visible catalog after you select a game. Enable it through **Settings → Game handoff settings** in Android Accessibility settings.

| Handoff behavior | Supported scope |
| --- | --- |
| RBTV version | **3.0.320**, the pinned companion build. |
| Leagues | NFL, college football, NBA and Premier League. |
| Match | A verified sport tab, both complete team names, and the local kickoff/date. |
| Stop point | The matching game page. You choose the server. Upcoming games may show a countdown. |
| Request limit | 30 seconds or 16 list scrolls. |
| Fallback | Ambiguous, missing, unsupported or differently timed events require manual selection. Other RBTV versions or leagues use an ordinary app launch. |

The helper reads and operates RBTV only during an explicit game request. It never intercepts Home, accepts updates or chooses a server. No verified external game/channel interface was found in the pinned version, so the helper does not promise a direct stream link.

RBTV+ is a separate third-party app included in the default installation bundle. Its terms, permissions and behavior are separate from prettTV. See [companion-app provenance and review limits](companion-apps.md).

## Related guides

- [Remote controls and app arrangement](usage.md)
- [Privacy and optional access](privacy.md)
- [RBTV+ companion app](companion-apps.md)
