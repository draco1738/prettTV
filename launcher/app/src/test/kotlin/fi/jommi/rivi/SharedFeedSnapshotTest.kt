package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class SharedFeedSnapshotTest {
    private fun card(id:String,pkg:String="installed")=Program(id,id,"","",pkg,pkg)
    @Test fun unavailableProviderPreservesLastCompleteHistoryAndRecommendations() {
        val old=SharedFeedSnapshot(listOf(card("suggestion")),listOf(card("watching")),mapOf(1L to "Continue watching"))
        assertEquals(old,old.updated(null,null,null))
    }
    @Test fun deliberateEmptyFeedClearsOnlyThatFeed() {
        val old=SharedFeedSnapshot(listOf(card("suggestion")),listOf(card("watching")))
        val next=old.updated(emptyList(),null,emptyMap())
        assertTrue(next.published.isEmpty())
        assertEquals(old.watching,next.watching)
    }
    @Test fun aFailedReadCannotResurrectUninstalledApps() {
        val old=SharedFeedSnapshot(listOf(card("a"),card("b","removed")),listOf(card("c","removed")))
        val next=old.updated(null,null,null).eligible(setOf("installed"))
        assertEquals(listOf("a"),next.published.map { it.id })
        assertTrue(next.watching.isEmpty())
    }
    @Test fun recoveringProviderReplacesRetainedResults() {
        val old=SharedFeedSnapshot(listOf(card("old")),listOf(card("old-watch")))
        val next=old.updated(listOf(card("fresh")),listOf(card("fresh-watch")),mapOf(2L to "Popular"))
        assertEquals("fresh",next.published.single().id)
        assertEquals("fresh-watch",next.watching.single().id)
        assertEquals(mapOf(2L to "Popular"),next.channels)
    }
}
