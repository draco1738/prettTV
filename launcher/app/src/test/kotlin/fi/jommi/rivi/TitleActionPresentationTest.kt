package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class TitleActionPresentationTest {
    private val program=Program("title","Title","Service · S2 E1 · 9 min left · S2 E1","","fixture.app","Service",
        intentUri="app://title",position=60_000,duration=600_000)

    @Test fun progressWithDestinationOffersResumeInTheNamedApp() {
        assertEquals("Resume in Service",TitleActionPresentation.from(program,false).openLabel)
    }
    @Test fun missingDestinationOffersOpenDespiteSuppliedProgress() {
        assertEquals("Open Service",TitleActionPresentation.from(program.copy(intentUri=""),false).openLabel)
    }
    @Test fun catalogAndCompletedTitlesCannotPretendToResume() {
        for(p in listOf(program.copy(catalog=true),program.copy(position=program.duration),program.copy(position=0)))
            assertEquals("Open Service",TitleActionPresentation.from(p,false).openLabel)
    }
    @Test fun currentSavedStateControlsTheWatchlistAction() {
        assertEquals("Add to Watchlist",TitleActionPresentation.from(program,false).watchlistLabel)
        assertEquals("Remove from Watchlist",TitleActionPresentation.from(program,true).watchlistLabel)
    }
    @Test fun subtitleKeepsKnownEpisodeAndTimeWithoutRepeatingFields() {
        assertEquals("Service · S2 E1 · 9 min left",TitleActionPresentation.from(program,false).subtitle)
    }
    @Test fun missingMetadataDoesNotInventAnEpisodeOrDuration() {
        val value=TitleActionPresentation.from(program.copy(detail="Service",position=0,duration=0),false)
        assertEquals("Service",value.subtitle)
    }
}
