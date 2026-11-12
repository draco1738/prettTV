# OLED Home and audio · 0.6.5 candidate

The owner approved rendered concepts **1 (Refined Home)** and **3 (Audio switcher)** on November 12, 2026. The TV is asleep. This candidate is staged for validation; it is not a published production release or a confirmed installation.

## Balanced Home

- Preserve the 1000-pixel hero, three 240×360 Discover portraits, persistent horizontal navigation and finite 208×94 app cards with 36-pixel corners.
- Reduce the hero title from 76 to 56 reference pixels and the play control to a 52-pixel square. Keep the truthful action and provider beside it, without a second duplicate provider label.
- Use one compact Shuffle control, retaining the existing bounded nonrepeat selection, Undo and empty-feed handling. Shows remains available in navigation.
- Show the fully visible range of included apps, updating it as the row scrolls. Hidden apps remain in Library.
- Place All activity / This TV filters in both Continue watching headings. They reuse the existing history policy, clear shared titles before local-only refresh, and preserve focus. Hold OK on a filter for an explanation. No account synchronization is added.
- Keep routine background refresh quiet in the top bar. Offline and failed-refresh notices remain available there.

## Audio

The temporary OLED panel opens at the right of Home and scrolls at larger text sizes. Bluetooth power stays separate from Sound output. TV speakers, HDMI ARC and Bluetooth audio are direct actions; no second chooser is required. A checkmark means a verified selected output; the outline means remote focus. Muted and unknown routes stay explicit. The panel never invents paired headphones or assumes that Bluetooth being on means audio is connected.

The existing identity-checked local control engine still verifies the selected route and rejects unsupported firmware. Output selection does not change Bluetooth power or copy volume. Bluetooth off retains the existing TV-speaker handoff. Manage devices and Back remain reachable during pending requests. Opening and binding the panel are read-only; repeated OK is ignored during an operation.

## Validation and delivery

JVM regression tests cover app-range boundaries and the separation of radio power, selected route, mute, pending and unverified states. Android checks cover focus retention, direct callbacks, repeated input, permission states, scaled text and Home filter restoration. Android test APK compilation is not a device-test pass.

No TV connection, wake command, installation, output change or native test is performed while the TV sleeps. Final native rendering, physical remote navigation and live output verification remain required before release. Existing screenshots in the README retain their historical version labels.
