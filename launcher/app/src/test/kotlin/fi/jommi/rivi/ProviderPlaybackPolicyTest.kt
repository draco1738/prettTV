package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class ProviderPlaybackPolicyTest {
    private val original=Program("p:1","Show","Service · S1 E1 · 9 min left","art","service","Service",
        position=60_000,duration=600_000,watchingSource=true,engagedAt=100,mediaType="series")
    @Test fun initialImportDoesNotInventObservationTime() { assertFalse(ProviderPlaybackPolicy.changed(null,original)) }
    @Test fun validProgressAdvanceCounts() { assertTrue(ProviderPlaybackPolicy.changed(original,original.copy(position=61_000))) }
    @Test fun validSeekBackCounts() { assertTrue(ProviderPlaybackPolicy.changed(original,original.copy(position=30_000))) }
    @Test fun timestampArtworkDescriptionAndDurationAloneDoNotCount() {
        assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(engagedAt=999,art="new",summary="Changed",duration=700_000)))
        assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(detail="Service · S1 E1 · Different description")))
    }
    @Test fun explicitEpisodeChangeWithProgressCounts() { assertTrue(ProviderPlaybackPolicy.changed(original,original.copy(detail="Service · S1 E2 · 9 min left"))) }
    @Test fun suppliedEpisodeTokenMustHaveKnownFormatterAndBothSides() {
        assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(detail="A title mentioning S1 E2")))
        assertFalse(ProviderPlaybackPolicy.changed(original.copy(detail="Service"),original.copy(detail="Service · S1 E2")))
    }
    @Test fun zeroPositionNextEpisodeDoesNotCount() { assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(detail="Service · S1 E2",position=0))) }
    @Test fun invalidShortOrOutOfRangeProgressDoesNotCount() {
        for(p in listOf(original.copy(position=1),original.copy(position=700_000),original.copy(duration=100_000),original.copy(catalog=true)))
            assertFalse(ProviderPlaybackPolicy.changed(original,p))
    }
    @Test fun unchangedRawSampleNeverRepeatsAnObservation() { assertFalse(ProviderPlaybackPolicy.changed(original,original.copy())) }
    @Test fun subsecondSamplesAccumulateAgainstTheRetainedAnchor() {
        val recent=original.copy(position=60_750)
        assertFalse(ProviderPlaybackPolicy.changed(original,recent))
        assertTrue(ProviderPlaybackPolicy.changed(recent,original.copy(position=61_000),original))
    }
    @Test fun unrelatedProviderOrTitleCannotShareAnAnchor() {
        assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(packageName="other",position=100_000)))
        assertFalse(ProviderPlaybackPolicy.changed(original,original.copy(title="Different",position=100_000)))
    }
    @Test fun observationClockIsMonotonicAcrossClockRollbackAndSameMillisecond() {
        assertEquals(501L,ProviderPlaybackPolicy.nextObservedAt(100,500))
        assertEquals(501L,ProviderPlaybackPolicy.nextObservedAt(500,500))
        assertEquals(900L,ProviderPlaybackPolicy.nextObservedAt(900,500))
        assertEquals(Long.MAX_VALUE,ProviderPlaybackPolicy.nextObservedAt(100,Long.MAX_VALUE))
    }
    @Test fun laterNewTitleCanKeepACredibleFreshProviderTimestamp() {
        assertEquals(150L,ProviderPlaybackPolicy.lateBaseline(original.copy(engagedAt=150),100,200,false))
    }
    @Test fun initialImportsBackfillsUnknownFutureAndZeroProgressDoNotReceiveLatePromotion() {
        assertNull(ProviderPlaybackPolicy.lateBaseline(original.copy(engagedAt=150),100,200,true))
        for(p in listOf(original.copy(engagedAt=0),original.copy(engagedAt=50),original.copy(engagedAt=250),original.copy(engagedAt=150,position=0)))
            assertNull(ProviderPlaybackPolicy.lateBaseline(p,100,200,false))
    }
}
