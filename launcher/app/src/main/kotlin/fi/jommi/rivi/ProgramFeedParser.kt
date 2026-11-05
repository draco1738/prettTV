package fi.jommi.rivi

import android.database.Cursor
import android.media.tv.TvContract.PreviewPrograms
import org.json.JSONArray
import org.json.JSONObject

/** Parse already-fetched feeds; portrait art never replaces the existing landscape selection. */
internal object ProgramFeedParser {
    fun provider(c:Cursor,table:String,available:Set<String>,channels:Map<Long,String>,appLabel:(String)->String):List<Program> {
        val out=ArrayList<Program>()
        fun str(key:String):String { val i=c.getColumnIndex(key); return if(i>=0 && !c.isNull(i))c.getString(i).orEmpty() else "" }
        fun number(key:String):Long { val i=c.getColumnIndex(key); return if(i>=0 && !c.isNull(i))c.getLong(i) else 0 }
        var scanned=0
        val counts=HashMap<String,Int>()
        while(c.moveToNext() && scanned++ < 5000 && out.size < 2000) {
            val pkg=str("package_name"); val title=str("title")
            if(pkg !in available || title.isBlank())continue
            if((counts[pkg] ?: 0)>=300)continue
            counts[pkg]=(counts[pkg] ?: 0)+1
            if(table=="watch_next_program" && number("watch_next_type")==3L)continue
            val app=appLabel(pkg)
            val position=number("last_playback_position_millis"); val duration=number("duration_millis")
            val episode=str("episode_display_number"); val season=str("season_display_number")
            val detail=listOfNotNull(app,if(episode.isNotBlank())"S${season.ifBlank { "1" }} E$episode" else null,
                if(duration>position && position>0)"${(duration-position)/60000} min left" else null).joinToString(" · ")
            val kind=when(str("type").toIntOrNull()) {
                PreviewPrograms.TYPE_TV_SERIES,PreviewPrograms.TYPE_TV_SEASON,PreviewPrograms.TYPE_TV_EPISODE -> "series"
                PreviewPrograms.TYPE_MOVIE -> "movie"
                else -> if(episode.isNotBlank() || season.isNotBlank())"series" else "unknown"
            }
            val sports=number("live")==1L && (str("canonical_genre")+" "+str("genre")).contains("SPORT",ignoreCase=true) &&
                (number("end_time_utc_millis")==0L || number("end_time_utc_millis")>System.currentTimeMillis())
            val channel=channels[number("channel_id")].orEmpty()
            val continuing=Regex("continue|watching|resume",RegexOption.IGNORE_CASE).containsMatchIn(channel)
            out.add(Program("$table:${str("_id")}",title,detail,str("thumbnail_uri").ifBlank { str("poster_art_uri") },pkg,app,str("intent_uri"),position,duration,
                summary=str("short_description"),watchingSource=table=="watch_next_program" || continuing || position>0,
                engagedAt=number("last_engagement_time_utc_millis"),mediaType=kind,liveSports=sports,sourceName=channel,
                genres=str("genre").ifBlank { str("canonical_genre") },providerWeight=number("weight"),
                releaseYear=str("release_date").take(4).toIntOrNull() ?: 0,posterArt=str("poster_art_uri")))
        }
        return out.sortedByDescending { it.resume }
    }

    fun tvmaze(j:JSONObject,id:Int,pkg:String,app:String):Program {
        val art=j.optString("pretttvLandscape").ifBlank { j.optJSONObject("image")?.optString("original").orEmpty() }
        val image=j.optJSONObject("image")
        val poster=(image?.opt("original") as? String).orEmpty().ifBlank { (image?.opt("medium") as? String).orEmpty() }
        val genres=j.optJSONArray("genres")
        val genre=if(genres!=null && genres.length()>0)genres.optString(0) else "Series"
        val summary=android.text.Html.fromHtml(j.optString("summary"),android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        return Program("tvmaze:$id",j.optString("name"),"$genre · $app",art,pkg,app,catalog=true,sourceUrl=j.optString("url"),summary=summary,
            sourceName="TVmaze",mediaType="series",genres=genre,releaseYear=j.optString("premiered").take(4).toIntOrNull() ?: 0,
            imdbId=j.optJSONObject("externals")?.optString("imdb").orEmpty(),posterArt=poster)
    }

    fun cinemeta(rows:JSONArray,type:String,catalog:String):List<Program> {
        val result=ArrayList<Program>()
        for(i in 0 until minOf(rows.length(),100)) {
            val j=rows.optJSONObject(i) ?: continue
            val id=j.optString("id"); val title=j.optString("name")
            if(!id.matches(Regex("tt[0-9]{5,12}")) || title.isBlank())continue
            val date=j.optString("released").take(10)
            val released=try { java.time.LocalDate.parse(date).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() } catch(_:Exception) { 0L }
            val genre=j.optJSONArray("genres")?.optString(0).orEmpty().ifBlank { if(type=="movie")"Film" else "Series" }
            val source="https://v3-cinemeta.strem.io/meta/$type/$id.json"
            result.add(Program("cinemeta:$type:$id",title,if(catalog=="year")"$date · ${if(type=="movie")"Film" else "Series"}" else "$genre · Stremio",
                j.optString("background").ifBlank { j.optString("poster") },RecommendationPolicy.STREMIO,"Stremio",catalog=true,sourceUrl=source,
                summary=j.optString("description"),sourceName="Cinemeta",released=released,detailUri="stremio:///detail/$type/$id",mediaType=type,genres=genre,imdbId=id,
                releaseYear=date.take(4).toIntOrNull() ?: j.optString("releaseInfo").take(4).toIntOrNull() ?: 0,posterArt=(j.opt("poster") as? String).orEmpty()))
        }
        return result
    }
}