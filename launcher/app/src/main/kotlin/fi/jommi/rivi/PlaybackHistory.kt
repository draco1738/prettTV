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
class PlaybackHistory(context:Context) {
    companion object { private val historyLock=Any() }
    private val prefs=context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE)
    private fun key(p:Program)=p.packageName+"|"+p.titleKey
    private fun json(p:Program)=JSONObject().put("id",p.id).put("title",p.title).put("detail",p.detail).put("art",p.art)
        .put("pkg",p.packageName).put("app",p.app).put("uri",p.intentUri).put("pos",p.position).put("dur",p.duration).put("type",p.mediaType)
        .put("imdbId",p.imdbId).put("releaseYear",p.releaseYear)
    private fun program(j:JSONObject)=Program(j.optString("id"),j.optString("title"),j.optString("detail"),j.optString("art"),j.optString("pkg"),j.optString("app"),
        j.optString("uri"),j.optLong("pos"),j.optLong("dur"),mediaType=j.optString("type"),watchingSource=true,
        imdbId=j.optString("imdbId"),releaseYear=j.optInt("releaseYear"))
    fun merge(rows:List<Program>):List<Program> = synchronized(historyLock) {
        val now=System.currentTimeMillis(); val cutoff=prefs.getLong("baselineCutoff",now)
        val initialImport=!prefs.contains("baselineCutoff")
        val previous=try {
            val values=JSONArray(prefs.getString("known","[]"))
            (0 until minOf(values.length(),2000)).mapNotNull { values.optJSONObject(it) }.associateBy { key(program(it)) }
        } catch(_:Exception) { emptyMap<String,JSONObject>() }
        fun progressBaseline(record:JSONObject)=program(record.optJSONObject("providerProgressBaseline") ?: record)
        val changed=rows.filter { p -> previous[key(p)]?.let { ProviderPlaybackPolicy.changed(program(it),p,progressBaseline(it)) }==true }.map(::key).toSet()
        // One observation time per changed batch: preserve incoming order for unknown ties.
        val observedAt=if(changed.isEmpty())0 else ProviderPlaybackPolicy.nextObservedAt(now,recencyCeiling())
        val edit=prefs.edit().putLong("baselineCutoff",cutoff)
        if(changed.isNotEmpty())edit.putLong("providerLastObserved",observedAt)
        val known=JSONArray()
        val result=rows.map { p ->
            val k=key(p); val prior=previous[k]
            // Keep the latest raw sample and a stable progress anchor, so repeated subsecond
            // publications can accumulate a meaningful change instead of resetting forever.
            known.put(json(p).put("providerProgressBaseline",json(if(k in changed || prior==null)p else progressBaseline(prior))))
            val old=if(prefs.contains("baseline:$k"))prefs.getLong("baseline:$k",0) else null
            val baseline=old ?: ProviderPlaybackPolicy.lateBaseline(p,cutoff,now,initialImport) ?: PlaybackPolicy.baseline(p.engagedAt,cutoff,null)
            edit.putLong("baseline:$k",baseline)
            val observed=prefs.getLong("played:$k",0)
            val providerObserved=if(k in changed)observedAt else prefs.getLong("providerObserved:$k",0)
            if(k in changed)edit.putLong("providerObserved:$k",providerObserved)
            val recency=maxOf(baseline,observed,providerObserved)
            if(observed>0 && observed>=providerObserved) {
                val position=prefs.getLong("position:$k",p.position)
                val duration=prefs.getLong("duration:$k",p.duration)
                val extra=if(duration>position && position>0)" · ${(duration-position)/60_000} min left" else ""
                p.copy(engagedAt=recency,position=position,duration=duration,detail=p.detail.replace(Regex(" · [0-9]+ min left$"),"")+extra)
            } else p.copy(engagedAt=recency)
        }
        edit.putString("known",known.toString()).apply()
        ContentPolicy.chronological(result)
    }
    fun confirm(p:Program,position:Long=p.position,duration:Long=p.duration,eventAt:Long=System.currentTimeMillis()) = synchronized(historyLock) {
        val k=key(p)
        val playedAt=ProviderPlaybackPolicy.nextObservedAt(System.currentTimeMillis(),recencyCeiling())
        prefs.edit().putLong("played:$k",playedAt).putLong("position:$k",position.coerceAtLeast(0))
            .putLong("duration:$k",duration.coerceAtLeast(0)).putLong("event:$k",eventAt.coerceAtLeast(0)).apply()
    }
    private fun recencyCeiling()=prefs.all.entries.asSequence().filter {
        it.key=="providerLastObserved" || it.key.startsWith("providerObserved:") || it.key.startsWith("played:") || it.key.startsWith("baseline:")
    }.mapNotNull { it.value as? Long }.maxOrNull()?.coerceAtLeast(0) ?: 0
    fun match(pkg:String,labels:List<String>):Program? {
        val rows=try { JSONArray(prefs.getString("known","[]")) } catch(_:Exception) { JSONArray() }
        val candidates=(0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::program) }.filter { it.packageName==pkg }
        val matching=candidates.filter { p -> labels.any { PlaybackPolicy.matches(p.title,it) } }.distinctBy { key(it) }
        if(matching.size==1)return matching.first()
        // Opening a title is not proof of playback. Missing/ambiguous metadata stays unknown.
        return null
    }
}
