package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class SportsPolicyTest {
    private val now=1_800_000_000_000L
    private fun event(id:String,state:String="pre",start:Long=now+60_000,fetched:Long=now)=
        SportsEvent(id,"BAL @ LV","NFL",start,state,"Q2 4:00","FOX","","https://site.api.espn.com/",fetched,"BAL 7 – LV 3")
    @Test fun liveFirstThenKickoffOrder() {
        val items=listOf(event("late",start=now+300_000),event("early"),event("live","in",now-7_200_000))
        assertEquals(listOf("live","early","late"),SportsPolicy.upcoming(items,now).map { it.id })
    }
    @Test fun excludesFinalCancelledAndFarFuture() {
        assertTrue(SportsPolicy.upcoming(listOf(event("final","post"),event("cancelled","unknown"),event("future",start=now+8*86_400_000L)),now).isEmpty())
    }
    @Test fun expiredCacheCannotClaimLive() {
        assertTrue(SportsPolicy.upcoming(listOf(event("live","in",fetched=now-901_000)),now).isEmpty())
    }
    @Test fun staleCacheRemovesLiveBadgeAndScores() {
        val item=event("stale","in",fetched=now-301_000)
        assertFalse(item.heading(now).contains("LIVE"))
        assertTrue(item.heading(now).contains("updated"))
        assertEquals("FOX",item.detail(now))
    }
    @Test fun unknownBroadcastIsHonest() {
        assertEquals("Broadcast TBA",event("tba").copy(network="").detail(now))
    }
    @Test fun deduplicatesAndBoundsRows() {
        assertEquals(60,SportsPolicy.upcoming((1..80).map { event("$it") }+event("1"),now).size)
    }
    @Test fun oldUpcomingGameExpires() {
        assertTrue(SportsPolicy.upcoming(listOf(event("old",start=now-3_600_001)),now).isEmpty())
    }
}
