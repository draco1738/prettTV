package fi.jommi.rivi

import java.io.File
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiscoveryMetadataPolicyTest {
    @get:Rule val temp = TemporaryFolder()
    private fun program(id: String = "provider:1", title: String = "The Office", type: String = "series",
        year: Int = 2005, imdb: String = "") = Program(id, title, "", "", "app", "App",
        mediaType = type, releaseYear = year, imdbId = imdb)

    @Test fun metadataRequestsCannotFollowAnUntrustedRedirect() {
        assertTrue(DiscoveryMetadataPolicy.allowedAddress("https://api.tvmaze.com/shows/1?embed=cast"))
        assertTrue(DiscoveryMetadataPolicy.allowedAddress("https://v3-cinemeta.strem.io/meta/movie/tt0133093.json"))
        listOf("http://api.tvmaze.com/shows/1", "https://api.tvmaze.com.evil.test/shows/1",
            "https://api.tvmaze.com@evil.test/shows/1", "https://evil@api.tvmaze.com/shows/1",
            "https://api.tvmaze.com:8443/shows/1", "https://127.0.0.1/shows/1", "file:///private",
            "https://api.tvmaze.com/shows/1#fragment").forEach {
            assertFalse(it, DiscoveryMetadataPolicy.allowedAddress(it))
        }
    }

    @Test fun exactIdsAndTypesKeepDifferentEditionsSeparate() {
        assertEquals("imdb:movie:tt0133093", DiscoveryMetadataPolicy.cacheKey(program(type = "movie", imdb = "TT0133093")))
        assertEquals("tvmaze:44933", DiscoveryMetadataPolicy.cacheKey(program(id = "tvmaze:44933")))
        assertNotEquals(DiscoveryMetadataPolicy.cacheKey(program(year = 2001)), DiscoveryMetadataPolicy.cacheKey(program(year = 2005)))
        assertNotEquals(DiscoveryMetadataPolicy.cacheKey(program(type = "movie", imdb = "tt0133093")),
            DiscoveryMetadataPolicy.cacheKey(program(type = "series", imdb = "tt0133093")))
        assertEquals(DiscoveryMetadataPolicy.cacheKey(program(title = " The  Office ")),
            DiscoveryMetadataPolicy.cacheKey(program(title = "the office")))
    }

    @Test fun ambiguousOrUnsupportedTitlesNeverCreateSearchRequests() {
        assertNull(DiscoveryMetadataPolicy.cacheKey(program(type = "movie")))
        assertNull(DiscoveryMetadataPolicy.cacheKey(program(year = 0)))
        assertNull(DiscoveryMetadataPolicy.cacheKey(program(type = "tv", imdb = "tt0133093")))
        assertNull(DiscoveryMetadataPolicy.cacheKey(program(title = " ")))
        assertNull(DiscoveryMetadataPolicy.cacheKey(program(imdb = "tt12345/../../path", year = 0)))
        val repo = DiscoveryMetadataRepository(File(temp.root, "metadata"))
        assertNull(repo.get(program(type = "movie")))
        repo.close()
    }

    @Test fun runtimesAreOnlyAcceptedWithAnUnambiguousUnitAndReasonableRange() {
        assertEquals(136, DiscoveryMetadataPolicy.runtime("136 min"))
        assertEquals(120, DiscoveryMetadataPolicy.runtime("120m"))
        assertEquals(147, DiscoveryMetadataPolicy.runtime("2h 27m"))
        assertEquals(60, DiscoveryMetadataPolicy.runtime("1 hour"))
        listOf("", "120", "45-60 min", "about 45 minutes", "0 min", "9999 min", "2h 99m", "-1 min").forEach {
            assertEquals(it, 0, DiscoveryMetadataPolicy.runtime(it))
        }
    }

    @Test fun batchesKeepTheSeedFirstAndAtMostTwelveDistinctPeers() {
        val seed = program(imdb = "tt0000001")
        val candidates = (1..100).map { program(id = "provider:$it", imdb = "tt${it.toString().padStart(7, '0')}") }
        val result = DiscoveryMetadataPolicy.batch(seed, candidates)
        assertEquals(13, result.size)
        assertEquals(seed, result.first())
        assertEquals(13, result.mapNotNull(DiscoveryMetadataPolicy::cacheKey).distinct().size)
    }

    @Test fun unidentifiedProviderTilesCannotConsumeAllMetadataCandidateSlots() {
        val seed = program(imdb = "tt0000001")
        val unidentified = (1..20).map { program(id = "provider:$it", title = "Unknown $it", type = "movie") }
        val known = program(id = "known", imdb = "tt0000002")
        assertEquals(listOf(seed, known), DiscoveryMetadataPolicy.batch(seed, unidentified + known))
    }

    @Test fun invalidAndRepeatedNamesAreDroppedWithoutInventingCredits() {
        assertEquals(listOf("Keanu Reeves", "Carrie-Anne Moss"),
            DiscoveryMetadataPolicy.names(listOf(" Keanu Reeves ", "keanu reeves", "", "null", "a".repeat(121), "Carrie-Anne Moss"), 40))
        assertEquals(listOf("First"), DiscoveryMetadataPolicy.names(listOf("First", "Second"), 1))
    }

    @Test fun cancelledOrClosedRepositoryMakesNoNetworkRequest() {
        val repo = DiscoveryMetadataRepository(File(temp.root, "metadata"))
        val identified = program(imdb = "tt0133093")
        assertNull(repo.get(identified) { false })
        assertTrue(repo.load(identified, listOf(identified)) { false }.isEmpty())
        repo.close()
        assertNull(repo.get(identified))
        assertTrue(repo.load(identified, emptyList()) { true }.isEmpty())
    }

    @Test fun corruptAndOversizedCachesFailClosed() {
        val directory = temp.newFolder("metadata")
        val file = File(directory, "metadata-v1.bin")
        file.writeText("corrupt")
        val repo = DiscoveryMetadataRepository(directory)
        assertNull(repo.cached(program(imdb = "tt0133093")))
        assertNull(repo.get(program(type = "movie")))
        repo.close()
        file.outputStream().use { it.write(ByteArray(900_001)) }
        val oversized = DiscoveryMetadataRepository(directory)
        assertNull(oversized.cached(program(imdb = "tt0133093")))
        oversized.close()
    }

    @Test fun privateCacheRetainsFactsAndCreditsWithoutInventingMissingFields() {
        val directory = temp.newFolder("valid-cache")
        writeCache(directory, System.currentTimeMillis() - 1000)
        val repo = DiscoveryMetadataRepository(directory)
        val cached = repo.cached(program(type = "movie", imdb = "tt0133093"))
        assertNotNull(cached)
        assertEquals(listOf("Keanu Reeves"), cached!!.cast)
        assertEquals(emptyList<String>(), cached.directors)
        assertEquals(136, cached.runtimeMinutes)
        assertEquals("Cinemeta", cached.sourceName)
        assertNull(repo.cached(program(type = "series", imdb = "tt0133093")))
        repo.close()
    }

    @Test fun expiredOrFutureDatedCacheCannotBecomeAnUnboundedOfflineFactStore() {
        val directory = temp.newFolder("expired-cache")
        val movie = program(type = "movie", imdb = "tt0133093")
        writeCache(directory, System.currentTimeMillis() - 31L * 86_400_000)
        DiscoveryMetadataRepository(directory).use { assertNull(it.cached(movie)) }
        writeCache(directory, System.currentTimeMillis() + 86_400_000)
        DiscoveryMetadataRepository(directory).use { assertNull(it.cached(movie)) }
    }

    @Test fun malformedCacheCountsAndTruncatedRecordsAreRejectedBeforeAllocation() {
        val directory = temp.newFolder("bad-count")
        val file = File(directory, "metadata-v1.bin")
        DataOutputStream(file.outputStream()).use {
            it.writeInt(0x50544d44); it.writeInt(1); it.writeInt(Int.MAX_VALUE)
        }
        val movie = program(type = "movie", imdb = "tt0133093")
        DiscoveryMetadataRepository(directory).use { assertNull(it.cached(movie)) }
        writeCache(directory, System.currentTimeMillis())
        file.writeBytes(file.readBytes().dropLast(5).toByteArray())
        DiscoveryMetadataRepository(directory).use { assertNull(it.cached(movie)) }
    }

    private fun writeCache(directory: File, checkedAt: Long) {
        DataOutputStream(File(directory, "metadata-v1.bin").outputStream()).use {
            it.writeInt(0x50544d44); it.writeInt(1); it.writeInt(1)
            it.writeUTF("imdb:movie:tt0133093"); it.writeLong(checkedAt)
            it.writeInt(1); it.writeUTF("Action")
            it.writeInt(1); it.writeUTF("Keanu Reeves")
            it.writeInt(0)
            it.writeInt(136); it.writeInt(1999)
            it.writeUTF("Cinemeta"); it.writeUTF("https://v3-cinemeta.strem.io/meta/movie/tt0133093.json")
        }
    }
}
