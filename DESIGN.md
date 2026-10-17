---
name: Luma TV
description: A quiet native television home with sage-black surfaces, ivory focus, and vivid app artwork.
colors:
  ivory: "#F4F1E9"
  sage-black: "#0F1510"
  card-ground: "#1C241E"
  ui-muted: "#B2C0B5"
  card-muted: "#BCC4BA"
  nav-muted: "#BCC3BA"
  card-edge: "#4B584B"
  panel-start: "#1D251E"
  panel-end: "#161E17"
  panel-divider: "#354032"
  button-ground: "#1B2922"
  button-focus: "#CDD9C7"
  nav-focus: "#313C31"
  rail-ground: "#15221B"
  rail-heading: "#ADC1B6"
  icon-ink: "#D5DAD2"
  icon-focus-ink: "#101612"
  empty-ground: "#171F18"
  empty-focus: "#263126"
  empty-edge: "#364033"
  hero-action-edge: "#89958A"
  hero-progress-track: "#414840"
  row-progress-track: "#435448"
  stremio-marker: "#8657D7"
  app-icon-fallback: "#1A1F2A"
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
  top-nav: "30px"
spacing:
  app-gap: "18px"
  card-gap: "20px"
  card-inset: "22px"
  section-gap: "24px"
  hero-inset: "38px"
  balanced-page-inline: "56px"
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
  nav-selected:
    backgroundColor: "{colors.ivory}"
    textColor: "{colors.sage-black}"
    typography: "{typography.label}"
    rounded: "{rounded.top-nav}"
    width: "128px"
    height: "56px"
  nav-focused:
    backgroundColor: "{colors.nav-focus}"
    textColor: "{colors.nav-muted}"
    typography: "{typography.label}"
    rounded: "{rounded.top-nav}"
    width: "128px"
    height: "56px"
  empty-row:
    backgroundColor: "{colors.empty-ground}"
    textColor: "{colors.ivory}"
    typography: "{typography.label}"
    rounded: "{rounded.card}"
    padding: "0px 32px"
---

# Design System: Luma TV

## Overview

**Creative North Star: "A quiet television home"**

The owner's direction is dark sage and black, lightweight and attractive, with subtle contrast and colored app logos. The interface feels restrained and cinematic: the dark ground recedes, real artwork supplies the color, and a small number of ivory focus treatments make the next remote action clear from a couch.

This is the built native Android TV system: Kotlin Android Views, Canvas cards, native dialogs, and the device's system sans-serif aliases. C Balanced is the selected home; A Cinema and B Quiet remain available. The provisional Luma identity appears in the footer's About/Credits entry, with no top wordmark or rail monogram.

**Key Characteristics:**

- Sage-black ground, gentle tonal separation, and fine visible card edges.
- Actual provider artwork and installed-app marks supply the color.
- A clear remote focus state, finite rows, and quiet finite motion.
- Native text scaling, readable metadata, and full accessible labels behind visual truncation.

This scan records `MainActivity.kt`, `LumaCard.kt`, `NavIcon.kt`, and `Apps.kt` under `launcher/app/src/main/kotlin/fi/jommi/rivi/`, together with the actual TV captures in `docs/screenshots/`. It does not elevate the obsolete static-comp font guess or fictional preview content into design authority. The finish verdict was **ship for the scored metadata-contrast and native-evidence fixes**, with no material regressions found in inspected changes; it was not whole-surface certification. See `docs/native-design-evidence.md` and `docs/validation.md`.

## Colors

The palette is a family of green-black surfaces, warm ivory text, and muted sage metadata. The frontmatter owns exact opaque colors; the sidecar records native alpha overlays and gradients.

### Primary

- **Ivory** is the principal text, selected top-navigation fill, focus outline, and progress fill. Its contrast provides the strongest interaction cue.
- **Pale sage focus** (`button-focus`) fills focused utility and rail buttons; their text switches to sage-black.

### Secondary

- **Stremio violet** is a source marker only: a small circular S before applicable titles. It is not a global action accent.
- Installed-app logos and content artwork retain their own color. Their palette is data, not a fixed theme-token scale.

### Neutral

- **Sage-black** is the rendered home background. **Card ground** fills unloaded cards and sports cards.
- **UI muted**, **card muted**, and **navigation muted** preserve the implementation's distinct supporting-text assignments.
- **Card edge** quietly separates adjacent dark surfaces. The shared panel uses a restrained diagonal **panel start → panel end** gradient and a **panel divider**.
- **Button ground**, **rail ground**, and **empty ground** distinguish utility regions. Their focused treatments are recorded separately.
- **App icon fallback** is used by the repository when an app supplies an icon without a banner. App tiles otherwise derive their ground from their installed artwork.

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
- **Label:** top navigation, generic buttons, the clock, and the Discover panel heading. The hero action uses the medium **action** variant.
- **Hero detail** and **compact detail:** provider, episode, timing, and source information in their respective card modes.

The frontmatter's `px` values are **1920-wide design-reference pixels, not CSS pixels or Android dp**. Layout uses `widthPixels / 1920f`. Text additionally multiplies by `resources.configuration.fontScale`; TextViews set `COMPLEX_UNIT_PX`, while Canvas text uses the same scale explicitly. Kotlin aliases, native baseline positioning, and font-scale behavior remain authoritative. Do not convert these observations into an unimplemented CSS or Material type system.

One-line labels ellipsize at the end. Cards expose the full title, detail, and action through `contentDescription`. System dialogs and the search field retain native text behavior; their font metrics are not custom design tokens.

## Layout

The home is a vertically scrolling 1920 × 1080 reference composition, scaled by display width. Content shelves scroll horizontally with hidden scrollbars and no overscroll glow. There are no implemented phone breakpoints or portrait layouts.

**C Balanced, selected:** page side margins use `balanced-page-inline`; the top inset is 30 reference pixels. A 56-pixel header precedes the upper content by 18 pixels. The feature uses `hero-balanced`, followed by a card-gap and one shared side panel (656 × 486). That panel contains a Discover heading and two compact rows (608 × 184), with 24-pixel horizontal padding. These dimensions describe C's current surface, not a mandatory composition for every future screen.

The app shelf follows after a card-gap. It shows six dark app tiles at a time, with app-gap spacing; width is the integer result of `(inner - 5 × 18) / 6`. C uses an inner width of 1808. Every included app occurs once in this finite row, and Home returns it to its start. A complete six-column app grid appears later on the page.

Content shelves show four complete cards and a visible portion of the next. Their width is the integer result of `(inner - 4 × 20 - 46) / 4`; cards are 218 reference pixels tall. Section headers occupy 44 pixels, shelf wrappers allow 6 pixels above and below focus-scaled cards, and section gaps separate shelves.

Current row order is Continue watching · Shows, Continue watching · Movies, Live sports, Suggestions, Trending, New releases, then All apps and the footer. Personal rows and the hero are ordered by newest published playback activity across providers; app-published timestamps can lag actual viewing, so this is not a guarantee of the user's true latest-watched title. The two compact cards remain discovery. Discovery rows can hold up to 60 titles; source labels preserve their distinction from personal history. These content choices belong to the current home contract in `docs/surface-brief.txt`.

**A Cinema:** an inner width of 1660, left page inset of 204, a 150 × 970 rail positioned 24 pixels from the top and left, a full-width 470-pixel hero, and 116-pixel app tiles.

**B Quiet:** the same rail and content width, a short introductory line, 176-pixel app tiles, then the shared content shelves. It omits the hero and shared Discover panel. A and B preserve the same colors, card language, source semantics, and remote behavior as C.

## Elevation & Depth

The home uses tonal layers, artwork scrims, fine outlines, and restrained focus scale. Custom cards do not draw drop shadows or glows. Dialog appearance remains owned by Android's `Theme.DeviceDefault.NoActionBar` theme.

SHOW cards use the native green-black scrim recorded in the sidecar: transparent through 32% of height, reaching alpha `0xE6` at 58% and `0xF2` at the bottom. This is the implemented metadata-contrast fix. The hero adds a left-to-right scrim and a bottom scrim behind its title and action. Those gradient stops are native paint values, not invented surface colors.

App tiles receive a very light white overlay (`0x06` alpha) to separate dark artwork from the page. Focused compact cards receive a `0x0D` white overlay. Neither changes the app-logo identity.

**The Quiet Focus Rule.** Resting surfaces stay still. Focus changes finish in 140 ms with `DecelerateInterpolator`; SHOW, APP, and SPORT cards scale to 1.025, while HERO and COMPACT remain at 1.0. When Android disables animators, the state changes immediately.

## Shapes

Cards share the softly rounded `card` silhouette, with the painted shape inset 3 reference pixels from the View boundary. Compact imagery has its own smaller `thumbnail` corners. The shared Discover panel is gently rounded; top-navigation pills and the hero action are more rounded than the content containers.

Resting non-compact cards use a 1-pixel card-edge outline. Focused non-hero cards use a 2.4-pixel ivory outline. Compact rows have no independent resting outline, because the shared panel contains them; focus reveals their card outline. Hero focus strengthens the action's outline instead of scaling or brightening the whole feature border.

## Components

### Hero and its action

One calm feature with a legible lower-left action. The title and metadata sit inside `hero-inset`. The action is painted within the focusable hero rather than implemented as a separate focus target. It is 64 reference pixels high, sizes to the measured label plus 82 pixels, and remains bounded by the hero's content width. A native play triangle precedes the label. The sidecar records its rest/focus fill alpha and outline widths.

Use actual provider art and published progress. The action says Resume in a provider only when the program supplies a resume intent; otherwise it says Open. If no feature is available, the same component offers Browse apps. Preserve the full accessible action label.

### Show cards and compact discovery

SHOW cards put the title and metadata at the bottom over the strong scrim, with optional real progress along the lower edge. They use `card-inset`. Portrait art is contained when its aspect ratio is below 1.3; wider artwork fills the image area. Do not stretch a source poster.

COMPACT cards share the Discover panel: imagery occupies 52% of the row width, followed by a 25-pixel gap and text. The title and provider are single-line; the summary uses two lines. The compact row stays at its original scale on focus and gains an ivory outline.

### App tiles

Dark and recognizable, with the installed app's logo as the visual focus. When a quiet banner's logo bounds can be detected, the logo fits within 70% of the card width and 58% of its height. Photographic banners retain their framing. The repository makes guarded local dark-tile transformations for recognized Netflix, YouTube, and Prime artwork; it preserves colored marks and avoids applying those transforms to materially changed assets. Missing artwork falls back to the app label.

The app row has a real beginning and end. D-pad focus remains at each endpoint instead of wrapping or manufacturing duplicate tiles. Hold OK opens the native arrangement menu; the full app label remains accessible.

### Sports cards

SPORT cards use the common ground, outline, title, and metadata styling. An authentic broadcast logo is contained near the top; a network or league name substitutes when no logo is present. The upper label carries event status or timing. ESPN schedule labels and app destination labels are explicit.

The section states **Opens RBTV+** when that is the selected destination. Selecting an event opens the chosen sports app with channel guidance. No verified per-game link or guaranteed channel availability is implied. Published provider events and labeled sports-app shortcuts retain their distinct presentation.

### Navigation and utility controls

C has one selected Home pill, quieter Shows, Apps, and Inputs actions, then authored Canvas search/settings icons and the clock. Unselected focused navigation adds the native nav-focus fill and ivory stroke. Selected Home retains its ivory fill. The icons use native lines and paths; focus inverts them onto an ivory rounded ground.

A and B use the same destinations in a sage rail with tonal utility buttons. The rail's upper spacer carries no monogram. Generic buttons invert to pale sage on focus. Luma's About/Credits button stays at the bottom of the page.

### Empty states, search, and menus

Empty shelves are full-width, two-line focusable native TextViews with tonal fill and a visible border. Their message identifies the current state and action: TV listings permission, no published personal items, unavailable titles, or disabled discovery. Focus changes to the recorded empty-focus ground and ivory border.

Search uses a native single-line `EditText` inside an Android `AlertDialog`; settings, layout selection, app choices, and title hiding use native dialogs. They are not custom web fields, chips, or sheets. Hold OK on a title exposes local hide choices; Settings → Hidden titles restores individual titles. Native surfaces outside the custom home retain their platform styling.

## Do's and Don'ts

### Do:

- **Do** preserve the sage-black ground, fine visible card edges, and color-pop app marks.
- **Do** keep remote focus clear through the implemented fill, outline, and finite scale treatments.
- **Do** retain system font scaling, full accessible labels, and honest visual truncation.
- **Do** use actual provider imagery, published progress, and labeled data sources.
- **Do** keep personal history chronological and distinguish discovery and app-opening sports cards.
- **Do** keep Luma branding and credits at the footer and retain A and B as alternatives to selected C.

### Don't:

- **Don't** add a top Luma wordmark or rail monogram.
- **Don't** replace native system sans with the obsolete automated Salsa guess, or flatten fictional comps into the running home.
- **Don't** invent watch progress, episode state, provider availability, or a verified sports stream link.
- **Don't** turn finite app shelves into looping rows or repeat apps to imply more content.
- **Don't** add idle animation, decorative drop shadows, or bright global accent surfaces to this quiet system.
- **Don't** describe the scoped finish verdict as complete functional certification or a passed pixel-fidelity gate.
