# Different picks: pure host seam

`DiscoverRotation.kt` is independent of Android, `Program`, artwork, stores and time. It changes only session-local title selection. Run it on one owning thread (normally the UI thread).

## Parent integration API

```kotlin
private val discoverRotation = DiscoverRotation()

// Build this using the parent's FULL already-ranked, already-filtered candidate list.
// Keep the FIRST representative for each stable title identity, not the last duplicate.
val candidatesByKey = linkedMapOf<String, Program>()
for (program in fullRankedEligibleCandidates) {
    candidatesByKey.putIfAbsent(program.titleKey, program)
}
val snapshot = discoverRotation.update(candidatesByKey.keys.toList(), sourceKey, content.discoverySettled)
val posters = snapshot.keys.mapNotNull(candidatesByKey::get)
```

- `update(rankedKeys, sourceKey = "default", initialComplete = true): Snapshot`: initial incremental publications can fill and re-rank the first batch until normal catalog loading finishes. The repository marks this before optional artwork; completion/failure also settles the last available catalog. A successful explicit `next()` freezes the selection early. Once settled, preserve surviving picks and their order: ordinary metadata updates, reorders and arrivals do **not** rotate or fill gaps. Resolve fresh metadata through the latest map, not cached `Program` objects. Removed picks disappear immediately. Provisional picks count as seen because they were genuinely displayed.
- `current(): Snapshot`: detached, unmodifiable `keys`; `hasMore`, `canUndo`, `undoDepth`, `rememberedCount`.
- `next(): Change?`: choose up to three **unseen** identities in supplied order. `Change` contains immutable `before`, `after` snapshots and an opaque `undoToken`. `null` means **no more picks**, with absolutely no state/history mutation. Do not register an external Undo on this branch.
- `undo(token: UndoToken? = null): Snapshot?`: restore the immediately previous retained batch, filtered against current eligibility. Prefer the exact token when registering a callback in the existing top-bar Undo. Null means stale token, wrong owner/order, evicted/unavailable batch, or another no-op. An Undo does **not** mark the replaced batch unseen. No automatic cycling after exhaustion.

On successful `next()`, render `change.after`; register `recordUndo("Different picks") { ... discoverRotation.undo(change.undoToken) ... }` only when the previous batch was nonempty and history is enabled (defaults are enabled). Reconcile the latest eligible candidate list **before** invoking Undo, and rebuild rendered cards from the latest identity map. Never restore a captured `Snapshot`/`Program` list directly. The external Undo integration must handle a nullable result honestly rather than claim unavailable picks were restored.

Pass only authoritative eligibility snapshots after hidden-title/Not interested/removed-source filtering. A refresh-start marker or a temporary partial empty feed is not an authoritative empty catalog: retain the previous eligibility until the parent has the replacement. If restrictions change during an in-flight refresh, publish the newly filtered current catalog immediately so Undo cannot restore a newly hidden title. Removing a key permanently prunes it from existing Undo entries, even if a later refresh adds it back.

`sourceKey` is a stable catalog/session generation token, **not** a counter incremented for every fetch. Change it on an intentional source/session reset; that clears seen/history and invalidates all old Undo tokens. Keep the same machine across ordinary Home rebuilds/background updates. There is no persistence or new background work.

**Parent seam warning:** the policy inspected at task start capped `BalancedArtworkPolicy.discoveryCandidates` to its six-title artwork lookup pool. Do not feed that truncated pool into rotation. The parent owns exposing the full eligible ranking (and preserving existing service/series/artwork tiers); rotation never ranks, invents, or caps that ranking to the artwork window. Stable `titleKey` matches current title-deduplication semantics; use a better existing stable canonical title identity if the parent has one, not array positions or reused provider row IDs.

## Bounds and explicit limits

Defaults: 3 cards, 20 Undo batches, at most 4096 unique eligible identities, and 180 remembered identities no longer eligible. Duplicates keep first occurrence. Blank identities or an over-limit catalog throw **before state changes**, rather than silently truncating content and reporting false exhaustion. No network, disk writes, clock, scheduler, randomization or Android dependency exists in production code.

Seen identities that stay eligible are never evicted, even when Undo history is capped. Removed identities are remembered within the bounded tombstone window; a sufficiently old removed identity can be treated as new if it later reappears after eviction. This is the explicit bounded-memory boundary, not a reset of still-eligible titles. Old Undo entries can never resurrect removed identities, regardless of tombstone eviction.

## Offline verification

From this worktree:

```bash
python3 tests/discover-rotation/run_checks.py --label final-green
python3 tests/discover-rotation/negative_controls.py
```

The helper compiles **only** `DiscoverRotation.kt` and `DiscoverRotationTest.kt`, using the existing E: Kotlin 2.1.0 compiler, JDK 17 and JUnit 4.13.2 jars. No Gradle, network, Android classes, ADB, APK, signing or existing shared runners are used. All outputs and temporary paths are on E:.

Evidence: `E:/Development/tcl-tv-tools/snapshots/home-control-2026-11-11/discover-rotation/`. Each run retains exact source/test copies, SHA-256s, compiler/test logs and exit codes in `summary.json`.

Executed results:

| Evidence directory | Compiler | JUnit |
| --- | --- | --- |
| `fail-first` | exit 0, API-only baseline | 21 tests, 21 failures, exit 1 |
| `green` | exit 0, initial implementation | 21 tests passed |
| `final-green` | exit 0, no warnings | 23 tests passed, exit 0 |
| `negative-repeat-seen` | exit 0 | 23 tests, 18 failures, exit 1 |
| `negative-undo-resurrection` | exit 0 | 23 tests, 2 failures, exit 1 |
| `negative-background-churn` | exit 0 | 23 tests, 9 failures, exit 1 |
| `initial-catalog-red` | exit 0, original first-publication latch | 27 tests, 3 failures, exit 1 |
| `initial-catalog-green` | exit 0 | 27 tests passed, exit 0 |

Negative controls mutate isolated copies only: remove the unseen filter, retain removed IDs in old Undo entries, or reselect on every publication. The live source remains unchanged. The initial green's two Kotlin data-class constructor visibility warnings were eliminated before final-green.

Coverage includes complete catalogs through 4096 identities (not just the artwork pool), ranked service order, exhaustion, short/empty feeds, duplicates, stable IDs, same-list reload, metadata resolution, new arrivals, no background churn, hidden/removed identities, Undo-after-refresh/re-add, stale/foreign/out-of-order tokens, source resets, bounded history/tombstones, immutable snapshots, and atomic invalid-input rejection. This is pure host evidence, **not** MainActivity integration or native/device validation.
