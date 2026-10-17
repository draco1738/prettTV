package fi.jommi.rivi

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** ESPN's public scoreboard feed: bounded requests, short-lived cache, no streaming URLs. */
class SportsCatalog(private val dir:File,private val get:(String,Int)->String) {
    fun load(active:()->Boolean):List<SportsEvent> {
        val result=ArrayList<SportsEvent>()
        for((path,label) in listOf("football/nfl" to "NFL","football/college-football" to "College football",
            "basketball/nba" to "NBA","hockey/nhl" to "NHL","baseball/mlb" to "MLB","soccer/eng.1" to "Premier League")) {
            if(!active())break
            val url="https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"
            val cache=File(dir,"sports-${path.replace('/','-')}.json")
            val now=System.currentTimeMillis()
            var data:String?=if(cache.exists() && now-cache.lastModified() in 0..900_000)try { cache.readText() } catch(_:Exception) { null } else null
            var fetched=cache.lastModified()
            if(data==null || now-fetched>120_000) {
                try {
                    val fresh=get(url,1_500_000)
                    // Validate before replacing a usable cached response.
                    require(JSONObject(fresh).has("events"))
                    data=fresh; fetched=System.currentTimeMillis()
                    cache.writeText(fresh); cache.setLastModified(fetched)
                } catch(_:Exception) { /* A brief cached schedule is explicitly marked stale in the UI. */ }
            }
            try {
                val events=JSONObject(data ?: continue).optJSONArray("events") ?: continue
                for(i in 0 until minOf(events.length(),100)) {
                    val e=events.optJSONObject(i) ?: continue
                    val id=e.optString("id").takeIf { it.matches(Regex("[0-9]{1,15}")) } ?: continue
                    val c=e.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                    val type=(e.optJSONObject("status") ?: c.optJSONObject("status"))?.optJSONObject("type") ?: continue
                    val date=try { Instant.parse(e.optString("date").let { if(it.length==17)it.dropLast(1)+":00Z" else it }).toEpochMilli() } catch(_:Exception) { continue }
                    var network=""; var logo=""
                    val geo=c.optJSONArray("geoBroadcasts")
                    if(geo!=null)for(j in 0 until geo.length()) {
                        val b=geo.optJSONObject(j) ?: continue
                        if(b.optString("region")!="us")continue
                        val media=b.optJSONObject("media") ?: continue
                        network=media.optString("shortName")
                        logo=media.optString("darkLogo").ifBlank { media.optString("logo") }
                        if(network.isNotBlank())break
                    }
                    if(network.isBlank())network=c.optJSONArray("broadcasts")?.optJSONObject(0)?.optJSONArray("names")?.optString(0).orEmpty()
                    if(!logo.startsWith("https://a.espncdn.com/"))logo=""
                    val competitors=c.optJSONArray("competitors")
                    val score=if(competitors==null)"" else (0 until competitors.length()).mapNotNull { j ->
                        val team=competitors.optJSONObject(j) ?: return@mapNotNull null
                        team.optJSONObject("team")?.optString("abbreviation")?.let { "$it ${team.optString("score")}" }
                    }.joinToString(" – ")
                    result.add(SportsEvent("espn:$path:$id",e.optString("shortName").ifBlank { e.optString("name") },label,date,
                        type.optString("state"),type.optString("shortDetail"),network,logo,url,fetched,score))
                }
            } catch(_:Exception) { }
        }
        return SportsPolicy.upcoming(result,System.currentTimeMillis())
    }
}
