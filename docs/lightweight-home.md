# A lighter Home

[prettTV](../README.md) / [Documentation](README.md) / Performance

Version 0.5.2 applies the [lightweight UI research](lightweight-ui-research-2026-11.md) to the existing native interface. It keeps the OLED palette, layouts, finite app row, title limits and remote controls.

## What changed

**Visible icons first.** App artwork is ordered from the visible apps through the remaining Home shortcuts. Library marks follow. Cache hits reuse decoded images; an app-version change invalidates its previous in-memory icon.

**Less duplicate image work.** Identical URL-and-size requests share one decode while retaining separate cancellation. Visible work overtakes queued prefetch. Home prefetches at most two near neighbours across the visible rows, following the latest horizontal direction. Moving away cancels obsolete subscribers and removes unused queued work. Two decoder workers, a maximum of 66 outstanding jobs, a 12 MiB decoded cache and existing compressed-file limits remain bounded. Cancellation suppresses delivery; it does not promise immediate interruption of a provider's active read.

**Fewer live cards in long shelves.** Suggestions, Discover, Trending and New releases with more than 16 titles keep the full model and scroll extent, but mount cards only around the viewport, with two neighbours on each side. A focused card stays mounted until focus transfers. Stable title keys restore app returns and refreshed rows; removing a late selected title selects its neighbour rather than returning to the shelf's beginning. Short shelves, Continue watching and paginated Watchlist retain their existing structure.

**Background memory release.** A short app switch retains warm Home state. After Home has remained stopped for 30 seconds, its content/app bitmap references and decoded artwork cache are released. The hierarchy, selected title, scroll positions and preferences stay intact. Returning reloads artwork through the caches without rediscovering apps or rebuilding Home. Android memory-pressure callbacks can trim sooner; this does not clear disk caches or stop another app.

**An optional quieter focus effect.** In **Settings → Home & apps → Focus motion**, Quiet uses a smaller 120 ms lift with still artwork. It avoids the per-frame artwork slide and spotlight repaint. Classic retains the previous motion and remains the default. Both have immediate white focus outlines and respect Android's disabled-animation setting. Neither debounces or drops directional key presses.

## How to verify

Build the release, isolated stability APK, instrumentation APK and fixture app as described in [stability testing](stability-testing.md). On an idle authorized TV:

```powershell
./tests/android-fault-tests.ps1 -Tv '<serial>' -Suite Lightweight -OutputDirectory 'E:/TV-validation/lightweight'
```

The targeted suite covers shared/cancelled artwork, priority, Quiet/Classic pixels, 60-title reachability, held/reversed input in A/B/C, metadata changes, app Back versus Home, late-title removal, background bitmap release and existing native visual/navigation checks. `-Suite All` additionally runs the broader fault, recovery, personalization and app-action suites. Test packages are isolated from production and removed afterward.

Use the same signed-build route and conditions when comparing `tests/measure-home-motion.ps1`. Read [the versioned validation record](validation.md) for actual observations. Synthetic UI-thread timings exclude remote transport and panel latency; fewer card objects are not a sustained frame-rate claim. Provider/network latency and the performance of streaming apps are outside the launcher's control.

No framework, new runtime dependency, autoplay or background animation was added. Baseline Profiles remain a possible later experiment if startup traces justify them. Existing source and icon attribution are unchanged; this implementation does not import code from the other researched launchers.
