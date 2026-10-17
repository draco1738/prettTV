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
    @Test fun favoritesSortWithinLiveAndUpcomingGroups() {
        val rows=listOf(event("plain-live","in"),event("favorite-pre").copy(teamIds=listOf("espn:nfl:1")),
            event("favorite-live","in").copy(teamIds=listOf("espn:nfl:1")),event("plain-pre"))
        assertEquals(listOf("favorite-live","plain-live","favorite-pre","plain-pre"),SportsPolicy.upcoming(rows,now,setOf("espn:nfl:1")).map { it.id })
    }
    @Test fun aFavoriteBeyondTheFirstSixtyKickoffsSurvivesTheRowLimit() {
        val rows=(1..80).map { event("$it",start=now+it*60_000L).copy(teamIds=listOf("team:$it")) }
        val result=SportsPolicy.upcoming(rows,now,setOf("team:80"))
        assertEquals(60,result.size); assertEquals("80",result.first().id)
        assertTrue(result.any { it.id=="80" })
    }
    @Test fun kickoffRemindersRequireFreshFavoriteAndTenMinuteWindow() {
        val fav=event("yes",start=now+600_000).copy(teamIds=listOf("fav"))
        val rows=listOf(fav,fav.copy(id="late",startsAt=now+600_001),fav.copy(id="started",startsAt=now),
            fav.copy(id="stale",fetchedAt=now-300_001),fav.copy(id="other",teamIds=listOf("other")),fav.copy(id="live",state="in"))
        assertEquals(listOf("yes"),SportsPolicy.reminders(rows,now,setOf("fav"),emptySet()).map { it.id })
    }
    @Test fun acknowledgedKickoffStaysQuietButRescheduledEventCanRemind() {
        val first=event("game",start=now+60_000).copy(teamIds=listOf("fav"))
        val shown=setOf(SportsPolicy.reminderKey(first))
        assertTrue(SportsPolicy.reminders(listOf(first),now,setOf("fav"),shown).isEmpty())
        assertEquals(1,SportsPolicy.reminders(listOf(first.copy(startsAt=now+120_000)),now,setOf("fav"),shown).size)
    }
}
