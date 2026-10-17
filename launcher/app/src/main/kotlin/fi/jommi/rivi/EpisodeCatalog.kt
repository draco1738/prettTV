package fi.jommi.rivi

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.URLEncoder
import java.time.Instant

data class EpisodeAlert(val id:String,val showKey:String,val program:Program,val season:Int,val number:Int,
    val name:String,val releasedAt:Long,val sourceUrl:String) {
    fun asProgram()=program.copy(id="episode-alert:$id",detail="S$season E$number · Episode released · Availability unconfirmed",
        summary=listOf(name,program.summary).filter { it.isNotBlank() }.joinToString("\n\n"),
        // Keep the known show destination; TVmaze never promises provider episode availability.
        sourceName="TVmaze",sourceUrl=sourceUrl,catalog=true,watchingSource=false,position=0,duration=0,released=releasedAt)
}

internal data class EpisodeRecord(val id:String,val season:Int,val number:Int,val name:String,val releasedAt:Long,val url:String)
internal data class EpisodeState(val key:String,val followedAt:Long,val baselineAt:Long,val showId:Int=0,
    val checkedAt:Long=0,val episodes:List<EpisodeRecord> = emptyList(),val dismissed:Set<String> = emptySet(),val status:String="Pending")
internal data class EpisodeSnapshot(val alerts:List<EpisodeAlert> = emptyList(),val status:String="No followed shows")

internal object EpisodePolicy {
    fun released(rows:List<EpisodeRecord>,baseline:Long,now:Long,dismissed:Set<String>):List<EpisodeRecord> = rows
        .filter { it.id.isNotBlank() && it.releasedAt>baseline && it.releasedAt<=now && it.id !in dismissed }
        .distinctBy { it.id }.sortedByDescending { it.releasedAt }.take(100)
    fun due(checkedAt:Long,now:Long)=checkedAt<=0 || now<checkedAt || now-checkedAt>=86_400_000L
    /** nanoTime measures elapsed time; clock corrections must never enlarge a pacing sleep. */
    fun requestDelayMillis(lastNanos:Long?,nowNanos:Long):Long = if(lastNanos==null)0 else
        (600L-(nowNanos-lastNanos).coerceAtLeast(0)/1_000_000L).coerceIn(0,600)
    fun exactMatch(title:String,year:Int,candidates:List<Triple<Int,String,Int>>):Int? {
        if(year !in 1900..2200)return null
        val key=title.trim().replace(Regex("\\s+")," ").lowercase(java.util.Locale.ROOT)
        return candidates.filter { it.first>0 && it.second.trim().replace(Regex("\\s+")," ").lowercase(java.util.Locale.ROOT)==key && it.third==year }
            .distinctBy { it.first }.singleOrNull()?.first
    }
}

/** Called only by ContentRepository's Home-visible worker; no alarms, services, or credentials. */
internal class EpisodeCatalog(private val file:File,private val get:(String,Int)->String) {
    private var loaded=false
    private var states=linkedMapOf<String,EpisodeState>()
    private var lastRequestNanos:Long?=null

    fun snapshot(follows:List<FollowedShow>,now:Long):EpisodeSnapshot {
        ensureLoaded()
        val alerts=follows.flatMap { follow ->
            val state=states[follow.key]?.takeIf { it.followedAt==follow.followedAt } ?: return@flatMap emptyList()
            EpisodePolicy.released(state.episodes,state.baselineAt,now,state.dismissed).map {
                EpisodeAlert(it.id,follow.key,follow.program,it.season,it.number,it.name,it.releasedAt,it.url)
            }
        }.sortedByDescending { it.releasedAt }.take(100)
        val unavailable=follows.count { states[it.key]?.status=="Unavailable" }
        val failed=follows.count { states[it.key]?.status=="Retry tomorrow" }
        val pending=follows.count { states[it.key]==null || states[it.key]?.status=="Pending" }
        val status=if(follows.isEmpty())"No followed shows" else buildList {
            add("${follows.size} followed")
            if(unavailable>0)add("$unavailable without a verified episode source")
            if(failed>0)add("$failed checks unavailable; retry tomorrow")
            if(pending>0)add("$pending pending")
            add("Release dates do not confirm streaming availability")
        }.joinToString(" · ")
        return EpisodeSnapshot(alerts,status)
    }

    fun load(follows:List<FollowedShow>,now:Long,allowNetwork:Boolean=true,active:()->Boolean):EpisodeSnapshot {
        ensureLoaded()
        val keys=follows.take(100).map { it.key }.toSet()
        states.keys.retainAll(keys)
        // Establish a baseline before the first network response, including failed first checks.
        for(follow in follows.take(100))if(states[follow.key]?.followedAt!=follow.followedAt)
            states[follow.key]=EpisodeState(follow.key,follow.followedAt,now)
        if(!allowNetwork) { if(active())save(); return snapshot(follows,now) }
        var calls=0
        fun request(url:String,limit:Int):String {
            check(active())
            if(calls>=8)throw RequestBudgetReached()
            val wait=EpisodePolicy.requestDelayMillis(lastRequestNanos,System.nanoTime())
            if(wait>0)Thread.sleep(wait)
            check(active()); calls++; lastRequestNanos=System.nanoTime()
            return get(url,limit)
        }
        for(follow in follows.sortedBy { states[it.key]?.checkedAt ?: 0L }.take(100)) {
            if(!active() || calls>=8)break
            val state=states[follow.key] ?: continue
            if(!EpisodePolicy.due(state.checkedAt,now))continue
            try {
                val showId=state.showId.takeIf { it>0 } ?: resolve(follow.program,::request)
                if(!active())break
                if(showId==null) {
                    states[follow.key]=state.copy(checkedAt=now,status="Unavailable"); continue
                }
                val data=JSONArray(request("https://api.tvmaze.com/shows/$showId/episodes",2_000_000))
                val episodes=buildList {
                    for(i in 0 until minOf(data.length(),4000)) {
                        val e=data.optJSONObject(i) ?: continue
                        val id=e.optInt("id"); if(id<=0)continue
                        // A date without a timestamp cannot reliably establish that an episode has aired.
                        val released=try { Instant.parse(e.optString("airstamp")).toEpochMilli() } catch(_:Exception) { continue }
                        add(EpisodeRecord("tvmaze:episode:$id",e.optInt("season"),e.optInt("number"),e.optString("name").take(500),released,
                            e.optString("url").takeIf { it.startsWith("https://www.tvmaze.com/") }.orEmpty().take(2000)))
                    }
                }.filter { it.releasedAt>state.baselineAt }.sortedByDescending { it.releasedAt }.take(200)
                if(active())states[follow.key]=state.copy(showId=showId,checkedAt=now,episodes=episodes,status="Ready")
            } catch(_:RequestBudgetReached) { break }
            catch(_:Exception) { if(active())states[follow.key]=state.copy(checkedAt=now,status="Retry tomorrow") }
        }
        if(active())save()
        return snapshot(follows,now)
    }

    fun dismiss(id:String) {
        ensureLoaded()
        for((key,state) in states.toMap())if(state.episodes.any { it.id==id })
            states[key]=state.copy(dismissed=(state.dismissed+id).takeLastSet(500))
        save()
    }

    private fun resolve(p:Program,request:(String,Int)->String):Int? {
        if(p.mediaType !in listOf("series","tv"))return null
        Regex("^tvmaze:([0-9]+)$").matchEntire(p.id)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it>0 }?.let { return it }
        val imdb=p.imdbId.takeIf { it.matches(Regex("tt[0-9]{5,12}")) }
            ?: Regex("^cinemeta:series:(tt[0-9]{5,12})$").matchEntire(p.id)?.groupValues?.get(1)
        if(imdb!=null)return JSONObject(request("https://api.tvmaze.com/lookup/shows?imdb=$imdb",300_000)).optInt("id").takeIf { it>0 }
        if(p.releaseYear !in 1900..2200)return null
        val rows=JSONArray(request("https://api.tvmaze.com/search/shows?q=${URLEncoder.encode(p.title,"UTF-8")}",500_000))
        val candidates=buildList {
            for(i in 0 until minOf(rows.length(),50)) {
                val show=rows.optJSONObject(i)?.optJSONObject("show") ?: continue
                add(Triple(show.optInt("id"),show.optString("name"),show.optString("premiered").take(4).toIntOrNull() ?: 0))
            }
        }
        return EpisodePolicy.exactMatch(p.title,p.releaseYear,candidates)
    }

    private fun ensureLoaded() {
        if(loaded)return
        loaded=true
        try {
            if(!file.isFile || file.length() !in 1..MAX_BYTES)return
            DataInputStream(ByteArrayInputStream(file.inputStream().use { it.readBytesCapped(MAX_BYTES.toInt()) })).use { input ->
                require(input.readInt()==MAGIC && input.readInt()==1)
                val entries=List(input.count(100)) {
                    val key=input.readUTF(); val followed=input.readLong(); val baseline=input.readLong(); val show=input.readInt(); val checked=input.readLong(); val status=input.readUTF()
                    val dismissed=List(input.count(500)) { input.readUTF() }.toSet()
                    val episodes=List(input.count(200)) { EpisodeRecord(input.readUTF(),input.readInt(),input.readInt(),input.readUTF(),input.readLong(),input.readUTF()) }
                    EpisodeState(key,followed,baseline,show,checked,episodes,dismissed,status)
                }
                require(input.available()==0)
                states=entries.associateByTo(linkedMapOf()) { it.key }
            }
        } catch(_:Exception) { states.clear() }
    }

    private fun save() {
        try {
            val bytes=BoundedByteArrayOutputStream(MAX_BYTES.toInt()).use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC); out.writeInt(1); out.writeInt(states.size)
                    for(s in states.values) {
                        out.writeUTF(s.key.take(1000)); out.writeLong(s.followedAt); out.writeLong(s.baselineAt); out.writeInt(s.showId); out.writeLong(s.checkedAt); out.writeUTF(s.status)
                        out.writeInt(s.dismissed.size); s.dismissed.forEach { out.writeUTF(it.take(200)) }
                        out.writeInt(s.episodes.size)
                        for(e in s.episodes) { out.writeUTF(e.id); out.writeInt(e.season); out.writeInt(e.number); out.writeUTF(e.name); out.writeLong(e.releasedAt); out.writeUTF(e.url) }
                    }
                }; buffer.toByteArray()
            }
            AtomicPrivateFile.write(file,bytes,MAX_BYTES)
        } catch(_:Exception) { /* In-memory alerts remain usable; the next active refresh retries persistence. */ }
    }
    private class RequestBudgetReached:Exception()
    companion object { private const val MAGIC=0x50544550; private const val MAX_BYTES=2_000_000L }
}

private fun <T> Set<T>.takeLastSet(limit:Int)=toList().takeLast(limit).toSet()
