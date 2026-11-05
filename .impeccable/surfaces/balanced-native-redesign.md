---
version: 1
slug: "balanced-native-redesign"
primary_target: "launcher/app/src/main/kotlin/fi/jommi/rivi/MainActivity.kt"
related_targets: ["launcher/app/src/main/kotlin/fi/jommi/rivi/PrettTvCard.kt", "launcher/app/src/main/kotlin/fi/jommi/rivi/BalancedHomeLayout.kt"]
---

## Direction contract

THESIS: Make the user's selected C composition native, not another incremental restyling.

OWN-WORLD: OLED black, white controls, native sans, real artwork and recognizable app logos. No sidebar, decorative blur or autoplay.

STORY: Resume the newest eligible viewing, explore three recommendations, or open an app with the remote.

FIRST VIEWPORT: At 1920×1080, 64px side insets; 1112×510 hero at y142, 40px gutter, three 202px portraits within the 640px right column; Browse shows beneath. One 208×94 app row follows, then compact chronological continuation cards. Text growth can increase height.

FORM: C Balanced, split-stage option from seed e3121a69, selected by the user from the reviewed A/B/C renders. Source of authority: private `pretttv-concepts-2026-11-05/renders/C.png` and C.html.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Scope and truth

Operate mode, Android TV only. Real provider data replaces sample content; no fabricated episode, progress or entitlement. Preserve chronological selection, local Watchlist, app destinations, row preferences, finite navigation, A/B alternatives, artwork bounds and existing remote/audio configuration. The owner's follow-up phone photo rejects wide banners letterboxed in tall Discover frames: prefer genuine, identity-checked portrait artwork and draw with one proportional fill scale, never independent stretching. Provider poster fields do not guarantee portrait dimensions. C-only optional TVmaze metadata is bounded and cached; ordinary feeds precede it, service tiers remain intact, and privacy/attribution disclosures describe the extra requests. The browser reference is not native evidence. Verify actual device screenshots and focus at normal/enlarged text, then signed APK integrity and retained settings. Keep all raw validation and device data private on E:.
