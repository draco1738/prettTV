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

The rows below Your apps are **Continue watching · Shows**, **Continue watching · Movies**, **Live sports**, **Suggestions**, **Discover**, **Trending** and **New releases**. Content rows scroll horizontally with up to 60 titles. The large feature uses the most recent eligible viewing record; [how viewing order works](content-and-sports.md#continue-watching-and-the-large-feature) explains the available data and fallback.

## Remote controls

| Control | Action |
| --- | --- |
| Arrows | Move between cards and rows. |
| OK | Open the focused app, title or action. |
| Hold OK on an app | Arrange favorites or hide/show it in Your apps. |
| Hold OK on a title | Open its source, correct viewing order or hide it. |
| Left at the start of a row | Reveal the sidebar with a fresh press once the row is at its beginning. Holding Left stops at the boundary. |
| Right or Back in the sidebar | Close the sidebar and return to the content card. Choosing a destination also closes it. |
| Back from content | Return to the top. |
| Home | Return to Home and reset Your apps to its first app. |
| Menu | Open prettTV Settings. Settings is also available in the sidebar. |

The sidebar contains Home, Shows, Library, Inputs, Search and Settings. **Choose default Home app** opens Android's normal Home selection; prettTV does not intercept the Home button with an accessibility service.

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

## Keep shared-account titles out of your Home

Hold OK on a title and choose **Hide from large hero**, **Hide from Continue watching**, or **Hide from both**. Restore a title individually through **Settings → Hidden titles**.

These filters stay on this TV and do not change the shared streaming account. They match normalized titles across sources, so identically named remakes share a filter.

If a title's viewing order is stale, hold OK → **I watched this most recently**. This corrects the local record without changing the streaming account. The [content guide](content-and-sports.md#continue-watching-and-the-large-feature) covers optional playback tracking and its limits.

## Useful settings

| Setting | What it controls |
| --- | --- |
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
