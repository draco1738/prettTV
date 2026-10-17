package fi.jommi.rivi

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Conservative matching: both complete team names and the advertised local kickoff. */
object RbtvMatchPolicy {
    fun tab(league:String)=when(league) { "NFL","College football"->6; "NBA"->2; "Premier League"->1; else->null }
    fun normalized(s:String)=s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun matches(event:SportsEvent,teams:List<String>,day:String,time:String,now:Long,zone:ZoneId=ZoneId.systemDefault()):Boolean {
        val expected=event.teams.map(::normalized).toSet()
        if(expected.size!=2 || teams.size!=2 || teams.map(::normalized).toSet()!=expected)return false
        val date=Instant.ofEpochMilli(event.startsAt).atZone(zone)
        if(time.trim()!=date.format(DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT)))return false
        return if(day.isBlank())date.toLocalDate()==Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        else day.trim()==date.format(DateTimeFormatter.ofPattern("MM/dd",Locale.ROOT))
    }
}
