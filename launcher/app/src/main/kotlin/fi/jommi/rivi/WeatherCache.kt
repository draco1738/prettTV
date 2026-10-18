package fi.jommi.rivi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

internal data class WeatherStation(val id:String,val name:String)
internal data class WeatherCache(
    val location:WeatherLocation,val stations:List<WeatherStation>,val mappedAt:Long,
    val reading:WeatherReading?=null,val fetchedAt:Long=0
)

/** Small atomic private cache; a corrupt or differently located entry is never rendered. */
internal class WeatherCacheStore(private val file:File) {
    fun load():WeatherCache?=try {
        if(!file.isFile || file.length() !in 1..MAX_BYTES)null
        else decode(file.inputStream().use { it.readBytesCapped(MAX_BYTES.toInt()) })
    } catch(_:Exception) { null }
    fun save(cache:WeatherCache)=runCatching { AtomicPrivateFile.write(file,encode(cache),MAX_BYTES) }.isSuccess

    companion object {
        private const val MAX_BYTES=16_384L
        internal fun encode(cache:WeatherCache):ByteArray=ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeInt(0x50545731)
                with(cache.location) { out.writeUTF(zip);out.writeUTF(city);out.writeUTF(region);out.writeDouble(latitude);out.writeDouble(longitude) }
                require(cache.stations.size<=3)
                out.writeLong(cache.mappedAt);out.writeInt(cache.stations.size)
                cache.stations.forEach { out.writeUTF(it.id);out.writeUTF(it.name) }
                out.writeLong(cache.fetchedAt);out.writeBoolean(cache.reading!=null)
                cache.reading?.let { r ->
                    out.writeDouble(r.temperatureCelsius);out.writeLong(r.observedAt);out.writeUTF(r.condition.name)
                    out.writeUTF(r.stationName);out.writeUTF(r.description);out.writeBoolean(r.isNight)
                }
            }
            bytes.toByteArray().also { require(it.size<=MAX_BYTES) }
        }
        internal fun decode(bytes:ByteArray):WeatherCache {
            require(bytes.size<=MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt()==0x50545731)
                val location=WeatherLocation(input.readUTF(),input.readUTF(),input.readUTF(),input.readDouble(),input.readDouble())
                require(WeatherPolicy.validLocation(location))
                val mappedAt=input.readLong();val count=input.readInt();require(count in 0..3)
                val stations=List(count) {
                    WeatherStation(input.readUTF(),input.readUTF()).also {
                        require(it.id.matches(Regex("[A-Za-z0-9]{3,12}")) && WeatherPolicy.validText(it.name,150))
                    }
                }
                val fetchedAt=input.readLong()
                val reading=if(input.readBoolean())WeatherReading(input.readDouble(),input.readLong(),WeatherCondition.valueOf(input.readUTF()),input.readUTF(),input.readUTF(),input.readBoolean()).also {
                    require(WeatherPolicy.validTemperatureCelsius(it.temperatureCelsius) && it.observedAt>0 &&
                        WeatherPolicy.validText(it.stationName,150) && it.description.length<=200 && it.description.none(Char::isISOControl))
                } else null
                require(input.available()==0 && mappedAt>=0 && fetchedAt>=0)
                WeatherCache(location,stations,mappedAt,reading,fetchedAt)
            }
        }
    }
}
