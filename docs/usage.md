# Using prettTV

[Documentation](README.md) · [Project home](../README.md) · [Content and sports](content-and-sports.md)

Choose a layout, arrange your apps and use the remote to move through your Home screen. The same apps, viewing history and feeds are available in all three layouts.

> [!NOTE]
> This page describes **0.6.3** source behavior. Actual checks, evidence and limitations are recorded by version in [Validation](validation.md); a control description is not a claim that every device or app supports it.

**Focus motion:** Settings → Home & apps → Focus motion offers Classic (the existing lift and artwork slide) or Quiet (a smaller 120 ms lift with still artwork). Classic remains the default. Both preserve the black cards, white focus outline and remote navigation. [Performance and memory behavior](lightweight-home.md).

prettTV's OLED theme uses pure-black backgrounds and app tiles, black dialogs and buttons, with white text and a thin white focus outline. Brand marks and program artwork retain their color; the hero uses a compact Resume button that stays black when selected. The theme covers all three Home layouts and prettTV's own utility screens; it does not restyle streaming apps, Google voice results or Android Settings.

## Choose a layout

Open **Settings → Home & apps → Layout** to switch. Every layout uses the same persistent opaque top navigation bar above the scrolling content.

| Layout | Arrangement |
| --- | --- |
| **A · Cinema** | Wide feature with content rows below. |
| **B · Quiet** | Apps first, followed by content rows. |
| **C · Balanced** | Large resume feature, three portrait discovery cards, Browse shows and one finite app row. The default layout. |

The default rows below Your apps are **Continue watching · Shows → Continue watching · Movies → Watchlist → New episodes → Live sports → Suggestions → Discover → Trending → New releases**. New episodes appears only when unread alerts exist. The large feature uses the most recent eligible viewing record; [how viewing order works](content-and-sports.md#continue-watching-and-the-large-feature) explains the available data and fallback.

Continue watching applies new chronological ordering immediately and keeps the selected title focused. The hero follows the latest eligible record. Changes in app-published playback progress can contribute a separately stored observation; refreshed timestamps alone cannot. Provider observations may be delayed or come from a shared account, remain separate from confirmed local viewing and do not automatically remove titles from Watchlist.

## Interface size, Undo and recommendations

**Settings → Home & apps → Interface size** offers **Standard · 100%** (default) and **Larger · 104%**. It scales prettTV-owned cards, text, spacing and controls consistently across Home, Discovery, Search, settings and utility screens. Android font scaling remains independent. It does not change Android density, streaming apps or system screens. C's three picture areas are now 240×360 reference pixels beside the 1000-pixel resume feature; lower shelves scroll normally.

After a supported local change, **Undo** appears directly in Home's top bar, without stealing selection or imposing a short expiry timer. Blind Pick and Rabbit Hole have their own session-local Undo beside Back. Undo is not a permanent transaction log; a settings restore clears stale session history.

Applicable recommendation title menus offer **Why this recommendation?** and **Not interested**. Explanations describe the actual source and explicit or inferred service priority, not subscriptions, sign-in status or a guessed viewing motive. Not interested changes local recommendation eligibility without removing Watchlist or viewing history. Preferred services remain under **Settings → Shows & recommendations → Recommendation services**; all feedback can be reset under **Recommendation feedback**.

## Local settings backup

Open **Settings → Support → Local settings backup**. **Save snapshot on this TV** stores a verified private copy of Home settings, app arrangement, local playback records and personalization, including Watchlist. Choose **Review saved snapshot**, inspect the summary, then explicitly choose **Restore these local settings**. Cancel is initially selected, held OK cannot accept immediately, and confirmation expires after two minutes. Leaving the screen revokes unfinished consent.

A verified pre-restore copy is required before a TV restore changes settings. Home pauses its settings writers, rejects obsolete queued writes and reloads restored preferences when you return. An already-started disk commit can finish after cancellation while rollback drains. Restoring four stores is not crash-atomic; if rollback cannot be verified, automatic writers stay paused until explicit recovery succeeds. A valid pre-restore copy can be reviewed on the TV; if none exists after a diagnostic-import failure, recovery requires an explicit diagnostic import of a known-good export retained before the failure.

This is **on-TV recovery, not an off-device backup**. Both copies are lost if prettTV is uninstalled or its app data is cleared. Android settings, streaming accounts, helper credentials and other apps are excluded. No cloud account or sign-in is involved.

## Remote controls

| Control | Action |
| --- | --- |
| Arrows | Move between cards and rows. |
| OK | Open the focused app, title or action. |
| Hold OK on an app | Open the anchored app panel. Open is selected first; favorites, arrangement, hide/show, eligible Force stop and App info remain available. |
| Hold OK on a title | Save/remove it, inspect details, follow a show, dismiss a recommendation, correct viewing order or hide it. |
| Left / Right at row boundaries | Stop at the first / last card. No sidebar appears and the row does not wrap. |
| Up from the first content card | Reach the top navigation bar. In Quiet, Up from Your apps also reaches it. |
| Down from navigation | Return to the remembered content card. |
| Back from content | Return to the top. |
| Remote Home / Home in the top bar | Reset the page and all rows to their default start. |
| Menu | Open prettTV Settings. Settings is also available in the top bar. |

The top bar contains **Home, Shows, Library, Inputs, Search and Settings**, followed by status/weather, time and the **Bluetooth symbol** at the far right. Shows and Library move to their content sections. The bar stays visible while the page scrolls; its own navigation strip can scroll horizontally when system text sizing needs more room. **Choose default Home app** opens Android's normal Home selection; the optional Home compatibility helper can return stock Home windows to prettTV on TVs that ignore this choice. It does not filter remote keys. Google's voice-result screens stay open; see [remote voice setup](browser-and-voice.md#preserve-pretttv-as-home).

Holding OK on an app shows a brief progress cue; releasing or leaving the card cancels it. A short boundary cue responds at row ends. These cues do not run while idle. Back from the app panel returns to its originating card and viewport; choosing an arrangement or hide action applies the existing deterministic focus rules below.

### Inputs and audio devices

Select the **Bluetooth symbol** at the far right and press **OK** to toggle it directly. Blue with a check means on; a muted symbol with a slash means off. Dots mean checking/changing, and a question mark means the state is unavailable. Shapes distinguish the states without relying on color, and full spoken labels remain available. These indicate the radio state, not whether headphones are connected. Repeated OK presses during an operation are ignored. **Hold OK** opens Audio devices and its native **Manage devices** fallback. Returning Home refreshes the status without changing Bluetooth. No accessibility service is required for this button.

Open **Inputs** in the top bar, then choose **TV inputs** for HDMI/television sources or **Audio devices** for a simple Bluetooth panel. Choose the on/off action to change Bluetooth; prettTV reads the resulting state before reporting completion. **Turning Bluetooth off disconnects Bluetooth headphones and remotes.**

Direct switching uses the already-authorized TV-local connection configured for [stock recovery](stock-recovery.md). Opening this panel does not change the radio, run a restore, or request a new debugging authorization. If that setup is unavailable, choose **Manage devices** to use the TV's native controls. On the tested TCL, this opens **Remotes and accessories**; other TVs fall back to their Bluetooth or general Settings screen when needed.

On supported TCL firmware, switching Bluetooth off also selects **TV speakers** and verifies the resulting media route. TCL applies its saved speaker volume; prettTV does not copy headphone volume or issue an unmute command. If the previous selection was Bluetooth-only, switching back on restores it only while the output is still the speaker setting prettTV selected. A different manual output choice takes priority. Bluetooth audio still requires a connected audio device. Other firmware keeps its native routing; an unverified selection is reported instead of claiming speaker success.

### Screensaver

The owner's TCL has been returned to its stock screensaver and EarthCam has been removed. This is that TV's configuration, not a new installation default imposed by prettTV. The [optional EarthCam handoff](earthcam-idle.md) remains available to other users who separately install EarthCam and explicitly select its handoff.

### Remote buttons

**Normal volume holds:** leave **prettTV Remote buttons** off in Android Accessibility settings. On the tested TCL Android 14 TV, enabling key filtering prevents held Volume Up/Down from repeating even though prettTV passes those keys through. A physical comparison confirmed normal repeats with the helper off. The owner's TV now uses this configuration and the top-bar Bluetooth switch. Global custom app/Settings remaps and double-Mute are unavailable while the helper is off; Home and the visible navigation controls continue working. The installer and app updates do not enable the helper automatically.

In Android **Settings → Accessibility**, enable **prettTV Remote buttons** (called **prettTV Settings button** before 0.4.1). Existing enabled installations keep the same service component and do not require setup again. On the tested TCL firmware, Settings opens TCL's native quick-settings panel over the current app. A second press closes that panel. Other manufacturers use the normal Android Settings screen, and TCL falls back to it if its panel service cannot start.

On TCL, the helper also replaces app shortcuts that otherwise depend on the disabled Google TV launcher:

| Remote label | Opens |
| --- | --- |
| Netflix | Netflix |
| Prime Video | Prime Video |
| YouTube | **TV Bro**, as requested for this setup |
| TCL / TCL Channel / TCL TV+ | **HBO Max**, as requested for this setup |
| Apple TV | Apple TV |
| Pluto TV, on remotes with this button | Pluto TV |
| Disney+ | Its existing native action; prettTV does not intercept it |

Targets must already be installed and enabled. A missing target produces an unavailable message; the helper does not install apps or enable disabled packages. The mapping covers the identified TCL codes, not every regional remote. Other manufacturers retain native app-button behavior.

**Bluetooth shortcut:** Quickly press the TCL remote's **speaker Mute** button twice. With **prettTV Remote buttons** enabled and the existing TV-local recovery connection authorized, the gesture toggles Bluetooth and shows a brief result after reading the resulting state. It does not open another app or settings screen. Use **Inputs → Audio devices** if the shortcut is unavailable. Turning Bluetooth off disconnects Bluetooth headphones and remotes.

A single Mute press keeps its immediate native behavior. Both presses of a double press pass through normally, so a native toggle returns to its previous mute state after the second press; prettTV neither delays nor replays those events. This shortcut recognizes the captured TCL speaker key, `KEYCODE_VOLUME_MUTE` (164). The microphone key, `KEYCODE_MUTE` (91), is not a Bluetooth shortcut. Physical gesture results and firmware limits are tracked in [Validation](validation.md).

On Android 13+, choose **Inputs → Audio devices → Enable shortcut messages** to allow the brief result while another app is open. Android can otherwise suppress background status messages even when Bluetooth changes successfully. This permission is optional and separate from playback-tracking notification access. Declining it leaves the toggle functional; inspect its state in Audio devices.

For mapped Settings and app shortcuts, the helper handles each complete press once and suppresses held-key repeats, canceled gestures and queued work during recovery. Home, arrows, volume, controller buttons and unknown keys pass through. It requests key filtering without window-content access. Disable it in Accessibility to restore the original key handling; stock troubleshooting recovery also pauses it. Without the optional helper, prettTV can handle only keys that actually reach its foreground window; Android-reserved global keys can still be intercepted by the firmware.

Some TCL firmware also requires **Auto Launch / Auto Start** permission for prettTV. If the helper is enabled but cannot start, inspect that permission in the TV's app settings. The tested TCL logged a blocked service bind until this permission was allowed. The installer does not change it automatically.

## Arrange Your apps

Your apps has a clear beginning and end. Each included launchable app appears once, with favorites first. It includes sideloaded apps that have an ordinary Android launch entry. Projectivy and LTvLauncher are excluded.

| To… | Do this |
| --- | --- |
| Review every available app | **Settings → Home & apps → Choose apps one by one**; choose Include or Hide for each app. |
| Hide one app from the row | Hold OK on it → **Hide from Your apps row**. |
| Restore one app | Find it in Library, hold OK → **Show in Your apps row**. |
| Restore all hidden apps to the row | **Settings → Home & apps → Restore apps to Your apps row**. This item appears when apps are hidden. |
| Arrange favorites | Hold OK on an app and use the available favorite and move controls. |
| Choose sports shortcuts | **Settings → Shows & recommendations → Live sports apps**. RBTV+ is the default sports app. |

Hiding an app only changes Your apps. It stays available in Library, search and viewing history. Hiding from the row moves focus to the next visible app, or the previous app at the end, while preserving the row's scroll position. Hiding from Library keeps focus on the same Library tile. Favorite rearrangement follows the moved app. These arrangement changes do not restart the recommendation feeds.

SportioTV and HD Streamz were removed at the owner's request and are not included in the release. See [companion apps](companion-apps.md) for what the installation bundle includes.

### App first aid

Hold **OK** on an app → **App first aid**, or open **Settings → Support → App first aid** and choose an app.

- **Connection** shows whether Android reports internet access, a network needing sign-in, or no connection. It does not test the streaming service’s servers or your account.
- **Media output** reads Android’s selected media route and, on the tested TCL, checks it against the vendor selector. Bluetooth being on does not mean sound is going to Bluetooth headphones. A route that cannot be verified stays unavailable. Mute is shown only when the TV reports it.
- **Restart app** asks for confirmation, stops the selected eligible app, verifies its stopped state and opens it again. Leaving the confirmation/progress screen cancels pending work and prevents a delayed reopen. Login, storage and saved data are kept. Current playback ends.
- **Check again**, **Audio devices**, **Network settings** and **App info** remain available for manual troubleshooting. Opening First aid never restarts an app or changes connectivity/audio settings.

Direct checks and restart use the existing authorized local control connection. Protected Home/system apps cannot be restarted here. A restart may recover a stuck app connection; it cannot establish a fix for a service outage, decoder problem or network fault.

### Search and Settings

**Search** keeps the query, grouped app/title results and an inline no-results explanation on one screen. Opening a result retains the search behind the app; Back returns to it when that app’s navigation permits. A Home press closes Search. The query is kept for the current prettTV process only. Searches cover installed apps and loaded Home titles, not every streaming catalog; Google voice search remains separate. Long result lists offer **More results** in batches of 60.

Settings groups controls under **Home & apps**, **Shows & recommendations**, **TV & audio**, and **Support**. Recent Undo stays at the top. The double-confirmed **Return to stock Google TV** remains separate at the bottom.

**Audio devices** shows the selected media output after checking it. **Change output**, beside the Bluetooth status, offers **TV speakers**, **Bluetooth audio**, and **HDMI ARC** on the tested TCL. It verifies the selected route without changing Bluetooth power or copying volume between outputs. Bluetooth audio requires the radio to be on and a connected audio device for sound. A selected route does not prove audible playback. The existing authorized local control setup and supported TCL firmware are required; use the TV's audio settings otherwise.

The top-bar Bluetooth symbol continues to describe radio state. App tiles retain colorful brand marks on a consistent dark ground; utility apps use original line glyphs and plain names; other unmapped apps use their installed icon, with adaptive foregrounds on black, rather than a full bright banner.

### Force stop without reopening

Hold **OK** on an installed app in **Your apps** or **Library**, choose **Force stop**, then confirm. The separate dark confirmation keeps Home visible and selects **Cancel** first. This closes that app and its connections. Choose **Open app** afterward to start it again, or the initially selected **Done** to stay in prettTV. Login and saved app data are kept; the action does not clear storage, uninstall the app or reset the TV's network.

Direct force-stop uses the TV-local debugging key and device record already configured for [stock recovery](stock-recovery.md). It does not run a stock restore or enable debugging. If that setup is unavailable, choose **App info** and use Android's Force stop control. prettTV reports success only after checking Android's stopped state. A timeout or cancellation after dispatch can leave the outcome unverified; the screen says so rather than claiming the app stayed open.

Home and essential TV controls are protected. App position, favorites and hidden-row settings are preserved. Force-stop is a manual troubleshooting action, not a background memory cleaner; the selected app's background playback and downloads also stop until it is opened again.

## Original apps in Library

The current source build adds **Blind Pick** and **Rabbit Hole** at the bottom of **Library · All apps**, after the installed apps. These are built into prettTV and stay out of the upper Your apps row.

- **Blind Pick:** Choose movies, TV shows or both, a genre-based mood and a time limit. Reveal up to three mystery choices, then open their app or save one to Watchlist. Unknown types remain **Unclassified title** and qualify only under **Shows & movies**; unknown runtimes remain **Runtime unavailable** and qualify only under **Any length**. Missing facts are not invented to fill three cards.
- **Rabbit Hole:** Start with a title and follow published cast/director connections, shared genres or release periods. Select a result to keep exploring; hold OK for watching and saving actions. Back retraces the recent path.

Both use your available listings and preferred-service order, exclude hidden/dismissed titles and leave missing facts unavailable. [Full controls, sources and limits →](original-apps.md)

When strict Blind Pick filters have no matches, a bounded metadata lookup can establish missing facts before trying those same filters again; it does not silently relax your choices. Rabbit Hole can make one additional automatic lookup when new facts still leave it without connections. You can also choose **Refresh connections**; ambiguous title matches remain unavailable.

## Save titles to Watchlist

This row currently contains prettTV's local saved titles. It does **not** synchronize the Google account / Google TV Watchlist. The tested TV does not publish that cloud list through Android's shared Watch Next provider.

Hold OK on a title → **Add to Watchlist**. Your saved titles appear in their own row, newest additions first. Each entry retains its source app and available artwork. Saving here does not change a streaming app's own watchlist.

Watchlist keeps every saved entry within its local storage limit. To keep navigation light, it displays **60 entries per page**, with **Older saved titles** and **Newer saved titles** controls when needed. Missing apps do not delete saved entries. Clearing the artwork cache does not clear Watchlist.

When supported playback confirms that you started a saved title after saving it, prettTV removes it from Watchlist. Opening an app alone is insufficient. **I watched this most recently** is also an explicit local viewing confirmation. Unsupported playback requires manual removal through Hold OK → **Remove from Watchlist**. Use **Settings → Undo** to restore a removed item; the same confirmed playback event will not remove the restored item again.

## Arrange content rows

Open **Settings → Home & apps → Customize rows**, select a row, then choose:

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

**Not interested** appears for titles in recommendation rows. It removes that title from recommendations and gently lowers similar genres within existing service priorities. It does not hide the title from Watchlist or Continue watching. Use **Settings → Shows & recommendations → Recommendation feedback → Reset feedback** to clear these choices.

**Settings → Undo · [last change]** reverses supported recent changes, including app-menu arrangement/hiding, title hiding, Watchlist changes, recommendation feedback, follows, row preferences and favorite teams. The history holds up to 20 changes in the current Home session; it is not a permanent change log. Bulk app-review controls and restoring an individual hidden title do not add undo entries.

## Follow new episodes

Hold OK on a show → **Follow new episodes**. Following is separate from Watchlist, so starting the show does not end its alerts. **Settings → Shows & recommendations → Followed shows** lists follows and their check status; up to 100 shows can be followed.

The **New episodes** row shows new releases after the initial baseline. prettTV needs an identifiable TVmaze/IMDb show or an unambiguous title-and-year match; some app cards lack enough information. Checks run while Home is visible, at most daily for each checked show, with a bounded number of requests per refresh.

Alerts mean **“episode released,” not “confirmed available in your streaming app.”** They do not interrupt playback. Hold OK on an alert to dismiss it, or unfollow through the show's menu. [Episode sources and limits →](content-and-sports.md#new-episode-alerts)

## Teams, offline Home and updates

- **Favorite teams:** Choose a league and teams in Settings. Their fixtures are prioritized within live and upcoming groups.
- **Kickoff reminders:** Optional reminders appear on Home during the ten minutes before a favorite team's scheduled kickoff, using fresh data. Press OK on the reminder to dismiss it. They do not interrupt other apps or start a stream.
- **Stable refresh and offline Home:** Existing content and artwork remain visible during refresh. A quiet **Updating…** label appears only for an actual request lasting beyond the short delay. An unavailable service is labeled separately from offline connectivity, with saved-content age when available. Recommendation-row reordering waits until focus leaves where possible; Continue watching updates chronologically right away while preserving the selected title. Removed or expired content is not retained just to keep its position. Cached recommendations expire after seven days and stale live scores disappear. Saved titles and installed-app shortcuts remain available, though streaming still depends on the app and connection.
- **Updates:** Open Settings → Support → Updates to see official release notes, check manually, and choose Download or Install. Android asks for installation permission and confirmation. [Update behavior →](installation.md#updates)

## Weather beside the clock

Open **Settings → TV & audio → Weather** and enter a **five-digit US ZIP code**. Setting a location enables weather; new installations start with it off and no preset location. Choose **°F or °C**; Fahrenheit is the default. The header shows the resolved place, a small condition icon and the temperature beside the existing clock in all three layouts. Changing the weather location does not change the TV's time zone or clock format.

Weather uses nearby **NWS observation stations**, with the ZIP's approximate location supplied by Zippopotam.us. US ZIP lookup and NWS station coverage are both required; some locations or stations may have no usable reading. A station reading describes its surroundings and may differ from conditions at your home. prettTV does not request GPS permission or read your Google account's address.

While Home is visible, weather refreshes about every **30 minutes**. A recent saved reading can appear immediately when you return. The header keeps the temperature uncluttered without a **cached** suffix; Weather settings and details retain freshness information. Readings older than two hours disappear from the header. Without a usable reading, the header shows **Updating…** during a request or **Unavailable** afterward. The clock stays usable; a missing reading never becomes a made-up zero-degree value. Weather updates do not move your selection or rebuild the content rows.

Use **Refresh weather** to request an update; temporary service failures may delay retries. **Weather details and sources** shows the observation's station and time, plus provider and licence links. Turn **Show on Home** off to hide weather and stop its refreshes while retaining your location and units. Weather is independent of **Show discovery**. [Weather requests and stored data →](privacy.md#weather)

## Keep shared-account titles out of your Home

Hold OK on a title and choose **Hide from large hero**, **Hide from Continue watching**, or **Hide from both**. Restore a title individually through **Settings → Shows & recommendations → Hidden titles**.

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
| **TV settings / Inputs** | Open device settings or choose TV inputs and Audio devices. |
| **About and credits** | Read licenses, sources and prettTV's privacy explanation. |
| **Return to stock Google TV** | Open the two-confirmation troubleshooting restore at the bottom of Settings. |

Full stock recovery needs the original private setup record and authorized local debugging. It restores the recorded Home, disabled apps and DNS while retaining installed apps and logins. Read [stock recovery](stock-recovery.md) before relying on it.

## Related guides

- [Content sources, viewing order and live sports](content-and-sports.md)
- [Privacy and optional access](privacy.md)
- [Return to stock for troubleshooting](stock-recovery.md)
