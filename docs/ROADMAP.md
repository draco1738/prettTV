> Upstream Rivi reference, retained for provenance. This describes Rivi v0.15, not the modified prettTV implementation. See the root README, THIRD_PARTY_NOTICES.md and docs/validation.md for prettTV.

# Roadmap

Not done yet, roughly in order of usefulness:

1. Test after a device reboot: does the service bring Rivi up over the stock home screen?
2. Test on more devices and remotes; keep a "tested on" list.
3. Measure the added latency of routing key presses through the accessibility service (needs a
   camera or hardware key source; adb-injected keys bypass the filter).
4. Benchmark against Arc Launcher and Projectivy with animations switched off.
5. Verify playback, DRM, casting and Bluetooth audio explicitly with Rivi as home. Nothing
   they depend on is changed, but it has not been checked.
6. Test the no-computer install route (Downloader + "Allow restricted settings").
7. A Play Store listing, which would remove sideloading from the install steps.
8. Persist focus on a hard kill, not only when leaving the home screen.

9. More screenshots (tile menu, grid focus) taken from the Rivi-named build.

Out of scope on purpose: wallpapers, widgets, recommendations, themes, accounts.
