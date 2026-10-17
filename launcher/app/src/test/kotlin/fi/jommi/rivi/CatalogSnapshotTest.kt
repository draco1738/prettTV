package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class CatalogSnapshotTest {
    private val stremio="com.stremio.one"
    private fun card(id:String,type:String="series")=Program(id,id,"","",stremio,"Stremio",mediaType=type,catalog=true)

    @Test fun backgroundRefreshAndOfflineFailureRetainVisibleCatalogCards() {
        val saved=CatalogSnapshot().updated("series",listOf(card("series")),listOf(card("new")))
        val initial=saved.eligible(true,setOf(stremio),emptySet())
        assertEquals(saved,initial)
        assertEquals(initial,initial.updated("series",null,null))
    }
    @Test fun validEmptyResponseClearsRowsButFailureDoesNot() {
        val saved=CatalogSnapshot().updated("series",listOf(card("old")),listOf(card("release")))
        val updated=saved.updated("series",emptyList(),null)
        assertTrue(updated.browse().isEmpty())
        assertEquals(saved.releases,updated.releases)
    }
    @Test fun disabledUninstalledOrHiddenCatalogCannotLeakRetainedResults() {
        val saved=CatalogSnapshot(seeds=listOf(card("seed"))).updated("series",listOf(card("top")),listOf(card("new")))
        for(filtered in listOf(saved.eligible(false,setOf(stremio),emptySet()),saved.eligible(true,emptySet(),emptySet()),
            saved.eligible(true,setOf(stremio),setOf(stremio)))) {
            assertTrue(filtered.seeds.isEmpty())
            assertTrue(filtered.browse().isEmpty())
            assertTrue(filtered.releases.values.flatten().isEmpty())
        }
    }
    @Test fun partialRefreshKeepsOtherMediaTypeAndProviderOrder() {
        val saved=CatalogSnapshot().updated("series",listOf(card("s1"),card("s2")),null)
            .updated("movie",listOf(card("m1","movie"),card("m2","movie")),null)
        val updated=saved.updated("series",listOf(card("fresh")),null).updated("movie",null,null)
        assertEquals(listOf("fresh","m1","m2"),updated.browse().map { it.id })
    }
    @Test fun retainedNewReleasesStillAgeOut() {
        val now=1_800_000_000_000L
        val old=card("old").copy(released=now-121L*86_400_000)
        val recent=card("new").copy(released=now-86_400_000)
        val saved=CatalogSnapshot().updated("series",null,listOf(old,recent))
        assertEquals(listOf("new"),saved.recent(now).map { it.id })
    }
}
