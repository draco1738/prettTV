package fi.jommi.rivi

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.media.tv.TvContract.PreviewPrograms
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** All queries and catalog work stay off the UI thread. Observers exist only while Home is visible. */
class ContentRepository(private val context: Context, private val deliver: (HomeContent) -> Unit) {
    private val thread = HandlerThread("pretttv-content").apply { start() }
    private val worker = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val dir = File(context.cacheDir, "catalog").apply { mkdirs() }
    @Volatile private var generation = 0
    @Volatile private var active = false
    private var installed = emptySet<String>()
    private var hidden = emptySet<String>()
    private var discovery = true
    private var preferred = emptyList<String>()
    private var serviceOverride:Set<String>?=null
    private var observing = false
    private var games=emptyList<SportsEvent>()
    private var lastCatalog=CatalogSnapshot()
    private val sports=SportsCatalog(dir,::get)
    private val playback=PlaybackHistory(context)
    @Volatile private var feedSummary="Feeds have not loaded yet"
    @Volatile private var completedRefreshes=0
    @Volatile private var refreshError="none"
    @Volatile var recommendationServices=emptySet<String>(); private set
    @Volatile var availableServices=emptySet<String>(); private set
    fun diagnostics():String="REFRESH completed=$completedRefreshes error=$refreshError\n$feedSummary"
    private val observer = object : ContentObserver(ui) { override fun onChange(selfChange: Boolean) { refresh() } }
    private val refreshTask = Runnable { load() }

    fun start(packages: Set<String>, hiddenPackages: Set<String>, catalogEnabled: Boolean,preferredPackages:List<String> = emptyList(),services:Set<String>?=null) {
        installed = packages.toSet(); hidden = hiddenPackages.toSet(); discovery = catalogEnabled; active = true
        preferred=preferredPackages
        serviceOverride=services?.toSet()
        if (!observing) {
            for (name in listOf("watch_next_program", "preview_program", "channel")) {
                try { context.contentResolver.registerContentObserver(Uri.parse("content://android.media.tv/$name"), true, observer) } catch (_: Exception) { }
            }
            observing = true
        }
        refresh()
    }

    fun stop() {
        active = false; generation++; ui.removeCallbacks(refreshTask); worker.removeCallbacksAndMessages(null)
        if (observing) { try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Exception) { }; observing = false }
    }
    fun close() { stop(); thread.quitSafely() }
    fun refresh() { if (active) { generation++; ui.removeCallbacks(refreshTask); ui.postDelayed(refreshTask, 220) } }

    private fun load() {
        val g = ++generation; val available = installed; val excluded = hidden; val useCatalog = discovery
        val priority=preferred; val selectedServices=serviceOverride
        worker.removeCallbacksAndMessages(null)
        worker.post {
            fun current() = active && g == generation
            try {
            val channels=sharedChannels()
            val published = query("preview_program", available, channels)
            val watching = (query("watch_next_program", available) + published.filter { it.watchingSource }).sortedByDescending { it.engagedAt }
            val previews = published.filterNot { it.watchingSource }
            val services=(selectedServices ?: RecommendationPolicy.inferredServices(watching)).intersect(available)
            recommendationServices=services
            availableServices=(watching+previews).map { it.packageName }.toSet()+RecommendationPolicy.knownServices.intersect(available)
            val sources=previews.groupBy { it.packageName }.entries.joinToString("\n") { (pkg,rows) ->
                "FEED $pkg count=${rows.size} channels="+rows.groupingBy { it.sourceName.ifBlank { "Unlabeled" } }.eachCount().entries.joinToString { "${it.key}:${it.value}" }+
                    " genres="+rows.map { it.genres }.filter { it.isNotBlank() }.distinct().take(8).joinToString(";")
            }
            var snapshot=lastCatalog.eligible(useCatalog,available,excluded)
            fun publish() {
                if (!current()) return
                val catalogRows=if(previews.size<4)snapshot.seeds else emptyList()
                val catalogBrowse=snapshot.browse()
                val releases=snapshot.recent(System.currentTimeMillis())
                val cleanWatching=ContentPolicy.clean(watching,excluded)
                val cleanReleases=ContentPolicy.clean(releases,excluded)
                val rows=RecommendationPolicy.build(previews,catalogRows+catalogBrowse,catalogBrowse,cleanWatching,cleanReleases,services,priority,excluded)
                val result = HomeContent(watching=playback.merge(cleanWatching),suggestions=rows.suggestions,
                    trending=rows.trending,newReleases=cleanReleases,discover=rows.discover,
                    liveSports=ContentPolicy.clean(published.filter { it.liveSports },excluded),
                    games=if(useCatalog)SportsPolicy.upcoming(games,System.currentTimeMillis()) else emptyList())
                feedSummary="SERVICES ${if(selectedServices==null)"viewing-activity" else "manual"}: "+services.joinToString()+"\n"+sources+"\n"+
                    listOf("Suggestions" to rows.suggestions,"Discover" to rows.discover,"Trending" to rows.trending).joinToString("\n") { (name,items) ->
                        "ROW $name count=${items.size} providers="+items.groupingBy { it.packageName }.eachCount()+
                            " first="+items.take(8).joinToString { "${it.packageName}:${it.mediaType}" }
                    }
                ui.post { if (active && g == generation) deliver(result) }
            }
            publish()
            // General discovery is separate from personal history. One cache refresh per day.
            if (useCatalog && previews.size < 4) for (seed in SEEDS) {
                if (!active || g != generation) break
                if (seed.pkg !in available || seed.pkg in excluded) continue
                try { catalog(seed)?.let { card ->
                    if(current()) {
                        snapshot=snapshot.copy(seeds=(snapshot.seeds.filterNot { it.id==card.id }+card))
                        lastCatalog=snapshot
                        publish()
                    }
                } } catch (_: Exception) { }
            }
            if(useCatalog && STREMIO in available && STREMIO !in excluded && active && g==generation) {
                val year=java.time.LocalDate.now().year
                for(type in listOf("series","movie")) {
                    if(!active || g!=generation)break
                    val popular=cinemeta(type,"top","")
                    if(!current())break
                    val recent=cinemeta(type,"year","genre=$year")
                    if(!current())break
                    snapshot=snapshot.updated(type,popular,recent)
                }
                // Preserve the provider's Popular ordering, alternating series and films.
                // Reuse all fetched entries for app-title popularity matching and discovery.
                if(current()) { lastCatalog=snapshot; publish() }
            }
            if(useCatalog && active && g==generation) {
                val freshGames=sports.load { current() }
                if(current()) { games=freshGames; publish() }
            }
            if(current()) { completedRefreshes++; refreshError="none" }
            } catch(e:Exception) {
                if(current())refreshError=e.javaClass.simpleName
                android.util.Log.w("PrettTvCatalog","Refresh failed: ${e.javaClass.simpleName}")
            } finally {
                ui.post { if(current()) { ui.removeCallbacks(refreshTask); ui.postDelayed(refreshTask,120_000) } }
            }
        }
    }

    private fun sharedChannels():Map<Long,String> {
        val channels=HashMap<Long,String>()
        try {
            context.contentResolver.query(Uri.parse("content://android.media.tv/channel"),arrayOf("_id","display_name"),null,null,null)?.use { c ->
                while(c.moveToNext()) {
                    channels[c.getLong(0)]=c.getString(1).orEmpty()
                }
            }
        } catch(_:Exception) { }
        return channels
    }

    private fun query(table: String, available: Set<String>,channels:Map<Long,String> = emptyMap()): List<Program> {
        val out = ArrayList<Program>()
        try {
            context.contentResolver.query(Uri.parse("content://android.media.tv/$table"), null, null, null, null)?.use { c ->
                fun str(key: String): String { val i=c.getColumnIndex(key); return if (i>=0 && !c.isNull(i)) c.getString(i).orEmpty() else "" }
                fun number(key: String): Long { val i=c.getColumnIndex(key); return if (i>=0 && !c.isNull(i)) c.getLong(i) else 0 }
                var scanned=0
                val counts=HashMap<String,Int>()
                while (c.moveToNext() && scanned++ < 5000 && out.size < 2000) {
                    val pkg=str("package_name"); val title=str("title")
                    if (pkg !in available || title.isBlank()) continue
                    if((counts[pkg] ?: 0)>=300)continue
                    counts[pkg]=(counts[pkg] ?: 0)+1
                    if (table == "watch_next_program" && number("watch_next_type") == 3L) continue
                    val app = try { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg,0)).toString() } catch (_: Exception) { pkg }
                    val position=number("last_playback_position_millis"); val duration=number("duration_millis")
                    val episode=str("episode_display_number"); val season=str("season_display_number")
                    val detail= listOfNotNull(app, if (episode.isNotBlank()) "S${season.ifBlank { "1" }} E$episode" else null,
                        if (duration>position && position>0) "${(duration-position)/60000} min left" else null).joinToString(" · ")
                    val kind=when(str("type").toIntOrNull()) {
                        PreviewPrograms.TYPE_TV_SERIES,PreviewPrograms.TYPE_TV_SEASON,PreviewPrograms.TYPE_TV_EPISODE -> "series"
                        PreviewPrograms.TYPE_MOVIE -> "movie"
                        else -> if(episode.isNotBlank() || season.isNotBlank())"series" else "unknown"
                    }
                    val sports=number("live")==1L && (str("canonical_genre")+" "+str("genre")).contains("SPORT",ignoreCase=true) &&
                        (number("end_time_utc_millis")==0L || number("end_time_utc_millis")>System.currentTimeMillis())
                    val channel=channels[number("channel_id")].orEmpty()
                    val continuing=Regex("continue|watching|resume",RegexOption.IGNORE_CASE).containsMatchIn(channel)
                    out.add(Program("$table:${str("_id")}",title,detail,str("thumbnail_uri").ifBlank { str("poster_art_uri") },pkg,app,str("intent_uri"),position,duration,summary=str("short_description"),watchingSource=table=="watch_next_program" || continuing || position>0,engagedAt=number("last_engagement_time_utc_millis"),mediaType=kind,liveSports=sports,sourceName=channel,genres=str("genre").ifBlank { str("canonical_genre") },providerWeight=number("weight")))
                }
            }
        } catch (_: Exception) { }
        return out.sortedByDescending { it.resume }
    }

    private fun catalog(seed: Seed): Program? {
        val file=File(dir,"${seed.id}.json")
        var json: JSONObject? = try { if (file.exists()) JSONObject(file.readText()) else null } catch (_: Exception) { null }
        if (json == null || json.optInt("pretttvArtVersion") != 2 || System.currentTimeMillis()-file.lastModified()>86_400_000L) {
            try {
                val fresh=JSONObject(get("https://api.tvmaze.com/shows/${seed.id}", 250_000))
                // Official episode stills supply photographic 16:9 scenes rather than title posters.
                // These are artwork references for each show, never claims about a user's episode.
                val episode=JSONObject(get("https://api.tvmaze.com/episodes/${seed.episode}",250_000))
                fresh.put("pretttvLandscape",episode.optJSONObject("image")?.optString("original").orEmpty())
                fresh.put("pretttvArtVersion",2)
                val tmp=File(dir,"${seed.id}.tmp"); tmp.writeText(fresh.toString()); if (!tmp.renameTo(file)) { file.writeText(fresh.toString()); tmp.delete() }
                json=fresh
            } catch (_: Exception) { }
        }
        val j=json ?: return null
        val art=j.optString("pretttvLandscape").ifBlank { j.optJSONObject("image")?.optString("original").orEmpty() }
        val genres=j.optJSONArray("genres")
        val genre=if (genres != null && genres.length()>0) genres.optString(0) else "Series"
        val summary=android.text.Html.fromHtml(j.optString("summary"),android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
        return Program("tvmaze:${seed.id}",j.optString("name"),"$genre · ${seed.app}",art,seed.pkg,seed.app,catalog=true,sourceUrl=j.optString("url"),summary=summary,sourceName="TVmaze",mediaType="series",genres=genre)
    }

    private fun cinemeta(type:String,catalog:String,extra:String):List<Program>? {
        val file=File(dir,"cinemeta-$type-$catalog-$extra.json")
        var data=try { if(file.exists())JSONObject(file.readText()) else null } catch(_:Exception) { null }
        if(data==null || System.currentTimeMillis()-file.lastModified()>21_600_000L) {
            try {
                val suffix=if(extra.isBlank())"" else "/$extra"
                val fresh=JSONObject(get("https://v3-cinemeta.strem.io/catalog/$type/$catalog$suffix.json",1_500_000))
                file.writeText(fresh.toString()); data=fresh
            } catch(e:Exception) { android.util.Log.w("PrettTvCatalog","$type/$catalog: ${e.javaClass.simpleName}: ${e.message}") }
        }
        val rows=data?.optJSONArray("metas") ?: return null
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
                j.optString("background").ifBlank { j.optString("poster") },STREMIO,"Stremio",catalog=true,sourceUrl=source,
                summary=j.optString("description"),sourceName="Cinemeta",released=released,detailUri="stremio:///detail/$type/$id",mediaType=type,genres=genre))
        }
        return result
    }

    private fun get(url: String, limit: Int): String {
        var address=URL(url)
        repeat(4) {
        if(address.protocol!="https" || address.userInfo!=null)throw java.io.IOException("Unsupported catalog address")
        val con=address.openConnection() as HttpURLConnection
        try {
            con.connectTimeout=4000; con.readTimeout=4000; con.instanceFollowRedirects=false
            con.setRequestProperty("User-Agent","prettTV/0.1.1")
            val status=con.responseCode
            if(status in listOf(301,302,303,307,308)) {
                address=URL(address,con.getHeaderField("Location") ?: throw java.io.IOException("Missing redirect"))
            } else {
                if(status!=200)throw java.io.IOException("Catalog HTTP $status")
                val bytes=con.inputStream.use { it.readBytesCapped(limit) }
                return bytes.toString(Charsets.UTF_8)
            }
        } finally { con.disconnect() }
        }
        throw java.io.IOException("Too many catalog redirects")
    }

    private data class Seed(val id:Int,val pkg:String,val app:String,val episode:Int)
    companion object {
        private const val STREMIO="com.stremio.one"
        private val SEEDS=listOf(
            Seed(44933,"com.apple.atve.androidtv.appletv","Apple TV",2238231),
            Seed(52341,"com.disney.disneyplus","Disney+",2333948),
            Seed(46562,"com.wbd.stream","Max",2423934),
            Seed(53647,"com.netflix.ninja","Netflix",2382723),
            Seed(54198,"com.hulu.livingroomplus","Hulu",2328025),
            Seed(45039,"com.apple.atve.androidtv.appletv","Apple TV",2270552))
    }
}

internal fun java.io.InputStream.readBytesCapped(limit:Int):ByteArray {
    val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while (true) { val count=read(buffer); if(count<0)break; if(out.size()+count>limit)throw java.io.IOException("Response too large"); out.write(buffer,0,count) }
    return out.toByteArray()
}
