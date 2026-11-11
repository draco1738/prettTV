---
name: prettTV
description: A quiet native television home with OLED-black surfaces, black focused controls, bright outlines, and vivid app artwork.
colors:
  off-white: "#FFFFFF"
  black: "#000000"
  card-ground: "#000000"
  ui-muted: "#B8B8B8"
  card-muted: "#C0C0C0"
  card-edge: "#3A3A3A"
  panel-start: "#000000"
  panel-end: "#000000"
  panel-divider: "#303030"
  button-ground: "#000000"
  button-focus: "#000000"
  focus-outline: "#FFFFFF"
  navigation-ground: "#000000"
  navigation-selected: "#000000"
  action-panel-ground: "#000000"
  action-panel-edge: "#646464"
  empty-ground: "#000000"
  empty-focus: "#000000"
  empty-edge: "#3A3A3A"
  hero-action-ground: "#000000"
  hero-progress-track: "#3A3A3A"
  row-progress-track: "#3A3A3A"
  stremio-marker: "#8657D7"
  app-icon-fallback: "#000000"
  app-pack-ground: "#000000"
  weather-sun: "#D8BC72"
typography:
  display:
    fontFamily: "sans-serif-medium"
    fontSize: "58px"
  title:
    fontFamily: "sans-serif-medium"
    fontSize: "29px"
  section:
    fontFamily: "sans-serif-medium"
    fontSize: "28px"
  body:
    fontFamily: "sans-serif"
    fontSize: "22px"
  label:
    fontFamily: "sans-serif"
    fontSize: "26px"
  navigation-label:
    fontFamily: "sans-serif"
    fontSize: "26px"
  weather-location:
    fontFamily: "sans-serif"
    fontSize: "23px"
  action:
    fontFamily: "sans-serif-medium"
    fontSize: "26px"
  hero-detail:
    fontFamily: "sans-serif"
    fontSize: "25px"
  compact-detail:
    fontFamily: "sans-serif"
    fontSize: "23px"
  support:
    fontFamily: "sans-serif"
    fontSize: "19px"
rounded:
  card: "15px"
  app-row-card: "36px"
  thumbnail: "10px"
  shared-panel: "16px"
  hero-action: "12px"
spacing:
  app-gap: "12px"
  library-app-gap: "18px"
  card-gap: "20px"
  card-inset: "22px"
  section-gap: "24px"
  hero-inset: "38px"
  page-left: "56px"
  page-right: "56px"
components:
  hero-balanced:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.display}"
    rounded: "{rounded.card}"
    width: "1132px"
    height: "486px"
  show-card:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "218px"
  show-card-small:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "190px"
  show-card-large:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "270px"
  app-card-balanced:
    textColor: "{colors.off-white}"
    rounded: "{rounded.app-row-card}"
    width: "208px"
    height: "94px"
  app-card-cinema:
    textColor: "{colors.off-white}"
    rounded: "{rounded.app-row-card}"
    width: "208px"
    height: "116px"
  app-card-quiet:
    textColor: "{colors.off-white}"
    rounded: "{rounded.app-row-card}"
    width: "208px"
    height: "176px"
  sport-card:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "218px"
  button:
    backgroundColor: "{colors.button-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 17px"
  button-focused:
    backgroundColor: "{colors.button-focus}"
    textColor: "{colors.off-white}"
    borderColor: "{colors.focus-outline}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 17px"
  navigation-button:
    backgroundColor: "{colors.navigation-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.navigation-label}"
    rounded: "{rounded.card}"
    padding: "0px 22px"
    height: "56px"
  navigation-button-focused:
    backgroundColor: "{colors.button-focus}"
    textColor: "{colors.off-white}"
    borderColor: "{colors.focus-outline}"
    typography: "{typography.navigation-label}"
    rounded: "{rounded.card}"
    padding: "0px 22px"
    height: "56px"
  empty-row:
    backgroundColor: "{colors.empty-ground}"
    textColor: "{colors.off-white}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 32px"
---

# Design System: prettTV

The **0.6.1 C Balanced override** below supersedes earlier C geometry, compact-panel descriptions and landscape containment. Other sections retain their stated historical source scope. Actual native visual and release outcomes belong to the [versioned validation record](docs/validation.md); earlier captures and passed checks remain historical evidence, not certification of a newer build.

## C Balanced composition · 0.6.1

**Refinement candidate:** Home interface size in the 0.6.2 source offers Standard (100%, unchanged default) and Larger (104%). The same reference geometry below is multiplied by one app-local unit for cards, text, icons and spacing. Outer margins are recomputed from the viewport to keep the stage inside the screen; lower shelves scroll. This is not a new composition, bitmap zoom or system-density change. Separate Activities/system screens are outside this setting. Versioned validation, not this source contract, establishes installation and device results.

The owner selected the reviewed split-stage C concept and requested native implementation. C uses 64-reference-pixel side insets and a 1792-pixel content width. At normal text scale, its 1112 × 510 resume feature begins at y142; a 40-pixel gap separates it from the 640-pixel discovery region. Three 202-pixel portrait cards use 16-pixel gaps and sit above Browse shows. The shared top navigation remains opaque and persistent, with no sidebar. A/B retain their earlier compositions.

The feature uses a left/bottom black legibility scrim, a native medium title up to two lines, supplied metadata/remaining time, a 336-pixel progress track only for known progress, and a compact outlined black action with the source beside it. Three right-side posters fill 2:3 picture areas using one proportional centered-crop scale, with separate white titles below. C prefers genuine, dimension-checked portraits from exact series matches within existing service tiers; unmatched titles retain provider artwork as a proportional crop, never independent stretching. Optional matched backgrounds remove baked-in promotional text from the feature when available. White focus outlines and still artwork distinguish the new modes without a spotlight or idle motion. Enlarged text increases stage and card heights to avoid overlap.

One finite 208 × 94 app row retains 36-pixel corners and 12-pixel gaps. Standard-size C continuation rows become three compact horizontal cards across the content width, 128 pixels tall at normal text size. Each has a left thumbnail and separate title/supporting information. Small/Large row preferences remain supported, as do separate Shows/Movies rows and all lower shelves. This composition does not replace real content with the concept's sample titles or progress. [Detailed behavior](docs/balanced-home.md).

The installed-app long-press panel includes **Force stop** for eligible apps. It opens with **Open** focused. Force stop uses a separate native confirmation that defaults to **Cancel**, retains Home behind its solid dark surface and returns to the same context. A verified stop offers **Done** first and **Open app**; a failed connection offers **App info** where available. The operation never silently relaunches the app or redraws/reorders the app row. Labels describe stopping an app, not clearing its data or resetting the TV network.

## Overview

**Creative North Star: "A quiet television home"**

The owner's direction is OLED black with white controls, lightweight and attractive, with subtle contrast and colored app logos. The interface feels restrained and cinematic: the black ground recedes, real artwork supplies the color, and bright focus outlines make the next remote action clear from a couch. Focused controls and the hero action keep dark fills rather than becoming large white blocks.

This is the native Android TV system: Kotlin Android Views, Canvas cards, native dialogs, and the device's system sans-serif aliases. C Balanced is the selected home; A Cinema and B Quiet remain available. The owner's latest direction replaces the sidebar with persistent opaque horizontal navigation in all three layouts. The exact brand spelling is prettTV, appearing in the footer's About/Credits entry, with no top wordmark.

The top bar contains Home, Shows, Library, Inputs, Search, and Settings; location, condition, temperature and clock sit to the right. It stays visible while the content scrolls. Left and Right remain finite at row boundaries. Up from the first content card reaches navigation; Down from navigation returns to the remembered content card. The Android application ID is `dev.zane.pretttv`.

**Key Characteristics:**

- True black ground, gentle tonal separation, and fine visible card edges.
- Actual provider artwork, attributed app icons, and installed-app marks supply the color.
- A clear remote focus state, finite rows, and quiet finite motion.
- A persistent opaque horizontal navigation bar shared by A, B, and C.
- Native text scaling, readable metadata, and full accessible labels behind visual truncation.

The current source retains the 208-reference-pixel Your apps widths, 36-pixel corners and 12-pixel gaps introduced in 0.2.2, alongside the finite focus spotlight and small artwork drift. Layout-specific app heights, Library geometry and content order remain intact. The [36-pixel comparison](docs/previews/rounder-36/README.md) records that historical refinement; the [earlier comparison](docs/previews/rounder-app-cards/README.md) preserves the 0.2.1 design. Version 0.4.0 additionally changes navigation, hero hierarchy, contextual app actions, selected metadata, section typography and refresh/interaction feedback.

[Validation history](docs/validation.md) records scoped checks, evidence versions and outcomes. Rendered previews document approvals; they are not native captures. The A/B/C gallery and audio-output capture show 0.5.1 on the TV; top-navigation and app-action captures remain historical 0.4.0 evidence; uncaptured details, reminder states and updater screens are not certified by that gallery. The [native design evidence](docs/native-design-evidence.md) distinguishes current source/captures from the original review's historical scope. Source in `launcher/app/src/main/kotlin/fi/jommi/rivi/` defines the native components below.

## Colors

The palette uses pure-black surfaces, black panels, white text, and neutral gray metadata. The frontmatter and `PrettTvColors` own current opaque colors; older sidecar material remains historical until explicitly refreshed.

### Primary

- **White** is the principal text, card focus outline, and progress fill. Its contrast provides the strongest card interaction cue.
- **Focus outline** (`focus-outline`) marks focused utility and navigation controls. Their fill stays black (`button-focus`) and their text stays white.

### Secondary

- **Stremio violet** is a source marker only: a small circular S before applicable titles. It is not a global action accent.
- Installed-app logos and content artwork retain their own color. Their palette is data, not a fixed theme-token scale.

### Neutral

- **True black** is the rendered home background. **Card ground** fills unloaded cards and sports cards.
- **UI muted** and **card muted** preserve the implementation's distinct supporting-text assignments. Navigation uses a solid neutral black ground and a quiet selected-page fill.
- **Card edge** quietly separates adjacent black surfaces. The shared Discover panel uses a solid black **panel start / panel end** ground and a **panel divider**.
- **Button ground**, **navigation ground**, and **empty ground** distinguish utility regions. Their focused treatments are recorded separately. Contextual app panels use a solid `action-panel-ground` with a fine `action-panel-edge` outline.
- **App pack ground** is the black native tile behind the mapped icon pack. **App icon fallback** places an unmapped app's installed icon on the same black ground. Known dark monochrome marks receive targeted contrast treatment; brand colors and content artwork retain their own colors.

**The Color-Pop Rule.** Keep the surrounding interface subdued so real app marks and still imagery carry the color.

## Typography

**Display and title face:** Android `sans-serif-medium`, requested with `Typeface.NORMAL`.

**Body and control face:** Android `sans-serif`, requested with `Typeface.NORMAL`.

These are the actual native aliases. No downloaded face, browser fallback stack, or numeric font weight is established. The larger title and modest medium weight create hierarchy without making every label bold.

### Hierarchy

- **Display:** the hero title.
- **Title:** show, compact-discovery, and sports titles.
- **Section:** horizontal-row headings; **support** is used for right-aligned counts and source notes.
- **Body:** show metadata and compact descriptions. Compact descriptions occupy at most two lines, with a native baseline step of 29 reference pixels multiplied by display scale and system font scale.
- **Label:** generic buttons and the clock. All six navigation destinations use the 26-pixel regular **navigation-label**. Section headings and Discover use 28-pixel medium text. The compact hero action uses 22-pixel medium text.
- **Weather location:** the muted place label beside the clock. Temperature and time retain the 26-pixel label size and off-white color.
- **Hero detail** and **compact detail:** provider, episode, timing, and source information in their respective card modes.

The frontmatter's `px` values are **1920-wide design-reference pixels, not CSS pixels or Android dp**. Layout uses `widthPixels / 1920f`. Text additionally multiplies by `resources.configuration.fontScale`; TextViews set `COMPLEX_UNIT_PX`, while Canvas text uses the same scale explicitly. Kotlin aliases, native baseline positioning, and font-scale behavior remain authoritative. Do not convert these observations into an unimplemented CSS or Material type system.

One-line labels ellipsize at the end. Cards expose the full title, detail, and action through `contentDescription`. System dialogs and the search field retain native text behavior; their font metrics are not custom design tokens.

## Layout

The home is a 1920 × 1080 reference composition, scaled by display width. The opaque navigation bar is a sibling of the content `ScrollView` in the root `FrameLayout`, drawn above it and outside its scrolling viewport. There is no left rail. Content shelves scroll horizontally with hidden scrollbars and no overscroll glow. There are no implemented phone breakpoints or portrait layouts.

**Shared frame, source-derived:** every layout uses an inner width of 1808, 56-pixel left and right insets, and a 40-pixel bottom inset. The full-width navigation bar is 88 pixels high with 16-pixel vertical padding, 56-pixel controls and a one-pixel bottom divider. The content viewport begins at y=104, leaving a 16-pixel gap. Navigation labels have 22-pixel horizontal padding and 12-pixel gaps; the strip can scroll horizontally at enlarged text sizes. Selected navigation uses a black fill and underline; focused controls add a bright outline while retaining white text. Content status, weather and the clock occupy the right side.

The optional weather cluster, introduced in v0.2.3, shares the 56-pixel top-bar control area in A/B/C: a muted 23-pixel location label, a 30 × 30 condition icon, an off-white 26-pixel temperature, a quiet 1 × 24 divider and the off-white 26-pixel clock. All are vertically centered. The city-to-icon gap is 16 reference pixels, icon-to-temperature is 10, and the separator has 20 pixels on each side. The clock takes its natural text width without an artificial minimum. The location label ellipsizes within 240 reference pixels; the group's accessible description retains the full location, reading and condition. Original Canvas icons use off-white strokes and a small `weather-sun` accent for sun or lightning, with no downloaded icon assets. The group is passive and adds no remote-focus stop.

Weather uses the selected US ZIP's place label and nearby NWS observations. Valid readings appear without a `cached` suffix in the header; freshness remains available in Weather settings/details and the accessible description. Missing readings show `Updating…` during a request or `Unavailable` afterward, without an invented icon or temperature. The clock keeps Android's time format and time zone. Header updates preserve Home content and remote position; weather adds no repeating animation. The current 0.4.0 A/B/C and top-navigation captures show this shared header.

**C Balanced, selected:** upper content starts at y=104 below the persistent bar. The feature uses `hero-balanced`, followed by a card-gap and one shared side panel (656 × 486). That panel contains a Discover heading and two compact rows (608 × 184), with 24-pixel horizontal padding. These dimensions describe C's current surface, not a mandatory composition for every future screen.

The app shelf follows after a card-gap. Your apps uses 208-pixel-wide dark tiles in all three layouts, with 36-pixel corners and 12-pixel app-gap spacing. Eight complete tiles and a slice of the next fit within the 1808-pixel reference width when enough apps are present. Their heights remain 116 in A, 176 in B, and 94 in C. Every included app occurs once in this finite row, and Home returns it to its start. The top bar's Library action opens the complete six-column Library · All apps grid later on the page. That grid retains its existing width formula, integer `(inner - 5 × 18) / 6`, 154-pixel card height, 18-pixel gaps and 15-pixel corners. Hiding an app affects only the horizontal Your apps row; it stays in Library, search, and viewing history.

Content shelves default to four complete Standard cards and a visible portion of the next. Small shows five cards, Standard four, and Large three; their heights use `show-card-small`, `show-card`, and `show-card-large`. Width is the integer result of `(inner - count × 20 - 46) / count`. Sports event cards use the same size choices. Section headers occupy 44 reference pixels, shelf wrappers allow 6 pixels above and below focus-scaled cards, and section gaps separate shelves. Settings → Customize rows reorders or hides the content shelves and restores defaults; title limits of 20, 40, or 60 apply to Suggestions, Discover, Trending, and New releases.

Default content order is Continue watching · Shows, Continue watching · Movies, Watchlist, New episodes when there are release alerts, Live sports, Suggestions, Discover, Trending, and New releases, then Library · All apps and the footer. A saved row order or hidden-row preference overrides that default. Watchlist is present by default even when empty; New episodes is conditional. The two compact side-panel cards remain discovery.

Personal rows and the hero use local playback confirmations and separately stored observations of provider progress. Published timestamps seed a frozen baseline; refreshing a provider timestamp alone does not promote a title. Optional supported media sessions or I watched this most recently update confirmed local recency. Provider observations do not consume Watchlist and may be delayed or originate from shared accounts; they cannot identify the viewer or exact playback time. Continue watching applies chronological updates immediately while preserving the selected title, and the hero follows the newest eligible record. App metadata can be missing or ambiguous, so the hero is not guaranteed to represent all real viewing. Feed providers are balanced before row limits; user-selected Recommendation services or real shared activity establish service priority, with general/free feeds later and Stremio last and badged. Inferred priority is not verified login status. Discover uses additional titles outside the other discovery shelves while preserving the same card language.

**A Cinema:** the shared full-width content frame and persistent top bar, a full-width 470-pixel hero, and 208 × 116-pixel Your apps tiles.

**B Quiet:** the shared full-width content frame and persistent top bar, a short introductory line, 208 × 176-pixel Your apps tiles, then the shared content shelves. It omits the hero and shared Discover panel. A and B preserve the same colors, card language, source semantics, and remote behavior as C.

## Elevation & Depth

The home uses tonal layers, artwork scrims, fine outlines, restrained focus scale, and a clipped focus spotlight. The spotlight is painted inside the card, with no external glow or drop shadow. General dialogs remain native; the app-action and Force stop surfaces use the shared solid dark `AppActionStyle`, with no background blur or new UI framework.

SHOW cards use the native neutral black scrim recorded in the sidecar: transparent through 32% of height, reaching alpha `0xE6` at 58% and `0xF2` at the bottom. This is the implemented metadata-contrast fix. The hero adds a left-to-right scrim and a bottom scrim behind its title and action. Those gradient stops are native paint values, not invented surface colors.

App tiles remain black at rest, without a white wash; fine borders provide separation. Focused compact content cards retain a `0x0D` white overlay. Brand artwork stays recognizable.

**The Quiet Focus Rule.** Cards animate only when focus changes, then stay still. SHOW, APP, and SPORT scale to 1.025 over 140 ms; HERO and COMPACT remain at 1.0. A separate 180 ms focus amount controls the spotlight opacity and up to 6 reference pixels of rightward artwork drift; loss of focus returns both to rest. Both transitions use `DecelerateInterpolator`. Text, progress, and action geometry do not independently drift with the artwork. Disabled animators apply the final focus state immediately.

Every `PrettTvCard` mode shares the spotlight, including Library app cards. Its off-white radial gradient uses ARGB `0x2EF4F1E9`, `0x0FF4F1E9`, and `0x00F4F1E9` at positions 0, 0.34, and 0.74. A size-cached matrix turns it into an ellipse centered at 23% of card width on the top edge, with axes scaled to 94% of width and 140% of height. It stays inside the rounded clip and beneath title text and the HERO/SHOW legibility scrims. App and Library tiles limit paint opacity to 100 of 255 at full focus; other card modes retain the existing intensity. Focus changes its opacity; there is no idle pulse or continuously running shader.

Landscape imagery reserves 12 reference pixels of horizontal overscan at rest to cover the full 6-pixel travel. Contained portrait imagery limits drift to its available side space. App logos keep their 70%-width/58%-height fit; fallback app artwork reserves 6 pixels on either side, and sports logos retain their existing fit. The artwork is translated rather than stretched.

Interrupted focus changes cancel the previous motion and retarget from the current focus amount. Animation starts only for an attached, shown card in a visible window. Window hiding, aggregated visibility loss, and detachment cancel both focus animators and settle the current focus state.

Horizontal focus travel retains its separate finite 180 ms native `ValueAnimator` with `DecelerateInterpolator`; disabling animators makes the scroll immediate. A thin 80 × 5 hold cue appears after 180 ms on long-clickable app/Library cards and stops by the system long-press threshold. Release, other keys, focus loss, window loss or detachment clear it. A static End cue lasts at most 650 ms after a boundary response. No feedback loops while idle. Content refreshes preserve unaffected cards, artwork, focus and viewport; status-only changes update the label without stopping row motion.

## Shapes

Your apps tiles use the rounder 36-pixel `app-row-card` silhouette. This override applies only to the horizontal Your apps row; Library app tiles, sports-app shortcuts, other cards and navigation buttons retain the 15-pixel `card` silhouette. The card's clip and outline use the same radius, with the painted shape inset 3 reference pixels from the View boundary. Compact imagery has its own smaller `thumbnail` corners. The shared Discover panel and hero action retain their own radii. App-action surfaces use 20-pixel corners and their focusable choices use 10-pixel corners.

Resting non-compact cards use a 1-pixel outline; Library uses the stronger edge token for legibility. Focused non-hero cards use a 2.4-pixel white outline. Compact rows have no independent resting outline, because the shared panel contains them; focus reveals their card outline. Hero focus keeps its action black with a white outline, play glyph and text, without scaling the feature.

## Components

### Hero and its action

One calm feature with a legible lower-left action. The title and metadata sit inside `hero-inset`. The action is painted within the focusable hero rather than implemented as a separate focus target. Its height is `36 + 32 × fontScale` reference pixels (68 at default font scale), and its width is the measured label plus 82 pixels, capped at 72% of the available inset width. A native play triangle precedes the label; the source app appears beside it. A thin 220-pixel progress track and remaining-time text appear only with valid supplied progress. The title stays at 58 reference pixels.

`ProgramPresentation` separates supplied provider details, source and calculated remaining time without guessing episodes. **Continue watching** requires a non-catalog title, position greater than zero but below duration, and a nonblank app destination. Otherwise the action says **Open [app]**. If no feature is available, the same component offers **Browse apps**. A destination does not guarantee exact-second resume behavior in another service. Preserve the full accessible action label.

### Show cards and compact discovery

SHOW cards keep the title and source visible over the strong bottom scrim, with optional real progress along the lower edge. Additional supplied metadata/remaining time fades into reserved footer space on focus using the existing finite focus amount. Card size and row position do not change. They use `card-inset`. Portrait art is contained when its aspect ratio is below 1.3; wider artwork fills the image area. Do not stretch a source poster.

COMPACT cards share the Discover panel: imagery occupies 52% of the row width, followed by a 25-pixel gap and text. The title and provider are single-line; the summary uses two lines. The compact row stays at its original scale on focus and gains an white outline.

### App tiles

Dark and recognizable, with the app mark as the visual focus. `AppIconPack` maps twelve attributed Homarr Dashboard Icons PNGs to installed packages: Netflix, Prime Video, YouTube, Apple TV+, Crunchyroll, Stremio, Paramount+, Peacock, Pluto TV, Hulu, NordVPN, and HBO. It composes each onto `app-pack-ground` in a native bitmap, initially containing it within 72% of tile width and 68% of height. The original source files remain unchanged; native composition lifts Paramount's monochrome mark and Pluto's black wordmark to off-white while retaining Pluto's colored rings and transparency.

`launcher/app/src/main/assets/app-icons/sources.json` records pinned source URLs, SHA-256 values, and modification flags; the adjacent `LICENSE.txt` carries the upstream license. Current source uses the attributed HBO light asset for its mapped packages and a native `ic_disney_dark` drawable for Disney+, composed onto the black tile ground. Unmapped apps use their installed icon on that ground. Final app-card drawing fits detected logo bounds within 70% of card width and 58% of height; missing artwork falls back to the app label.

The app row has a real beginning and end. Right stays on the last app and Left stays on the first; a scrolled first-card viewport is revealed before the boundary response. No sidebar opens, row wraps or duplicate tiles are manufactured. Hold OK opens the anchored app panel and retains Hide from Your apps row or Show in Your apps row. Library retains every discoverable app, including those hidden from the row; search, suggestions, and viewing history remain unaffected. Settings → Restore apps to Your apps row clears every row-only hide choice. If the row is empty, Choose apps for this row remains focusable. The full app label remains accessible.

The contextual panel is 400 reference pixels wide with 56-pixel safe insets and a 20-pixel anchor gap. It chooses the available side of the selected tile and clamps inside the screen; long menus/font sizes scroll. Its solid surface dims Home without moving the selected card. Open receives initial focus; D-pad stays inside the finite action list. Dismissal restores origin/viewport before any chosen action runs. Force stop uses a separate translucent Activity and real native confirmation with Cancel first; held repeats never confirm it automatically. Its title/message scroll independently above a measured action column so enlarged text and long messages cannot clip the buttons. Result screens focus Done first.

### Sports cards

SPORT cards use the common ground, outline, title, and metadata styling. An authentic broadcast logo is contained near the top; a network or league name substitutes when no logo is present. The upper label carries event status or timing. ESPN schedule labels and app destination labels are explicit.

The section states **Opens RBTV+** when that is the selected destination. An optional accessibility helper can match both teams and kickoff time inside supported RBTV 3.0.320 layouts for NFL, college football, NBA, and Premier League. It starts only after an explicit game selection, runs for at most 30 seconds, and stops before the user's server choice. Unsupported or unmatched events use the app-opening/manual-selection path. This is bounded UI assistance, not a verified per-game deep link or a guarantee that the game, channel, server, or stream is available. Published provider events and labeled sports-app shortcuts retain their distinct presentation.

Favorite teams are chosen through native league and checkbox dialogs. Live events rank first, then favorite-team relevance and kickoff time. Optional kickoff reminders use an existing tonal full-width button below the header, say Starting soon with the event title, and expose OK to dismiss. They appear for eligible upcoming favorite-team events while Home is open; no background or playback guarantee is implied.

### Navigation and utility controls

All A/B/C layouts use the same persistent opaque top bar with Home, Shows, Library, Inputs, Search and Settings. Navigation stays fixed while the content scrolls. The current destination gets a black selected fill and underline; focused controls retain a dark fill and gain a bright outline. Its first and last entries stop horizontal travel. Up from the first content card reaches Home navigation; in Quiet, every Your apps tile has an Up path to navigation. Down returns to the remembered content card. Shows and Library focus their sections. Header Home resets the page and all rows to their default start, matching remote Home. The former sidebar is absent.

Generic utility buttons retain the tonal focus treatment. The exact footer label is About prettTV · Credits; branding stays at the bottom. Search and Settings are labeled top-navigation destinations, with weather and time to their right.

### Empty states, search, and menus

Empty shelves are full-width, two-line focusable native TextViews with tonal fill and a visible border. Their message identifies the current state and action: TV listings permission, no published personal items, unavailable titles, or disabled discovery. Focus changes to the recorded empty-focus ground and off-white border.

Search uses a full dark native page with a single-line `EditText`, explicit Search/Clear/Back actions, grouped app/title results and an inline empty state. It retains the current-process query and result focus; long results page in batches of 60. Settings, layout selection, app choices and title hiding use native dialogs. Hold OK on a title exposes local hide choices; Settings → Shows & recommendations → Hidden titles restores individual titles.

Hold OK also offers I watched this most recently to correct local recency. Playback tracking is optional: its native settings explanation identifies Android Notification access, media-session metadata, local viewing records, and the limits of apps that omit title metadata. The tracker does not read or store notification text. Game handoff settings lead to Android Accessibility settings and explain the request-bounded RBTV helper. These additions use existing native dialogs rather than new visual components.

### Watchlist, release alerts, and title details

Watchlist reuses SHOW cards, size controls, metadata treatment, and the Saved by you source note. It sorts saved titles newest first and pages long lists in groups of 60 with Newer saved titles and Older saved titles actions. Its empty state says Save something for later and points to Hold OK → Add to Watchlist. Saving is local; confirmed later viewing may remove a saved title through the recorded local-playback policy, with an Undo entry. Saving does not assert availability.

The long-OK native menu adds or removes a title from Watchlist. Series also offer Follow new episodes or Unfollow new episodes. The New episodes shelf appears only when release alerts exist and explicitly says Episode released · availability unconfirmed. Alerts require verified show matching and release metadata; individual alerts can be dismissed. Followed shows and their check status remain accessible through native Settings dialogs.

About this title / source opens a scrollable native details dialog with source-supplied synopsis, real progress when available, and availability wording. Open / actions presents actual provider destinations; Save to Watchlist or Remove saved is a separate native action. The details body uses the existing regular system face at 24 reference pixels with native scrolling. Uncaptured dialog appearance remains source-derived.

Settings exposes Undo with the latest action label for recorded changes, including app arrangement, Watchlist, following, title hiding, row preferences, and recommendation feedback. It is bounded session history, not a promise that every operation has a persistent undo. Not interested removes a title from recommendation shelves and adjusts genre preference within service priorities while leaving Watchlist and viewing history intact; Settings also resets feedback.

### Offline status and updates

Offline Home reuses cached content and app access. A generation-scoped status reveals Updating only after an actual refresh exceeds 700 ms and clears it on completion or cancellation. Failures retain usable content and artwork; Offline identifies unavailable connectivity, while Update unavailable identifies a source failure without claiming the TV is offline. Saved-content age appears when known. Metadata updates rebind stable cards, and recommendation-row reordering waits until focus leaves where possible. Continue watching reorders immediately by recency while preserving the selected title. Removed or expired content is not retained. Cached recommendations still expire after seven days and stale live events remain suppressed. These statuses do not manufacture fresh viewing evidence or stream availability.

Settings → Updates opens a native scrollable screen with the same black ground, off-white and muted text, quiet borders, and black focused buttons with bright outlines. It has explicit Check now, Download, Install, Cancel, and Back actions, state-dependent availability, a polite accessibility status region, and plain release notes. It never opens automatically from Home. APK download is user initiated and Android confirms installation.

This screen uses Android dp/sp rather than the home's 1920-wide reference units: buttons are 52 dp high with 8 dp corners, 1 dp outlines, 17 sp labels, and 12 dp gaps. Disabled button text uses `PrettTvColors.DISABLED` (#868686). These are source-local native control measurements, not replacements for the home token scale. Versioned native evidence records which updater states were captured.

## Do's and Don'ts

### Library and original experiences

The bottom Library retains the six columns, 154-reference-pixel cards, 15-pixel corners and 18-pixel gaps introduced in 0.3.0. In 0.5.1 source, cards use #000000 with #646464 resting outlines, a contained logo region up to 64 pixels high and a separate 24-pixel off-white name. Missing artwork uses an initial plus the app name. Real installed icons replace full banners for unmapped apps. The known monochrome Paramount silhouette and Pluto wordmark are lifted for contrast; full-color brand details retain their colors. Focus keeps the existing finite motion and bright outline, with a reduced spotlight. The top Your apps geometry is unchanged.

Blind Pick and Rabbit Hole appear after installed apps in Library only. Their original glyphs depict three concealed cards with a reveal spark and a rabbit emerging from a portal. Their native activity uses the established true black/off-white controls, three mystery cards with strict filters, or a starting title and factual relationship filters. It does not add animated backgrounds or accounts.

The rendered proposals live in `docs/previews/original-apps` and `docs/previews/library-glyphs`. They are design previews, not TV captures. During the original 0.3.0 source review, native rendering and remote checks were still required; subsequent outcomes belong to the versioned validation record. Earlier Home screenshots alone do not validate these additions or the 0.4.0 implementation.

### Do:

- **Do** preserve the true black ground, fine visible card edges, and color-pop app marks.
- **Do** keep remote focus clear through the implemented fill, outline, finite scale, clipped spotlight, and bounded artwork drift.
- **Do** retain system font scaling, full accessible labels, and honest visual truncation.
- **Do** use actual provider imagery, attributed icon assets, published progress, and labeled data sources.
- **Do** order personal history using local playback evidence and distinguish discovery, game-assistance, and app-opening paths.
- **Do** keep the opaque six-destination top navigation visible in all three layouts and make Library open the complete app grid.
- **Do** spell the brand prettTV, keep branding and credits at the footer, and retain A and B as alternatives to selected C.
- **Do** reuse native cards and dialogs for personal shelves, preserve user row preferences, and distinguish saved titles, release alerts, and playable availability.

### Don't:

- **Don't** add a top prettTV wordmark, restore the sidebar, or crowd the weather header with a cached suffix.
- **Don't** replace native system sans with the obsolete automated Salsa guess, or flatten fictional comps into the running home.
- **Don't** treat refreshed provider timestamps as proof of playback or invent progress, episode state, provider availability, or a verified sports stream link.
- **Don't** turn finite app shelves into looping rows or repeat apps to imply more content.
- **Don't** add idle animation, decorative drop shadows, or bright global accent surfaces to this quiet system.
- **Don't** extend the confirmed hide/restore and Home checks into claims about untested playback tracking or RBTV matching.
- **Don't** extend Home visual checks to uncaptured dialogs or apply one build's validation results to another build.

App hiding (0.1.5): keep the current viewport and move focus to the next surviving app, or the previous app at the row's end. An empty Your apps row focuses its chooser. Hiding from Library retains the same Library tile. Arrangement actions apply after the menu dismisses; they do not send the user to the hero or restart content fetching. Explicit Home still resets to the top and the beginning of the app row.

### Native utility extension · 0.5.0 source

This scoped extension retains the existing palette, typography, focus language and selected C layout. It updates the earlier Search and unmapped app-artwork descriptions without changing the home composition.

`AppFirstAidActivity` uses an opaque true black page with the app name below a 42-reference-pixel heading. Two independently scrollable columns separate connection/media-output diagnostics from a 580-reference-pixel action column, with a 64-pixel gap and 80-pixel side insets. Check again receives initial focus; the finite action list also contains Restart app, Audio devices, Network settings, App info and Back. Supporting text explains the limits of the checks. Media output and Bluetooth radio status remain separate labels, including an explicit unverified-output state. Unavailable restart stays dimmed and exposes its reason to accessibility. The separate restart confirmation reuses the dark `AppForceStopActivity` surface and defaults to Cancel.

`HomeSearchPanel` is a full dark native dialog with query, Search/Clear/Back controls, status and grouped results on one page. It uses 80-pixel side insets, a 42-pixel title and the shared control focus treatment. Results scroll below the query, with More results adding 60 at a time; opening a result retains the search surface behind it. Settings keeps native dialogs and presents Home & apps, Shows & recommendations, TV & audio, and Support, with Return to stock Google TV separate at the bottom. Unmapped app tiles now contain the real installed icon on `app-pack-ground`; mapped artwork and tile geometry remain intact.

Native fixture review covered First aid ready/error states, enlarged text and Search, with no material visual finding. Actual checks and production observations are recorded by version in [Validation](docs/validation.md); fixture review alone does not establish a passed release gate.

### OLED black and direct output selection · 0.5.1 source

The owner requested pure black cards and buttons with white text. `PrettTvColors` uses #000000 for page, header, cards, panels and selected controls; #FFFFFF for principal text and focus outlines; and #B8B8B8 for supporting text. Resting edges use #3A3A3A or #646464. App and Library cards remain black when selected, with a white outline and no spotlight fill. Native alert buttons and list selection mirror these colors. Original line icons and plain names replace bright utility-app plates; streaming logos keep their attributed marks and color accents. Adaptive fallback icons use their foreground layer on black. Cache generation icons9 refreshes old tiles.

The hero action is a compact Resume button when progress is available: 22-reference-pixel text, 46-pixel height at normal font scale, 12-pixel corners, a small play mark and 1.5-pixel white focus outline. Its width follows the label; the previous title and progress positions are preserved. Other provider actions retain their honest Open label. This styling applies to prettTV's own screens, not other apps or system overlays.

Audio devices adds a 240-reference-pixel Change output button beside the Bluetooth status in its existing 620-pixel panel. The output report and operation feedback remain below that row. The native choice dialog lists TV speakers, Bluetooth audio and HDMI ARC, marking the current verified selection. Choosing a route does not change radio power, request pairing or copy volume. Unknown, unsupported, changed-user and unverified results remain explicit. The initial safe focus is Change output; finite Down traversal retains Bluetooth power, optional messages, Manage devices and Back. Enlarged text is checked without changing the owner's system font size.

Earlier screenshots and sidecar material remain historical; the current palette is defined above and in source. Versioned checks and actual device observations belong to docs/validation.md.
