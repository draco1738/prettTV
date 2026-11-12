package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class DiscoverRotationTest {
    private fun keys(n:Int) = (1..n).map { "title-$it" }

    @Test fun partialFirstPublicationFillsBeforeInitialCatalogSettles() {
        val r=DiscoverRotation()
        assertEquals(keys(1),r.update(keys(1),initialComplete=false).keys)
        assertEquals(keys(2),r.update(keys(2),initialComplete=false).keys)
        assertEquals(keys(3),r.update(keys(8),initialComplete=true).keys)
        assertEquals(keys(3),r.update(keys(8).reversed(),initialComplete=false).keys)
    }

    @Test fun finalInitialServicePriorityReplacesProvisionalLowerTier() {
        val r=DiscoverRotation()
        r.update(listOf("pluto"),initialComplete=false)
        assertEquals(listOf("preferred-a","preferred-b","preferred-c"),r.update(
            listOf("preferred-a","preferred-b","preferred-c","pluto","other"),initialComplete=true).keys)
        // A provisional title was genuinely displayed; do not repeat it on explicit rotation.
        assertEquals(listOf("other"),r.next()!!.after.keys)
    }

    @Test fun explicitRotationDuringInitialLoadFreezesChoiceAndUndo() {
        val r=DiscoverRotation()
        r.update(keys(4),initialComplete=false)
        val change=r.next()!!
        assertEquals(listOf("title-4"),r.update(keys(9).reversed(),initialComplete=false).keys)
        assertEquals(listOf("title-4"),r.update(keys(9),initialComplete=true).keys)
        assertEquals(keys(3),r.undo(change.undoToken)!!.keys)
    }

    @Test fun newSourceCanCompleteItsOwnInitialCatalog() {
        val r=DiscoverRotation()
        r.update(keys(6),"old");r.next()
        r.update(listOf("new-a"),"new",initialComplete=false)
        assertEquals(listOf("new-a","new-b"),r.update(listOf("new-a","new-b"),"new",initialComplete=true).keys)
        assertFalse(r.current().canUndo)
    }

    @Test fun rankedCandidatesAreVisitedOnceIncludingTheTail() {
        val r=DiscoverRotation()
        assertEquals(keys(3),r.update(keys(8)).keys)
        assertEquals(keys(8).subList(3,6),r.next()!!.after.keys)
        assertEquals(keys(8).subList(6,8),r.next()!!.after.keys)
        val before=r.current()
        assertNull(r.next())
        assertEquals(before,r.current())
        assertFalse(r.current().hasMore)
    }

    @Test fun noMoreDoesNotConsumeUndo() {
        val r=DiscoverRotation()
        r.update(keys(4))
        val change=r.next()!!
        repeat(20) { assertNull(r.next()) }
        assertEquals(keys(3),r.undo(change.undoToken)!!.keys)
        assertNull(r.undo(change.undoToken))
    }

    @Test fun undoDoesNotPretendPreviouslyDisplayedTitlesAreUnseen() {
        val r=DiscoverRotation()
        r.update(keys(9))
        r.next()
        assertEquals(keys(3),r.undo()!!.keys)
        assertEquals(keys(9).subList(6,9),r.next()!!.after.keys)
    }

    @Test fun emptyAndShortCatalogsNeverManufactureCardsOrUndo() {
        for(n in 0..3) {
            val r=DiscoverRotation()
            assertEquals(keys(n),r.update(keys(n)).keys)
            assertNull(r.next())
            assertNull(r.undo())
            assertFalse(r.current().canUndo)
        }
    }

    @Test fun stableIdentitiesNotIndicesSurviveReorderAndMetadataReload() {
        val r=DiscoverRotation()
        val original=r.update(keys(9))
        repeat(10) { assertEquals(original,r.update(keys(9).reversed())) }
        assertEquals(listOf("title-9","title-8","title-7"),r.next()!!.after.keys)
        // The parent resolves these IDs against its NEW metadata; no Program is cached here.
        val metadata=keys(9).associateWith { "new artwork for $it" }
        assertEquals("new artwork for title-9",metadata[r.current().keys.first()])
    }

    @Test fun duplicateIdentitiesUseOnlyOneSlotAndPreserveFirstPriority() {
        val r=DiscoverRotation()
        assertEquals(listOf("b","a","c"),r.update(listOf("b","b","a","c","a","d","d")).keys)
        assertEquals(listOf("d"),r.next()!!.after.keys)
        assertNull(r.next())
    }

    @Test fun priorityOfUnseenTitlesWinsOverNewArrayPositions() {
        val r=DiscoverRotation()
        r.update(listOf("preferred-a","preferred-b","preferred-c","ordinary-a","pluto-a"))
        r.update(listOf("preferred-new","preferred-a","ordinary-a","preferred-b","preferred-c","pluto-a"))
        assertEquals(listOf("preferred-new","ordinary-a","pluto-a"),r.next()!!.after.keys)
    }

    @Test fun newArrivalsAfterExhaustionWaitForExplicitNext() {
        val r=DiscoverRotation()
        r.update(keys(4));r.next()
        val held=r.current().keys
        val refreshed=r.update(listOf("new-high-priority")+keys(4)+"new-low-priority")
        assertEquals(held,refreshed.keys)
        assertTrue(refreshed.hasMore)
        assertEquals(listOf("new-high-priority","new-low-priority"),r.next()!!.after.keys)
        assertNull(r.next())
    }

    @Test fun emptyInitialLoadMayPopulateButBackgroundRefreshCannotRotate() {
        val r=DiscoverRotation()
        r.update(emptyList())
        assertEquals(keys(3),r.update(keys(6)).keys)
        val before=r.current()
        repeat(25) { assertEquals(before,r.update(keys(6)+"new")) }
    }

    @Test fun removalPrunesCurrentAndUndoWithoutBackfillingOrResurrection() {
        val r=DiscoverRotation()
        r.update(keys(9)); val change=r.next()!!
        assertEquals(listOf("title-4","title-6"),r.update(keys(9)-setOf("title-2","title-5")).keys)
        // Even if a later refresh reintroduces a removed key, the old Undo cannot resurrect it.
        r.update(keys(9))
        assertEquals(listOf("title-1","title-3"),r.undo(change.undoToken)!!.keys)
        assertEquals(keys(9).subList(6,9),r.next()!!.after.keys)
    }

    @Test fun completelyUnavailableUndoIsANoOpAndDoesNotReplaceCurrent() {
        val r=DiscoverRotation()
        r.update(keys(6));val change=r.next()!!
        r.update(keys(6).drop(3))
        val before=r.current()
        assertFalse(before.canUndo)
        assertNull(r.undo(change.undoToken))
        assertEquals(before,r.current())
        r.update(keys(6))
        assertNull(r.undo(change.undoToken))
    }

    @Test fun allRemovedMeansEmptyNotFakeCardsAndNewArrivalsStayDeliberate() {
        val r=DiscoverRotation()
        r.update(keys(6));r.next()
        assertTrue(r.update(emptyList()).keys.isEmpty())
        assertTrue(r.update(listOf("fresh")).keys.isEmpty())
        assertEquals(listOf("fresh"),r.next()!!.after.keys)
    }

    @Test fun sourceGenerationResetClearsHistoryAndInvalidatesOldTokens() {
        val r=DiscoverRotation()
        r.update(keys(9),"source-A:1");val old=r.next()!!
        assertEquals(keys(3),r.update(keys(9),"source-A:2").keys)
        assertFalse(r.current().canUndo)
        val fresh=r.next()!!
        assertNull(r.undo(old.undoToken))
        assertEquals(keys(3),r.undo(fresh.undoToken)!!.keys)
        assertEquals(listOf("different"),r.update(listOf("different"),"source-B:1").keys)
    }

    @Test fun outOfOrderAndForeignUndoTokensAreHarmless() {
        val r=DiscoverRotation();r.update(keys(12))
        val first=r.next()!!;val second=r.next()!!
        val other=DiscoverRotation();other.update(keys(12));val foreign=other.next()!!
        val before=r.current()
        assertNull(r.undo(first.undoToken));assertNull(r.undo(foreign.undoToken))
        assertEquals(before,r.current())
        assertEquals(keys(12).subList(3,6),r.undo(second.undoToken)!!.keys)
        assertEquals(keys(3),r.undo(first.undoToken)!!.keys)
    }

    @Test fun pastHistoryIsBoundedWithoutLosingSeenEligibility() {
        val r=DiscoverRotation(historyLimit=2)
        r.update(keys(15));val evicted=r.next()!!;r.next();r.next();r.next()
        assertEquals(2,r.current().undoDepth)
        assertNull(r.undo(evicted.undoToken))
        assertEquals(keys(15).subList(9,12),r.undo()!!.keys)
        assertEquals(keys(15).subList(6,9),r.undo()!!.keys)
        assertNull(r.undo());assertNull(r.next())
    }

    @Test fun snapshotsAndInputsCannotMutateState() {
        val r=DiscoverRotation()
        val input=keys(6).toMutableList()
        val before=r.update(input)
        input.clear()
        try { (before.keys as MutableList<String>).clear();fail("mutable snapshot") }
        catch(_:UnsupportedOperationException) { }
        val change=r.next()!!
        assertEquals(keys(3),before.keys)
        assertEquals(keys(3),change.before.keys)
        r.undo()
        assertEquals(keys(6).drop(3),change.after.keys)
    }

    @Test fun removedIdentityMemoryIsBoundedButEligibleSeenKeysAreNeverEvicted() {
        val r=DiscoverRotation(removedMemoryLimit=2)
        r.update(listOf("persistent","old-a","old-b"))
        repeat(100) { n ->
            r.update(listOf("persistent","new-$n"))
            assertEquals(listOf("new-$n"),r.next()!!.after.keys)
            assertTrue(r.current().rememberedCount<=4)
        }
        r.update(listOf("persistent","new-99","new-98"))
        assertNull(r.next())
    }

    @Test fun repeatedShortRefreshDoesNotChurnEvenWhenSlotsAreEmpty() {
        val r=DiscoverRotation();r.update(keys(2))
        repeat(30) { assertEquals(keys(2),r.update(keys(9)).keys) }
        assertEquals(keys(9).subList(2,5),r.next()!!.after.keys)
    }

    @Test fun invalidInputIsRejectedAtomicallyNotSilentlyTruncated() {
        val r=DiscoverRotation(candidateLimit=4)
        val before=r.update(keys(4))
        for(bad in listOf(keys(5),listOf("valid"," "))) {
            try { r.update(bad,"changed");fail("invalid input accepted") }
            catch(_:IllegalArgumentException) { }
            assertEquals(before,r.current())
        }
        assertEquals(listOf("title-4"),r.next()!!.after.keys)
    }

    @Test fun constructorRejectsInvalidBounds() {
        for(make in listOf<()->DiscoverRotation>(
            { DiscoverRotation(batchSize=0) },{ DiscoverRotation(historyLimit=-1) },
            { DiscoverRotation(candidateLimit=0) },{ DiscoverRotation(removedMemoryLimit=-1) })) {
            try { make();fail("invalid bound accepted") } catch(_:IllegalArgumentException) { }
        }
    }

    @Test fun fullCatalogBeyondArtworkPoolIsReachableAcrossUnchangedReloads() {
        for(size in listOf(1,2,3,4,6,7,60,180,4096)) {
            val r=DiscoverRotation()
            val ranked=keys(size)
            val visited=mutableListOf<String>()
            visited.addAll(r.update(ranked).keys)
            while(r.current().hasMore) {
                assertTrue("rotation must terminate without duplicate picks",visited.size<size)
                val before=r.current()
                assertEquals(before,r.update(ranked.toList()))
                val change=r.next()!!
                assertTrue(change.after.keys.size in 1..3)
                assertTrue(change.after.undoDepth<=20)
                visited.addAll(change.after.keys)
            }
            assertEquals(ranked,visited)
            assertEquals(size,visited.toSet().size)
            assertNull(r.next())
        }
    }

    @Test fun recentRemovedSeenTitlesStaySeenButOldTombstonesHaveAnExplicitBound() {
        val r=DiscoverRotation(batchSize=1,removedMemoryLimit=1)
        r.update(listOf("a"))
        r.update(listOf("b"));r.next()
        r.update(listOf("c"));r.next()
        r.update(listOf("b","c"))
        assertNull(r.next())
        // a left eligibility and the bounded tombstone window; only explicit Next may show it.
        assertEquals(listOf("c"),r.update(listOf("a","b","c")).keys)
        assertEquals(listOf("a"),r.next()!!.after.keys)
    }

    @Test fun zeroUndoBudgetStillAllowsRotationAndNoOpDoesNotCreateHistory() {
        val r=DiscoverRotation(historyLimit=0)
        r.update(keys(6));assertNotNull(r.next())
        assertFalse(r.current().canUndo);assertEquals(0,r.current().undoDepth)
        assertNull(r.undo());assertNull(r.next())
    }
}
