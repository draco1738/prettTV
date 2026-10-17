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

This record combines the native source in `launcher/app/src/main/kotlin/fi/jommi/rivi/` with confirmed TCL Android TV evidence for installed v0.1.1 and 52 passing tests. Prime Video was hidden through Hold OK → Hide from Your apps row: the row count changed from 34 to 33, the app remained available in Library with Show in Your apps row, and restoring it returned the count to 34. Search and history independence were checked in source, not in separate device sessions. Cold Home after force-stop resolved `dev.zane.pretttv` to C with The Night Agent in the hero.

Final actual TV captures were recorded as `prettTV-final-A.png`, `prettTV-final-B.png`, `prettTV-final-C.png`, and `prettTV-final-sidebar.png`; their repository destinations are `docs/screenshots/A-cinema.png`, `B-quiet.png`, `C-balanced.png`, and `sidebar.png`. The prior font-scale 1.3 capture review passed. The v0.1.1 scoped visual confirmation passed. A subsequent clean-cache check identified bright installed-banner fallback; v0.1.2 replaces that fallback for Disney+ and HBO. Optional playback tracking and RBTV assistance remain off, so no end-to-end playback or game-matching result is claimed.

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

Content shelves show four complete cards and a visible portion of the next. Their width is the integer result of `(inner - 4 × 20 - 46) / 4`; cards are 218 reference pixels tall. Section headers occupy 44 pixels, shelf wrappers allow 6 pixels above and below focus-scaled cards, and section gaps separate shelves.

Current row order is Continue watching · Shows, Continue watching · Movies, Live sports, Suggestions, Trending, New releases, then Library · All apps and the footer. Personal rows and the hero use local playback evidence across providers. Published timestamps seed a frozen baseline; refreshing a provider timestamp alone no longer promotes a title. Optional supported media sessions or the manual I watched this most recently action update local recency. App metadata can be missing or ambiguous, so the hero is not guaranteed to represent all real viewing. The two compact cards remain discovery. Discovery rows can hold up to 60 titles; source labels preserve their distinction from personal history. These behaviors reflect the latest owner request and source, superseding earlier timestamp-only wording in the surface contract.

**A Cinema:** the shared full-width content frame and hidden overlay rail, a full-width 470-pixel hero, and 116-pixel app tiles.

**B Quiet:** the shared full-width content frame and hidden overlay rail, a short introductory line, 176-pixel app tiles, then the shared content shelves. It omits the hero and shared Discover panel. A and B preserve the same colors, card language, source semantics, and remote behavior as C.

## Elevation & Depth

The home uses tonal layers, artwork scrims, fine outlines, and restrained focus scale. Custom cards do not draw drop shadows or glows. Dialog appearance remains owned by Android's `Theme.DeviceDefault.NoActionBar` theme.

SHOW cards use the native green-black scrim recorded in the sidecar: transparent through 32% of height, reaching alpha `0xE6` at 58% and `0xF2` at the bottom. This is the implemented metadata-contrast fix. The hero adds a left-to-right scrim and a bottom scrim behind its title and action. Those gradient stops are native paint values, not invented surface colors.

App tiles receive a very light white overlay (`0x06` alpha) to separate dark artwork from the page. Focused compact cards receive a `0x0D` white overlay. Neither changes the app-logo identity.

**The Quiet Focus Rule.** Resting surfaces stay still. Focus changes finish in 140 ms with `DecelerateInterpolator`; SHOW, APP, and SPORT cards scale to 1.025, while HERO and COMPACT remain at 1.0. When Android disables animators, the state changes immediately.

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

`launcher/app/src/main/assets/app-icons/sources.json` records pinned source URLs, SHA-256 values, and `modified: false`; the adjacent `LICENSE.txt` carries the upstream Apache 2.0 license. Disney+ and HBO Max deliberately retain installed artwork because the candidate pack images did not fit. Disney+ logo sizing has been corrected in the current source. Other unmapped apps also use installed banners or icons. When a quiet banner's logo bounds can be detected, final card drawing fits the detected logo within 70% of card width and 58% of height. Photographic banners retain their framing. The guarded dark-banner fallback remains only for unpacked artwork; missing artwork falls back to the app label.

The app row has a real beginning and end. Right stays on the last app; Left from the first app reveals the overlay with Library focused. Other shelf starts reveal Shows. No row wraps or manufactures duplicate tiles. Hold OK opens the native arrangement menu and toggles Hide from Your apps row or Show in Your apps row. Library retains every discoverable app, including those hidden from the row; search, suggestions, and viewing history remain unaffected. Settings → Restore apps to Your apps row clears every row-only hide choice. If the row is empty, Choose apps for this row remains focusable. The full app label remains accessible.

### Sports cards

SPORT cards use the common ground, outline, title, and metadata styling. An authentic broadcast logo is contained near the top; a network or league name substitutes when no logo is present. The upper label carries event status or timing. ESPN schedule labels and app destination labels are explicit.

The section states **Opens RBTV+** when that is the selected destination. An optional accessibility helper can match both teams and kickoff time inside supported RBTV 3.0.320 layouts for NFL, college football, NBA, and Premier League. It starts only after an explicit game selection, runs for at most 30 seconds, and stops before the user's server choice. Unsupported or unmatched events use the app-opening/manual-selection path. This is bounded UI assistance, not a verified per-game deep link or a guarantee that the game, channel, server, or stream is available. Published provider events and labeled sports-app shortcuts retain their distinct presentation.

### Navigation and utility controls

All A/B/C layouts use the same hidden-by-default sage overlay rail, with Home, Shows, Library, Inputs, Search, and Settings as labeled native buttons. Left from a shelf's first card or the hero reveals the rail and dims content; the hero reveals Home. Buttons rest on the tonal button ground and invert to pale sage on focus. There is no persistent selected Home pill. Left remains within the open rail and its first and last destinations stop vertical travel. Right or Back hides the rail and restores content focus. Choosing a destination also hides the rail before performing its action, and moving focus into content dismisses it.

The rail's upper spacer carries no monogram. Generic utility buttons retain the same tonal focus treatment. The exact footer label is About prettTV · Credits; branding stays at the bottom. Search and Settings no longer appear as top-right icons.

### Empty states, search, and menus

Empty shelves are full-width, two-line focusable native TextViews with tonal fill and a visible border. Their message identifies the current state and action: TV listings permission, no published personal items, unavailable titles, or disabled discovery. Focus changes to the recorded empty-focus ground and ivory border.

Search uses a native single-line `EditText` inside an Android `AlertDialog`; settings, layout selection, app choices, and title hiding use native dialogs. They are not custom web fields, chips, or sheets. Hold OK on a title exposes local hide choices; Settings → Hidden titles restores individual titles. Native surfaces outside the custom home retain their platform styling.

Hold OK also offers I watched this most recently to correct local recency. Playback tracking is optional: its native settings explanation identifies Android Notification access, media-session metadata, local viewing records, and the limits of apps that omit title metadata. The tracker does not read or store notification text. Game handoff settings lead to Android Accessibility settings and explain the request-bounded RBTV helper. These additions use existing native dialogs rather than new visual components.

## Do's and Don'ts

### Do:

- **Do** preserve the sage-black ground, fine visible card edges, and color-pop app marks.
- **Do** keep remote focus clear through the implemented fill, outline, and finite scale treatments.
- **Do** retain system font scaling, full accessible labels, and honest visual truncation.
- **Do** use actual provider imagery, attributed icon assets, published progress, and labeled data sources.
- **Do** order personal history using local playback evidence and distinguish discovery, game-assistance, and app-opening paths.
- **Do** keep the six-destination sidebar hidden by default in all three layouts and make Library open the complete app grid.
- **Do** spell the brand prettTV, keep branding and credits at the footer, and retain A and B as alternatives to selected C.

### Don't:

- **Don't** add a top prettTV wordmark, top navigation, or rail monogram.
- **Don't** replace native system sans with the obsolete automated Salsa guess, or flatten fictional comps into the running home.
- **Don't** treat refreshed provider timestamps as proof of playback or invent progress, episode state, provider availability, or a verified sports stream link.
- **Don't** turn finite app shelves into looping rows or repeat apps to imply more content.
- **Don't** add idle animation, decorative drop shadows, or bright global accent surfaces to this quiet system.
- **Don't** extend the confirmed hide/restore and Home checks into claims about untested playback tracking or RBTV matching.
