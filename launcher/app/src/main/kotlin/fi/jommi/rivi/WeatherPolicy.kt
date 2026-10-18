package fi.jommi.rivi

import java.net.URI
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class WeatherCondition { CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW, STORM, FOG, WIND, UNKNOWN }

data class WeatherLocation(val zip:String,val city:String,val region:String,val latitude:Double,val longitude:Double)
data class WeatherReading(
    val temperatureCelsius:Double,val observedAt:Long,val condition:WeatherCondition,
    val stationName:String,val description:String,val isNight:Boolean=false
)
data class WeatherState(
    val location:WeatherLocation?=null,val reading:WeatherReading?=null,
    val loading:Boolean=false,val error:String?=null,val stale:Boolean=false
)

/** Provider-independent rules are also used when admitting persisted data. */
object WeatherPolicy {
    const val FRESH_MS=30*60_000L
    const val MAX_AGE_MS=2*60*60_000L
    const val FUTURE_SKEW_MS=5*60_000L
    const val MAPPING_AGE_MS=7*24*60*60_000L

    fun normalizeZip(raw:String):String?=raw.trim().takeIf { it.matches(Regex("[0-9]{5}")) }
    fun validCoordinates(latitude:Double,longitude:Double)=latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
    fun validText(value:String,max:Int)=value.isNotBlank() && value.length<=max && value==value.trim() && value.none { it.isISOControl() }
    fun validLocation(location:WeatherLocation)=normalizeZip(location.zip)==location.zip && validText(location.city,100) &&
        location.region.matches(Regex("[A-Z]{2}")) && validCoordinates(location.latitude,location.longitude)
    fun validTemperatureCelsius(value:Double?)=value!=null && value.isFinite() && value in -100.0..70.0
    fun parseTimestamp(raw:String):Long?=if(raw.length !in 1..60)null else runCatching { OffsetDateTime.parse(raw,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    fun visible(observedAt:Long,now:Long)=observedAt>0 && observedAt<=now+FUTURE_SKEW_MS && observedAt>=now-MAX_AGE_MS
    fun stale(observedAt:Long,now:Long)=!visible(observedAt,now) || observedAt<now-FRESH_MS
    fun temperatureCelsius(value:Double?,unit:String):Double? {
        if(value==null || !value.isFinite())return null
        val celsius=when(unit) { "wmoUnit:degC" -> value; "wmoUnit:degF" -> (value-32.0)*5.0/9.0; else -> return null }
        return celsius.takeIf(::validTemperatureCelsius)
    }
    fun formatTemperature(celsius:Double,units:String):String {
        require(validTemperatureCelsius(celsius) && units in setOf("F","C"))
        val value=if(units=="F")celsius*9.0/5.0+32.0 else celsius
        return "${value.roundToInt()}°$units"
    }
    fun classify(description:String):WeatherCondition {
        val text=description.lowercase(Locale.US)
        return when {
            listOf("thunder","tornado","funnel").any(text::contains) -> WeatherCondition.STORM
            listOf("snow","sleet","ice pellet","freezing","hail").any(text::contains) -> WeatherCondition.SNOW
            listOf("rain","drizzle","shower").any(text::contains) -> WeatherCondition.RAIN
            listOf("fog","mist","haze","smoke").any(text::contains) -> WeatherCondition.FOG
            "partly" in text || "few cloud" in text || "scattered" in text -> WeatherCondition.PARTLY_CLOUDY
            "cloud" in text || "overcast" in text -> WeatherCondition.CLOUDY
            "wind" in text || "breezy" in text -> WeatherCondition.WIND
            "clear" in text || "sunny" in text || text=="fair" -> WeatherCondition.CLEAR
            else -> WeatherCondition.UNKNOWN
        }
    }
    fun isAllowedNwsUrl(raw:String):Boolean=allowedUrl(raw,"api.weather.gov")
    internal fun allowedUrl(raw:String,host:String):Boolean=runCatching {
        val uri=URI(raw)
        raw.length<=2048 && uri.scheme=="https" && uri.host==host && uri.rawUserInfo==null &&
            uri.port in setOf(-1,443) && uri.rawFragment==null && uri.rawPath.startsWith("/") &&
            !uri.rawPath.contains("\\") && uri.normalize().rawPath==uri.rawPath
    }.getOrDefault(false)
    fun retryDelayMillis(failures:Int,retryAfter:String?,now:Long):Long {
        val backoff=(60_000L shl (failures-1).coerceIn(0,5)).coerceAtMost(FRESH_MS)
        val server=retryAfter?.trim()?.takeIf { it.length<=100 }?.let { value ->
            value.toLongOrNull()?.takeIf { it>=0 }?.coerceAtMost(86_400L)?.times(1000)
                ?: runCatching { ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-now }.getOrNull()
        }?.coerceIn(0,86_400_000L) ?: 0L
        return maxOf(backoff,server)
    }
}
