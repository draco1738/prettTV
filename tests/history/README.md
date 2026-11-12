# Viewing-history policy checks

## Behavior

`COMBINED` remains the default. Optional `THIS_TV_ONLY` uses positive locally confirmed playback evidence (`played:` plus the local position/duration) for Continue watching, the large hero and automatic service inference. Provider baselines, timestamp churn, newly shared titles and later provider progress cannot create local history or replace its position/recency. This does not identify the viewer or inspect another device/account. Opening an app or title is not proof of playback.

The optional media-session tracker requires supported, unambiguous same-package title matching, at least 15 seconds of sustained playback and at least 15 seconds of observed position. A known duration below three minutes is rejected. Missing/nonpositive session duration stays unknown (zero); it never borrows provider duration or clamps observed position to provider progress. No notification text is read and no permission is enabled by this setting. Explicit “I watched this most recently” correction remains available. With no local evidence—or all local hero titles hidden—the hero is empty, not a recommendation. Suggestions/Discover remain available as recommendations.

Automatic matching and `observe(...)` retain only the associated title, app/package and available artwork, with a local title ID. Episode details, provider IDs, destinations and other provider metadata are not promoted to local evidence. The tracker supplies observed position/duration separately. Even `Example show:S1E1` matching a provider `Example show` row with S9E2 does not establish episode equivalence: neither episode is asserted in the automatic local snapshot. Explicit `confirm(...)` still accepts the deliberately selected row's metadata and default progress.

A separate metadata snapshot is captured on confirmation. Matched provider artwork may enrich the display, but episode text and launch destination remain the local snapshot's values. Legacy confirmations without such a snapshot retain only known local progress plus a safe app-level destination; no current provider episode is guessed. Confirmed metadata is retained across provider disappearance within the existing 2,000-record bound, favoring recent local records. Uninstalled or hidden packages are excluded from repository output without deleting their history. Provider evidence is retained separately so changing modes is reversible.

See [exact integration API](INTEGRATION.md) for UI reset, settings restore and backup details.

## Offline host commands

From the E: worktree:

```sh
python tests/history/run_checks.py --repository --label verified-green
python tests/history/run_checks.py --repository --negative-control hero-fallback --label verified-negative-hero
python tests/history/run_checks.py --repository --negative-control provider-source --label verified-negative-provider
```

Negative controls are expected to exit 1. They alter only isolated E: source copies, never the shared worktree. Each run recompiles its source list, writes compiler/JUnit logs and source/dependency hashes under `E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/history/<label>`. The harness uses the cached Kotlin compiler, JDK, Android API jar, JUnit and pinned JSON-java; unchanged dependencies come from the sibling checkout's existing classes. It never installs or downloads dependencies.

## Actual verification

- Fail-first `red`: **34 tests, 10 failures**, after adding only the enum/merge argument and tests; policy still used the legacy combined implementation.
- `verified-green`: **46 tests passed**, including the actual ContentRepository selection boundary, real HomeContent feature/copy behavior, JSON-backed PlaybackHistory, SettingsBackupGuard, LocalSettingsTransfer round-trip and binary offline snapshot encode/decode.
- `verified-negative-hero`: **46 tests, 2 expected failures** when recommendation fallback is restored.
- `verified-negative-provider`: **46 tests, 20 expected failures** when local selection is replaced by provider/combined output.
- Python harness Ruff checks and owned tracked-file `git diff --check` passed. `python -m ruff` was unavailable; the existing E: cached Ruff executable passed instead.

The 46-test total includes the existing PlaybackPolicyTest and ProviderPlaybackPolicyTest suites, not just new tests. Intermediate successes overlap these totals. Early compile attempts exposed the not-yet-authorized model field and older cached PersonalizationStore/UndoHistory signatures; the final command compiles current source for these dependencies and the actual authorized model change. No provisional model copy was used for a successful final run.

## Audit P1: automatic metadata contamination

All audit runs use `--repository` so current ContentModel, repository and scoped constructor clients compile together. Commands below must be launched through the snapshot `quiet-run.py` wrapper with `CREATE_NO_WINDOW` when focus must remain with the user's game; do not use a console/GUI launcher.

- `audit-p1-red`: 49 tests, **2 expected failures** before production changes: title match exposes S9E2, and the old match/confirm chain persists S9E2 as local metadata.
- `audit-p1-tracker-red`: 52 tests, **4 expected failures** with the tracker write boundary extracted without changing behavior. Adds unsanitized-provider persistence and missing-session-duration clamping (950,000 observed becomes 900,000 provider duration).
- `audit-p1-green` and final `audit-p1-final-green`: **54 passed** each. Includes explicit confirmation, automatic boundary, actual session progress, unknown duration, thresholds, subsequent provider changes, disappearance/recreation, settings round-trip and stale/busy writer rejection. Final Python harness Ruff check also passed using the cached E: executable after `python -m ruff` reported that the module was absent.
- `audit-p1-negative-match-metadata`: 54 tests, **2 expected failures**.
- `audit-p1-negative-observed-metadata`: 54 tests, **3 expected failures**.
- `audit-p1-negative-session-duration`: 54 tests, **1 expected failure**.

Reproduce controls with `python tests/history/run_checks.py --repository --negative-control NAME --label LABEL`, where NAME is `match-metadata`, `observed-metadata` or `session-duration`. Only isolated snapshot source copies are mutated. Red/green/compiler logs and exact input hashes are retained under the existing history evidence directory. These totals overlap; they are not additive.

No schema migration is performed. Previously persisted snapshots have no provenance to distinguish deliberate user confirmation from the old automatic path, so they are not rewritten speculatively. A new automatic observation replaces that title's local snapshot with title-only metadata; explicit user correction can still deliberately accept a selected episode.

## Limits

These are scoped host-JVM checks, not a full Gradle build, Android instrumentation, MainActivity UI/lifecycle validation, actual media-session playback or provider IPC. The real ContentRepository source compiles, but host tests invoke its extracted selection boundary rather than Android Handler/Looper queues. Mode/generation callback checks and selection-before-inference wiring are source-reviewed, not native scheduling evidence. The parent owns full build, UI integration, ADB and deployment. No network, device/config changes or Git mutations were performed by this worker.
