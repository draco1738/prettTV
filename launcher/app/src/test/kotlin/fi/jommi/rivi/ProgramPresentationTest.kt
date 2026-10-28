package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class ProgramPresentationTest {
    private fun program(detail:String="Service · S2 E1 · 36 min left",position:Long=60_000,duration:Long=37*60_000L,uri:String="app://title")=
        Program("title","Title",detail,"","fixture.app","Service",intentUri=uri,position=position,duration=duration)
    @Test fun suppliedEpisodeIsKeptWithoutDuplicatingSourceOrTime() {
        val value=ProgramPresentation.from(program())
        assertEquals("S2 E1",value.metadata); assertEquals("36 min left",value.remaining)
        assertEquals("S2 E1 · 36 min left",value.selectedDetail); assertTrue(value.canResume)
    }
    @Test fun unknownDurationDoesNotInventProgressOrRemainingTime() {
        val value=ProgramPresentation.from(program("Service · Episode unknown",position=0,duration=0))
        assertEquals("Episode unknown",value.metadata); assertEquals("",value.remaining); assertFalse(value.canResume)
    }
    @Test fun partialMinuteHasHonestLabel() {
        val value=ProgramPresentation.from(program("Service · 0 min left",position=30_000,duration=60_000))
        assertEquals("",value.metadata); assertEquals("Less than a minute left",value.remaining)
    }
    @Test fun completedAndInvalidPositionsDoNotOfferResume() {
        for(position in listOf(-1L,60_000L,90_000L)) {
            val value=ProgramPresentation.from(program("Service",position=position,duration=60_000))
            assertEquals("",value.remaining); assertFalse(value.canResume)
        }
    }
    @Test fun missingDestinationOffersOpenEvenWithProgress() {
        assertFalse(ProgramPresentation.from(program(uri="")).canResume)
    }
    @Test fun arbitraryMetadataIsNeverParsedAsEpisodeOrTitle() {
        val value=ProgramPresentation.from(program("Drama · The Service story · Chapter finale"))
        assertEquals("Drama · The Service story · Chapter finale",value.metadata)
    }
    @Test fun catalogContentDoesNotPretendToResume() {
        assertFalse(ProgramPresentation.from(program().copy(catalog=true)).canResume)
    }
}
