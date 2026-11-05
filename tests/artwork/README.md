# C-only presentation artwork checks

## Integration contract

- `ContentRepository.start(..., balancedArtwork: Boolean = false)` has a new optional **final** argument. Pass true only for C Balanced; keep the default for A/B and other repository users.
- `HomeContent.presentationArtwork: Map<String, PresentationArtwork> = emptyMap()` is a new final field. Keys are original `Program.id` values. The immutable record has `poster`, `backdrop`, and `sourceUrl` strings; blank means unavailable.
- Use `BalancedArtworkPolicy.discovery(content, services, recent, feedback)` for C's three display cards. It shares the repository's six-candidate pool via `discoveryCandidates(...)`: existing service/feedback ranking, series preference within each service tier, and sports/feature exclusion. Verified portraits are preferred **within each service tier of that bounded pool**, not across service priorities or from an unrestricted catalog.
- Resolve the C poster request as `content.presentationArtwork[p.id]?.poster?.takeIf { it.isNotBlank() } ?: p.posterArt.ifBlank { p.art }`. Do **not** skip enrichment merely because `posterArt` is nonblank: Android providers use `poster_art_uri` for wide banners too.
- Resolve the C feature request as `content.presentationArtwork[p.id]?.backdrop?.takeIf { it.isNotBlank() } ?: p.art`.
- Include display-art changes in the view's artwork-rebind/change detection, even when the original `Program` is unchanged. Keep the actual `Program` attached to actions, progress, focus and persistence. Never copy display art into the provider model.
- `sourceUrl` is the returned TVmaze show page. Existing About/credits attributes TVmaze; optional per-title presentation-source UI may use this field without overwriting provider provenance.
- MainActivity opts in only for C, checks artwork URL changes even when the Program is unchanged, and keeps the selected title focused when verified portraits change the three-card order. The renderer uses one proportional center-crop scale to fill the 2:3 frame. A/B and lower provider shelves keep their original images.

## Safety and limits

All normal catalog, sports, episode and requested team-directory work precedes optional network work. Cache hits can accompany ordinary deliveries. Further deliveries are progressive and generation-checked. An empty candidate pool with no feature leaves the pass armed. `stop()` and `refresh()` cancel active artwork connections. Only `start()` arms a lookup pass; existing periodic refreshes never re-arm it. Discovery must be enabled and Android must report a network before a new pass. No new account, runtime dependency, polling schedule, Stremio requirement or history upload.

The pass considers at most six ranked discovery candidates, stops after three verified portraits in candidate order, then may resolve the visible hero. Metadata requests share an eight-request limit (including redirects), a twelve-second network-work budget, 600 ms pacing, 2.5-second maximum connect/read waits and a 600 KB response cap. An independent timer disconnects the active connection when its remaining batch budget expires. This is not a strict end-to-end wall-clock guarantee: connection creation, private-cache I/O and platform disconnect behavior remain outside that guarantee. Failures can produce fewer than three matches. It does not scan farther down the feed or promise catalog coverage.

Series matching uses valid supplied IDs when present; otherwise it requires one exact normalized name among the complete bounded search response after matching any known year. Missing-year namesakes/remakes are rejected. Wrong-title/ID results, malformed IDs, untyped items, movies and sports are not silently reinterpreted. Poster selection requires TVmaze's `poster` category and real dimensions near 2:3, preferring actual 2:3 variants over 3:4 main images. Backgrounds require the `background` category and landscape dimensions. A CDN URL must be supplied by the response; no URL or episode is fabricated. No episode-still lookup is implemented because the current provider model does not reliably preserve both actual episode identifiers.

Separate atomic cache: 96 entries / 500 KB, 7-day positives, 1-day misses, 1-hour transport/parser failures and global 429 backoff. Keys hash complete matching evidence, not just a mutable provider row ID. URLs and attribution are stored, not progress, launch URIs, history or raw metadata responses. The existing image loader owns image-byte caching.

## Run without a project build

From the repository root on the existing E: toolchain:

```sh
python tests/artwork/run_checks.py
```

This invokes cached Kotlin 2.1.0 and JUnit jars directly, compiling only the two owned existing sources, three new implementation files and the checks. Unchanged dependencies come from the already-built release compile jar. No Gradle, signing, APK generation, device operation or installed-app call occurs. A SHA-256-pinned JSON-java 20240303 jar is downloaded into the E: evidence directory for the desktop JVM parser checks only; Android itself supplies `org.json` in production. If existing compile dependencies are absent, the harness fails rather than running a build.

Checks cover presentation-only behavior, exact title/type/year and ID boundaries, remakes/fuzzy matches, malformed/truncated parsing, poster/background categories and dimensions, URL allowlists, selection/feedback/candidate bounds, request/elapsed/response budgets, cancellation, positive/negative reload and TTLs, cache corruption/eviction, and provider-ID reuse. Test logs are under `../snapshots/balanced-artwork-2026-11-05/checks/`.

Optional replay of separately fetched **public** API evidence (not device history):

```sh
python tests/artwork/run_checks.py --evidence
```

This requires the local `public-artwork-evidence.json`, `search-*.json` and `images-*.json` files in that evidence directory. It runs the production parser without network requests; no illustrative show list or asset is bundled in the APK. The evidence probe verified real portraits for What We Do in the Shadows, Stick and Game of Thrones visible in the supplied native screenshot. The Morning Show had several exact namesakes and was correctly refused without a year/ID. These public metadata/image checks alone are **not** proof that three eligible discovery cards match on the installed device. The subsequent signed 0.6.1 installation independently reported genuine 2:3 images for Nocturne, Frieren: Beyond Journey's End and Slow Horses through `--artwork-diagnostics`; provider types and exact metadata, not assumptions from a screenshot title, determine eligibility.
