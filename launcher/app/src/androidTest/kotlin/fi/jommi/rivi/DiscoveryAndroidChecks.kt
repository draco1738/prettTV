package fi.jommi.rivi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

/** Android JSON/persistence fixtures only. No real provider requests and no owner preferences. */
object DiscoveryAndroidChecks {
    fun run(context:Context,test:(String,()->Unit)->Unit) {
        val dir=File(context.cacheDir,"discovery-checks").apply { mkdirs() }
        val now=System.currentTimeMillis()
        fun episode(id:Int,time:Long)=JSONObject().put("id",id).put("season",1).put("number",id).put("name","Fixture $id")
            .put("airstamp",Instant.ofEpochMilli(time).toString()).put("url","https://www.tvmaze.com/episodes/$id")
        fun follow(id:String="tvmaze:12",year:Int=0,imdb:String="")=FollowedShow("fixture",Program(id,"Fixture Show","","","fixture.app","Fixture",mediaType="series",releaseYear=year,imdbId=imdb),now)
        fun file(name:String)=File(dir,name).apply { delete() }

        test("missing-year series metadata uses one exact search result and persists verified facts") {
            val metadataDir=File(dir,"missing-year").apply {
                mkdirs();listFiles()?.forEach { check(it.isFile && it.delete()) { "Stale metadata fixture could not be removed" } }
            }
            val p=Program("provider:fixture","Fixture Series","","","fixture.app","Fixture",mediaType="series")
            val calls=mutableListOf<String>()
            try {
                DiscoveryMetadataRepository(metadataDir) { url,_ ->
                    calls.add(url)
                    if(url.contains("/search/shows?"))"""[{"show":{"id":12,"name":"Fixture Series","premiered":"2025-01-01"}}]"""
                    else """{"id":12,"name":"Fixture Series","premiered":"2025-01-01","genres":["Drama"],"runtime":42,"_embedded":{"cast":[{"person":{"name":"Fixture Actor"}}]}}"""
                }.use { repo ->
                    val facts=checkNotNull(repo.get(p))
                    check(facts.runtimeMinutes==42 && facts.cast==listOf("Fixture Actor") && facts.year==2025)
                    check(calls.size==2 && calls[1].contains("/shows/12?"))
                }
                DiscoveryMetadataRepository(metadataDir) { _,_ -> error("Fresh metadata must be cached") }.use {
                    check(it.get(p)?.runtimeMinutes==42)
                }
            } finally { metadataDir.listFiles()?.forEach { it.delete() };metadataDir.delete() }
        }
        test("ambiguous missing-year series and changed full metadata are rejected") {
            val metadataDir=File(dir,"ambiguous-title").apply {
                mkdirs();listFiles()?.forEach { check(it.isFile && it.delete()) { "Stale metadata fixture could not be removed" } }
            }
            val p=Program("provider:fixture","Fixture Series","","","fixture.app","Fixture",mediaType="series")
            try {
                var calls=0
                DiscoveryMetadataRepository(metadataDir) { url,_ ->
                    calls++;check(url.contains("/search/shows?"))
                    """[{"show":{"id":12,"name":"Fixture Series","premiered":"2001-01-01"}},{"show":{"id":13,"name":"Fixture Series","premiered":"2025-01-01"}}]"""
                }.use { check(it.get(p)==null && calls==1) }
                DiscoveryMetadataRepository(metadataDir) { url,_ ->
                    if(url.contains("/search/shows?"))"""[{"show":{"id":12,"name":"Fixture Series","premiered":"2025-01-01"}}]"""
                    else """{"id":12,"name":"Different Series","premiered":"2025-01-01","genres":["Drama"]}"""
                }.use { check(it.get(p)==null) }
                DiscoveryMetadataRepository(metadataDir) { _,_ -> error("No persisted match") }.use { check(it.cached(p)==null) }
            } finally { metadataDir.listFiles()?.forEach { it.delete() };metadataDir.delete() }
        }

        test("a second bounded metadata batch reuses cached identities and reaches useful peers") {
            val metadataDir=File(dir,"bounded-peer-progress").apply {
                mkdirs();listFiles()?.forEach { check(it.isFile && it.delete()) }
            }
            val rows=(1..9).map { i -> Program("provider:$i","Budget Show $i","","","fixture.app","Fixture",mediaType="series") }
            var calls=0
            val transport:(String,Int)->String={ url,_ ->
                calls++
                if(url.contains("/search/shows?")) {
                    val title=java.net.URLDecoder.decode(url.substringAfter("q="),"UTF-8")
                    val id=title.substringAfterLast(' ').toInt()
                    """[{"show":{"id":$id,"name":"$title"}}]"""
                } else {
                    val id=url.substringAfter("/shows/").substringBefore('?').toInt()
                    val genre=if(id==1 || id==5)"Drama" else "Comedy"
                    """{"id":$id,"name":"Budget Show $id","genres":["$genre"],"runtime":42}"""
                }
            }
            try {
                val first=DiscoveryMetadataRepository(metadataDir,transport).use { it.load(rows.first(),rows) { true } }
                check(calls==8 && first.size==4)
                check(RabbitHolePolicy.related(rows.first(),rows,metadata=first).isEmpty())
                val second=DiscoveryMetadataRepository(metadataDir,transport).use { it.load(rows.first(),rows) { true } }
                check(calls==16 && second.size==8) { "Continuation exceeded its request budget or failed to reuse cache" }
                check(RabbitHolePolicy.related(rows.first(),rows,metadata=first+second).single().program==rows[4])
            } finally { metadataDir.listFiles()?.forEach { it.delete() };metadataDir.delete() }
        }

        test("episode alerts baseline then release once, persist dismissal and unfollow") {
            val f=file("episodes-release.bin"); var calls=0
            val body=JSONArray().put(episode(1,now-1000)).put(episode(2,now+1000)).toString()
            val catalog=EpisodeCatalog(f) { url,_ -> check(url=="https://api.tvmaze.com/shows/12/episodes"); calls++; body }
            check(catalog.load(listOf(follow()),now) { true }.alerts.isEmpty())
            check(catalog.load(listOf(follow()),now+2000) { true }.alerts.size==1)
            check(calls==1) // Cached future release becomes current without another request.
            catalog.dismiss("tvmaze:episode:2")
            val restored=EpisodeCatalog(f) { _,_ -> error("Daily cache must not request") }
            check(restored.load(listOf(follow()),now+3000) { true }.alerts.isEmpty())
            check(restored.load(emptyList(),now+4000,false) { true }.alerts.isEmpty())
            f.delete()
        }
        test("unavailable episode destinations hide without erasing followed baselines or dismissals") {
            val f=file("episodes-uninstalled.bin")
            val follows=listOf(follow())
            val body=JSONArray().put(episode(1,now-1000)).put(episode(2,now+1000)).toString()
            try {
                val catalog=EpisodeCatalog(f) { _,_ -> body }
                check(catalog.load(follows,now) { true }.alerts.isEmpty())
                val released=catalog.load(follows,now+2000) { true }
                check(released.alerts.single().id=="tvmaze:episode:2")
                check(released.eligible(emptySet()).alerts.isEmpty())
                val restored=EpisodeCatalog(f) { _,_ -> error("Availability must not request metadata") }
                val installed=restored.load(follows,now+3000,false) { true }.eligible(setOf("fixture.app"))
                check(installed.alerts.single().id=="tvmaze:episode:2") { "Uninstall discarded the release baseline" }
                restored.dismiss("tvmaze:episode:2")
                val dismissed=EpisodeCatalog(f) { _,_ -> error("Dismissal must survive offline") }
                check(dismissed.load(follows,now+4000,false) { true }.eligible(emptySet()).alerts.isEmpty())
                check(dismissed.load(follows,now+5000,false) { true }.eligible(setOf("fixture.app")).alerts.isEmpty())
                check(dismissed.snapshot(follows,now+5000).status.startsWith("1 followed"))
            } finally { f.delete() }
        }
        test("ambiguous same-name same-year episode matches are refused") {
            val f=file("episodes-ambiguous.bin"); var requests=0
            val search=JSONArray().put(JSONObject().put("show",JSONObject().put("id",1).put("name","Fixture Show").put("premiered","2025-01-01")))
                .put(JSONObject().put("show",JSONObject().put("id",2).put("name","Fixture Show").put("premiered","2025-02-01")))
            val result=EpisodeCatalog(f) { url,_ -> check(url.contains("/search/shows?")); requests++; search.toString() }
                .load(listOf(follow("provider:1",2025)),now) { true }
            check(requests==1 && result.alerts.isEmpty() && result.status.contains("without a verified")); f.delete()
        }
        test("exact IMDb match uses lookup and bounded episode list") {
            val f=file("episodes-imdb.bin"); val requests=mutableListOf<String>()
            val result=EpisodeCatalog(f) { url,_ -> requests.add(url)
                if(url.contains("/lookup/"))"{\"id\":12}" else JSONArray().put(episode(1,now-1)).toString()
            }.load(listOf(follow("provider:1",imdb="tt1234567")),now) { true }
            check(requests.size==2 && requests[0].contains("imdb=tt1234567") && requests[1].endsWith("/12/episodes"))
            check(result.alerts.isEmpty()); f.delete()
        }
        test("failed episode refresh retains known alerts and backs off for a day") {
            val f=file("episodes-failure.bin"); var calls=0
            val catalog=EpisodeCatalog(f) { _,_ -> calls++; if(calls>1)error("fixture outage") else JSONArray().put(episode(2,now+1)).toString() }
            catalog.load(listOf(follow()),now) { true }
            val after=catalog.load(listOf(follow()),now+86_400_001) { true }
            check(after.alerts.size==1 && after.status.contains("retry tomorrow"))
            catalog.load(listOf(follow()),now+86_400_002) { true }; check(calls==2); f.delete()
        }
        test("cancelled episode fetch cannot persist stale replacement") {
            val f=file("episodes-cancel.bin"); var active=true
            val catalog=EpisodeCatalog(f) { _,_ -> active=false; JSONArray().put(episode(1,now-1)).toString() }
            catalog.load(listOf(follow()),now) { active }
            check(!f.exists())
        }
        test("team directory namespaces IDs per league and reuses cached data") {
            val teamsDir=File(dir,"teams").apply { mkdirs() }; teamsDir.listFiles()?.forEach { it.delete() }; var calls=0
            val body=JSONObject().put("sports",JSONArray().put(JSONObject().put("leagues",JSONArray().put(JSONObject().put("teams",JSONArray().put(
                JSONObject().put("team",JSONObject().put("id","22").put("displayName","Fixture Team").put("abbreviation","FT")))))))).toString()
            val catalog=SportsCatalog(teamsDir) { url,_ -> check(url.endsWith("/teams?limit=1000")); calls++; body }
            val first=catalog.teams { true }; val second=catalog.teams { true }
            check(first.size==SportsCatalog.LEAGUES.size && first.map { it.id }.distinct().size==first.size)
            check(first==second && calls==SportsCatalog.LEAGUES.size)
            teamsDir.listFiles()?.forEach { it.delete() }; teamsDir.delete()
        }
        test("sports catalog ranks a late favorite before its first sixty-event limit") {
            val sportsDir=File(dir,"ranked-sports").apply { mkdirs() }; sportsDir.listFiles()?.forEach { it.delete() }
            val events=JSONArray()
            for(i in 1..61)events.put(JSONObject().put("id",i.toString()).put("shortName","Fixture $i")
                .put("date",Instant.ofEpochMilli(now+i*60_000L).toString())
                .put("status",JSONObject().put("type",JSONObject().put("state","pre").put("shortDetail","Scheduled")))
                .put("competitions",JSONArray().put(JSONObject().put("competitors",JSONArray().put(JSONObject().put("team",
                    JSONObject().put("id",i.toString()).put("displayName","Team $i").put("abbreviation","T$i")))))))
            val catalog=SportsCatalog(sportsDir) { url,_ ->
                if(url.contains("/football/nfl/"))JSONObject().put("events",events).toString() else "{\"events\":[]}"
            }
            val rows=catalog.load(setOf("espn:football/nfl:61")) { true }
            check(rows.size==60 && rows.first().id=="espn:football/nfl:61")
            // Existing cache must apply a changed favorite selection before truncating too.
            check(catalog.load(setOf("espn:football/nfl:60")) { true }.first().id=="espn:football/nfl:60")
            sportsDir.listFiles()?.forEach { it.delete() }; sportsDir.delete()
        }
        dir.delete()
    }
}
