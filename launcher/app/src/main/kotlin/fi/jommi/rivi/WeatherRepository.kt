package fi.jommi.rivi

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos

class WeatherPreferences internal constructor(private val store:SharedPreferences) {
    val location:WeatherLocation? get() {
        val raw=runCatching { store.getString("weatherLocation",null) }.getOrNull() ?: return null
        return runCatching { WeatherJson.location(raw) }.getOrNull()
    }
    val units:String get()=runCatching { store.getString("weatherUnits","F") }.getOrNull().takeIf { it=="F" || it=="C" } ?: "F"
    val enabled:Boolean get()=runCatching { store.getBoolean("weatherEnabled",false) }.getOrDefault(false)
    internal fun location(value:WeatherLocation) { store.edit().putString("weatherLocation",WeatherJson.location(value).toString()).apply() }
    internal fun units(value:String) { store.edit().putString("weatherUnits",value).apply() }
    internal fun enabled(value:Boolean) { store.edit().putBoolean("weatherEnabled",value).apply() }
}

/** One worker and one bounded visible-Home timer; callbacks and preference commits are on main. */
class WeatherRepository internal constructor(
    context:Context,private val onState:(WeatherState)->Unit,
    private val transport:WeatherTransport,private val clock:()->Long
) {
    constructor(context:Context,onState:(WeatherState)->Unit):this(context,onState,WeatherHttpTransport(),System::currentTimeMillis)
    val prefs=WeatherPreferences(context.applicationContext.getSharedPreferences("pretttv",Context.MODE_PRIVATE))
    @Volatile var state=WeatherState(location=prefs.location);private set
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { task -> Thread(task,"prettTV-weather").apply { isDaemon=true } }
    private val cacheStore=WeatherCacheStore(File(context.applicationContext.filesDir,"weather-cache-v1.bin"))
    private val generation=AtomicLong()
    @Volatile private var active=false
    @Volatile private var closed=false
    private var work:Future<*>?=null
    private var busy=false
    private var cacheLoaded=false
    private var cache:WeatherCache?=null
    private var failures=0
    private var retryAt=0L
    private var lastError:String?=null
    private val timer=Runnable { publish();if(!busy)refresh() else schedule() }

    fun start():Unit=onMain {
        if(closed)return@onMain
        active=true
        // Import may have replaced preferences while this Activity remained alive.
        if(state.location!=prefs.location) {
            cancelWork();cache=null;cacheLoaded=false;failures=0;retryAt=0;lastError=null
        }
        publish(notify=true)
        refresh()
    }
    fun stop():Unit=onMain {
        active=false;cancelWork();state=state.copy(loading=false)
    }
    fun close():Unit=onMain {
        if(closed)return@onMain
        closed=true;active=false;cancelWork();worker.shutdownNow();state=state.copy(loading=false)
    }
    fun setUnits(units:String):Unit=onMain {
        require(units=="F" || units=="C")
        if(!closed) { prefs.units(units);publish(notify=true) }
    }
    fun setEnabled(enabled:Boolean):Unit=onMain {
        if(closed)return@onMain
        prefs.enabled(enabled)
        if(!enabled)cancelWork()
        publish(notify=true)
        if(enabled)refresh()
    }
    fun refresh(force:Boolean=false):Unit=onMain {
        if(closed || !active || busy || !prefs.enabled)return@onMain
        val location=prefs.location ?: return@onMain
        if(state.location!=location) {
            cancelWork();cache=null;cacheLoaded=false;failures=0;retryAt=0;lastError=null;publish()
        }
        val token=generation.get()
        if(!cacheLoaded) {
            busy=true;publish()
            work=worker.submit {
                val saved=cacheStore.load()?.takeIf { it.location==location }
                deliver(token) { cache=saved;cacheLoaded=true;busy=false;work=null;publish();refresh(force) }
            }
            return@onMain
        }
        val now=clock()
        if(now<retryAt || (!force && cache?.fetchedAt?.let { now-it in 0 until WeatherPolicy.FRESH_MS }==true)) {
            publish();schedule();return@onMain
        }
        busy=true;lastError=null;publish();schedule()
        val previous=cache
        work=worker.submit {
            var next=previous ?: WeatherCache(location,emptyList(),0)
            try {
                ensureCurrent(token)
                if(next.stations.isEmpty() || clock()-next.mappedAt !in 0..WeatherPolicy.MAPPING_AGE_MS) {
                    next=next.copy(stations=stations(location,token),mappedAt=clock())
                }
                var reading:WeatherReading?=null
                var lastFailure:Exception?=null
                for(station in next.stations.take(3)) {
                    ensureCurrent(token)
                    try {
                        reading=WeatherJson.observation(read("https://api.weather.gov/stations/${station.id}/observations/latest?require_qc=true",token),station,clock())
                        if(reading!=null)break
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(error:WeatherRequestException) { if(error.retryAfter!=null || error.status==429)throw error;lastFailure=error }
                    catch(error:Exception) { lastFailure=error }
                }
                if(reading==null)throw lastFailure ?: IOException("No recent temperature is available from nearby stations.")
                next=next.copy(reading=reading,fetchedAt=clock())
                ensureCurrent(token);cacheStore.save(next)
                val result=next
                deliver(token) { cache=result;busy=false;work=null;failures=0;retryAt=0;lastError=null;publish();schedule() }
            } catch(_:CancellationException) { /* A stopped or superseded request cannot publish or save preferences. */ }
            catch(error:Exception) {
                if(isCurrent(token)) {
                    cacheStore.save(next)
                    val retained=next
                    deliver(token) {
                        cache=retained;busy=false;work=null;failures++
                        retryAt=clock()+WeatherPolicy.retryDelayMillis(failures,(error as? WeatherRequestException)?.retryAfter,clock())
                        lastError=readable(error);publish();schedule()
                    }
                }
            }
        }
    }
    fun configureZip(zip:String,complete:(Result<WeatherLocation>)->Unit):Unit=onMain {
        if(closed || !active)return@onMain
        val normalized=WeatherPolicy.normalizeZip(zip)
        if(normalized==null) { complete(Result.failure(IllegalArgumentException("Enter a five-digit US ZIP code.")));return@onMain }
        cancelWork();busy=true;lastError=null;publish();schedule()
        val token=generation.get()
        work=worker.submit {
            try {
                val location=WeatherJson.zip(read("https://api.zippopotam.us/us/$normalized",token),normalized)
                deliver(token) {
                    // This commit shares the main-thread generation check with its completion callback.
                    prefs.location(location)
                    if(cache?.location!=location)cache=null
                    cacheLoaded=true;busy=false;work=null;failures=0;retryAt=0;lastError=null
                    publish();complete(Result.success(location));refresh()
                }
            } catch(_:CancellationException) { }
            catch(error:Exception) {
                deliver(token) {
                    busy=false;work=null;lastError=readable(error,true);publish()
                    complete(Result.failure(IOException(lastError)));schedule()
                }
            }
        }
    }

    private fun onMain(action:()->Unit) { if(Looper.myLooper()==Looper.getMainLooper())action() else main.post { action() } }
    private fun cancelWork() {
        generation.incrementAndGet();main.removeCallbacks(timer);transport.cancel();work?.cancel(true);work=null;busy=false
    }
    private fun isCurrent(token:Long)=!closed && active && generation.get()==token
    private fun ensureCurrent(token:Long) { if(!isCurrent(token) || Thread.currentThread().isInterrupted)throw CancellationException() }
    private fun deliver(token:Long,action:()->Unit) { main.post { if(isCurrent(token))action() } }
    private fun read(url:String,token:Long):String { ensureCurrent(token);return transport.read(url) { ensureCurrent(token) }.also { ensureCurrent(token) } }
    private fun publish(notify:Boolean=false) {
        val location=prefs.location
        val reading=cache?.takeIf { prefs.enabled && it.location==location }?.reading?.takeIf { WeatherPolicy.visible(it.observedAt,clock()) }
        val next=WeatherState(location,reading,busy,if(prefs.enabled)lastError else null,reading?.let { WeatherPolicy.stale(it.observedAt,clock()) } ?: false)
        if(next!=state || notify) { state=next;if(active && !closed)onState(next) }
    }
    private fun schedule() {
        main.removeCallbacks(timer)
        if(!active || closed || !prefs.enabled || prefs.location==null)return
        val now=clock();val deadlines=mutableListOf<Long>()
        if(!busy)deadlines+=maxOf(retryAt,cache?.fetchedAt?.plus(WeatherPolicy.FRESH_MS) ?: now)
        cache?.reading?.let {
            for(boundary in listOf(it.observedAt+WeatherPolicy.FRESH_MS+1,it.observedAt+WeatherPolicy.MAX_AGE_MS+1))if(boundary>now)deadlines+=boundary
        }
        deadlines.minOrNull()?.let { main.postDelayed(timer,(it-now).coerceAtLeast(1000)) }
    }
    private fun stations(location:WeatherLocation,token:Long):List<WeatherStation> {
        val coordinates=String.format(Locale.US,"%.4f,%.4f",location.latitude,location.longitude)
        val point=JSONObject(read("https://api.weather.gov/points/$coordinates",token)).getJSONObject("properties")
        val url=point.getString("observationStations")
        require(WeatherPolicy.isAllowedNwsUrl(url))
        val features=JSONObject(read(url,token)).getJSONArray("features")
        val candidates=(0 until minOf(features.length(),200)).mapNotNull { index ->
            runCatching {
                val item=features.getJSONObject(index);val p=item.getJSONObject("properties")
                val id=p.getString("stationIdentifier");require(id.matches(Regex("[A-Za-z0-9]{3,12}")))
                val name=WeatherJson.text(p.optString("name",id),150).ifBlank { id }
                val position=item.getJSONObject("geometry").getJSONArray("coordinates")
                val longitude=position.getDouble(0);val latitude=position.getDouble(1)
                require(WeatherPolicy.validCoordinates(latitude,longitude))
                val dy=latitude-location.latitude;val dx=(longitude-location.longitude)*cos(Math.toRadians(location.latitude))
                (dx*dx+dy*dy) to WeatherStation(id,name)
            }.getOrNull()
        }
        return candidates.sortedBy { it.first }.map { it.second }.distinctBy { it.id }.take(3).also {
            if(it.isEmpty())throw IOException("No nearby observation station is available for this ZIP code.")
        }
    }
    private fun readable(error:Exception,zip:Boolean=false):String=when {
        error is WeatherRequestException && error.status==404 && zip -> "That US ZIP code was not found. Your previous location is unchanged."
        error is WeatherRequestException && error.status==429 -> "The weather service is busy. It will retry later."
        error is java.net.UnknownHostException || error is java.net.SocketTimeoutException -> if(zip)"Couldn’t look up that ZIP code. Check your connection and try again." else "Weather is temporarily unavailable. It will retry later."
        error is IOException && error.message=="No recent temperature is available from nearby stations." -> error.message!!
        else -> if(zip)"Couldn’t verify that ZIP code. Your previous location is unchanged." else "Weather is temporarily unavailable. It will retry later."
    }
    companion object {
        fun validateLocationJson(raw:String):Boolean=runCatching { WeatherJson.location(raw);true }.getOrDefault(false)
    }
}

/** Injectable in the isolated instrumentation app; no device or global network settings are needed. */
internal interface WeatherTransport {
    fun read(url:String,checkCurrent:()->Unit):String
    fun cancel()
}
internal class WeatherRequestException(val status:Int,val retryAfter:String?):IOException("Weather HTTP $status")
internal class WeatherHttpTransport:WeatherTransport {
    private val lock=Any()
    private var connection:HttpURLConnection?=null
    override fun cancel() { synchronized(lock) { connection?.disconnect();connection=null } }
    override fun read(url:String,checkCurrent:()->Unit):String {
        val nws=WeatherPolicy.isAllowedNwsUrl(url)
        fun allowed(value:String)=if(nws)WeatherPolicy.isAllowedNwsUrl(value) else
            WeatherPolicy.allowedUrl(value,"api.zippopotam.us") && URI(value).rawPath.matches(Regex("/us/[0-9]{5}")) && URI(value).rawQuery==null
        var address=url
        repeat(3) {
            require(allowed(address));checkCurrent()
            val request=URL(address).openConnection() as HttpURLConnection
            request.connectTimeout=8000;request.readTimeout=8000;request.instanceFollowRedirects=false;request.useCaches=false
            request.setRequestProperty("User-Agent","prettTV-weather (https://github.com/draco1738/prettTV)")
            request.setRequestProperty("Accept",if(nws)"application/geo+json" else "application/json")
            request.setRequestProperty("Accept-Encoding","identity")
            synchronized(lock) { checkCurrent();connection=request }
            try {
                val status=request.responseCode;checkCurrent()
                if(status in setOf(301,302,303,307,308)) {
                    val target=request.getHeaderField("Location") ?: throw IOException("Invalid weather redirect")
                    address=URL(URL(address),target).toString();require(allowed(address))
                } else {
                    if(status!=200)throw WeatherRequestException(status,request.getHeaderField("Retry-After"))
                    require(request.contentLengthLong<=MAX_BYTES)
                    return request.inputStream.use { stream ->
                        val buffer=ByteArray(8192);val output=ByteArrayOutputStream()
                        while(true) { checkCurrent();val count=stream.read(buffer);if(count<0)break;require(output.size()+count<=MAX_BYTES);output.write(buffer,0,count) }
                        checkCurrent();output.toString("UTF-8")
                    }
                }
            } finally { synchronized(lock) { if(connection===request)connection=null };request.disconnect() }
        }
        throw IOException("Too many weather redirects")
    }
    companion object { private const val MAX_BYTES=256*1024 }
}

internal object WeatherJson {
    fun text(raw:String,max:Int)=raw.filterNot(Char::isISOControl).trim().take(max)
    fun location(raw:String):WeatherLocation {
        require(raw.length in 1..2048)
        val fields=setOf("zip","city","region","latitude","longitude")
        val values=mutableMapOf<String,Any>()
        JsonReader(StringReader(raw)).use { reader ->
            reader.isLenient=false;reader.beginObject()
            while(reader.hasNext()) {
                val name=reader.nextName();require(name in fields && name !in values)
                values[name]=if(name=="latitude" || name=="longitude") {
                    require(reader.peek()==JsonToken.NUMBER);reader.nextDouble()
                } else { require(reader.peek()==JsonToken.STRING);reader.nextString() }
            }
            reader.endObject();require(reader.peek()==JsonToken.END_DOCUMENT && values.keys==fields)
        }
        val location=WeatherLocation(values.getValue("zip") as String,values.getValue("city") as String,values.getValue("region") as String,
            values.getValue("latitude") as Double,values.getValue("longitude") as Double)
        require(WeatherPolicy.validLocation(location));return location
    }
    fun location(value:WeatherLocation)=JSONObject().put("zip",value.zip).put("city",value.city).put("region",value.region).put("latitude",value.latitude).put("longitude",value.longitude)
    fun zip(raw:String,zip:String):WeatherLocation {
        val json=JSONObject(raw);require(json.getString("country abbreviation")=="US" && json.getString("post code")==zip)
        val place=json.getJSONArray("places").getJSONObject(0)
        return WeatherLocation(zip,place.getString("place name"),place.getString("state abbreviation"),place.getString("latitude").toDouble(),place.getString("longitude").toDouble()).also {
            require(WeatherPolicy.validLocation(it))
        }
    }
    fun observation(raw:String,station:WeatherStation,now:Long):WeatherReading? {
        val properties=JSONObject(raw).getJSONObject("properties")
        val temperature=properties.optJSONObject("temperature") ?: return null
        val value=WeatherPolicy.temperatureCelsius((temperature.opt("value") as? Number)?.toDouble(),temperature.optString("unitCode")) ?: return null
        val time=WeatherPolicy.parseTimestamp(properties.optString("timestamp")) ?: return null
        if(!WeatherPolicy.visible(time,now))return null
        val description=text(properties.optString("textDescription",""),200)
        val icon=properties.optString("icon","")
        return WeatherReading(value,time,WeatherPolicy.classify(description),station.name,description,"/night/" in icon)
    }
}
