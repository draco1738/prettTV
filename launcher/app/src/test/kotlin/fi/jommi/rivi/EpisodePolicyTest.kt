package fi.jommi.rivi

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpisodePolicyTest {
    @get:Rule val temp=TemporaryFolder()
    private val now=1_800_000_000_000L
    private fun episode(id:String,time:Long)=EpisodeRecord(id,1,2,"Episode",time,"https://www.tvmaze.com/episodes/1")
    @Test fun baselineDoesNotFloodHistoricalEpisodes() {
        assertEquals(listOf("new"),EpisodePolicy.released(listOf(episode("old",now-100),episode("baseline",now),episode("new",now+1)),now,now+2,emptySet()).map { it.id })
    }
    @Test fun futureAndDismissedEpisodesAreExcluded() {
        val rows=listOf(episode("future",now+1),episode("dismissed",now-1))
        assertTrue(EpisodePolicy.released(rows,now-10,now,setOf("dismissed")).isEmpty())
    }
    @Test fun duplicateEpisodeIdsProduceOneAlertNewestFirst() {
        val rows=listOf(episode("a",now-2),episode("a",now-2),episode("b",now-1))
        assertEquals(listOf("b","a"),EpisodePolicy.released(rows,now-10,now,emptySet()).map { it.id })
    }
    @Test fun movedReleaseDateCannotAlertBeforeRelease() {
        assertTrue(EpisodePolicy.released(listOf(episode("moved",now+86_400_000)),now-100,now,emptySet()).isEmpty())
    }
    @Test fun matchingRequiresKnownYearAndUniqueExactTitle() {
        val candidates=listOf(Triple(1,"The Office",2001),Triple(2,"The Office",2005),Triple(3,"Office",2005))
        assertEquals(2,EpisodePolicy.exactMatch(" THE  OFFICE ",2005,candidates))
        assertNull(EpisodePolicy.exactMatch("The Office",0,candidates))
        assertNull(EpisodePolicy.exactMatch("The Offices",2005,candidates))
        assertNull(EpisodePolicy.exactMatch("The Office",2005,candidates+Triple(4,"The Office",2005)))
    }
    @Test fun dailyChecksBackOffAndRecoverFromClockRollback() {
        assertFalse(EpisodePolicy.due(now,now+86_399_999))
        assertTrue(EpisodePolicy.due(now,now+86_400_000)); assertTrue(EpisodePolicy.due(now,now-1)); assertTrue(EpisodePolicy.due(0,now))
    }
    @Test fun requestPacingUsesElapsedNanosecondsAndCapsBackwardsClockValues() {
        assertEquals(0L,EpisodePolicy.requestDelayMillis(null,1_000_000_000))
        assertEquals(350L,EpisodePolicy.requestDelayMillis(1_000_000_000,1_250_000_000))
        assertEquals(0L,EpisodePolicy.requestDelayMillis(1_000_000_000,2_000_000_000))
        // Even a backwards source value (or signed nanoTime wrap) can wait no more than 600ms.
        assertEquals(600L,EpisodePolicy.requestDelayMillis(3_600_000_000_000,0))
        assertTrue(EpisodePolicy.requestDelayMillis(Long.MAX_VALUE,Long.MIN_VALUE) in 0L..600L)
    }
    @Test fun releaseCardDoesNotInventEpisodeStreamingDestination() {
        val p=Program("show","Show","","","app","App",intentUri="app:show",mediaType="series",position=12,duration=100)
        val card=EpisodeAlert("episode","show",p,2,3,"New episode",now,"https://www.tvmaze.com/episodes/1").asProgram()
        assertEquals("app:show",card.intentUri); assertEquals(0L,card.position)
        assertTrue(card.detail.contains("Availability unconfirmed")); assertEquals("TVmaze",card.sourceName)
    }
    @Test fun unverifiedShowsMakeNoFuzzyNetworkRequests() {
        var calls=0
        val catalog=EpisodeCatalog(File(temp.root,"episodes.bin")) { _,_ -> calls++; error("Unexpected request") }
        val follow=FollowedShow("key",Program("provider:1","Ambiguous","","","app","App",mediaType="series"),now)
        val result=catalog.load(listOf(follow),now) { true }
        assertEquals(0,calls); assertTrue(result.alerts.isEmpty()); assertTrue(result.status.contains("without a verified"))
        assertEquals(result,catalog.load(listOf(follow),now+1) { true })
    }
    @Test fun corruptEpisodeStateRecoversWithoutHistoricalAlerts() {
        val file=File(temp.root,"episodes.bin"); file.writeText("broken")
        val catalog=EpisodeCatalog(file) { _,_ -> error("No network") }
        val p=Program("id","Show","","","app","App",mediaType="series")
        assertTrue(catalog.load(listOf(FollowedShow("key",p,now)),now,false) { true }.alerts.isEmpty())
        assertTrue(file.length()>6)
    }
    @Test fun unfollowDropsPersistedStateAndRefollowStartsFresh() {
        val file=File(temp.root,"episodes.bin")
        val p=Program("id","Show","","","app","App",mediaType="series")
        val first=EpisodeCatalog(file) { _,_ -> error("No network") }
        first.load(listOf(FollowedShow("key",p,now)),now,false) { true }
        assertEquals("No followed shows",first.load(emptyList(),now+1,false) { true }.status)
        val second=EpisodeCatalog(file) { _,_ -> error("No network") }
        val result=second.load(listOf(FollowedShow("key",p,now+2)),now+2,false) { true }
        assertTrue(result.alerts.isEmpty()); assertTrue(result.status.contains("pending"))
    }
}
