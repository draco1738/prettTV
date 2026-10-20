# Using prettTV

[Documentation](README.md) · [Project home](../README.md) · [Content and sports](content-and-sports.md)

Choose a layout, arrange your apps and use the remote to move through your Home screen. The same apps, viewing history and feeds are available in all three layouts.

## Choose a layout

Open **Settings → Layout** to switch. Every layout uses the hidden left sidebar.

| Layout | Arrangement |
| --- | --- |
| **A · Cinema** | Wide feature with content rows below. |
| **B · Quiet** | Apps first, followed by content rows. |
| **C · Balanced** | Large feature, two discovery cards and a compact app row. The default layout. |

The default rows below Your apps are **Continue watching · Shows → Continue watching · Movies → Watchlist → New episodes → Live sports → Suggestions → Discover → Trending → New releases**. New episodes appears only when unread alerts exist. The large feature uses the most recent eligible viewing record; [how viewing order works](content-and-sports.md#continue-watching-and-the-large-feature) explains the available data and fallback.

## Remote controls

| Control | Action |
| --- | --- |
| Arrows | Move between cards and rows. |
| OK | Open the focused app, title or action. |
| Hold OK on an app | Arrange favorites or hide/show it in Your apps. |
| Hold OK on a title | Save/remove it, inspect details, follow a show, dismiss a recommendation, correct viewing order or hide it. |
| Left at the start of a row | Reveal the sidebar with a fresh press once the row is at its beginning. Holding Left stops at the boundary. |
| Right or Back in the sidebar | Close the sidebar and return to the content card. Choosing a destination also closes it. |
| Back from content | Return to the top. |
| Home | Return to Home and reset Your apps to its first app. |
| Menu | Open prettTV Settings. Settings is also available in the sidebar. |

The sidebar contains Home, Shows, Library, Inputs, Search and Settings. **Choose default Home app** opens Android's normal Home selection; prettTV does not intercept the Home button with an accessibility service.

### Remote Settings button

In Android **Settings → Accessibility**, enable **prettTV Settings button** to make the remote's Settings key work across apps. On the tested TCL firmware, the helper opens TCL's native quick-settings panel over the current app. A second press closes that panel. Other manufacturers use the normal Android Settings screen, and TCL falls back to it if its panel service cannot start.

The helper handles a complete press once, including held-key repeats, and ignores Home, arrows, volume and other buttons. It requests key filtering without window-content access. Disable it in Accessibility to restore the original key handling; stock troubleshooting recovery also pauses it. Without the optional helper, prettTV handles this shortcut only while Home itself receives the key.

Some TCL firmware also requires **Auto Launch / Auto Start** permission for prettTV. If the helper is enabled but cannot start, inspect that permission in the TV's app settings. The tested TCL logged a blocked service bind until this permission was allowed. The installer does not change it automatically.

## Arrange Your apps

Your apps has a clear beginning and end. Each included launchable app appears once, with favorites first. It includes sideloaded apps that have an ordinary Android launch entry. Projectivy and LTvLauncher are excluded.

| To… | Do this |
| --- | --- |
| Review every available app | **Settings → Choose apps one by one**; choose Include or Hide for each app. |
| Hide one app from the row | Hold OK on it → **Hide from Your apps row**. |
| Restore one app | Find it in Library, hold OK → **Show in Your apps row**. |
| Restore all hidden apps to the row | **Settings → Restore apps to Your apps row**. This item appears when apps are hidden. |
| Arrange favorites | Hold OK on an app and use the available favorite and move controls. |
| Choose sports shortcuts | **Settings → Live sports apps**. RBTV+ is the default sports app. |

Hiding an app only changes Your apps. It stays available in Library, search and viewing history. Hiding from the row moves focus to the next visible app, or the previous app at the end, while preserving the row's scroll position. Hiding from Library keeps focus on the same Library tile. Favorite rearrangement follows the moved app. These arrangement changes do not restart the recommendation feeds.

SportioTV and HD Streamz were removed at the owner's request and are not included in the release. See [companion apps](companion-apps.md) for what the installation bundle includes.

## Original apps in Library

The current source build adds **Blind Pick** and **Rabbit Hole** at the bottom of **Library · All apps**, after the installed apps. These are built into prettTV and stay out of the upper Your apps row.

- **Blind Pick:** Choose movies, TV shows or both, a genre-based mood and a time limit. Reveal up to three mystery choices, then open their app or save one to Watchlist.
- **Rabbit Hole:** Start with a title and follow published cast/director connections, shared genres or release periods. Select a result to keep exploring; hold OK for watching and saving actions. Back retraces the recent path.

Both use your available listings and preferred-service order, exclude hidden/dismissed titles and leave missing facts unavailable. [Full controls, sources and limits →](original-apps.md)

## Save titles to Watchlist

This row currently contains prettTV's local saved titles. It does **not** synchronize the Google account / Google TV Watchlist. The tested TV does not publish that cloud list through Android's shared Watch Next provider.

Hold OK on a title → **Add to Watchlist**. Your saved titles appear in their own row, newest additions first. Each entry retains its source app and available artwork. Saving here does not change a streaming app's own watchlist.

Watchlist keeps every saved entry within its local storage limit. To keep navigation light, it displays **60 entries per page**, with **Older saved titles** and **Newer saved titles** controls when needed. Missing apps do not delete saved entries. Clearing the artwork cache does not clear Watchlist.

When supported playback confirms that you started a saved title after saving it, prettTV removes it from Watchlist. Opening an app alone is insufficient. **I watched this most recently** is also an explicit local viewing confirmation. Unsupported playback requires manual removal through Hold OK → **Remove from Watchlist**. Use **Settings → Undo** to restore a removed item; the same confirmed playback event will not remove the restored item again.

## Arrange content rows

Open **Settings → Customize rows**, select a row, then choose:

| Control | Behavior |
| --- | --- |
| **Hide row / Show row** | Change its visibility without deleting its content or preferences. |
| **Move up / Move down** | Reorder content rows below Your apps. |
| **Card size** | Small, Standard or Large. Standard keeps the default proportions. |
| **Title limit** | 20, 40 or 60 entries for Suggestions, Discover, Trending and New releases. Default: 60. |
| **Restore default rows** | Reset order, visibility, sizes and title limits. |

Row preferences apply across A, B and C. Watchlist pagination is independent of discovery limits. The Library remains available below the content rows.

## Details, recommendations and undo

Hold OK → **About this title / source** for the supplied synopsis, episode information, progress and source. **Open / actions** shows known app destinations for confidently matching titles. Missing details remain unavailable; an app destination does not establish a subscription or regional availability.

**Not interested** appears for titles in recommendation rows. It removes that title from recommendations and gently lowers similar genres within existing service priorities. It does not hide the title from Watchlist or Continue watching. Use **Settings → Recommendation feedback → Reset feedback** to clear these choices.

**Settings → Undo · [last change]** reverses supported recent changes, including app-menu arrangement/hiding, title hiding, Watchlist changes, recommendation feedback, follows, row preferences and favorite teams. The history holds up to 20 changes in the current Home session; it is not a permanent change log. Bulk app-review controls and restoring an individual hidden title do not add undo entries.

## Follow new episodes

Hold OK on a show → **Follow new episodes**. Following is separate from Watchlist, so starting the show does not end its alerts. **Settings → Followed shows** lists follows and their check status; up to 100 shows can be followed.

The **New episodes** row shows new releases after the initial baseline. prettTV needs an identifiable TVmaze/IMDb show or an unambiguous title-and-year match; some app cards lack enough information. Checks run while Home is visible, at most daily for each checked show, with a bounded number of requests per refresh.

Alerts mean **“episode released,” not “confirmed available in your streaming app.”** They do not interrupt playback. Hold OK on an alert to dismiss it, or unfollow through the show's menu. [Episode sources and limits →](content-and-sports.md#new-episode-alerts)

## Teams, offline Home and updates

- **Favorite teams:** Choose a league and teams in Settings. Their fixtures are prioritized within live and upcoming groups.
- **Kickoff reminders:** Optional reminders appear on Home during the ten minutes before a favorite team's scheduled kickoff, using fresh data. Press OK on the reminder to dismiss it. They do not interrupt other apps or start a stream.
- **Offline Home:** The last successful feed snapshot and available cached artwork remain usable during outages. Home labels offline content with its update age; stale live scores disappear. Saved titles and installed-app shortcuts remain available, though streaming still depends on the app and connection.
- **Updates:** Open Settings → Updates to see official release notes, check manually, and choose Download or Install. Android asks for installation permission and confirmation. [Update behavior →](installation.md#updates)

## Weather beside the clock

Open **Settings → Weather** and enter a **five-digit US ZIP code**. Setting a location enables weather; new installations start with it off and no preset location. Choose **°F or °C**; Fahrenheit is the default. The header shows the resolved place, a small condition icon and the temperature beside the existing clock in all three layouts. Changing the weather location does not change the TV's time zone or clock format.

Weather uses nearby **NWS observation stations**, with the ZIP's approximate location supplied by Zippopotam.us. US ZIP lookup and NWS station coverage are both required; some locations or stations may have no usable reading. A station reading describes its surroundings and may differ from conditions at your home. prettTV does not request GPS permission or read your Google account's address.

While Home is visible, weather refreshes about every **30 minutes**. A recent saved reading can appear immediately when you return. Observations older than 30 minutes show **cached**, and readings older than two hours disappear from the header. Without a usable reading, the header shows **Updating…** during a request or **Unavailable** afterward. The clock stays usable; a missing reading never becomes a made-up zero-degree value. Weather updates do not move your selection or rebuild the content rows.

Use **Refresh weather** to request an update; temporary service failures may delay retries. **Weather details and sources** shows the observation's station and time, plus provider and licence links. Turn **Show on Home** off to hide weather and stop its refreshes while retaining your location and units. Weather is independent of **Show discovery**. [Weather requests and stored data →](privacy.md#weather)

## Keep shared-account titles out of your Home

Hold OK on a title and choose **Hide from large hero**, **Hide from Continue watching**, or **Hide from both**. Restore a title individually through **Settings → Hidden titles**.

These filters stay on this TV and do not change the shared streaming account. They match normalized titles across sources, so identically named remakes share a filter.

If a title's viewing order is stale, hold OK → **I watched this most recently**. This corrects the local record without changing the streaming account. The [content guide](content-and-sports.md#continue-watching-and-the-large-feature) covers optional playback tracking and its limits.

## Useful settings

| Setting | What it controls |
| --- | --- |
| **Customize rows / Undo** | Arrange content sections and reverse supported recent changes. |
| **Followed shows** | Manage episode follows and inspect release-check status. |
| **Favorite teams / Kickoff reminders** | Prioritize team fixtures and optionally show Home-only reminders. |
| **Updates** | Check official releases and explicitly download/install an update. |
| **Weather** | Enter a US ZIP, choose °F/°C, or turn the optional header weather off. |
| **Recommendation services** | Choose the signed-in services to prioritize, or select **Use viewing activity** for automatic inference. |
| **Show discovery** | Turn discovery on or off. |
| **Playback tracking** | Explain and open optional Notification access for supported media-session metadata. |
| **Game handoff settings** | Open Android Accessibility settings for the optional RBTV game helper. |
| **Refresh apps and shows** | Reload apps and content. |
| **TV settings / Inputs** | Open the device's settings or input selector. |
| **About and credits** | Read licenses, sources and prettTV's privacy explanation. |
| **Return to stock Google TV** | Open the two-confirmation troubleshooting restore at the bottom of Settings. |

Full stock recovery needs the original private setup record and authorized local debugging. It restores the recorded Home, disabled apps and DNS while retaining installed apps and logins. Read [stock recovery](stock-recovery.md) before relying on it.

## Related guides

- [Content sources, viewing order and live sports](content-and-sports.md)
- [Privacy and optional access](privacy.md)
- [Return to stock for troubleshooting](stock-recovery.md)
