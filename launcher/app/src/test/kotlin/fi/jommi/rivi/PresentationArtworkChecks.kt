package fi.jommi.rivi

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Normal Gradle/CI JVM regressions; the scoped runner also supports offline API evidence replay. */
class PresentationArtworkChecks {
    private val now=1_800_000_000_000L
    private fun p(title:String="Example",id:String="provider:1",year:Int=2020,type:String="series") =
        Program(id,title,"supplied detail","provider-wide","service","Service",intentUri="service://original",position=20,duration=100,
            mediaType=type,releaseYear=year,posterArt="provider-wide")
    private fun show(id:Int=10,name:String="Example",year:Int=2020,type:String="Scripted",imdb:String="tt1234567") =
        JSONObject().put("id",id).put("name",name).put("premiered","$year-01-01").put("type",type)
            .put("url","https://www.tvmaze.com/shows/$id/example").put("externals",JSONObject().put("imdb",imdb))
    private fun search(vararg shows:JSONObject)=JSONArray(shows.map { JSONObject().put("show",it) }).toString()
    private fun image(type:String="poster",w:Int=1000,h:Int=1500,url:String="https://static.tvmaze.com/uploads/images/original_untouched/1/2.jpg",main:Boolean=true)=
        JSONObject().put("type",type).put("main",main).put("resolutions",JSONObject().put("original",JSONObject().put("width",w).put("height",h).put("url",url)))
    private fun images()=JSONArray(listOf(image(),image("background",1920,1080,"https://static.tvmaze.com/uploads/images/original_untouched/1/3.jpg"))).toString()
    private fun file()=File(Files.createTempDirectory(File(requireNotNull(System.getProperty("java.io.tmpdir"))).toPath(),"art-test-").toFile(),"art.bin")
    private fun resolver(file:File=file(),fetch:(String,Int)->String)=PresentationArtworkResolver(file,fetch,paceMillis=0)

    @Test fun presentationDoesNotChangeProgramsOrPersistenceFields() {
        val original=p(); val content=HomeContent(suggestions=listOf(original))
        assertTrue(content.presentationArtwork.isEmpty())
        val changed=content.copy(presentationArtwork=mapOf(original.id to PresentationArtwork("portrait","background","source")))
        assertEquals(original,changed.suggestions.single())
        assertEquals("provider-wide",changed.suggestions.single().art)
        assertEquals("service://original",changed.suggestions.single().intentUri)
        assertEquals(20L,changed.suggestions.single().position)
    }
    @Test fun titleYearAndTypeAreIdentityBoundaries() {
        val a=p(); assertNotEquals(BalancedArtworkPolicy.cacheKey(a),BalancedArtworkPolicy.cacheKey(a.copy(releaseYear=2021)))
        assertNotEquals(BalancedArtworkPolicy.cacheKey(a),BalancedArtworkPolicy.cacheKey(a.copy(title="Other")))
        assertNull(BalancedArtworkPolicy.cacheKey(a.copy(mediaType="movie")))
        assertNull(BalancedArtworkPolicy.cacheKey(a.copy(mediaType="unknown")))
        assertNull(BalancedArtworkPolicy.cacheKey(a.copy(imdbId="TT1234567")))
        assertNull(BalancedArtworkPolicy.cacheKey(a.copy(imdbId=" tt1234567")))
        assertNull(BalancedArtworkPolicy.cacheKey(a.copy(id="tvmaze:0010")))
    }
    @Test fun exactMatchingRejectsFuzzyTitlesRemakesAndConflictingIds() {
        assertNull(TvmazeArtworkParser.match(p(),search(show(name="Examples"))))
        assertNull(TvmazeArtworkParser.match(p(),search(show(year=2000))))
        assertNull(TvmazeArtworkParser.match(p(year=0),search(show(),show(id=11,year=2000))))
        assertNull(TvmazeArtworkParser.match(p(),search(show(),show(id=11))))
        assertEquals(10,TvmazeArtworkParser.match(p(title=" EXAMPLE "),search(show()))?.id)
        assertEquals(10,TvmazeArtworkParser.match(p(year=0),search(show()))?.id)
        assertEquals(10,TvmazeArtworkParser.match(p(),search(show(),show(id=11,year=2000)))?.id)
        assertNull(TvmazeArtworkParser.direct(p(id="tvmaze:10"),show(id=11).toString()))
        assertNull(TvmazeArtworkParser.direct(p().copy(imdbId="tt1234567"),show(imdb="tt7654321").toString()))
        assertNull(TvmazeArtworkParser.direct(p(id="tvmaze:10"),show(name="Wrong title").toString()))
    }
    @Test fun parserRejectsSportsAndDoesNotClaimUniquenessFromTruncation() {
        assertNull(TvmazeArtworkParser.match(p(),search(show(type="Sports"))))
        val oversized=JSONArray((1..101).map { JSONObject().put("show",show(id=it)) })
        assertNull(TvmazeArtworkParser.match(p(),oversized.toString()))
        assertNull(TvmazeArtworkParser.match(p(),"[{\"show\":null},{\"bad\":true}]"))
        assertNull(TvmazeArtworkParser.match(p(),search(show().put("id","10"))))
    }
    @Test fun portraitAndBackgroundRequireTypedDimensionsAndTrustedUrls() {
        val selected=TvmazeArtworkParser.images(10,"https://www.tvmaze.com/shows/10/example",images())
        assertTrue(selected.poster.isNotBlank()); assertTrue(selected.backdrop.isNotBlank())
        for(bad in listOf(image(w=1920,h=1080),image(type="banner"),image(w=0),image(url="http://static.tvmaze.com/a.jpg"),
            image(url="https://evil.example/a.jpg"),image(url="https://static.tvmaze.com@evil.example/a.jpg"),image(url="https://static.tvmaze.com:8443/a.jpg"))) {
            assertEquals("",TvmazeArtworkParser.images(10,"https://www.tvmaze.com/shows/10/example",JSONArray(listOf(bad)).toString()).poster)
        }
        assertEquals("",TvmazeArtworkParser.images(10,"https://www.tvmaze.com/shows/11/wrong",images()).poster)
        assertEquals("",TvmazeArtworkParser.images(10,"https://www.tvmaze.com/shows/10/example","[null,{}]").poster)
    }
    @Test fun exactTwoByThreeWinsOverThreeByFourMainPoster() {
        val exact="https://static.tvmaze.com/uploads/images/original_untouched/1/4.jpg"
        val rows=JSONArray(listOf(image(w=900,h=1200),image(w=1000,h=1500,url=exact,main=false)))
        assertEquals(exact,TvmazeArtworkParser.images(10,"https://www.tvmaze.com/shows/10/example",rows.toString()).poster)
    }
    @Test fun candidatePoolIsBoundedRankedAndExcludesFeatureAndSports() {
        val hero=p("Hero","hero").copy(engagedAt=1)
        val rows=(1..12).map { p("Show $it","p$it").copy(providerWeight=(20-it).toLong()) }
        val sport=p("Teams","sport").copy(genres="SPORTS")
        val live=p("Live","live").copy(liveSports=true)
        val content=HomeContent(watching=listOf(hero),suggestions=listOf(sport,live)+rows+hero)
        val candidates=BalancedArtworkPolicy.discoveryCandidates(content,setOf("service"))
        assertEquals(6,candidates.size); assertEquals(rows.take(6),candidates)
        val artwork=rows.take(3).associate { it.id to PresentationArtwork("poster") }
        assertEquals(rows.take(3),BalancedArtworkPolicy.discovery(content.copy(presentationArtwork=artwork),setOf("service")))
    }
    @Test fun feedbackAndServicePriorityRemainEffectiveWithinSeriesPool() {
        val a=p("A","a").copy(packageName="priority",providerWeight=1)
        val b=p("B","b").copy(packageName="other",providerWeight=100)
        val c=p("C","c").copy(packageName="priority",providerWeight=2)
        val content=HomeContent(suggestions=listOf(a,b,c),hiddenHeroTitles=setOf(a.titleKey,b.titleKey,c.titleKey))
        assertEquals(listOf(c,a,b),BalancedArtworkPolicy.discoveryCandidates(content,setOf("priority")))
        val feedback=RecommendationFeedback(dismissed=setOf(ContentIdentity.key(c)))
        assertEquals(listOf(a,b),BalancedArtworkPolicy.discoveryCandidates(content,setOf("priority"),feedback=feedback))
    }
    @Test fun portraitAvailabilityAndSeriesPreferenceNeverOverrideServiceTiers() {
        val paid=p("Paid movie","paid",type="movie").copy(packageName="com.netflix.ninja")
        val fallback=p("Fallback series","fallback").copy(packageName=RecommendationPolicy.STREMIO)
        val content=HomeContent(suggestions=listOf(paid,fallback),hiddenHeroTitles=setOf(paid.titleKey,fallback.titleKey),
            presentationArtwork=mapOf(fallback.id to PresentationArtwork("verified-poster")))
        assertEquals(listOf(paid,fallback),BalancedArtworkPolicy.discoveryCandidates(content,setOf(paid.packageName)))
        assertEquals(listOf(paid,fallback),BalancedArtworkPolicy.discovery(content,setOf(paid.packageName)))
    }
    @Test fun threePostersStopTheBoundedCandidateScanAndKeepProviderIdentity() {
        var calls=0
        val r=resolver { url,_ -> calls++; if(url.endsWith("/images"))images() else {
            val title=java.net.URLDecoder.decode(url.substringAfter("?q="),"UTF-8")
            search(show(name=title))
        } }
        val rows=(1..6).map { p("Show $it","p$it") }
        val result=r.enrich(rows,null,now,true,{true})
        assertEquals(6,calls); assertEquals(rows.take(3).map { it.id }.toSet(),result.keys)
        assertTrue(result.values.all { it.poster.isNotBlank() })
        assertEquals("provider-wide",rows.first().art)
    }
    @Test fun positiveAndNegativeCacheSurviveReloadAndExpireIndependently() {
        val f=file(); var calls=0
        val first=resolver(f) { url,_ -> calls++; if(url.endsWith("/images"))images() else search(show()) }
        assertTrue(first.enrich(listOf(p()),null,now,true,{true}).isNotEmpty()); assertEquals(2,calls)
        val loaded=resolver(f) { _,_ -> calls++; error("Fresh cache must not request") }
        assertTrue(loaded.enrich(listOf(p()),null,now+1,true,{true}).isNotEmpty()); assertEquals(2,calls)
        assertTrue(loaded.cached(listOf(p()),now+PresentationArtworkResolver.POSITIVE_TTL+1).isEmpty())
        val negative=file(); var misses=0
        resolver(negative) { _,_ -> misses++; "[]" }.enrich(listOf(p()),null,now,true,{true})
        resolver(negative) { _,_ -> misses++; "[]" }.enrich(listOf(p()),null,now+1,true,{true})
        assertEquals(1,misses)
        resolver(negative) { _,_ -> misses++; "[]" }.enrich(listOf(p()),null,now+PresentationArtworkResolver.NEGATIVE_TTL+1,true,{true})
        assertEquals(2,misses)
    }
    @Test fun lowerRankedCachedPostersCannotSuppressAHigherRankedNewCandidate() {
        var calls=0
        val r=resolver { url,_ -> calls++; if(url.endsWith("/images"))images() else {
            search(show(name=java.net.URLDecoder.decode(url.substringAfter("?q="),"UTF-8")))
        } }
        val old=(1..3).map { p("Old $it","old$it") }
        r.enrich(old,null,now,true,{true}); assertEquals(6,calls)
        val newer=p("New priority","new")
        val result=r.enrich(listOf(newer)+old,null,now+1,true,{true})
        assertEquals(8,calls); assertTrue(result[newer.id]?.poster?.isNotBlank()==true)
    }
    @Test fun networkOffUsesCacheOnlyAndCancelledResponsesCannotPublishOrPoisonCache() {
        var active=true; var deliveries=0; val f=file(); var calls=0
        val r=resolver(f) { _,_ -> calls++; active=false; search(show()) }
        assertTrue(r.enrich(listOf(p()),null,now,false,{true}) { deliveries++ }.isEmpty()); assertEquals(0,calls)
        assertTrue(r.enrich(listOf(p()),null,now,true,{active}) { deliveries++ }.isEmpty())
        assertEquals(0,deliveries); assertTrue(r.cached(listOf(p()),now).isEmpty())
        val next=resolver(f) { url,_ -> calls++; if(url.endsWith("/images"))images() else search(show()) }
        assertTrue(next.enrich(listOf(p()),null,now+1,true,{true}).isNotEmpty()); assertEquals(3,calls)
    }
    @Test fun errorsAreNegativelyCachedAndMalformedResponsesAreNotArt() {
        val f=file(); var calls=0
        resolver(f) { _,_ -> calls++; throw IOException("unavailable") }.enrich(listOf(p()),null,now,true,{true})
        resolver(f) { _,_ -> calls++; "invalid" }.enrich(listOf(p()),null,now+1,true,{true})
        assertEquals(1,calls)
        assertTrue(resolver { _,_ -> "not JSON" }.enrich(listOf(p()),null,now,true,{true}).isEmpty())
    }
    @Test fun corruptCacheIsIgnoredAndCacheDoesNotContainProviderDetails() {
        val f=file(); f.writeText("broken")
        val r=resolver(f) { url,_ -> if(url.endsWith("/images"))images() else search(show()) }
        assertTrue(r.enrich(listOf(p()),null,now,true,{true}).isNotEmpty())
        val raw=f.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(raw.contains("service://original")); assertFalse(raw.contains("supplied detail")); assertFalse(raw.contains("provider:1"))
        assertTrue(f.length()<=PresentationArtworkResolver.CACHE_BYTES)
    }
    @Test fun cachedIdentityCannotAttachToAReusedProviderId() {
        val r=resolver { url,_ -> if(url.endsWith("/images"))images() else search(show()) }
        r.enrich(listOf(p()),null,now,true,{true})
        assertTrue(r.cached(listOf(p("Other")),now).isEmpty())
        assertTrue(r.cached(listOf(p(year=2021)),now).isEmpty())
    }
    @Test fun requestBudgetIncludesHeroAndUnknownMoviesNeverTriggerSearch() {
        var calls=0
        val r=resolver { url,_ -> calls++; if(url.endsWith("/images"))"[]" else {
            val name=java.net.URLDecoder.decode(url.substringAfter("?q="),"UTF-8"); search(show(name=name))
        } }
        r.enrich((1..20).map { p("Show $it","p$it") },p("Hero","hero"),now,true,{true})
        assertEquals(PresentationArtworkResolver.REQUESTS,calls)
        calls=0
        r.enrich(listOf(p(type="unknown"),p(type="movie")),null,now,true,{true})
        assertEquals(0,calls)
    }
    @Test fun cancellationTokenRejectsResponsesEvenWhenCallerStillReportsActive() {
        lateinit var r:PresentationArtworkResolver
        var updates=0
        r=resolver { _,_ -> r.cancel(); search(show()) }
        assertTrue(r.enrich(listOf(p()),null,now,true,{true}) { updates++ }.isEmpty())
        assertEquals(0,updates); assertTrue(r.cached(listOf(p()),now).isEmpty())
    }
    @Test fun elapsedDeadlineAndOversizedResponseStopWithoutFalsePositive() {
        var elapsed=0L; var calls=0
        val r=PresentationArtworkResolver(file(),{ _,_ -> calls++; elapsed=13_000_000_000L; search(show()) },0,{elapsed})
        assertTrue(r.enrich(listOf(p()),null,now,true,{true}).isEmpty()); assertEquals(1,calls)
        assertTrue(resolver { _,limit -> "x".repeat(limit+1) }.enrich(listOf(p()),null,now,true,{true}).isEmpty())
    }
    @Test fun explicitIdLookupNeverFallsBackToAFuzzySearch() {
        val requests=ArrayList<String>()
        val r=resolver { url,_ -> requests.add(url); if(url.endsWith("/images"))images() else show().toString() }
        assertTrue(r.enrich(listOf(p().copy(imdbId="tt1234567")),null,now,true,{true}).isNotEmpty())
        assertEquals("https://api.tvmaze.com/lookup/shows?imdb=tt1234567",requests.first())
        assertFalse(requests.any { it.contains("search") })
        val reject=resolver { url,_ -> requests.add(url); show(name="Not Example").toString() }
        requests.clear()
        assertTrue(reject.enrich(listOf(p(id="tvmaze:10")),null,now,true,{true}).isEmpty())
        assertEquals(listOf("https://api.tvmaze.com/shows/10"),requests)
    }
    @Test fun totalDeadlineDisconnectsAResponseBlockedBeforeHeadersComplete() {
        val disconnected=java.util.concurrent.CountDownLatch(1)
        val entered=java.util.concurrent.atomic.AtomicBoolean(false)
        val interrupted=java.util.concurrent.atomic.AtomicBoolean(false)
        val headerNanos=java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE)
        val r=PresentationArtworkResolver(file(),paceMillis=0,batchMillis=100,openConnection={ url->
            object:java.net.HttpURLConnection(url) {
                override fun connect()=Unit
                override fun usingProxy()=false
                override fun disconnect() { disconnected.countDown() }
                override fun getResponseCode():Int {
                    entered.set(true)
                    val started=System.nanoTime()
                    interrupted.set(disconnected.await(2,java.util.concurrent.TimeUnit.SECONDS))
                    headerNanos.set(System.nanoTime()-started)
                    throw IOException("Disconnected by total deadline")
                }
            }
        })
        assertTrue(r.enrich(listOf(p()),null,now,true,{true}).isEmpty())
        assertTrue(entered.get())
        assertTrue("No deadline interrupted the blocked header read",interrupted.get())
        // Measure the transport wait, not the later cache fsync/platform cleanup.
        assertTrue("Blocked header exceeded the test deadline allowance",headerNanos.get()<1_000_000_000L)
    }
    @Test fun cacheEvictsOldestAndRejectsFutureOrOversizedFiles() {
        val f=file()
        val r=resolver(f) { _,_ -> "[]" }
        for(i in 1..100)r.enrich(listOf(p("Example $i","p$i")),null,now+i,true,{true})
        java.io.DataInputStream(f.inputStream()).use { input ->
            input.readInt(); input.readInt(); input.readLong(); assertEquals(96,input.readInt())
        }
        var calls=0
        resolver(f) { _,_ -> calls++; "[]" }.enrich(listOf(p("Example 1","p1")),null,now+200,true,{true})
        assertEquals(1,calls)
        val positive=file()
        val good=resolver(positive) { url,_ -> if(url.endsWith("/images"))images() else search(show()) }
        good.enrich(listOf(p()),null,now,true,{true})
        assertTrue(good.cached(listOf(p()),now-1).isEmpty())
        positive.writeBytes(ByteArray(PresentationArtworkResolver.CACHE_BYTES.toInt()+1))
        assertTrue(resolver(positive) { _,_ -> error("no lookup") }.cached(listOf(p()),now).isEmpty())
    }
    @Test fun apiAllowlistRejectsRedirectCredentialAndSchemeEscapes() {
        assertTrue(BalancedArtworkPolicy.apiAddress("https://api.tvmaze.com/search/shows?q=Example"))
        for(url in listOf("http://api.tvmaze.com/shows/10","https://api.tvmaze.com.evil.test/shows/10",
            "https://evil@api.tvmaze.com/shows/10","https://api.tvmaze.com:8443/shows/10",
            "https://api.tvmaze.com/shows/10#fragment","file:///secret"))assertFalse(url,BalancedArtworkPolicy.apiAddress(url))
    }
}
