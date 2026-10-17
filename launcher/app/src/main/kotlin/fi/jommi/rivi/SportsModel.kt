package fi.jommi.rivi

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A schedule entry is not a stream. Broadcast names do not identify an RBTV channel. */
data class SportsEvent(val id:String,val title:String,val league:String,val startsAt:Long,
    val state:String,val status:String,val network:String,val logo:String,val source:String,
    val fetchedAt:Long,val score:String="") {
    fun fresh(now:Long)=now-fetchedAt in 0..300_000
    fun heading(now:Long):String = "$league · "+when {
        !fresh(now) -> "Schedule · updated ${((now-fetchedAt).coerceAtLeast(0)/60_000)}m ago"
        state=="in" -> "LIVE · $status"
        else -> DateTimeFormatter.ofPattern("EEE h:mm a",Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(startsAt))
    }
    fun detail(now:Long)=listOfNotNull(network.ifBlank { "Broadcast TBA" },score.takeIf { state=="in" && fresh(now) && it.isNotBlank() }).joinToString(" · ")
}

object SportsPolicy {
    fun upcoming(events:List<SportsEvent>,now:Long):List<SportsEvent> = events
        .filter { it.title.isNotBlank() && now-it.fetchedAt in 0..900_000 &&
            (it.state=="in" || (it.state=="pre" && it.startsAt in (now-3_600_000)..(now+7*86_400_000L))) }
        .distinctBy { it.id }
        .sortedWith(compareBy<SportsEvent> { it.state!="in" }.thenBy { it.startsAt })
        .take(60)
}
