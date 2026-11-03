# Software review · November 2026

[Documentation](README.md) · [Validation history](validation.md)

This focused review covered the remote helpers, Home-return responsiveness, drawing and artwork work, content availability after app removal, update recovery, and release documentation. It is not a repository-wide security audit or a playback-performance certification.

## Repairs in 0.4.1

- Restore the documented TCL app shortcuts through the existing optional Remote buttons helper. Preserve native Disney handling, controller input, unrelated keys and stock-recovery pause behavior.
- Pass unrelated navigation keys directly through the shortcut handlers, avoiding recovery preference and input-device lookups on that common path.
- Reset Home's focus and scroll positions without recreating unchanged rows; keep the full rebuild for actual layout changes and unsettled initial layout. Remove an unused per-card title-matching calculation.
- Skip repeated artwork visibility queries inside clipped offscreen rows and cache compact summary wrapping without changing the text pixels.
- Add an Audio devices card under Inputs with direct Bluetooth on/off and a secondary **Manage devices** action. Direct switching reuses the existing authorized local recovery key and reads the resulting state before reporting completion. Opening the panel requests no new authorization and changes no radio setting; unavailable setup falls back to native controls. The panel explains that Bluetooth off disconnects Bluetooth headphones and remotes.
- Add a quick double-press **speaker Mute** Bluetooth shortcut through the enabled Remote buttons helper and the same authorized local connection. Both native mute events pass through immediately, restoring the prior mute state when the TV uses normal toggle behavior; no event is delayed or replayed. A brief checked result leaves the current app in place. Only the captured TCL `VOLUME_MUTE` key (164) participates, not microphone `MUTE` (91). Inputs → Audio devices remains the fallback; physical gesture evidence is recorded separately in Validation.
- Exclude new-episode alerts whose source app is no longer installed. Keep followed-show records so reinstalling an app does not erase a follow.
- Reconcile Rabbit Hole's starting title and back history after app removal, retaining valid history and selecting an available replacement when needed. Blind Pick also replaces a removed provider even when another app shares the same title identity.
- Repair discovery dead ends by loading metadata even when Blind Pick's strict genre/runtime filters initially return no choices. Unknown types and runtimes stay unknown. Series without a year can use a unique exact-title match; known years remain strict, malformed identities are rejected, and fetched records are rechecked. Rabbit Hole allows one extra visible lookup batch only when new facts still produce no connections, preserving the request limits and manual refresh.
- Recover update sessions interrupted before Android accepted the installation, while preserving an installation already waiting for Android confirmation.
- Keep Updates actions focusable after touch input and state changes so remote navigation reaches an enabled control.
- Correct the README's remote-helper link and refresh the release/build documentation.

Checks and remaining physical-device limitations are recorded against the final artifact in [Validation](validation.md). Existing Home layouts and preferences are preserved.

At the owner's request, this TCL was returned to its stock screensaver and EarthCam was removed. The optional EarthCam bridge remains available to other installations. This device configuration is separate from a software default.

The [historical release audit](release-audit-2026-11.md) found all 17 prior releases already marked stable and verified their exact-commit CI and signed APK identity. It retains incomplete checks and current functional reports rather than treating the release flag as proof that every feature works.

## Recommended next work

These are proposals, not features included in 0.4.1.

| Priority | Improvement | What it would solve |
| :--- | :--- | :--- |
| 1 | **Remote button editor and tester** | Let the owner press a supported remote key, select an installed app, test it and restore the default. Show firmware-reserved keys clearly; do not promise every manufacturer can be remapped. |
| 2 | **Viewing-source health panel** | Show when each app last supplied usable viewing progress and whether the latest order came from confirmed playback, provider progress or a manual correction. Put these details in Settings, keeping Home uncluttered. |
| 3 | **Unavailable Watchlist actions** | Preserve saved titles when their source app disappears, explain why Open is unavailable, and offer another already-known provider destination when one exists. Do not invent availability or silently delete saved titles. |
| 4 | **Smaller Home components with behavior checks** | Separate navigation/focus restoration, row assembly and settings actions from MainActivity. Preserve the current layout while making regressions easier to isolate and test. |
| 5 | **Desktop installer verification** | Validate the launcher APK against bundled release metadata before ADB installation, extending the integrity checks already used by the in-app updater and the RBTV companion installer. |

The first two address repeated troubleshooting friction. The fourth reduces maintenance risk as the launcher gains features; it should be delivered in small changes with the existing focus/navigation checks.
