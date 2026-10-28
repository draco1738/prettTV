package fi.jommi.rivi

import org.json.JSONObject
import java.io.File
import java.time.Instant

/** ESPN's public scoreboard feed: bounded requests, short-lived cache, no streaming URLs. */
class SportsCatalog(private val dir:File,private val get:(String,Int)->String,private val onFailure:()->Unit) {
    constructor(dir:File,get:(String,Int)->String):this(dir,get,{})
    fun load(favoriteTeams:Set<String> = emptySet(),active:()->Boolean):List<SportsEvent> {
        val result=ArrayList<SportsEvent>()
        for((path,label) in LEAGUES) {
            if(!active())break
            val url="https://site.api.espn.com/apis/site/v2/sports/$path/scoreboard"
            val cache=File(dir,"sports-${path.replace('/','-')}.json")
            val now=System.currentTimeMillis()
            var data:String?=if(cache.exists() && cache.length()<=1_500_000 && now-cache.lastModified() in 0..900_000)try { cache.readText() } catch(_:Exception) { null } else null
            var fetched=cache.lastModified()
            if(data==null || now-fetched>120_000) {
                try {
                    val fresh=get(url,1_500_000)
                    // Validate before replacing a usable cached response.
                    require(JSONObject(fresh).optJSONArray("events")!=null)
                    data=fresh; fetched=System.currentTimeMillis()
                    if(!active())break
                    AtomicPrivateFile.write(cache,fresh.toByteArray(Charsets.UTF_8),1_500_000); cache.setLastModified(fetched)
                } catch(_:Exception) { if(active())onFailure() /* A brief cached schedule is explicitly marked stale in the UI. */ }
            }
            try {
                val events=JSONObject(data ?: continue).optJSONArray("events")
                if(events==null) { if(active())onFailure(); continue }
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
                    val teams=if(competitors==null)emptyList() else (0 until competitors.length()).mapNotNull { j ->
                        competitors.optJSONObject(j)?.optJSONObject("team")?.optString("displayName")?.takeIf { it.isNotBlank() }
                    }
                    val teamIds=if(competitors==null)emptyList() else (0 until competitors.length()).mapNotNull { j ->
                        competitors.optJSONObject(j)?.optJSONObject("team")?.optString("id")?.takeIf { it.matches(Regex("[0-9]{1,15}")) }?.let { "espn:$path:$it" }
                    }
                    val score=if(competitors==null)"" else (0 until competitors.length()).mapNotNull { j ->
                        val team=competitors.optJSONObject(j) ?: return@mapNotNull null
                        team.optJSONObject("team")?.optString("abbreviation")?.let { "$it ${team.optString("score")}" }
                    }.joinToString(" – ")
                    result.add(SportsEvent("espn:$path:$id",e.optString("shortName").ifBlank { e.optString("name") },label,date,
                        type.optString("state"),type.optString("shortDetail"),network,logo,url,fetched,score,teams,teamIds))
                }
            } catch(_:Exception) { if(active())onFailure() }
        }
        // Rank before the first row limit, so a later kickoff for a favorite cannot be discarded.
        return SportsPolicy.upcoming(result,System.currentTimeMillis(),favoriteTeams)
    }

    /** Loaded only when requested by the team picker; weekly disk cache avoids Home requests. */
    fun teams(active:()->Boolean):List<SportsTeam> {
        val result=ArrayList<SportsTeam>()
        for((path,label) in LEAGUES) {
            if(!active())break
            val cache=File(dir,"teams-${path.replace('/','-')}.json")
            val now=System.currentTimeMillis()
            var data=try { if(cache.isFile && cache.length()<=1_500_000)cache.readText() else null } catch(_:Exception) { null }
            if(data==null || now-cache.lastModified() !in 0..7*86_400_000L) {
                try {
                    val fresh=get("https://site.api.espn.com/apis/site/v2/sports/$path/teams?limit=1000",1_500_000)
                    require(JSONObject(fresh).optJSONArray("sports")!=null)
                    if(!active())break
                    AtomicPrivateFile.write(cache,fresh.toByteArray(Charsets.UTF_8),1_500_000); data=fresh
                } catch(_:Exception) { if(active())onFailure() /* An older directory is safe: IDs do not claim live game state. */ }
            }
            try {
                val rows=JSONObject(data ?: continue).optJSONArray("sports")?.optJSONObject(0)?.optJSONArray("leagues")?.optJSONObject(0)?.optJSONArray("teams")
                if(rows==null) { if(active())onFailure(); continue }
                for(i in 0 until minOf(rows.length(),1000)) {
                    val team=rows.optJSONObject(i)?.optJSONObject("team") ?: continue
                    val id=team.optString("id").takeIf { it.matches(Regex("[0-9]{1,15}")) } ?: continue
                    val name=team.optString("displayName").trim(); if(name.isBlank())continue
                    result.add(SportsTeam("espn:$path:$id",name.take(200),label,team.optString("abbreviation").take(30)))
                }
            } catch(_:Exception) { if(active())onFailure() }
        }
        return result.distinctBy { it.id }.sortedWith(compareBy<SportsTeam> { LEAGUES.map { league -> league.second }.indexOf(it.league) }.thenBy { it.name })
    }

    companion object {
        val LEAGUES=listOf("football/nfl" to "NFL","football/college-football" to "College football",
            "basketball/nba" to "NBA","hockey/nhl" to "NHL","baseball/mlb" to "MLB","soccer/eng.1" to "Premier League")
    }
}
