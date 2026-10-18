package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class WeatherPolicyTest {
    private val now=Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()

    @Test fun zipKeepsLeadingZeroesAndRejectsPartialOrNonAsciiInput() {
        assertEquals("01234",WeatherPolicy.normalizeZip(" 01234 "))
        for(value in listOf("1234","123456","12345-6789","１２３４５","12a45","12345/../../"))assertNull(WeatherPolicy.normalizeZip(value))
    }
    @Test fun missingAndInvalidTemperatureNeverBecomeZero() {
        for(value in listOf(null,Double.NaN,Double.POSITIVE_INFINITY,-101.0,71.0))assertNull(WeatherPolicy.temperatureCelsius(value,"wmoUnit:degC"))
        assertNull(WeatherPolicy.temperatureCelsius(20.0,"wmoUnit:K"))
        assertEquals(0.0,WeatherPolicy.temperatureCelsius(0.0,"wmoUnit:degC")!!,0.0)
    }
    @Test fun providerFahrenheitConvertsBeforeValidation() {
        assertEquals(0.0,WeatherPolicy.temperatureCelsius(32.0,"wmoUnit:degF")!!,0.0)
        assertEquals(-40.0,WeatherPolicy.temperatureCelsius(-40.0,"wmoUnit:degF")!!,0.0)
        assertNull(WeatherPolicy.temperatureCelsius(1000.0,"wmoUnit:degF"))
    }
    @Test fun unitsRoundTheConvertedTemperatureAndHandleNegativeValues() {
        assertEquals("77°F",WeatherPolicy.formatTemperature(25.0,"F"))
        assertEquals("25°C",WeatherPolicy.formatTemperature(25.4,"C"))
        assertEquals("-40°F",WeatherPolicy.formatTemperature(-40.0,"F"))
        assertEquals("31°F",WeatherPolicy.formatTemperature(-0.6,"F"))
    }
    @Test(expected=IllegalArgumentException::class) fun invalidDisplayUnitsAreRejected() { WeatherPolicy.formatTemperature(20.0,"Kelvin") }
    @Test fun oldObservationsExpireEvenWhenJustFetched() {
        assertTrue(WeatherPolicy.visible(now-WeatherPolicy.MAX_AGE_MS,now))
        assertFalse(WeatherPolicy.visible(now-WeatherPolicy.MAX_AGE_MS-1,now))
        assertFalse(WeatherPolicy.visible(0,now))
    }
    @Test fun cacheChangesToStaleAtThirtyMinutes() {
        assertFalse(WeatherPolicy.stale(now-WeatherPolicy.FRESH_MS,now))
        assertTrue(WeatherPolicy.stale(now-WeatherPolicy.FRESH_MS-1,now))
        assertTrue(WeatherPolicy.visible(now-WeatherPolicy.FRESH_MS-1,now))
    }
    @Test fun futureTimestampAllowsOnlySmallClockSkew() {
        assertTrue(WeatherPolicy.visible(now+WeatherPolicy.FUTURE_SKEW_MS,now))
        assertFalse(WeatherPolicy.visible(now+WeatherPolicy.FUTURE_SKEW_MS+1,now))
        assertTrue(WeatherPolicy.stale(now+WeatherPolicy.FUTURE_SKEW_MS+1,now))
    }
    @Test fun timestampsParseOffsetsWithoutLocalTimezoneAssumptions() {
        assertEquals(now,WeatherPolicy.parseTimestamp("2026-01-15T07:00:00-05:00"))
        assertEquals(now,WeatherPolicy.parseTimestamp("2026-01-15T12:00:00+00:00"))
        assertNull(WeatherPolicy.parseTimestamp("2026-01-15 12:00"))
        assertNull(WeatherPolicy.parseTimestamp("not a date"))
    }
    @Test fun strongerConditionsTakePrecedenceOverCloudDescription() {
        assertEquals(WeatherCondition.STORM,WeatherPolicy.classify("Thunderstorm and Rain"))
        assertEquals(WeatherCondition.SNOW,WeatherPolicy.classify("Light Freezing Rain"))
        assertEquals(WeatherCondition.RAIN,WeatherPolicy.classify("Mostly Cloudy with Rain Showers"))
        assertEquals(WeatherCondition.PARTLY_CLOUDY,WeatherPolicy.classify("Partly Cloudy"))
        assertEquals(WeatherCondition.CLEAR,WeatherPolicy.classify("Mostly Clear"))
        assertEquals(WeatherCondition.FOG,WeatherPolicy.classify("Fog/Mist"))
        assertEquals(WeatherCondition.UNKNOWN,WeatherPolicy.classify(""))
    }
    @Test fun responseLinksCannotLeaveTheNwsHttpsOrigin() {
        assertTrue(WeatherPolicy.isAllowedNwsUrl("https://api.weather.gov/gridpoints/ABC/1,2/stations"))
        assertTrue(WeatherPolicy.isAllowedNwsUrl("https://api.weather.gov/stations/KAAA/observations/latest?require_qc=true"))
        for(value in listOf("http://api.weather.gov/points/1,2","https://api.weather.gov.attacker.test/x","https://api.weather.gov@attacker.test/x",
            "https://someone@api.weather.gov/x","https://api.weather.gov:8080/x","https://api.weather.gov/x#fragment","file:///etc/passwd",
            "https://api.weather.gov/a/../x","https://api.weather.gov\\@attacker.test/x"))assertFalse(value,WeatherPolicy.isAllowedNwsUrl(value))
    }
    @Test fun backoffIsBoundedAndRetryAfterOverridesShorterDelay() {
        assertEquals(60_000L,WeatherPolicy.retryDelayMillis(1,null,now))
        assertEquals(120_000L,WeatherPolicy.retryDelayMillis(2,null,now))
        assertEquals(WeatherPolicy.FRESH_MS,WeatherPolicy.retryDelayMillis(100,null,now))
        assertEquals(600_000L,WeatherPolicy.retryDelayMillis(1,"600",now))
        assertEquals(86_400_000L,WeatherPolicy.retryDelayMillis(1,"99999999999",now))
        assertEquals(60_000L,WeatherPolicy.retryDelayMillis(1,"invalid",now))
    }
    @Test fun retryAfterHttpDateAndPastDatesAreHandled() {
        val future=DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now+300_000).atZone(ZoneOffset.UTC))
        assertEquals(300_000L,WeatherPolicy.retryDelayMillis(1,future,now))
        val past=DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(now-300_000).atZone(ZoneOffset.UTC))
        assertEquals(60_000L,WeatherPolicy.retryDelayMillis(1,past,now))
    }
    @Test fun locationValidationRejectsNonfiniteOrOutOfRangeCoordinatesAndControlText() {
        val location=WeatherLocation("01234","Example Town","MA",42.0,-72.0)
        assertTrue(WeatherPolicy.validLocation(location))
        assertFalse(WeatherPolicy.validLocation(location.copy(latitude=Double.NaN)))
        assertFalse(WeatherPolicy.validLocation(location.copy(latitude=90.1)))
        assertFalse(WeatherPolicy.validLocation(location.copy(longitude=-180.1)))
        assertFalse(WeatherPolicy.validLocation(location.copy(city="Example\nTown")))
        assertFalse(WeatherPolicy.validLocation(location.copy(region="Massachusetts")))
    }
}
