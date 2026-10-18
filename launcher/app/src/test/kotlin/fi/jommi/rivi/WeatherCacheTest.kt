package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WeatherCacheTest {
    private val sample=WeatherCache(WeatherLocation("01234","Example Town","MA",42.0,-72.0),
        listOf(WeatherStation("KAAA","Example station")),1_700_000_000_000,
        WeatherReading(-3.25,1_700_000_000_000,WeatherCondition.PARTLY_CLOUDY,"Example station","Partly Cloudy",true),1_700_000_001_000)

    @Test fun cacheRoundTripKeepsObservationAgeNightAndResolvedLocation() {
        assertEquals(sample,WeatherCacheStore.decode(WeatherCacheStore.encode(sample)))
    }
    @Test fun mappingCanPersistWithoutInventingAReading() {
        val cache=sample.copy(reading=null,fetchedAt=0)
        assertEquals(cache,WeatherCacheStore.decode(WeatherCacheStore.encode(cache)))
    }
    @Test fun truncatedOrForeignDataFailsClosed() {
        val bytes=WeatherCacheStore.encode(sample)
        for(data in listOf(byteArrayOf(),bytes.copyOf(10),bytes+1,bytes.copyOf().apply { this[0]=0 })) {
            assertTrue(runCatching { WeatherCacheStore.decode(data) }.isFailure)
        }
    }
    @Test fun invalidCacheValuesCannotEnterTheHeader() {
        for(cache in listOf(sample.copy(location=sample.location.copy(latitude=Double.NaN)),
            sample.copy(stations=listOf(WeatherStation("../../x","Example"))),
            sample.copy(reading=sample.reading!!.copy(temperatureCelsius=Double.NaN)),
            sample.copy(reading=sample.reading!!.copy(observedAt=0)))) {
            assertTrue(runCatching { WeatherCacheStore.decode(WeatherCacheStore.encode(cache)) }.isFailure)
        }
    }
    @Test fun atomicStoreRecoversFromCorruptionWithoutAPlaceholderTemperature() {
        val base=File("build/tmp/weather-tests").apply { mkdirs() }
        val directory=Files.createTempDirectory(base.toPath(),"weather-test-").toFile()
        try {
            val file=File(directory,"weather.bin");val store=WeatherCacheStore(file)
            assertNull(store.load());assertTrue(store.save(sample));assertEquals(sample,store.load())
            file.writeText("broken cache");assertNull(store.load())
            assertTrue(store.save(sample));assertEquals(sample,store.load())
            assertFalse(File(directory,"weather.bin.pending").exists())
        } finally { directory.listFiles()?.forEach { it.delete() };directory.delete() }
    }
}
