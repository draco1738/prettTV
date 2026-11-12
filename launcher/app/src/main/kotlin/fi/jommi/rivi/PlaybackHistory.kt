package fi.jommi.rivi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object PlaybackPolicy {
    fun normalized(s:String)=s.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
    fun matches(title:String,label:String):Boolean {
        val t=normalized(title); val l=normalized(label)
        return t.length>=4 && (l==t || l.startsWith("$t "))
    }
    fun baseline(original:Long,firstSeen:Long,stored:Long?):Long=stored ?: original.coerceIn(0,firstSeen)
}

/** Local confirmations and separately observed provider progress; timestamps alone never count. */
class PlaybackHistory internal constructor(private val prefs:android.content.SharedPreferences) {
    constructor(context:Context):this(context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE))
    companion object { private val historyLock=Any(); private const val MAX_KNOWN=2000 }
    private fun key(p:Program)=p.packageName+"|"+p.titleKey
    private fun json(p:Program)=JSONObject().put("id",p.id).put("title",p.title).put("detail",p.detail).put("art",p.art)
        .put("pkg",p.packageName).put("app",p.app).put("uri",p.intentUri).put("pos",p.position).put("dur",p.duration).put("type",p.mediaType)
        .put("imdbId",p.imdbId).put("releaseYear",p.releaseYear).put("posterArt",p.posterArt)
    private fun program(j:JSONObject)=Program(j.optString("id"),j.optString("title"),j.optString("detail"),j.optString("art"),j.optString("pkg"),j.optString("app"),
        j.optString("uri"),j.optLong("pos"),j.optLong("dur"),mediaType=j.optString("type"),watchingSource=true,
        imdbId=j.optString("imdbId"),releaseYear=j.optInt("releaseYear"),posterArt=(j.opt("posterArt") as? String).orEmpty())
    private fun known():Map<String,JSONObject> = try {
        val values=JSONArray(prefs.getString("known","[]"))
        (0 until minOf(values.length(),MAX_KNOWN)).mapNotNull { values.optJSONObject(it) }.associateBy { key(program(it)) }
    } catch(_:Exception) { emptyMap() }
    private fun withProgress(p:Program,position:Long,duration:Long,recency:Long):Program {
        val extra=if(duration>position && position>0)" · ${(duration-position)/60_000} min left" else ""
        return p.copy(engagedAt=recency,position=position,duration=duration,
            detail=p.detail.replace(Regex(" · [0-9]+ min left$"),"")+extra)
    }
    private fun compatibleTitle(local:Program,provider:Program)=key(local)==key(provider) &&
        (local.imdbId.isBlank() || provider.imdbId.isBlank() || local.imdbId==provider.imdbId) &&
        (local.releaseYear==0 || provider.releaseYear==0 || local.releaseYear==provider.releaseYear)
    /** Repair old title-only snapshots without importing provider episode/progress evidence. */
    private fun withKnownMediaType(local:Program,provider:Program):Program =
        if(local.mediaType !in setOf("series","movie") && provider.mediaType in setOf("series","movie") && compatibleTitle(local,provider))
            local.copy(mediaType=provider.mediaType) else local
    /** Legacy local positions do not establish the provider's current episode or deep link. */
    private fun localSnapshot(record:JSONObject):Program? {
        val p=program(record);val k=key(p)
        if(prefs.getLong("played:$k",0)<=0 || p.title.isBlank() || p.packageName.isBlank())return null
        val saved=record.optJSONObject("localSnapshot")?.let(::program)?.takeIf { key(it)==k }
        val base=saved ?: p.copy(detail=p.app,intentUri="")
        return withProgress(withKnownMediaType(base,p),prefs.getLong("position:$k",0).coerceAtLeast(0),
            prefs.getLong("duration:$k",0).coerceAtLeast(0),prefs.getLong("played:$k",0))
    }
    private fun encodeKnown(records:Collection<JSONObject>):String {
        // Bound retained metadata by the existing reader limit, favoring recent local evidence.
        val ordered=records.sortedByDescending { prefs.getLong("played:${key(program(it))}",0) }.take(MAX_KNOWN)
        return JSONArray().also { array -> ordered.forEach(array::put) }.toString()
    }
    fun merge(rows:List<Program>,settingsGeneration:Long=SettingsBackupGuard.generation,
        mode:ViewingHistoryMode=ViewingHistoryMode.COMBINED):List<Program> =
        SettingsBackupGuard.writeResult(emptyList(),settingsGeneration) { synchronized(historyLock) {
        val now=System.currentTimeMillis(); val cutoff=prefs.getLong("baselineCutoff",now)
        val initialImport=!prefs.contains("baselineCutoff")
        val previous=known()
        fun progressBaseline(record:JSONObject)=program(record.optJSONObject("providerProgressBaseline") ?: record)
        val changed=rows.filter { p -> previous[key(p)]?.takeUnless { it.optBoolean("providerAbsent") }
            ?.let { ProviderPlaybackPolicy.changed(program(it),p,progressBaseline(it)) }==true }.map(::key).toSet()
        // One observation time per changed batch: preserve incoming order for unknown ties.
        val observedAt=if(changed.isEmpty())0 else ProviderPlaybackPolicy.nextObservedAt(now,recencyCeiling())
        val edit=prefs.edit().putLong("baselineCutoff",cutoff)
        if(changed.isNotEmpty())edit.putLong("providerLastObserved",observedAt)
        val records=linkedMapOf<String,JSONObject>()
        val result=rows.map { p ->
            val k=key(p); val prior=previous[k]
            // Keep the latest raw sample and a stable progress anchor, so repeated subsecond
            // publications can accumulate a meaningful change instead of resetting forever.
            val record=json(p).put("providerProgressBaseline",json(if(k in changed || prior==null || prior.optBoolean("providerAbsent"))p else progressBaseline(prior)))
            localSnapshot(prior ?: record)?.let { local -> record.put("localSnapshot",json(withKnownMediaType(local,p))) }
            records[k]=record
            val old=if(prefs.contains("baseline:$k"))prefs.getLong("baseline:$k",0) else null
            val baseline=old ?: ProviderPlaybackPolicy.lateBaseline(p,cutoff,now,initialImport) ?: PlaybackPolicy.baseline(p.engagedAt,cutoff,null)
            edit.putLong("baseline:$k",baseline)
            val observed=prefs.getLong("played:$k",0)
            val providerObserved=if(k in changed)observedAt else prefs.getLong("providerObserved:$k",0)
            if(k in changed)edit.putLong("providerObserved:$k",providerObserved)
            val recency=maxOf(baseline,observed,providerObserved)
            if(observed>0 && observed>=providerObserved) {
                withProgress(p,prefs.getLong("position:$k",p.position),prefs.getLong("duration:$k",p.duration),recency)
            } else p.copy(engagedAt=recency)
        }
        previous.filterKeys { it !in records }.forEach { (k,record) ->
            localSnapshot(record)?.let { local -> records[k]=record.put("localSnapshot",json(local)).put("providerAbsent",true) }
        }
        edit.putString("known",encodeKnown(records.values)).apply()
        val selected=if(mode==ViewingHistoryMode.COMBINED)result else records.values.mapNotNull { record ->
            localSnapshot(record)?.let { local ->
                val provider=program(record)
                // Artwork may enrich a matching title; episode text and deep links stay local.
                if(compatibleTitle(local,provider))local.copy(art=provider.art.ifBlank { local.art },posterArt=provider.posterArt.ifBlank { local.posterArt }) else local
            }
        }
        ContentPolicy.chronological(selected)
    } }
    /** A title association is not episode equivalence, even when session text names an episode. */
    private fun titleOnly(p:Program)=Program(id="local:${key(p)}",title=p.title,detail=p.app,
        art=p.art,packageName=p.packageName,app=p.app,watchingSource=true,posterArt=p.posterArt,mediaType=p.mediaType)

    /** Deliberate user correction: the selected row's metadata and progress are accepted explicitly. */
    fun confirm(p:Program,position:Long=p.position,duration:Long=p.duration,eventAt:Long=System.currentTimeMillis(),
        settingsGeneration:Long=SettingsBackupGuard.generation) =
        recordLocal(p,position,duration,eventAt,settingsGeneration)

    /** Automatic title-only playback. Progress must come from the session, never the matched row. */
    fun observe(p:Program,position:Long,duration:Long=0,eventAt:Long=System.currentTimeMillis(),
        settingsGeneration:Long=SettingsBackupGuard.generation) =
        recordLocal(titleOnly(p),position,duration,eventAt,settingsGeneration)

    private fun recordLocal(p:Program,position:Long,duration:Long,eventAt:Long,settingsGeneration:Long) =
        SettingsBackupGuard.tryWrite(settingsGeneration) { synchronized(historyLock) {
        val k=key(p)
        val playedAt=ProviderPlaybackPolicy.nextObservedAt(System.currentTimeMillis(),recencyCeiling())
        val records=known().toMutableMap()
        records[k]=(records[k] ?: json(p).put("providerAbsent",true))
            .put("localSnapshot",json(withProgress(p,position.coerceAtLeast(0),duration.coerceAtLeast(0),playedAt)))
        val stored=JSONArray().also { array ->
            array.put(records.getValue(k))
            records.filterKeys { it!=k }.values.sortedByDescending { prefs.getLong("played:${key(program(it))}",0) }
                .take(MAX_KNOWN-1).forEach(array::put)
        }
        prefs.edit().putLong("played:$k",playedAt).putLong("position:$k",position.coerceAtLeast(0))
            .putLong("duration:$k",duration.coerceAtLeast(0)).putLong("event:$k",eventAt.coerceAtLeast(0))
            .putString("known",stored.toString()).apply()
    } }
    private fun recencyCeiling()=prefs.all.entries.asSequence().filter {
        it.key=="providerLastObserved" || it.key.startsWith("providerObserved:") || it.key.startsWith("played:") || it.key.startsWith("baseline:")
    }.mapNotNull { it.value as? Long }.maxOrNull()?.coerceAtLeast(0) ?: 0
    fun match(pkg:String,labels:List<String>):Program? {
        val candidates=known().values.map(::program).filter { it.packageName==pkg }
        val matching=candidates.filter { p -> labels.any { PlaybackPolicy.matches(p.title,it) } }.distinctBy { key(it) }
        if(matching.size==1)return titleOnly(matching.first())
        // Opening a title is not proof of playback. Missing/ambiguous metadata stays unknown.
        return null
    }
}
