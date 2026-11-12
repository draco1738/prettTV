package fi.jommi.rivi

import android.app.Instrumentation
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Rect
import android.media.tv.TvContract.PreviewPrograms
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.FrameLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Full repository/Android queues and stores; all catalog/provider responses are synthetic.
 * Integration: StartupReliabilityAndroidChecks.run(this, ::test) from StabilityInstrumentation.
 * Does not launch MainActivity, contact public servers, change system settings or measure remote latency. */
internal object StartupReliabilityAndroidChecks {
    private const val NETFLIX="com.netflix.ninja"
    private const val STREMIO="com.stremio.one"
    private const val PLUTO="tv.pluto.android"

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        check(instrumentation.targetContext.packageName=="dev.zane.pretttv.stability")
        test("Cold-cache ContentRepository publishes provider, catalog, sports, episode status and teams before optional artwork") {
            fixture(instrumentation) { f ->
                val catalogGate=f.gate();val artworkGate=f.gate()
                f.sources.catalogGate=catalogGate;f.sources.artworkGate=artworkGate
                PersonalizationStore(f.context).follow(Program("tvmaze:53647","Seed Fixture","","",NETFLIX,"Fixture",mediaType="series"))
                val repo=f.repository()
                check(f.cache.walkTopDown().none { it.isFile } && f.files.walkTopDown().none { it.isFile }) { "Not a cold cache" }
                val started=SystemClock.elapsedRealtime()
                f.main { f.start(repo);repo.requestTeams { f.teams.set(it) } }
                catalogGate.awaitEntered()
                f.waitUntil("ordinary provider publication while catalog is blocked") { f.states.any { it.watching.isNotEmpty() && it.suggestions.isNotEmpty() } }
                val providerMs=SystemClock.elapsedRealtime()-started
                check(f.sources.artworkUrls.isEmpty())
                check(f.states.last().presentationArtwork.isEmpty())
                catalogGate.release.countDown();artworkGate.awaitEntered()
                f.waitUntil("all ordinary feeds before artwork release") {
                    f.teams.get().isNotEmpty() && f.states.any { it.trending.isNotEmpty() && it.newReleases.isNotEmpty() &&
                        it.games.isNotEmpty() && it.episodeStatus.contains("1 followed") && !it.episodeStatus.contains("pending") }
                }
                val ordinaryMs=SystemClock.elapsedRealtime()-started
                val ordinary=f.states.last()
                check(ordinary.presentationArtwork.isEmpty()) { "Artwork gate was bypassed" }
                check(ordinary.watching.map { it.id }==listOf("watch_next_program:1"))
                check((ordinary.suggestions+ordinary.discover).any { it.id=="tvmaze:53647" }) { "Cold-cache seed missing from useful feeds" }
                check(ordinary.trending.map { it.mediaType }==listOf("series","movie")) { "Popular catalog order changed" }
                val candidates=BalancedArtworkPolicy.discoveryCandidates(ordinary,setOf(NETFLIX))
                check(candidates.first().id=="preview_program:2") { "Lower-tier series displaced preferred-service movie" }
                check(f.sources.urls.any { it.endsWith("/shows/53647/episodes") }) { "EpisodeCatalog was not reached" }
                check(f.sources.urls.count { it.contains("/scoreboard") }==SportsCatalog.LEAGUES.size)
                check(f.sources.urls.count { it.contains("/teams?") }==SportsCatalog.LEAGUES.size)
                check(File(f.files,"content/home-v1.bin").isFile) { "Full repository did not persist its cold-cache snapshot" }
                artworkGate.release.countDown()
                f.waitUntil("artwork publication and consumed one-shot pass") { f.states.any { it.presentationArtwork.isNotEmpty() } && !f.pending(repo) }
                f.drain(repo)
                check(ordinaryRows(f.states.last())==ordinaryRows(ordinary)) { "Presentation metadata reordered or mutated ordinary feeds" }
                check(f.sources.artworkUrls.any { it.endsWith("/shows/53647") }) { "One-shot pool missed a cold-cache catalog title" }
                check(f.sources.artworkUrls.size<=PresentationArtworkResolver.REQUESTS)
                val requests=f.sources.artworkUrls.size
                val completed=f.completed(repo)
                f.main { repo.refresh() };f.waitCompleted(repo,completed);f.drain(repo)
                check(f.sources.artworkUrls.size==requests) { "Refresh polled optional metadata after the one-shot pass" }
                f.assertHealthy()
                timing(instrumentation,"cold-cache synthetic feeds", "providerMs=$providerMs allOrdinaryMs=$ordinaryMs totalMs=${SystemClock.elapsedRealtime()-started}; gates, not elapsed thresholds, establish publication order")
            }
        }
        test("Empty artwork pass remains armed until a later eligible catalog arrives without another start") {
            fixture(instrumentation) { f ->
                f.sources.empty=true
                val repo=f.repository();f.main { f.start(repo,empty=true) }
                f.waitCompleted(repo,0);f.drain(repo)
                check(f.states.last().feature==null && f.sources.artworkUrls.isEmpty() && f.pending(repo)) { "Empty pass was consumed" }
                // The server fixture changes; invalidate only its own fresh catalog cache, not production data.
                f.sources.empty=false;f.sources.version=2
                f.cache.resolve("catalog").listFiles().orEmpty().filter { it.name.startsWith("cinemeta-") }.forEach { check(it.delete()) }
                val completed=f.completed(repo)
                f.main { repo.refresh() };f.waitCompleted(repo,completed)
                f.waitUntil("late cold-cache title enrichment") { f.states.any { it.presentationArtwork.isNotEmpty() } && !f.pending(repo) }
                f.drain(repo)
                check(f.states.last().trending.any { it.title=="Series Fixture 2" })
                check(f.sources.artworkUrls.any { it.contains("imdb=tt9000002") })
                val requests=f.sources.artworkUrls.size
                f.sources.version=3
                f.cache.resolve("catalog").listFiles().orEmpty().filter { it.name.startsWith("cinemeta-") }.forEach { check(it.delete()) }
                val before=f.completed(repo)
                f.main { repo.refresh() };f.waitCompleted(repo,before);f.drain(repo)
                check(f.states.last().trending.any { it.title=="Series Fixture 3" })
                check(f.sources.artworkUrls.size==requests) { "New catalog data rearmed an already consumed pass" }
                f.assertHealthy()
            }
        }
        test("Activity stop/start cancels repository work and preserves a usable selected Android fixture widget") {
            fixture(instrumentation) { f ->
                val gate=f.gate();f.sources.artworkGate=gate
                val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,CardFocusTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
                var away:Activity?=null
                var lifecycle:Application.ActivityLifecycleCallbacks?=null
                try {
                    // Actual attached Android widget, not a model-only focus assertion. This is NOT MainActivity.
                    val selected=f.main {
                        val button=Button(activity).apply { isFocusableInTouchMode=true }
                        activity.setContentView(FrameLayout(activity).apply { addView(button,FrameLayout.LayoutParams(600,120)) })
                        button
                    }
                    f.bind={ content -> content.feature?.let { selected.text=it.title;selected.tag=it.id } }
                    val repo=f.repository();val started=SystemClock.elapsedRealtime()
                    f.main { f.start(repo) };gate.awaitEntered()
                    f.waitUntil("visible usable content widget") { f.main { selected.text.isNotBlank() && visible(selected) && activity.hasWindowFocus() } }
                    f.main { check(selected.requestFocusFromTouch()) }
                    val identity=f.main { selected.tag to selected.text.toString() }
                    val readyMs=SystemClock.elapsedRealtime()-started
                    check(identity.first=="watch_next_program:1" && identity.second=="Watching Fixture")
                    val stopped=CountDownLatch(1);val restarted=CountDownLatch(1)
                    lifecycle=object:Application.ActivityLifecycleCallbacks {
                        override fun onActivityStopped(a:Activity) { if(a===activity) { repo.stop();repo.refresh();stopped.countDown() } }
                        override fun onActivityStarted(a:Activity) { if(a===activity) { f.start(repo);restarted.countDown() } }
                        override fun onActivityCreated(a:Activity,b:Bundle?) {}
                        override fun onActivityResumed(a:Activity) {}
                        override fun onActivityPaused(a:Activity) {}
                        override fun onActivitySaveInstanceState(a:Activity,b:Bundle) {}
                        override fun onActivityDestroyed(a:Activity) {}
                    }
                    f.main { activity.application.registerActivityLifecycleCallbacks(lifecycle) }
                    // Real framework transitions, but this wiring belongs to the fixture, not MainActivity.
                    // NEW_TASK can deliver to the existing task root (START_DELIVERED_TO_TOP),
                    // leaving startActivitySync waiting forever for an Activity that is never created.
                    // Launch from this Activity into its ordinary stack and bound the creation wait.
                    val monitor=instrumentation.addMonitor(CardFocusTestActivity::class.java.name,null,false)
                    try {
                        f.main { activity.startActivity(Intent(activity,CardFocusTestActivity::class.java)) }
                        away=monitor.waitForActivityWithTimeout(5_000)
                        check(away!=null && away!==activity) { "A distinct covering fixture Activity was not created" }
                    } finally { instrumentation.removeMonitor(monitor) }
                    check(stopped.await(5,TimeUnit.SECONDS)) { "Fixture Activity never stopped" }
                    val count=f.states.size;val calls=f.sources.artworkUrls.size
                    gate.release.countDown();f.drain(repo)
                    check(f.states.size==count && f.sources.artworkUrls.size==calls) { "Stopped generation delivered or continued optional work" }
                    check(f.main { selected.tag==identity.first && selected.text.toString()==identity.second })
                    check(f.states.last().presentationArtwork.isEmpty()) { "Late artwork leaked into the stopped generation" }
                    val completed=f.completed(repo)
                    f.main { away?.finish() }
                    check(restarted.await(5,TimeUnit.SECONDS)) { "Fixture Activity never restarted" }
                    f.waitCompleted(repo,completed)
                    f.waitUntil("restart enrichment") { !f.pending(repo) && f.states.last().presentationArtwork.isNotEmpty() }
                    f.drain(repo)
                    f.waitUntil("returned fixture window focus") { f.main { activity.hasWindowFocus() } }
                    check(f.sources.artworkUrls.size>calls) { "Cancelled artwork request was not retried" }
                    check(f.main { activity.currentFocus===selected && selected.tag==identity.first && selected.text.toString()==identity.second && visible(selected) })
                    f.assertHealthy()
                    timing(instrumentation,"Activity/repository stop-start attached-widget fixture","usableSelectedWidgetMs=$readyMs; no physical remote, MainActivity lifecycle, or sleep/wake latency claim")
                } finally {
                    f.bind={}
                    f.main {
                        lifecycle?.let { activity.application.unregisterActivityLifecycleCallbacks(it) }
                        away?.finish();activity.finish()
                    };instrumentation.waitForIdleSync()
                }
            }
        }
        StartupReliabilityTransportChecks.run(instrumentation,test)
    }

    private fun ordinaryRows(c:HomeContent)=listOf(c.watching,c.suggestions,c.discover,c.trending,c.newReleases,c.games,c.newEpisodes)
    private fun visible(v:android.view.View)=v.isShown && v.isEnabled && v.getGlobalVisibleRect(Rect()) && v.width>0 && v.height>0
    internal fun timing(i:Instrumentation,name:String,details:String) {
        i.sendStatus(0,Bundle().apply { putString("stream","MEASUREMENT $name: $details\n") })
    }
    private fun fixture(i:Instrumentation,body:(Fixture)->Unit) { val f=Fixture(i);try { body(f) } finally { f.close() } }

    private class Gate {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fun awaitEntered() { check(entered.await(10,TimeUnit.SECONDS)) { "Fixture request was never reached" } }
        fun block() { entered.countDown();check(release.await(15,TimeUnit.SECONDS)) { "Fixture gate was not released" } }
    }

    private class Sources {
        @Volatile var empty=false
        @Volatile var version=1
        @Volatile var providerEmpty=false
        var catalogGate:Gate?=null;var artworkGate:Gate?=null
        val urls=CopyOnWriteArrayList<String>();val artworkUrls=CopyOnWriteArrayList<String>()
        val faults=CopyOnWriteArrayList<String>();val cursors=CopyOnWriteArrayList<Cursor>()
        val offWorker=AtomicInteger()
        fun worker() { if(Looper.myLooper()==Looper.getMainLooper())offWorker.incrementAndGet() }
        fun provider(table:String,projection:Array<String>?):Cursor {
            worker()
            if(table=="channel")return MatrixCursor(projection!!).apply { addRow(arrayOf<Any>(7L,"Fixture recommendations"));cursors+=this }
            return MatrixCursor(arrayOf("_id","package_name","title","thumbnail_uri","intent_uri","channel_id","type",
                "last_playback_position_millis","duration_millis","last_engagement_time_utc_millis","release_date")).apply {
                if(!providerEmpty)when(table) {
                    "watch_next_program" -> addRow(arrayOf<Any>(1,NETFLIX,"Watching Fixture","","fixture://watch",7,PreviewPrograms.TYPE_TV_SERIES,60_000,600_000,100,"2020-01-01"))
                    "preview_program" -> {
                        addRow(arrayOf<Any>(2,NETFLIX,"Preferred Movie Fixture","","fixture://preferred",7,PreviewPrograms.TYPE_MOVIE,0,0,0,"2020-01-01"))
                        addRow(arrayOf<Any>(3,PLUTO,"Fallback Series Fixture","","fixture://fallback",7,PreviewPrograms.TYPE_TV_SERIES,0,0,0,"2020-01-01"))
                    }
                    else -> error("Unexpected fixture provider $table")
                }
                cursors+=this
            }
        }
        fun request(url:String,limit:Int):String {
            worker();urls+=url
            if(urls.size==1)catalogGate?.block()
            val body=when {
                url=="https://api.tvmaze.com/shows/53647" -> show(53647,"Seed Fixture").toString()
                url=="https://api.tvmaze.com/episodes/2382723" -> "{}"
                url=="https://api.tvmaze.com/shows/53647/episodes" -> "[]"
                url.startsWith("https://v3-cinemeta.strem.io/catalog/") -> {
                    val series=url.contains("/series/");val recent=url.contains("/year/")
                    val title="${if(series)"Series" else "Movie"} ${if(recent)"Release" else "Fixture"} $version"
                    val id=if(series)"tt900000$version" else "tt910000$version"
                    val rows=JSONArray()
                    if(!empty)rows.put(JSONObject().put("id",if(recent)id+"0" else id).put("name",title)
                        .put("released",if(recent)LocalDate.now().minusDays(1).toString() else "2020-01-01"))
                    JSONObject().put("metas",rows).toString()
                }
                url.contains("/scoreboard") -> if(empty)"{\"events\":[]}" else JSONObject().put("events",JSONArray().put(JSONObject()
                    .put("id","1").put("name","Synthetic teams fixture").put("date",Instant.ofEpochMilli(System.currentTimeMillis()+3_600_000).toString())
                    .put("status",JSONObject().put("type",JSONObject().put("state","pre")))
                    .put("competitions",JSONArray().put(JSONObject().put("competitors",JSONArray()))))).toString()
                url.contains("/teams?limit=1000") -> """{"sports":[{"leagues":[{"teams":[{"team":{"id":"1","displayName":"Synthetic team"}}]}]}]}"""
                else -> { faults+=url;throw IOException("Unexpected synthetic route: $url") }
            }
            check(body.toByteArray().size<=limit);return body
        }
        fun artwork(url:String,limit:Int):String {
            worker();artworkUrls+=url
            if(artworkUrls.size==1)artworkGate?.block()
            val body=when {
                url.endsWith("/images") -> """[{"type":"poster","main":true,"resolutions":{"original":{"width":600,"height":900,"url":"https://static.tvmaze.com/uploads/images/original_untouched/0/1.jpg"}}}]"""
                url.endsWith("/shows/53647") -> show(53647,"Seed Fixture").toString()
                url.contains("/lookup/shows?imdb=tt900000") -> {
                    val v=url.substringAfter("tt900000").toInt()
                    show(900+v,"Series Fixture $v","tt900000$v").toString()
                }
                url.contains("/search/shows?") -> "[]"
                else -> { faults+=url;throw IOException("Unexpected synthetic artwork route: $url") }
            }
            check(body.toByteArray().size<=limit);return body
        }
        private fun show(id:Int,name:String,imdb:String="")=JSONObject().put("id",id).put("name",name).put("type","Scripted")
            .put("premiered","2020-01-01").put("url","https://www.tvmaze.com/shows/$id/fixture")
            .put("externals",JSONObject().put("imdb",imdb))
    }

    private class Fixture(private val i:Instrumentation) {
        private val base=i.targetContext
        private val id="startup-reliability-${SystemClock.elapsedRealtimeNanos()}"
        private val root=File(base.cacheDir,id).apply { check(mkdirs()) }
        val cache=File(root,"cache").apply { check(mkdirs()) };val files=File(root,"files").apply { check(mkdirs()) }
        private val preferences=Collections.synchronizedSet(mutableSetOf<String>())
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getCacheDir():File=cache
            override fun getFilesDir():File=files
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val isolated="$id-$name";preferences+=isolated
                return base.getSharedPreferences(isolated,mode)
            }
        }
        val sources=Sources();val states=CopyOnWriteArrayList<HomeContent>()
        val teams=AtomicReference<List<SportsTeam>>(emptyList())
        private val offMain=AtomicInteger();private val repositories=mutableListOf<ContentRepository>();private val gates=mutableListOf<Gate>()
        var bind:(HomeContent)->Unit={}
        fun gate()=Gate().also { gates+=it }
        fun repository():ContentRepository=main {
            ContentRepository(context) { content ->
                if(Looper.myLooper()!=Looper.getMainLooper())offMain.incrementAndGet()
                states+=content;bind(content)
            }.also {
                repositories+=it
                it.installStabilityInputs(ContentRepository.StabilityInputs({true},sources::provider,sources::request,
                    PresentationArtworkResolver(File(cache,"presentation/artwork-v1.bin"),fetch=sources::artwork,paceMillis=0)))
            }
        }
        fun start(repo:ContentRepository,empty:Boolean=false) {
            sources.providerEmpty=empty
            repo.start(if(empty)setOf(STREMIO) else setOf(NETFLIX,STREMIO,PLUTO),emptySet(),true,
                preferredPackages=listOf(NETFLIX),services=setOf(NETFLIX),balancedArtwork=true)
        }
        fun <T> main(block:()->T):T {
            val result=AtomicReference<Result<T>>()
            i.runOnMainSync { result.set(runCatching(block)) };return result.get().getOrThrow()
        }
        fun waitUntil(label:String,condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+10_000
            while(!condition()) { check(SystemClock.elapsedRealtime()<deadline) { "Timed out: $label; faults=${sources.faults}" };SystemClock.sleep(10) }
        }
        private fun field(repo:ContentRepository,name:String)=ContentRepository::class.java.getDeclaredField(name).apply { isAccessible=true }.get(repo)
        fun completed(repo:ContentRepository)=field(repo,"completedRefreshes") as Int
        fun pending(repo:ContentRepository)=field(repo,"artworkPassPending") as Boolean
        fun waitCompleted(repo:ContentRepository,before:Int)=waitUntil("completed repository refresh") { completed(repo)>before }
        fun drain(repo:ContentRepository) {
            val done=CountDownLatch(1)
            check((field(repo,"worker") as Handler).post { done.countDown() })
            check(done.await(10,TimeUnit.SECONDS)) { "Content worker did not drain" };i.waitForIdleSync()
        }
        fun assertHealthy() {
            check(sources.faults.isEmpty()) { "Unexpected fixture routes: ${sources.faults}" }
            check(offMain.get()==0 && sources.offWorker.get()==0) { "Repository crossed the wrong Android queue" }
            check(sources.cursors.all { it.isClosed }) { "Provider cursor leaked" }
            check(repositories.all { it.diagnostics().contains("error=none sharedFailures=none snapshot=saved") })
        }
        fun close() {
            gates.forEach { it.release.countDown() }
            main { repositories.forEach { it.close() } }
            val threads=repositories.map { field(it,"thread") as HandlerThread }
            threads.forEach { it.join(10_000) }
            i.waitForIdleSync()
            try { check(threads.none { it.isAlive }) { "Content worker leaked after close" } }
            finally {
                synchronized(preferences) { preferences.forEach { name ->
                    // Flush pending apply() operations before removing only the unique fixture store.
                    check(base.getSharedPreferences(name,Context.MODE_PRIVATE).edit().clear().commit())
                    check(base.deleteSharedPreferences(name))
                } }
                check(root.deleteRecursively())
            }
        }
    }
}
