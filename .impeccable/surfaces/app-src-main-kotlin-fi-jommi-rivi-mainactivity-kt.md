---
version: 1
slug: "app-src-main-kotlin-fi-jommi-rivi-mainactivity-kt"
primary_target: "launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt"
related_targets: ["launcher/app/src/main/kotlin/fi/jommi/rivi/PrettTvCard.kt", "launcher/app/src/main/kotlin/fi/jommi/rivi/BalancedHomeLayout.kt", "launcher/app/src/main/kotlin/fi/jommi/rivi/HomeInterfaceSize.kt"]
---
## Direction contract

THESIS: Preserve the owner's approved v0.6.1 C Balanced television home while making small, reversible readability refinements.

OWN-WORLD: OLED black, white native system type, thin white focus outlines and recognizable dark app marks with restrained brand color. Real artwork supplies the visual interest. No sidebar, top wordmark, autoplay, decorative blur or bright focused fills.

STORY: Resume the newest eligible viewing, inspect three recommendations, or open an app with a remote from couch distance.

FIRST VIEWPORT: The owner's later request explicitly enlarges Discover beside the resume feature without replacing C. At Standard size on a 1920×1080 viewport, the new source keeps 64-pixel side insets and the 40-pixel gutter, narrows the feature to 1000 pixels and widens Discover to 752 pixels. All three portraits grow from 202×303 to 240×360 picture areas, with the same 16-pixel gaps and full title clearance; Browse shows remains below. The shared stage grows from 510 to 567 pixels instead of squeezing titles. Your apps remains one finite row of 208×94 cards with 36-pixel corners and 12-pixel gaps. Standard-size C Continue watching rows retain three compact 128-pixel-high cards and scroll normally below. System text scaling may increase heights. A Cinema and B Quiet remain unchanged.

HEADER: A persistent opaque horizontal bar contains Home, Shows, Library, Inputs, Search and Settings, then optional location/weather, clock and the far-right Bluetooth symbol. Up reaches navigation; Down returns to remembered content. Normal system volume is untouched. Bluetooth radio status is not proof of the selected audio output.

FORM: Refinement, not redesign. The v0.6.1 screenshots and DESIGN.md's C override supersede the older sidebar and two-card side-panel proposals. The matching `balanced-native-redesign.md` brief records the approved native composition. Obsolete surface guidance is retained only as historical evidence in `../archive/pre-0.6-home-surface.md`.

FINISH: Versioned native captures and validation determine what shipped. Current source is not proof of installation. An unavailable TV blocks native verification and release promotion; preserve the source and report that limit rather than reusing old passes.

## Refinement source contract

Interface size offers Standard (100%) and Larger (104%). One app-local unit scales cards, text, icons, spacing and controls proportionally. Smaller outer margins retain the complete hero and three-poster stage rather than cropping a magnified bitmap. Lower content scrolls normally. Android density, system font preferences, other applications and separate system screens are not changed. Standard remains the default; both sizes use the newly requested Discover proportions, independent of the overall size option. The saved setting is included in explicit local settings transfer. New source, including broader prettTV screen coverage, requires its own native verification before installation claims.

Continue watching compact metadata must show each known episode/source/time field once. Full provider descriptions remain available in title details. Do not infer progress, episode identity, entitlement or account-wide history from a decorative label.

## Behavior and data constraints

Operate mode, Android TV only. Kotlin/native Views and Canvas; no new runtime UI framework. Keep queues/caches bounded, title identities stable, normal content available before optional presentation lookups, and selection stable during artwork updates.

The hero follows real viewing evidence and explicit local corrections. Discovery remains recommendations, not a replacement for chronology. Preserve preferred-service tiers during artwork selection. Dimension-checked exact-match portrait metadata is preferred; fallback artwork uses one proportional centered crop, never independent stretching. Ambiguous identities remain unmatched.

C's feature/poster/continuation artwork stays still. Other modes retain Classic/Quiet focus motion and disabled-animation support. Home resets rows to their beginning; Back and app-return retain their established behavior. Horizontal boundaries are finite, not wrapping. Hidden Home shortcuts remain in Library. Blind Pick and Rabbit Hole remain at the bottom of Library.

Keep separate Shows/Movies continuation rows, local Watchlist, following, feedback, hidden-title rules and configurable long discovery shelves. Missing information remains unknown. The owner's disabled Google Home packages, helper-off/native-volume configuration, stock screensaver and removed apps are not changed by an interface update.

## Evidence, storage and attribution

Approved baseline: `docs/screenshots/C-balanced-0.6.1.png`, `C-discovery-focused-0.6.1.png`, `C-browse-shows-0.6.1.png`. These are historical v0.6.1 device captures, not evidence for a newer candidate. See `docs/validation.md` for versioned outcomes and explicit limitations.

Keep source, tools, builds, caches and private snapshots on E:. Conversation archives, device dumps, preference exports and credentials stay outside public commits/releases. Preserve Rivi/Jommi, PLUI design-reference, app-icon and TVmaze attribution in THIRD_PARTY_NOTICES.md and applicable assets. Do not claim Google account Watchlist synchronization.
