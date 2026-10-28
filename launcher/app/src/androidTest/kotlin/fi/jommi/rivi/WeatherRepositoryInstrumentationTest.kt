package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Real Android JSON, preferences and main/worker queues; every provider response is synthetic. */
internal object WeatherRepositoryInstrumentationTest {
    private val location=WeatherLocation("00000","Fixture City","ZZ",40.0,-100.0)
    private val otherLocation=WeatherLocation("00001","Other Fixture","ZZ",41.0,-101.0)
    private const val NOW=1_800_000_000_000L

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        check(instrumentation.targetContext.packageName=="dev.zane.pretttv.stability") { "Weather fixtures require the isolated test package" }
        for(close in listOf(false,true))test("Weather late ZIP lookup cannot persist after ${if(close)"close" else "Home stops"}") {
            fixture(instrumentation) { f ->
                val gate=f.gate();val callbacks=AtomicInteger()
                val transport=FakeTransport { _,_ -> gate.block();zip(otherLocation) }
                val repo=f.repository(transport)
                f.main { repo.start();repo.configureZip(otherLocation.zip) { callbacks.incrementAndGet() } }
                gate.awaitEntered()
                f.main { if(close)repo.close() else repo.stop() }
                val published=f.states.size
                gate.release.countDown();f.drain(repo)
                check(repo.prefs.location==null && callbacks.get()==0 && f.states.size==published)
                check(!f.cacheFile.exists() && transport.cancels.get()>0)
            }
        }
        test("Weather superseded ZIP lookup commits only the newest request") {
            fixture(instrumentation) { f ->
                val gate=f.gate();val oldCallbacks=AtomicInteger();val newCallbacks=AtomicInteger()
                val transport=FakeTransport { url,_ ->
                    if(url.endsWith("/${location.zip}")) { gate.block();zip(location) } else zip(otherLocation)
                }
                val repo=f.repository(transport)
                f.main { repo.start();repo.configureZip(location.zip) { oldCallbacks.incrementAndGet() } }
                gate.awaitEntered()
                f.main { repo.configureZip(otherLocation.zip) { check(it.isSuccess);newCallbacks.incrementAndGet() } }
                gate.release.countDown();f.waitUntil { newCallbacks.get()==1 };f.drain(repo)
                check(oldCallbacks.get()==0 && repo.prefs.location==otherLocation)
                check(transport.urls.size==2 && f.offMainCallbacks.get()==0)
            }
        }
        test("Weather invalid or malformed ZIP responses preserve the previous location") {
            fixture(instrumentation) { f ->
                f.seed(location,enabled=false)
                val transport=FakeTransport { _,_ -> "{\"country abbreviation\":\"CA\",\"post code\":\"00001\",\"places\":[]}" }
                val repo=f.repository(transport);val rejected=AtomicInteger()
                f.main { repo.start();repo.configureZip("not-a-zip") { check(it.isFailure);rejected.incrementAndGet() } }
                check(rejected.get()==1 && transport.urls.isEmpty())
                f.main { repo.configureZip(otherLocation.zip) { check(it.isFailure);rejected.incrementAndGet() } }
                f.waitUntil { rejected.get()==2 };f.drain(repo)
                check(repo.prefs.location==location && transport.urls.size==1)
                check(!repo.state.loading && !f.cacheFile.exists())
            }
        }
        test("Weather fresh private cache rehydrates without network across Home stop and start") {
            fixture(instrumentation) { f ->
                val reading=reading(f.now.get());f.seed(location)
                check(WeatherCacheStore(f.cacheFile).save(WeatherCache(location,listOf(WeatherStation("KAAA","Fixture Station")),f.now.get(),reading,f.now.get())))
                val transport=FakeTransport { _,_ -> error("Fresh cache must not request the network") }
                val repo=f.repository(transport)
                f.main { repo.start() };f.waitUntil { repo.state.reading==reading && !repo.state.loading }
                f.main { repo.stop();repo.start() };f.drain(repo)
                check(repo.state.reading==reading && transport.urls.isEmpty() && !repo.state.stale)
            }
        }
        test("Weather corrupt cache and malformed observations recover through bounded station fallback") {
            fixture(instrumentation) { f ->
                f.seed(location);f.cacheFile.writeBytes(ByteArray(16_385) { 7 })
                val transport=FakeTransport { url,_ -> when {
                    url.contains("/points/") -> points()
                    url.endsWith("/fixture/stations") -> stations()
                    url.contains("/KAAA/") -> "{malformed"
                    url.contains("/KBBB/") -> observation(f.now.get(),null)
                    url.contains("/KCCC/") -> observation(f.now.get(),19.0)
                    else -> error("Unexpected fixture URL")
                } }
                val repo=f.repository(transport)
                f.main { repo.start() };f.waitUntil { repo.state.reading?.temperatureCelsius==19.0 }
                f.drain(repo)
                check(transport.urls.size==5 && repo.state.error==null && !repo.state.loading)
                check(repo.state.reading?.stationName=="Fixture C")
                check(WeatherCacheStore(f.cacheFile).load()?.reading==repo.state.reading)
            }
        }
        test("Weather failed refresh retains cached data and force refresh respects Retry-After") {
            fixture(instrumentation) { f ->
                f.seed(location)
                val old=reading(f.now.get()-WeatherPolicy.FRESH_MS-1)
                check(WeatherCacheStore(f.cacheFile).save(WeatherCache(location,listOf(WeatherStation("KAAA","Fixture Station")),f.now.get(),old,f.now.get()-WeatherPolicy.FRESH_MS-1)))
                var failing=true
                val transport=FakeTransport { _,_ -> if(failing)throw WeatherRequestException(429,"120") else observation(f.now.get(),24.0) }
                val repo=f.repository(transport)
                f.main { repo.start() };f.waitUntil { repo.state.error!=null && !repo.state.loading }
                check(repo.state.reading==old && repo.state.stale && transport.urls.size==1)
                f.main { repeat(3) { repo.refresh(force=true) } };f.drain(repo)
                check(transport.urls.size==1)
                failing=false;f.now.addAndGet(120_001)
                f.main { repo.refresh(force=true) };f.waitUntil { repo.state.reading?.temperatureCelsius==24.0 }
                check(transport.urls.size==2 && repo.state.error==null && !repo.state.stale)
            }
        }
        test("Weather stop cancels a late refresh and restart can refresh normally") {
            fixture(instrumentation) { f ->
                f.seed(location)
                val old=reading(f.now.get()-WeatherPolicy.FRESH_MS-1)
                check(WeatherCacheStore(f.cacheFile).save(WeatherCache(location,listOf(WeatherStation("KAAA","Fixture Station")),f.now.get(),old,f.now.get()-WeatherPolicy.FRESH_MS-1)))
                val gate=f.gate();val requests=AtomicInteger()
                val transport=FakeTransport { _,_ -> if(requests.incrementAndGet()==1)gate.block();observation(f.now.get(),26.0) }
                val repo=f.repository(transport)
                f.main { repo.start() };gate.awaitEntered()
                f.main { repo.stop();repo.refresh(force=true) };val published=f.states.size
                gate.release.countDown();f.drain(repo)
                check(transport.urls.size==1 && f.states.size==published)
                check(WeatherCacheStore(f.cacheFile).load()?.reading==old)
                f.main { repo.start() };f.waitUntil { repo.state.reading?.temperatureCelsius==26.0 }
                check(transport.urls.size==2 && !repo.state.loading)
            }
        }
        test("Weather imported location cannot display the old location cache") {
            fixture(instrumentation) { f ->
                f.seed(location)
                val old=reading(f.now.get())
                check(WeatherCacheStore(f.cacheFile).save(WeatherCache(location,listOf(WeatherStation("KAAA","Fixture Station")),f.now.get(),old,f.now.get())))
                val transport=FakeTransport { url,_ -> when {
                    url.contains("/points/") -> { check(url.endsWith("41.0000,-101.0000"));points() }
                    url.endsWith("/fixture/stations") -> stations(otherLocation)
                    else -> observation(f.now.get(),12.0)
                } }
                val repo=f.repository(transport)
                f.main { repo.start() };f.waitUntil { repo.state.reading==old }
                val beforeSwap=f.states.size
                f.main { repo.stop();f.preferences.edit().putString("weatherLocation",WeatherJson.location(otherLocation).toString()).commit();repo.start() }
                f.waitUntil { repo.state.reading?.temperatureCelsius==12.0 }
                check(f.states.drop(beforeSwap).none { it.location==otherLocation && it.reading==old })
                check(repo.state.location==otherLocation && WeatherCacheStore(f.cacheFile).load()?.location==otherLocation)
            }
        }
        test("Weather disabled mode makes no requests and expires old cached observations") {
            fixture(instrumentation) { f ->
                f.seed(location,enabled=false)
                val old=reading(f.now.get()-WeatherPolicy.MAX_AGE_MS-1)
                check(WeatherCacheStore(f.cacheFile).save(WeatherCache(location,listOf(WeatherStation("KAAA","Fixture Station")),f.now.get(),old,f.now.get()-WeatherPolicy.FRESH_MS-1)))
                val transport=FakeTransport { _,_ -> throw IOException("Synthetic offline response") }
                val repo=f.repository(transport)
                f.main { repo.start();repo.refresh(force=true) };f.drain(repo)
                check(transport.urls.isEmpty() && repo.state.reading==null)
                f.main { repo.setEnabled(true) };f.waitUntil { repo.state.error!=null && !repo.state.loading }
                check(repo.state.reading==null)
                val count=transport.urls.size
                f.main { repo.setEnabled(false);repo.refresh(force=true) };f.drain(repo)
                check(transport.urls.size==count && repo.state.error==null)
            }
        }
        test("Weather restored preferences reject invalid values before any store editor") {
            fixture(instrumentation) { f ->
                val home=RecordingPreferences(f.preferences)
                val apps=RecordingPreferences(f.context.getSharedPreferences("apps",Context.MODE_PRIVATE))
                home.edit().putString("layout","BALANCED").putString("weatherLocation",WeatherJson.location(location).toString()).putString("weatherUnits","F").putBoolean("weatherEnabled",true).commit()
                apps.edit().putString("theme","dark").commit()
                val stores=linkedMapOf<String,SharedPreferences>("apps" to apps,"home" to home)
                val baseline=LocalSettingsTransfer.export(stores)
                val raw=WeatherJson.location(location).toString()
                val invalidLocations=listOf(
                    raw.replace("\"00000\"","\"0000\""), raw.replace("\"ZZ\"","\"invalid\""),
                    raw.replace("40", "91"), JSONObject(raw).put("extra",true).toString(),
                    JSONObject(raw).put("latitude","40.0").toString(), raw.dropLast(1)+",\"zip\":\"00001\"}",
                    raw+" {}", JSONObject(raw).apply { remove("city") }.toString(),
                )
                val changes=invalidLocations.map { "weatherLocation" to typed("string",it) } + listOf(
                    "weatherUnits" to typed("string","K"),"weatherUnits" to typed("boolean",true),
                    "weatherEnabled" to typed("string","true"),"weatherLocation" to typed("boolean",false),
                )
                for((key,value) in changes) {
                    val payload=JSONObject(String(Base64.decode(baseline,Base64.DEFAULT),Charsets.UTF_8))
                    payload.getJSONObject("apps").put("theme",typed("string","must-not-write"))
                    payload.getJSONObject("home").put(key,value)
                    home.editors=0;apps.editors=0
                    val encoded=Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP)
                    check(runCatching { LocalSettingsTransfer.restore(encoded,stores) }.isFailure) { "Invalid weather field accepted: $key" }
                    check(home.editors==0 && apps.editors==0) { "Validation started writing stores before rejection" }
                    check(LocalSettingsTransfer.export(stores)==baseline)
                }
                val valid=JSONObject(String(Base64.decode(baseline,Base64.DEFAULT),Charsets.UTF_8))
                valid.getJSONObject("home").put("weatherLocation",typed("string",WeatherJson.location(otherLocation).toString())).put("weatherUnits",typed("string","C")).put("weatherEnabled",typed("boolean",false))
                LocalSettingsTransfer.restore(Base64.encodeToString(valid.toString().toByteArray(Charsets.UTF_8),Base64.NO_WRAP),stores)
                check(WeatherPreferences(home).location==otherLocation && WeatherPreferences(home).units=="C" && !WeatherPreferences(home).enabled)
            }
        }
        test("Weather passive header preserves focus and child identity across state changes") {
            fixture(instrumentation) { f -> f.main {
                val parent=LinearLayout(f.context)
                val focus=Button(f.context).apply { text="Fixture app";isFocusableInTouchMode=true }
                val header=WeatherHeaderView(f.context,1f)
                val clock=TextView(f.context).apply { text="12:00" }
                parent.addView(focus);parent.addView(header);parent.addView(clock)
                check(focus.requestFocus())
                val identities=descendants(header)
                check(identities.none { it.isFocusable })
                val fresh=WeatherState(location,reading(NOW))
                for((state,enabled) in listOf(fresh to true,fresh.copy(stale=true) to true,WeatherState(location=location) to true,fresh to false)) {
                    header.bind(state,enabled,"F")
                    check(parent.findFocus()===focus && header.parent===parent)
                    check(descendants(header).zip(identities).all { (current,original)->current===original } && descendants(header).size==identities.size)
                    check(identities.none { it.isFocusable } && clock.visibility==View.VISIBLE)
                    val labels=identities.filterIsInstance<TextView>().map { it.text.toString() }
                    check(labels.none { it.contains("cached",ignoreCase=true) || it.contains("stale",ignoreCase=true) })
                    if(state.reading!=null) {
                        check(WeatherPolicy.formatTemperature(state.reading.temperatureCelsius,"F") in labels)
                        check(header.contentDescription.contains("Cached weather reading")==state.stale)
                    } else check("Unavailable" in labels)
                }
                check(header.visibility==View.GONE)
            } }
        }
    }

    private fun typed(type:String,value:Any)=JSONObject().put("type",type).put("value",value)
    private fun reading(time:Long)=WeatherReading(20.0,time,WeatherCondition.CLEAR,"Fixture Station","Clear")
    private fun zip(value:WeatherLocation)=JSONObject().put("country abbreviation","US").put("post code",value.zip)
        .put("places",JSONArray().put(JSONObject().put("place name",value.city).put("state abbreviation",value.region)
            .put("latitude",value.latitude.toString()).put("longitude",value.longitude.toString()))).toString()
    private fun points()="""{"properties":{"observationStations":"https://api.weather.gov/fixture/stations"}}"""
    private fun stations(value:WeatherLocation=location)=JSONObject().put("features",JSONArray().apply {
        listOf("KAAA" to "Fixture A","KBBB" to "Fixture B","KCCC" to "Fixture C").forEachIndexed { i,(id,name)->
            put(JSONObject().put("properties",JSONObject().put("stationIdentifier",id).put("name",name))
                .put("geometry",JSONObject().put("coordinates",JSONArray().put(value.longitude+i*.01).put(value.latitude))))
        }
    }).toString()
    private fun observation(time:Long,temperature:Double?)=JSONObject().put("properties",JSONObject()
        .put("temperature",JSONObject().put("value",temperature ?: JSONObject.NULL).put("unitCode","wmoUnit:degC"))
        .put("timestamp",Instant.ofEpochMilli(time).toString()).put("textDescription","Clear")).toString()
    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup)(0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
    private fun fixture(instrumentation:Instrumentation,body:(Fixture)->Unit) {
        val f=Fixture(instrumentation)
        try { body(f) } finally { f.close() }
    }

    private class FakeTransport(val response:(String,()->Unit)->String):WeatherTransport {
        val urls=CopyOnWriteArrayList<String>();val cancels=AtomicInteger()
        override fun read(url:String,checkCurrent:()->Unit):String { urls+=url;return response(url,checkCurrent) }
        override fun cancel() { cancels.incrementAndGet() }
    }
    private class Gate {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        fun awaitEntered() { check(entered.await(5,TimeUnit.SECONDS)) { "Fixture worker never entered request" } }
        fun block() {
            entered.countDown()
            val deadline=SystemClock.elapsedRealtime()+8_000
            while(release.count>0 && SystemClock.elapsedRealtime()<deadline) {
                try { release.await(25,TimeUnit.MILLISECONDS) } catch(_:InterruptedException) { /* Simulate a late non-cooperative response. */ }
            }
            check(release.count==0L) { "Fixture request was not released" }
        }
    }
    private class RecordingPreferences(private val delegate:SharedPreferences):SharedPreferences by delegate {
        var editors=0
        override fun edit():SharedPreferences.Editor { editors++;return delegate.edit() }
    }
    private class Fixture(private val instrumentation:Instrumentation) {
        private val base=instrumentation.targetContext
        private val id="weather-fixture-${SystemClock.elapsedRealtimeNanos()}"
        private val directory=File(base.cacheDir,id).apply { check(mkdirs()) }
        private val preferenceNames=mutableSetOf<String>()
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=directory
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val isolated="$id-$name";preferenceNames+=isolated
                return base.getSharedPreferences(isolated,mode)
            }
        }
        val preferences=context.getSharedPreferences("pretttv",Context.MODE_PRIVATE)
        val cacheFile=File(directory,"weather-cache-v1.bin")
        val now=AtomicLong(NOW)
        val states=CopyOnWriteArrayList<WeatherState>();val offMainCallbacks=AtomicInteger()
        private val repositories=mutableListOf<WeatherRepository>();private val gates=mutableListOf<Gate>()
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun gate()=Gate().also { gates+=it }
        fun seed(value:WeatherLocation,enabled:Boolean=true) {
            check(preferences.edit().putString("weatherLocation",WeatherJson.location(value).toString()).putBoolean("weatherEnabled",enabled).putString("weatherUnits","F").commit())
        }
        fun repository(transport:WeatherTransport):WeatherRepository {
            lateinit var repo:WeatherRepository
            main { repo=WeatherRepository(context,{ state ->
                if(Looper.myLooper()!=Looper.getMainLooper())offMainCallbacks.incrementAndGet()
                states+=state
            },transport) { now.get() } }
            repositories+=repo;return repo
        }
        fun waitUntil(condition:()->Boolean) {
            val deadline=SystemClock.elapsedRealtime()+5_000
            do { if(condition())return;SystemClock.sleep(10) } while(SystemClock.elapsedRealtime()<deadline)
            error("Weather fixture did not reach its expected state")
        }
        private fun worker(repo:WeatherRepository)=WeatherRepository::class.java.getDeclaredField("worker").apply { isAccessible=true }.get(repo) as ExecutorService
        fun drain(repo:WeatherRepository) {
            val queue=worker(repo)
            if(queue.isShutdown)check(queue.awaitTermination(5,TimeUnit.SECONDS)) else queue.submit {}.get(5,TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
        }
        fun close() {
            gates.forEach { it.release.countDown() }
            main { repositories.forEach { it.close() } }
            repositories.forEach { check(worker(it).awaitTermination(5,TimeUnit.SECONDS)) }
            instrumentation.waitForIdleSync()
            preferenceNames.forEach { base.deleteSharedPreferences(it) }
            check(directory.deleteRecursively())
        }
    }
}
