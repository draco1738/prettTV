package fi.jommi.rivi

import java.util.Collections

/**
 * Session-only, deterministic selection; no Android, clock, persistence, randomization or I/O.
 * Call on one owning thread. Supply the FULL eligible list in final service-priority order,
 * using stable title identities (not positions or provider row IDs that can be reused).
 * Resolve returned keys against the latest metadata in the caller; no Program is cached here.
 *
 * update() is an authoritative eligibility publication, not a refresh-start/partial-empty signal.
 * It retains surviving picks in place and permanently prunes removed keys from old Undo entries.
 * Initial publications may fill/re-rank until initialComplete=true. Explicit next() freezes early.
 * After that, only a different sourceKey, next(), or undo() selects a different batch.
 * Use a stable sourceKey across ordinary reloads; change it for an intentional source/session reset.
 *
 * Bounds: at most candidateLimit eligible keys, historyLimit batches of at most batchSize keys,
 * and removedMemoryLimit remembered keys that are no longer eligible. Continuously eligible seen
 * keys are NEVER evicted. Very old removed keys may be treated as new if they later return after
 * leaving that bounded tombstone window. Removed keys can never return through an old Undo entry.
 */
internal class DiscoverRotation(
    private val batchSize:Int=3,
    private val historyLimit:Int=20,
    private val candidateLimit:Int=4096,
    private val removedMemoryLimit:Int=180,
) {
    init {
        require(batchSize>0 && candidateLimit>0 && batchSize<=candidateLimit)
        require(historyLimit>=0 && removedMemoryLimit>=0)
    }

    /** Detached immutable lists; status is recomputed, not a cached exhaustion latch. */
    data class Snapshot(
        val keys:List<String>, val hasMore:Boolean, val undoDepth:Int, val rememberedCount:Int,
    ) {
        val canUndo:Boolean get()=undoDepth>0
    }
    /** Opaque identity, valid only for its owning machine's latest retained change. */
    class UndoToken internal constructor()
    data class Change(val before:Snapshot,val after:Snapshot,val undoToken:UndoToken)
    private data class Past(val token:UndoToken,val keys:List<String>)

    private var source:String?=null
    private var populated=false
    private var eligible=linkedSetOf<String>()
    private var picks:List<String> = emptyList()
    private val seen=linkedSetOf<String>()
    private val past=mutableListOf<Past>()

    fun current():Snapshot = Snapshot(
        immutable(picks),eligible.any { it !in seen },past.count { it.keys.isNotEmpty() && it.keys!=picks },seen.size,
    )

    /** Duplicates keep their first ranked occurrence. Invalid input fails before ANY state changes. */
    fun update(rankedKeys:List<String>,sourceKey:String="default",initialComplete:Boolean=true):Snapshot {
        val incoming=linkedSetOf<String>()
        for(key in rankedKeys) {
            require(key.isNotBlank()) { "Discover identities must be nonblank" }
            incoming.add(key)
            require(incoming.size<=candidateLimit) { "Full Discover catalog exceeds candidateLimit; do not truncate it" }
        }
        if(source!=sourceKey) {
            source=sourceKey
            populated=false
            picks=emptyList()
            seen.clear()
            past.clear()
        }
        eligible=incoming
        picks=picks.filter { it in eligible }
        // Prune the stored IDs, not just the returned view, so a later re-add cannot revive old Undo.
        for(index in past.indices)past[index]=past[index].copy(keys=past[index].keys.filter { it in eligible })
        past.removeAll { it.keys.isEmpty() }
        trimRemovedMemory()
        if(!populated && eligible.isNotEmpty()) {
            picks=eligible.take(batchSize)
            seen.addAll(picks)
            populated=initialComplete
        }
        return current()
    }

    /** null means honest no-more-picks; no history, seen state or current picks change. No cycling. */
    fun next():Change? {
        val replacement=eligible.asSequence().filter { it !in seen }.take(batchSize).toList()
        if(replacement.isEmpty())return null
        val before=current()
        val token=UndoToken()
        if(historyLimit>0 && picks.isNotEmpty()) {
            past.add(Past(token,picks.toList()))
            while(past.size>historyLimit)past.removeAt(0)
        }
        picks=replacement
        seen.addAll(picks)
        populated=true
        return Change(before,current(),token)
    }

    /**
     * The optional token makes external top-bar Undo callbacks safe after resets, eviction or other
     * rotations. Never restore an arbitrary Snapshot: eligibility may have changed since capture.
     * Undo never un-sees the batch it replaces. A no-op does not pop any entry.
     */
    fun undo(token:UndoToken?=null):Snapshot? {
        val previous=past.lastOrNull() ?: return null
        if(token!=null && token!==previous.token)return null
        val restored=previous.keys.filter { it in eligible }
        if(restored.isEmpty() || restored==picks)return null
        past.removeAt(past.lastIndex)
        picks=restored
        seen.addAll(picks)
        return current()
    }

    private fun trimRemovedMemory() {
        var excess=seen.count { it !in eligible }-removedMemoryLimit
        if(excess<=0)return
        val iterator=seen.iterator()
        while(iterator.hasNext() && excess>0) {
            if(iterator.next() !in eligible) {
                iterator.remove()
                excess--
            }
        }
    }

    private fun immutable(keys:List<String>):List<String> = Collections.unmodifiableList(ArrayList(keys))
}
