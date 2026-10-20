# Blind Pick and Rabbit Hole

[Documentation](README.md) · [Using prettTV](usage.md) · [Data and privacy](privacy.md)

Two original prettTV experiences for finding a movie or TV show. Their tiles sit at the **bottom of Library · All apps**, after installed apps. They do not appear in the upper **Your apps** row and need no separate APK or account.

> [!NOTE]
> These features describe the current source build. Check [validation history](validation.md) and the release notes for the build you install; earlier TV results do not validate these additions.

## Blind Pick

Choose **Movies + TV**, **Movies** or **TV shows**, then a mood and available time. Blind Pick presents up to three concealed choices. Each shows a short clue and available genre, year and runtime information while keeping the title and artwork hidden.

| Control | What it does |
| --- | --- |
| **Reveal this pick** | Show the title, artwork and source app. |
| **Open [app]** | Use the known title destination, or open its app when a title link is unavailable. |
| Revealed title card | Open actions to watch, save to prettTV Watchlist or explore in Rabbit Hole. |
| **Three new picks** | Draw a new set using the current filters. Small catalogs can repeat titles. |
| **Back** | Return to Library. |

Mood choices group published genres; prettTV does not invent a plot or infer a title's emotional tone. Runtimes come from the provider and may describe an episode or the supplied title. **Any length** includes unknown runtimes; the 30-, 60- and 120-minute limits require known matching runtimes. If fewer than three titles qualify, the screen shows the available choices. Titles already in Continue watching are excluded from Blind Pick.

## Rabbit Hole

Start with a title from your available listings, Watchlist or catalog. **Change title** searches that local set. Select a related card to make it the next starting point, then use **Back** to retrace your recent path.

| Connection | Evidence used |
| --- | --- |
| **Shared cast** | Matching published cast names for both titles. |
| **Same director** | Matching published director credits. |
| **Similar genres** | Shared catalog or app-published genres. |
| **All connections** | The above, plus matching release years or decades when known. |

Each result explains its connection. Shared genres or release dates do not claim that one work inspired another. The app explores titles already available to prettTV rather than promising a complete actor filmography or every streaming catalog.

Hold **OK** on a related card for watching options, **Add to Watchlist**, or **Explore in Rabbit Hole**. **Catalog sources** opens the provider and licence links. **Refresh connections** retries a bounded metadata lookup; unavailable credits remain unavailable.

## Your sources come first

Both experiences favor your selected recommendation services, or services inferred from shared viewing activity. Free services rank lower; Pluto TV is further down and Stremio stays last. Hidden titles and **Not interested** choices are excluded. Hiding an app only from Your apps does not remove its titles or Library access.

Published app destinations are retained. A catalog assignment or matching title does not prove regional availability, an active subscription or a working episode link. If only an app launch is available, prettTV tells you to find the title there.

## Metadata, offline use and limits

Cinemeta supplies movie and series metadata. TVmaze supplies identifiable show and credit data under **CC BY-SA**. IMDb/TVmaze identifiers are matched exactly; a series without one needs a unique exact title-and-original-year match. Movie titles without an IMDb identifier are not guessed.

Metadata work runs only while the experience is open and **Show discovery** is enabled. A batch considers its starting title and up to twelve identifiable peers, with at most eight HTTP requests and a twelve-second request budget. Cached facts appear first. Responses and local storage are bounded; leaving the screen cancels active lookup work.

The private metadata cache holds up to 96 records, fresh for seven days. During failures, records up to 30 days old may be reused. Existing catalog snapshots and cached artwork can support offline browsing, but opening a streaming app still depends on that app and its connection.

[Privacy details](privacy.md#blind-pick-and-rabbit-hole) · [TVmaze and Cinemeta credits](../THIRD_PARTY_NOTICES.md#tvmaze-and-show-artwork)
