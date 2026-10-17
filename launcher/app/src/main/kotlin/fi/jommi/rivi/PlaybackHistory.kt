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

/** Local playback evidence. Refreshing a provider timestamp alone never means a show was played. */
class PlaybackHistory(context:Context) {
    private val prefs=context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE)
    private fun key(p:Program)=p.packageName+"|"+p.titleKey
    private fun json(p:Program)=JSONObject().put("id",p.id).put("title",p.title).put("detail",p.detail).put("art",p.art)
        .put("pkg",p.packageName).put("app",p.app).put("uri",p.intentUri).put("pos",p.position).put("dur",p.duration).put("type",p.mediaType)
        .put("imdbId",p.imdbId).put("releaseYear",p.releaseYear)
    private fun program(j:JSONObject)=Program(j.optString("id"),j.optString("title"),j.optString("detail"),j.optString("art"),j.optString("pkg"),j.optString("app"),
        j.optString("uri"),j.optLong("pos"),j.optLong("dur"),mediaType=j.optString("type"),watchingSource=true,
        imdbId=j.optString("imdbId"),releaseYear=j.optInt("releaseYear"))
    @Synchronized fun merge(rows:List<Program>):List<Program> {
        val now=System.currentTimeMillis(); val cutoff=prefs.getLong("baselineCutoff",now)
        val edit=prefs.edit().putLong("baselineCutoff",cutoff)
        val known=JSONArray()
        val result=rows.map { p ->
            known.put(json(p)); val k=key(p)
            val old=if(prefs.contains("baseline:$k"))prefs.getLong("baseline:$k",0) else null
            val baseline=PlaybackPolicy.baseline(p.engagedAt,cutoff,old); edit.putLong("baseline:$k",baseline)
            val observed=prefs.getLong("played:$k",0)
            if(observed>0) {
                val position=prefs.getLong("position:$k",p.position)
                val duration=prefs.getLong("duration:$k",p.duration)
                val extra=if(duration>position && position>0)" · ${(duration-position)/60_000} min left" else ""
                p.copy(engagedAt=observed,position=position,duration=duration,detail=p.detail.replace(Regex(" · [0-9]+ min left$"),"")+extra)
            } else p.copy(engagedAt=baseline)
        }
        edit.putString("known",known.toString()).apply()
        return ContentPolicy.chronological(result)
    }
    @Synchronized fun confirm(p:Program,position:Long=p.position,duration:Long=p.duration,eventAt:Long=System.currentTimeMillis()) {
        val k=key(p)
        prefs.edit().putLong("played:$k",System.currentTimeMillis()).putLong("position:$k",position.coerceAtLeast(0))
            .putLong("duration:$k",duration.coerceAtLeast(0)).putLong("event:$k",eventAt.coerceAtLeast(0)).apply()
    }
    fun match(pkg:String,labels:List<String>):Program? {
        val rows=try { JSONArray(prefs.getString("known","[]")) } catch(_:Exception) { JSONArray() }
        val candidates=(0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(::program) }.filter { it.packageName==pkg }
        val matching=candidates.filter { p -> labels.any { PlaybackPolicy.matches(p.title,it) } }.distinctBy { key(it) }
        if(matching.size==1)return matching.first()
        // Opening a title is not proof of playback. Missing/ambiguous metadata stays unknown.
        return null
    }
}
