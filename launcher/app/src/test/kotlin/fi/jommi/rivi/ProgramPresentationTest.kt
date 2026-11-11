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
    @Test fun compactBindingCombinesSourceWithSelectedDetailNotRawProviderDetail() {
        val supplied=program()
        val value=ProgramPresentation.from(supplied)
        // CONTINUATION binds source as detail and compactSelectedDetail as secondaryDetail.
        val line=listOf(value.source,value.compactSelectedDetail).filter(String::isNotBlank).joinToString(" · ")
        assertEquals("Service · S2 E1 · 36 min left",line)
        assertEquals("S2 E1",value.compactMetadata)
        assertEquals("Service · S2 E1 · 36 min left",supplied.detail)
    }
    @Test fun compactEpisodeDuplicatesAreRemovedWithoutChangingLegacyDetail() {
        val value=ProgramPresentation.from(program("Service · S2 E1 · 36 min left · S2 E1"))
        assertEquals("S2 E1",value.compactMetadata)
        assertEquals("S2 E1 · 36 min left",value.compactSelectedDetail)
        assertEquals("S2 E1 · S2 E1",value.metadata)
        assertEquals("S2 E1 · S2 E1 · 36 min left",value.selectedDetail)
    }
    @Test fun compactDeduplicationRetainsFirstOccurrenceAndUniqueFacts() {
        val value=ProgramPresentation.from(program("Service · Drama · S2 E1 · Drama · Chapter finale · S2 E1 · Service"))
        assertEquals("Drama · S2 E1 · Chapter finale",value.compactMetadata)
        assertEquals("Drama · S2 E1 · Chapter finale · 36 min left",value.compactSelectedDetail)
    }
    @Test fun compactKeepsUniqueSynopsisEvenWhenItMentionsKnownFields() {
        val synopsis="The Service story revisits S2 E1 with 36 min left before departure."
        val supplied=program("Service · S2 E1 · $synopsis · S2 E1").copy(summary=synopsis)
        val value=ProgramPresentation.from(supplied)
        assertEquals("S2 E1 · $synopsis",value.compactMetadata)
        assertEquals("S2 E1 · $synopsis · 36 min left",value.compactSelectedDetail)
        assertEquals("S2 E1 · $synopsis · S2 E1",value.metadata)
        assertEquals(synopsis,supplied.summary)
        assertEquals("Service · S2 E1 · $synopsis · S2 E1",supplied.detail)
    }
    @Test fun compactDoesNotGuessEpisodesOrRewriteSimilarLookingFields() {
        val facts="S2 E1 · S2 E10 · S02 E01 · s2 e1 · Season 2 Episode 1 · 37 min left · Drama · drama"
        val value=ProgramPresentation.from(program("Service · $facts · S2 E1 · 36 min left"))
        assertEquals(facts,value.compactMetadata)
        assertEquals("$facts · 36 min left",value.compactSelectedDetail)
    }
    @Test fun compactDoesNotExtractFactsFromTitleSummaryOrGenres() {
        val supplied=program("Service",position=0,duration=0).copy(
            title="S2 E1",summary="Previously on S3 E4",genres="Drama")
        val value=ProgramPresentation.from(supplied)
        assertEquals("",value.compactMetadata)
        assertEquals("",value.compactSelectedDetail)
        assertFalse(value.canResume)
    }
    @Test fun compactDoesNotSplitUnrecognizedDelimitersOrEpisodeProse() {
        for(fact in listOf("S2 E1 | S2 E1","S2 E1•S2 E1","S2 E1·S2 E1","S2 E1: S2 E1 revisited")) {
            val value=ProgramPresentation.from(program("Service · $fact"))
            assertEquals(fact,value.compactMetadata)
            assertEquals("$fact · 36 min left",value.compactSelectedDetail)
        }
    }
    @Test fun compactPartialMinuteUsesOneHonestRemainingLabel() {
        val value=ProgramPresentation.from(program(
            "Service · S2 E1 · 0 min left · Less than a minute left · S2 E1",
            position=30_000,duration=60_000))
        assertEquals("S2 E1",value.compactMetadata)
        assertEquals("S2 E1 · Less than a minute left",value.compactSelectedDetail)
        assertEquals("Less than a minute left",value.remaining)
    }
    @Test fun compactKeepsSuppliedTimeWhenProgressCannotVerifyIt() {
        for((position,duration) in listOf(0L to 0L,0L to 60_000L,-1L to 60_000L,60_000L to 60_000L,90_000L to 60_000L)) {
            val value=ProgramPresentation.from(program("Service · S2 E1 · 36 min left · 36 min left",position,duration))
            assertEquals("S2 E1 · 36 min left",value.compactMetadata)
            assertEquals("S2 E1 · 36 min left",value.compactSelectedDetail)
            assertEquals("",value.remaining)
            assertFalse(value.canResume)
        }
    }
    @Test fun compactIgnoresEmptyFieldsAndPreservesInternalWhitespace() {
        val value=ProgramPresentation.from(program(" Service ·  S2 E1  ·  · S2 E1 · Chapter  finale · Chapter finale "))
        assertEquals("S2 E1 · Chapter  finale · Chapter finale",value.compactMetadata)
        assertEquals("S2 E1 · Chapter  finale · Chapter finale · 36 min left",value.compactSelectedDetail)
        for(detail in listOf(""," · ","Service ·  · Service")) {
            val empty=ProgramPresentation.from(program(detail,position=0,duration=0))
            assertEquals("",empty.compactMetadata)
            assertEquals("",empty.compactSelectedDetail)
        }
    }
}
