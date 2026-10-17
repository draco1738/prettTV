package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class PlaybackPolicyTest {
    @Test fun timestampRefreshDoesNotReplaceBaseline() { assertEquals(100L,PlaybackPolicy.baseline(900,1000,100)) }
    @Test fun futureProviderClockIsCapped() { assertEquals(1000L,PlaybackPolicy.baseline(2000,1000,null)) }
    @Test fun missingTimestampIsNotInvented() { assertEquals(0L,PlaybackPolicy.baseline(-1,1000,null)) }
    @Test fun exactSeriesWithEpisodeMetadataMatches() { assertTrue(PlaybackPolicy.matches("The Night Agent","The Night Agent: S1 E4")) }
    @Test fun unrelatedAnimeDoesNotMatch() { assertFalse(PlaybackPolicy.matches("Re:ZERO","Solo Leveling")) }
    @Test fun missingMetadataDoesNotMatch() { assertFalse(PlaybackPolicy.matches("The Night Agent","")) }
    @Test fun partialTitleDoesNotMatch() { assertFalse(PlaybackPolicy.matches("The Night Agent","The Night")) }
}
