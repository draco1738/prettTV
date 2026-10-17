package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ArtworkDiskCacheTest {
    @get:Rule val temporary=TemporaryFolder()
    private val url="https://example.invalid/art.jpg"

    @Test fun concurrentReplacementNeverExposesAPartialImage() {
        val dir=temporary.newFolder()
        val writer=ArtworkDiskCache(dir)
        val reader=ArtworkDiskCache(dir)
        val first=ByteArray(32_000) { 1 }; val second=ByteArray(64_000) { 2 }
        writer.write(url,first)
        val executor=Executors.newSingleThreadExecutor()
        try {
            val writes=executor.submit { repeat(100) { writer.write(url,if(it%2==0)second else first) } }
            repeat(500) {
                val bytes=reader.read(url)
                assertNotNull(bytes)
                assertTrue(bytes!!.contentEquals(first) || bytes.contentEquals(second))
            }
            writes.get(10,TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
    @Test fun invalidImageCanBeDiscardedAndRecovered() {
        val cache=ArtworkDiskCache(temporary.newFolder())
        cache.write(url,byteArrayOf(1,2))
        cache.discard(url)
        assertNull(cache.read(url))
        cache.write(url,byteArrayOf(3,4))
        assertArrayEquals(byteArrayOf(3,4),cache.read(url))
    }
    @Test fun unavailableCacheDoesNotThrowIntoArtworkDelivery() {
        val cache=ArtworkDiskCache(temporary.newFile())
        cache.write(url,byteArrayOf(1,2))
        assertNull(cache.read(url))
    }
}
