---
name: prettTV
description: A quiet native television home with sage-black surfaces, ivory focus, and vivid app artwork.
colors:
  ivory: "#F4F1E9"
  sage-black: "#0F1510"
  card-ground: "#1C241E"
  ui-muted: "#B2C0B5"
  card-muted: "#BCC4BA"
  card-edge: "#4B584B"
  panel-start: "#1D251E"
  panel-end: "#161E17"
  panel-divider: "#354032"
  button-ground: "#1B2922"
  button-focus: "#CDD9C7"
  rail-ground: "#15221B"
  rail-heading: "#ADC1B6"
  empty-ground: "#171F18"
  empty-focus: "#263126"
  empty-edge: "#364033"
  hero-action-edge: "#89958A"
  hero-progress-track: "#414840"
  row-progress-track: "#435448"
  stremio-marker: "#8657D7"
  app-icon-fallback: "#1A1F2A"
  app-pack-ground: "#141C17"
typography:
  display:
    fontFamily: "sans-serif-medium"
    fontSize: "58px"
  title:
    fontFamily: "sans-serif-medium"
    fontSize: "29px"
  section:
    fontFamily: "sans-serif"
    fontSize: "24px"
  body:
    fontFamily: "sans-serif"
    fontSize: "22px"
  label:
    fontFamily: "sans-serif"
    fontSize: "26px"
  rail-label:
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
  thumbnail: "10px"
  shared-panel: "16px"
  hero-action: "23px"
spacing:
  app-gap: "18px"
  card-gap: "20px"
  card-inset: "22px"
  section-gap: "24px"
  hero-inset: "38px"
  page-left: "56px"
  page-right: "56px"
components:
  hero-balanced:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.display}"
    rounded: "{rounded.card}"
    width: "1132px"
    height: "486px"
  show-card:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "218px"
  show-card-small:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "190px"
  show-card-large:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "270px"
  app-card-balanced:
    textColor: "{colors.ivory}"
    rounded: "{rounded.card}"
    height: "94px"
  sport-card:
    backgroundColor: "{colors.card-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.title}"
    rounded: "{rounded.card}"
    height: "218px"
  button:
    backgroundColor: "{colors.button-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 17px"
  button-focused:
    backgroundColor: "{colors.button-focus}"
    textColor: "{colors.sage-black}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 17px"
  rail-button:
    backgroundColor: "{colors.button-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.rail-label}"
    rounded: "{rounded.card}"
    padding: "0px 6px"
    width: "130px"
    height: "70px"
  rail-button-focused:
    backgroundColor: "{colors.button-focus}"
    textColor: "{colors.sage-black}"
    typography: "{typography.rail-label}"
    rounded: "{rounded.card}"
    padding: "0px 6px"
    width: "130px"
    height: "70px"
  empty-row:
    backgroundColor: "{colors.empty-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 32px"
---

# Design System: prettTV

## Overview

**Creative North Star: "A quiet television home"**

The owner's direction is dark sage and black, lightweight and attractive, with subtle contrast and colored app logos. The interface feels restrained and cinematic: the dark ground recedes, real artwork supplies the color, and a small number of ivory focus treatments make the next remote action clear from a couch.

This is the native Android TV system: Kotlin Android Views, Canvas cards, native dialogs, and the device's system sans-serif aliases. C Balanced is the selected home; A Cinema and B Quiet remain available. The owner has chosen a left sidebar that is hidden by default in all three layouts and appears as a fixed overlay on request. The exact brand spelling is prettTV, appearing in the footer's About/Credits entry, with no top wordmark or rail monogram.

The hidden sidebar is the latest explicit owner override. It contains Home, Shows, Library, Inputs, Search, and Settings. Left from the first card reveals it and dims content; Right, Back, or choosing a destination dismisses it. The content keeps its full width when the rail is closed. The Android application ID is `dev.zane.pretttv`.

**Key Characteristics:**

- Sage-black ground, gentle tonal separation, and fine visible card edges.
- Actual provider artwork, attributed app icons, and installed-app marks supply the color.
- A clear remote focus state, finite rows, and quiet finite motion.
- A hidden-by-default left overlay sidebar shared by A, B, and C.
- Native text scaling, readable metadata, and full accessible labels behind visual truncation.

This 0.2.0 refresh records an extension of the incumbent world. Current actual ADB captures in `docs/screenshots/A-cinema.png`, `B-quiet.png`, and `C-balanced.png` preserve the home palette, native typography, cards, and A/B/C compositions; B visibly introduces Watchlist below Movies. Source in `launcher/app/src/main/kotlin/fi/jommi/rivi/` establishes the new configurable shelves and native feature controls. Functional validation reports 202 JVM tests and 35 Android tests passing. The final functional gate and the user-approved one-hour soak checkpoint review passed; see `docs/validation.md`. The original two-hour run was stopped at the user's request and its native outcome remains UNRECORDED.

The current capture set does not show the new details, personalization, sports-preference, or update dialogs. Their descriptions below are source-derived, not a claimed visual pass. Earlier font-scale, hide/restore, cold-Home, and scoped visual checks remain historical evidence in the validation record. Optional playback tracking and RBTV assistance remain off in the supplied evidence; no new end-to-end playback or game-matching result is claimed. Screenshot pixels are unmodified actual device output.

The earlier finish verdict was **ship for the scored metadata-contrast and native-evidence fixes**, with no material regressions found in the changes inspected at that time. It was not whole-surface certification; the confirmed device actions and current bounded visual confirmation have their own stated scope. The obsolete static-comp font guess and fictional preview content remain outside design authority. Original provenance and review scope are retained in `docs/native-design-evidence.md` and `docs/validation.md`.

## Colors

The palette is a family of green-black surfaces, warm ivory text, and muted sage metadata. The frontmatter owns exact opaque colors; the sidecar records native alpha overlays and gradients.

### Primary

- **Ivory** is the principal text, card focus outline, and progress fill. Its contrast provides the strongest card interaction cue.
- **Pale sage focus** (`button-focus`) fills focused utility and rail buttons; their text switches to sage-black.

### Secondary

- **Stremio violet** is a source marker only: a small circular S before applicable titles. It is not a global action accent.
- Installed-app logos and content artwork retain their own color. Their palette is data, not a fixed theme-token scale.

### Neutral

- **Sage-black** is the rendered home background. **Card ground** fills unloaded cards and sports cards.
- **UI muted** and **card muted** preserve the implementation's distinct supporting-text assignments. **Rail heading** colors the Home heading above content.
- **Card edge** quietly separates adjacent dark surfaces. The shared panel uses a restrained diagonal **panel start → panel end** gradient and a **panel divider**.
- **Button ground**, **rail ground**, and **empty ground** distinguish utility regions. Their focused treatments are recorded separately.
- **App pack ground** is the dark native tile behind the mapped icon pack. **App icon fallback** is used when an unmapped app supplies an icon without a banner. Other app tiles derive their ground from installed artwork.

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
- **Label:** generic buttons, the clock, and the Discover panel heading. The smaller **rail-label** applies to all six sidebar destinations. The hero action uses the medium **action** variant.
- **Hero detail** and **compact detail:** provider, episode, timing, and source information in their respective card modes.

The frontmatter's `px` values are **1920-wide design-reference pixels, not CSS pixels or Android dp**. Layout uses `widthPixels / 1920f`. Text additionally multiplies by `resources.configuration.fontScale`; TextViews set `COMPLEX_UNIT_PX`, while Canvas text uses the same scale explicitly. Kotlin aliases, native baseline positioning, and font-scale behavior remain authoritative. Do not convert these observations into an unimplemented CSS or Material type system.

One-line labels ellipsize at the end. Cards expose the full title, detail, and action through `contentDescription`. System dialogs and the search field retain native text behavior; their font metrics are not custom design tokens.

## Layout

The home is a 1920 × 1080 reference composition, scaled by display width. The rail is a sibling of the content `ScrollView` in the root `FrameLayout`, but starts at `View.GONE` and occupies no permanent content column. When revealed, it is a fixed overlay and content alpha changes to 0.45. Dismissal restores content alpha to 1.0. Content shelves scroll horizontally with hidden scrollbars and no overscroll glow. There are no implemented phone breakpoints or portrait layouts.

**Shared frame, source-derived:** every layout uses an inner width of 1808, 56-pixel left and right insets, a 30-pixel top inset, and a 40-pixel bottom inset. The hidden overlay rail is 150 × 970, positioned 24 pixels from the top and left, with 10-pixel horizontal padding. Six 130 × 70 destination buttons follow a 100-pixel upper spacer; 20 pixels separate them. The content header holds Home and the clock, without top navigation or search/settings icons.

**C Balanced, selected:** a 56-pixel header precedes the upper content by 18 pixels. The feature uses `hero-balanced`, followed by a card-gap and one shared side panel (656 × 486). That panel contains a Discover heading and two compact rows (608 × 184), with 24-pixel horizontal padding. These dimensions describe C's current surface, not a mandatory composition for every future screen.

The app shelf follows after a card-gap. It shows six dark app tiles at a time, with app-gap spacing; width is the integer result of `(inner - 5 × 18) / 6`. Every included app occurs once in this finite row, and Home returns it to its start. The sidebar's Library action opens the complete six-column Library · All apps grid later on the page. Hiding an app affects only this horizontal Your apps row; it stays in Library, search, and viewing history.

Content shelves default to four complete Standard cards and a visible portion of the next. Small shows five cards, Standard four, and Large three; their heights use `show-card-small`, `show-card`, and `show-card-large`. Width is the integer result of `(inner - count × 20 - 46) / count`. Sports event cards use the same size choices. Section headers occupy 44 reference pixels, shelf wrappers allow 6 pixels above and below focus-scaled cards, and section gaps separate shelves. Settings → Customize rows reorders or hides the content shelves and restores defaults; title limits of 20, 40, or 60 apply to Suggestions, Discover, Trending, and New releases.

Default content order is Continue watching · Shows, Continue watching · Movies, Watchlist, New episodes when there are release alerts, Live sports, Suggestions, Discover, Trending, and New releases, then Library · All apps and the footer. A saved row order or hidden-row preference overrides that default. Watchlist is present by default even when empty; New episodes is conditional. The two compact side-panel cards remain discovery.

Personal rows and the hero use local playback evidence across providers. Published timestamps seed a frozen baseline; refreshing a provider timestamp alone does not promote a title. Optional supported media sessions or I watched this most recently update local recency. App metadata can be missing or ambiguous, so the hero is not guaranteed to represent all real viewing. Feed providers are balanced before row limits; user-selected Recommendation services or real shared activity establish service priority, with general/free feeds later and Stremio last and badged. Inferred priority is not verified login status. Discover uses additional titles outside the other discovery shelves while preserving the same card language.

**A Cinema:** the shared full-width content frame and hidden overlay rail, a full-width 470-pixel hero, and 116-pixel app tiles.

**B Quiet:** the shared full-width content frame and hidden overlay rail, a short introductory line, 176-pixel app tiles, then the shared content shelves. It omits the hero and shared Discover panel. A and B preserve the same colors, card language, source semantics, and remote behavior as C.

## Elevation & Depth

The home uses tonal layers, artwork scrims, fine outlines, and restrained focus scale. Custom cards do not draw drop shadows or glows. Dialog appearance remains owned by Android's `Theme.DeviceDefault.NoActionBar` theme.

SHOW cards use the native green-black scrim recorded in the sidecar: transparent through 32% of height, reaching alpha `0xE6` at 58% and `0xF2` at the bottom. This is the implemented metadata-contrast fix. The hero adds a left-to-right scrim and a bottom scrim behind its title and action. Those gradient stops are native paint values, not invented surface colors.

App tiles receive a very light white overlay (`0x06` alpha) to separate dark artwork from the page. Focused compact cards receive a `0x0D` white overlay. Neither changes the app-logo identity.

**The Quiet Focus Rule.** Resting surfaces stay still. Focus changes finish in 140 ms with `DecelerateInterpolator`; SHOW, APP, and SPORT cards scale to 1.025, while HERO and COMPACT remain at 1.0. When Android disables animators, the state changes immediately.

Horizontal focus travel uses a separate finite 180 ms native `ValueAnimator` with `DecelerateInterpolator`; disabling animators makes the scroll immediate. Content refreshes replace changed rows and preserve unaffected cards, artwork, focus, and viewport where possible. This description remains source-derived; final functional validation and the passed user-approved one-hour soak checkpoint review are scoped in `docs/validation.md`.

## Shapes

Cards and sidebar buttons share the softly rounded `card` silhouette. Cards inset their painted shape 3 reference pixels from the View boundary. Compact imagery has its own smaller `thumbnail` corners. The shared Discover panel is gently rounded; the hero action is more rounded than the content containers. The previous top-navigation pill is no longer part of the current home.

Resting non-compact cards use a 1-pixel card-edge outline. Focused non-hero cards use a 2.4-pixel ivory outline. Compact rows have no independent resting outline, because the shared panel contains them; focus reveals their card outline. Hero focus strengthens the action's outline instead of scaling or brightening the whole feature border.

## Components

### Hero and its action

One calm feature with a legible lower-left action. The title and metadata sit inside `hero-inset`. The action is painted within the focusable hero rather than implemented as a separate focus target. It is 64 reference pixels high, sizes to the measured label plus 82 pixels, and remains bounded by the hero's content width. A native play triangle precedes the label. The sidecar records its rest/focus fill alpha and outline widths.

Use actual provider art and published progress. The action says Resume in a provider only when the program supplies a resume intent; otherwise it says Open. If no feature is available, the same component offers Browse apps. Preserve the full accessible action label.

### Show cards and compact discovery

SHOW cards put the title and metadata at the bottom over the strong scrim, with optional real progress along the lower edge. They use `card-inset`. Portrait art is contained when its aspect ratio is below 1.3; wider artwork fills the image area. Do not stretch a source poster.

COMPACT cards share the Discover panel: imagery occupies 52% of the row width, followed by a 25-pixel gap and text. The title and provider are single-line; the summary uses two lines. The compact row stays at its original scale on focus and gains an ivory outline.

### App tiles

Dark and recognizable, with the app mark as the visual focus. `AppIconPack` maps twelve unmodified Homarr Dashboard Icons PNGs to installed packages: Netflix, Prime Video, YouTube, Apple TV+, Crunchyroll, Stremio, Paramount+, Peacock, Pluto TV, Hulu, NordVPN, and HBO. It composes each onto `app-pack-ground` in a native bitmap, initially containing it within 72% of tile width and 68% of height. The original source files remain unchanged; the native composition is separate.

`launcher/app/src/main/assets/app-icons/sources.json` records pinned source URLs, SHA-256 values, and modification flags; the adjacent `LICENSE.txt` carries the upstream license. Current source uses the attributed HBO light asset for its mapped packages and a native `ic_disney_dark` drawable for Disney+, composed onto the dark tile ground. Unmapped apps use installed banners or icons. When a quiet banner's logo bounds can be detected, final card drawing fits the detected logo within 70% of card width and 58% of height. Photographic banners retain their framing. The guarded dark-banner fallback remains only for unpacked artwork; missing artwork falls back to the app label.

The app row has a real beginning and end. Right stays on the last app; Left from the first app reveals the overlay with Library focused. Other shelf starts reveal Shows. No row wraps or manufactures duplicate tiles. Hold OK opens the native arrangement menu and toggles Hide from Your apps row or Show in Your apps row. Library retains every discoverable app, including those hidden from the row; search, suggestions, and viewing history remain unaffected. Settings → Restore apps to Your apps row clears every row-only hide choice. If the row is empty, Choose apps for this row remains focusable. The full app label remains accessible.

### Sports cards

SPORT cards use the common ground, outline, title, and metadata styling. An authentic broadcast logo is contained near the top; a network or league name substitutes when no logo is present. The upper label carries event status or timing. ESPN schedule labels and app destination labels are explicit.

The section states **Opens RBTV+** when that is the selected destination. An optional accessibility helper can match both teams and kickoff time inside supported RBTV 3.0.320 layouts for NFL, college football, NBA, and Premier League. It starts only after an explicit game selection, runs for at most 30 seconds, and stops before the user's server choice. Unsupported or unmatched events use the app-opening/manual-selection path. This is bounded UI assistance, not a verified per-game deep link or a guarantee that the game, channel, server, or stream is available. Published provider events and labeled sports-app shortcuts retain their distinct presentation.

Favorite teams are chosen through native league and checkbox dialogs. Live events rank first, then favorite-team relevance and kickoff time. Optional kickoff reminders use an existing tonal full-width button below the header, say Starting soon with the event title, and expose OK to dismiss. They appear for eligible upcoming favorite-team events while Home is open; no background or playback guarantee is implied.

### Navigation and utility controls

All A/B/C layouts use the same hidden-by-default sage overlay rail, with Home, Shows, Library, Inputs, Search, and Settings as labeled native buttons. Left from a shelf's first card or the hero reveals the rail and dims content; the hero reveals Home. Buttons rest on the tonal button ground and invert to pale sage on focus. There is no persistent selected Home pill. Left remains within the open rail and its first and last destinations stop vertical travel. Right or Back hides the rail and restores content focus. Choosing a destination also hides the rail before performing its action, and moving focus into content dismisses it.

The rail's upper spacer carries no monogram. Generic utility buttons retain the same tonal focus treatment. The exact footer label is About prettTV · Credits; branding stays at the bottom. Search and Settings no longer appear as top-right icons.

### Empty states, search, and menus

Empty shelves are full-width, two-line focusable native TextViews with tonal fill and a visible border. Their message identifies the current state and action: TV listings permission, no published personal items, unavailable titles, or disabled discovery. Focus changes to the recorded empty-focus ground and ivory border.

Search uses a native single-line `EditText` inside an Android `AlertDialog`; settings, layout selection, app choices, and title hiding use native dialogs. They are not custom web fields, chips, or sheets. Hold OK on a title exposes local hide choices; Settings → Hidden titles restores individual titles. Native surfaces outside the custom home retain their platform styling.

Hold OK also offers I watched this most recently to correct local recency. Playback tracking is optional: its native settings explanation identifies Android Notification access, media-session metadata, local viewing records, and the limits of apps that omit title metadata. The tracker does not read or store notification text. Game handoff settings lead to Android Accessibility settings and explain the request-bounded RBTV helper. These additions use existing native dialogs rather than new visual components.

### Watchlist, release alerts, and title details

Watchlist reuses SHOW cards, size controls, metadata treatment, and the Saved by you source note. It sorts saved titles newest first and pages long lists in groups of 60 with Newer saved titles and Older saved titles actions. Its empty state says Save something for later and points to Hold OK → Add to Watchlist. Saving is local; confirmed later viewing may remove a saved title through the recorded local-playback policy, with an Undo entry. Saving does not assert availability.

The long-OK native menu adds or removes a title from Watchlist. Series also offer Follow new episodes or Unfollow new episodes. The New episodes shelf appears only when release alerts exist and explicitly says Episode released · availability unconfirmed. Alerts require verified show matching and release metadata; individual alerts can be dismissed. Followed shows and their check status remain accessible through native Settings dialogs.

About this title / source opens a scrollable native details dialog with source-supplied synopsis, real progress when available, and availability wording. Open / actions presents actual provider destinations; Save to Watchlist or Remove saved is a separate native action. The details body uses the existing regular system face at 24 reference pixels with native scrolling. Uncaptured dialog appearance remains source-derived.

Settings exposes Undo with the latest action label for recorded changes, including app arrangement, Watchlist, following, title hiding, row preferences, and recommendation feedback. It is bounded session history, not a promise that every operation has a persistent undo. Not interested removes a title from recommendation shelves and adjusts genre preference within service priorities while leaving Watchlist and viewing history intact; Settings also resets feedback.

### Offline status and updates

Offline Home reuses cached content and app access. The header displays Offline with the cached-content age using the existing muted support text. Cached recommendation data expires after seven days; the status is informational and does not manufacture fresh viewing evidence.

Settings → Updates opens a native scrollable screen with the same sage-black, ivory, muted text, quiet borders, and pale-sage focus. It has explicit Check now, Download, Install, Cancel, and Back actions, state-dependent availability, a polite accessibility status region, and plain release notes. It never opens automatically from Home. APK download is user initiated and Android confirms installation.

This screen uses Android dp/sp rather than the home's 1920-wide reference units: buttons are 52 dp high with 8 dp corners, 1 dp card-edge outlines, 17 sp labels, and 12 dp gaps. Disabled button text is the source's muted `0xFF7C897E`. These are source-local native control measurements, not replacements for the home token scale. No screenshot of the updater or its permission dialog is included in the current evidence set.

## Do's and Don'ts

### Do:

- **Do** preserve the sage-black ground, fine visible card edges, and color-pop app marks.
- **Do** keep remote focus clear through the implemented fill, outline, and finite scale treatments.
- **Do** retain system font scaling, full accessible labels, and honest visual truncation.
- **Do** use actual provider imagery, attributed icon assets, published progress, and labeled data sources.
- **Do** order personal history using local playback evidence and distinguish discovery, game-assistance, and app-opening paths.
- **Do** keep the six-destination sidebar hidden by default in all three layouts and make Library open the complete app grid.
- **Do** spell the brand prettTV, keep branding and credits at the footer, and retain A and B as alternatives to selected C.
- **Do** reuse native cards and dialogs for personal shelves, preserve user row preferences, and distinguish saved titles, release alerts, and playable availability.

### Don't:

- **Don't** add a top prettTV wordmark, top navigation, or rail monogram.
- **Don't** replace native system sans with the obsolete automated Salsa guess, or flatten fictional comps into the running home.
- **Don't** treat refreshed provider timestamps as proof of playback or invent progress, episode state, provider availability, or a verified sports stream link.
- **Don't** turn finite app shelves into looping rows or repeat apps to imply more content.
- **Don't** add idle animation, decorative drop shadows, or bright global accent surfaces to this quiet system.
- **Don't** extend the confirmed hide/restore and Home checks into claims about untested playback tracking or RBTV matching.
- **Don't** treat the passed functional gate and user-approved one-hour soak review as visual certification of uncaptured 0.2.0 dialogs, or as completion of the original two-hour run.

App hiding (0.1.5): keep the current viewport and move focus to the next surviving app, or the previous app at the row's end. An empty Your apps row focuses its chooser. Hiding from Library retains the same Library tile. Arrangement actions apply after the menu dismisses; they do not send the user to the hero or restart content fetching. Explicit Home still resets to the top and the beginning of the app row.
