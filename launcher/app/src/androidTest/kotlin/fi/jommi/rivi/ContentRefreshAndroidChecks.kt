package fi.jommi.rivi

import android.content.Context
import android.database.MatrixCursor
import android.media.tv.TvContract.PreviewPrograms
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** JSON faults use isolated cache files and injected responses, never the owner's feeds or network. */
object ContentRefreshAndroidChecks {
    fun run(context:Context,test:(String,()->Unit)->Unit) {
        test("Published and watch-next feeds retain supplied posters without changing thumbnail art or metadata") {
            for(table in listOf("preview_program","watch_next_program")) {
                MatrixCursor(arrayOf("_id","package_name","title","thumbnail_uri","poster_art_uri","intent_uri","channel_id",
                    "last_playback_position_millis","duration_millis","last_engagement_time_utc_millis","type","genre","release_date","weight","watch_next_type")).use { cursor ->
                    cursor.addRow(arrayOf(1,"fixture.app","Fixture","content://fixture/scene","content://fixture/poster","fixture://play",7,60_000,600_000,100,
                        PreviewPrograms.TYPE_TV_SERIES,"Drama","2026-01-01",8,0))
                    cursor.addRow(arrayOf(2,"removed.app","Excluded","scene","poster","",7,0,0,0,0,"","",0,0))
                    val p=ProgramFeedParser.provider(cursor,table,setOf("fixture.app"),mapOf(7L to "Continue watching")) { "Fixture app" }.single()
                    check(p.art=="content://fixture/scene" && p.posterArt=="content://fixture/poster")
                    check(p.id=="$table:1" && p.intentUri=="fixture://play" && p.packageName=="fixture.app")
                    check(p.sourceName=="Continue watching" && p.watchingSource && p.resume && p.engagedAt==100L)
                    check(p.mediaType=="series" && p.genres=="Drama" && p.releaseYear==2026 && p.providerWeight==8L)
                }
            }
        }
        test("Provider poster is optional and never inferred from a thumbnail") {
            for(poster in listOf(null,"","content://fixture/poster")) {
                MatrixCursor(arrayOf("_id","package_name","title","thumbnail_uri","poster_art_uri")).use { cursor ->
                    cursor.addRow(arrayOf(1,"fixture.app","Fixture","",poster))
                    val p=ProgramFeedParser.provider(cursor,"preview_program",setOf("fixture.app"),emptyMap()) { "Fixture app" }.single()
                    check(p.posterArt==poster.orEmpty() && p.art==poster.orEmpty())
                }
            }
            MatrixCursor(arrayOf("_id","package_name","title","thumbnail_uri")).use { cursor ->
                cursor.addRow(arrayOf(1,"fixture.app","Fixture","content://fixture/thumbnail"))
                val p=ProgramFeedParser.provider(cursor,"preview_program",setOf("fixture.app"),emptyMap()) { "Fixture app" }.single()
                check(p.posterArt.isEmpty() && p.art=="content://fixture/thumbnail")
            }
        }
        test("TVmaze keeps episode scenes and separately selects supplied original or medium show posters") {
            val json=JSONObject().put("name","Fixture").put("url","https://example.test/show").put("summary","<p>Synopsis</p>")
                .put("pretttvLandscape","https://example.test/episode").put("genres",JSONArray().put("Drama"))
                .put("premiered","2026-01-01").put("externals",JSONObject().put("imdb","tt1234567"))
            val image=JSONObject().put("original","https://example.test/original").put("medium","https://example.test/medium")
            json.put("image",image)
            fun parsed()=ProgramFeedParser.tvmaze(json,1,"fixture.app","Fixture app")
            val p=parsed()
            check(p.art=="https://example.test/episode" && p.posterArt=="https://example.test/original")
            check(p.catalog && p.sourceName=="TVmaze" && p.sourceUrl=="https://example.test/show" && p.summary=="Synopsis")
            check(p.imdbId=="tt1234567" && p.releaseYear==2026 && p.mediaType=="series")
            for(missing in listOf("",JSONObject.NULL,42)) {
                image.put("original",missing)
                check(parsed().posterArt=="https://example.test/medium" && parsed().art==p.art)
            }
            image.remove("original"); check(parsed().posterArt=="https://example.test/medium")
            json.put("image",JSONObject.NULL); check(parsed().posterArt.isEmpty() && parsed().art==p.art)
            json.remove("image"); check(parsed().posterArt.isEmpty() && parsed().art==p.art)
            image.put("original","https://example.test/original"); json.put("image",image); json.remove("pretttvLandscape")
            check(parsed().art=="https://example.test/original" && parsed().posterArt=="https://example.test/original")
        }
        test("Cinemeta keeps backgrounds and optional posters for both series and movie catalogs") {
            for(type in listOf("series","movie"))for(catalog in listOf("top","year")) {
                val json=JSONObject().put("id","tt1234567").put("name","Fixture").put("background","https://example.test/background")
                    .put("poster","https://example.test/poster").put("released","2026-01-01").put("genres",JSONArray().put("Drama"))
                fun parsed()=ProgramFeedParser.cinemeta(JSONArray().put(json),type,catalog).single()
                val p=parsed()
                check(p.art=="https://example.test/background" && p.posterArt=="https://example.test/poster")
                check(p.sourceName=="Cinemeta" && p.mediaType==type && p.catalog && p.packageName==RecommendationPolicy.STREMIO)
                check(p.sourceUrl=="https://v3-cinemeta.strem.io/meta/$type/tt1234567.json" && p.detailUri=="stremio:///detail/$type/tt1234567")
                check(p.released>0 && p.releaseYear==2026 && p.genres=="Drama")
                for(missing in listOf("",JSONObject.NULL,42)) {
                    json.put("poster",missing); check(parsed().posterArt.isEmpty() && parsed().art==p.art)
                }
                json.remove("poster"); check(parsed().posterArt.isEmpty() && parsed().art==p.art)
                json.put("poster","https://example.test/poster").remove("background")
                check(parsed().art==p.posterArt && parsed().posterArt==p.posterArt)
            }
        }
        val dir=File(context.cacheDir,"refresh-status-checks").apply { mkdirs() }
        fun clean(folder:File) { folder.listFiles()?.forEach { check(it.isFile); it.delete() }; folder.delete() }
        test("malformed sports service response reports failure without replacing valid cache") {
            val folder=File(dir,"sports-fault").apply { mkdirs() }
            var failures=0
            val original="{\"events\":[]}"
            for((path,_) in SportsCatalog.LEAGUES)File(folder,"sports-${path.replace('/','-')}.json").apply {
                writeText(original); check(setLastModified(System.currentTimeMillis()-180_000))
            }
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> "{\"events\":\"invalid\"}" },{ failures++ })
                check(catalog.load { true }.isEmpty())
                check(failures==SportsCatalog.LEAGUES.size)
                check(folder.listFiles().orEmpty().all { it.readText()==original })
            } finally { clean(folder) }
        }
        test("a legitimately empty sports feed is successful and not a refresh error") {
            val folder=File(dir,"sports-empty").apply { mkdirs() }
            var failures=0
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> "{\"events\":[]}" },{ failures++ })
                check(catalog.load { true }.isEmpty())
                check(failures==0)
            } finally { clean(folder) }
        }
        test("cancellation during a source request does not publish a failure") {
            val folder=File(dir,"sports-cancel").apply { mkdirs() }
            var failures=0; var active=true
            try {
                val catalog=SportsCatalog(folder,{ _,_ -> active=false; throw java.io.IOException("Cancelled fixture") },{ failures++ })
                check(catalog.load { active }.isEmpty())
                check(failures==0 && folder.listFiles().orEmpty().isEmpty())
            } finally { clean(folder) }
        }
        test("malformed episode response reports failure and preserves the retry policy") {
            val file=File(dir,"episode-fault.bin").apply { delete() }
            var failures=0; var requests=0
            val now=System.currentTimeMillis()
            val follow=FollowedShow("fixture",Program("tvmaze:12","Fixture","","","fixture.app","Fixture",mediaType="series"),now)
            try {
                val catalog=EpisodeCatalog(file,{ _,_ -> requests++; "malformed" },{ failures++ })
                val result=catalog.load(listOf(follow),now) { true }
                check(failures==1 && requests==1 && result.alerts.isEmpty() && result.status.contains("retry tomorrow"))
                catalog.load(listOf(follow),now+1) { true }
                check(failures==1 && requests==1)
            } finally { file.delete() }
        }
        dir.delete()
    }
}
