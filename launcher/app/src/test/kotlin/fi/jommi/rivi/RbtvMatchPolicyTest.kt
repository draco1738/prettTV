package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class RbtvMatchPolicyTest {
    private val zone=ZoneId.of("America/New_York")
    private val start=Instant.parse("2026-10-18T17:00:00Z").toEpochMilli()
    private val event=SportsEvent("1","CLE @ BAL","NFL",start,"pre","","FOX","","",start,teams=listOf("Cleveland Browns","Baltimore Ravens"))
    private fun match(teams:List<String> = event.teams,day:String="10/18",time:String="13:00",now:Long=start)=RbtvMatchPolicy.matches(event,teams,day,time,now,zone)
    @Test fun reversedHomeAwayStillMatches() { assertTrue(match(event.teams.reversed())) }
    @Test fun wrongOpponentCannotOpen() { assertFalse(match(listOf("Cleveland Browns","Las Vegas Raiders"))) }
    @Test fun cityOnlyDoesNotMatch() { assertFalse(match(listOf("Cleveland","Baltimore"))) }
    @Test fun wrongKickoffCannotOpen() { assertFalse(match(time="16:00")) }
    @Test fun rematchDateCannotOpen() { assertFalse(match(day="10/19")) }
    @Test fun missingDayOnlyAllowedToday() { assertTrue(match(day="")); assertFalse(match(day="",now=start-86_400_000)) }
    @Test fun emptyTeamsNeverMatch() { assertFalse(match(emptyList())) }
    @Test fun onlyVerifiedTabsAreEnabled() { assertEquals(6,RbtvMatchPolicy.tab("NFL")); assertNull(RbtvMatchPolicy.tab("NHL")) }
}
