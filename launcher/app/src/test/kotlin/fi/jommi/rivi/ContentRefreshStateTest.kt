package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class ContentRefreshStateTest {
    @Test fun fastCompletionCannotFlashUpdatingEvenIfUiCallbackRunsLate() {
        val refresh=ContentRefreshState()
        refresh.begin(1,100,true)
        assertTrue(refresh.finish(1,false,true))
        assertFalse(refresh.reveal(1,10_000))
        assertEquals(ContentRefreshStatus(),refresh.snapshot())
    }

    @Test fun slowWorkAppearsOnlyAfterDelayAndFinishesWithoutAContentChange() {
        val refresh=ContentRefreshState()
        val unchanged=HomeContent(suggestions=listOf(program("existing")))
        refresh.begin(1,100,true)
        assertFalse(refresh.reveal(1,799))
        assertTrue(refresh.reveal(1,800))
        val busy=refresh.snapshot().applyTo(unchanged)
        assertTrue(busy.refreshing)
        assertSame(unchanged.suggestions,busy.suggestions)
        assertTrue(refresh.finish(1,false,true))
        assertFalse(refresh.snapshot().applyTo(busy).refreshing)
    }

    @Test fun replacingARequestRejectsItsLateIndicatorAndCompletion() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,true)
        assertTrue(refresh.reveal(1,700))
        assertTrue(refresh.cancel())
        refresh.begin(2,1000,true)
        assertFalse(refresh.reveal(1,2000))
        assertFalse(refresh.finish(1,true,false))
        assertEquals(ContentRefreshStatus(),refresh.snapshot())
        assertTrue(refresh.reveal(2,1700))
        assertTrue(refresh.finish(2,false,true))
        assertEquals(ContentRefreshStatus(),refresh.snapshot())
    }

    @Test fun cancellationClearsBusyAndCannotBeUndoneByAnOldCallback() {
        val refresh=ContentRefreshState()
        refresh.begin(2,0,false)
        refresh.reveal(2,700)
        assertTrue(refresh.cancel())
        assertFalse(refresh.snapshot().refreshing)
        assertTrue(refresh.snapshot().offline)
        assertFalse(refresh.finish(2,true,true))
        assertFalse(refresh.reveal(2,10_000))
    }

    @Test fun cancellingBeforeDelayDoesNotInventATerminalFailure() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,true)
        assertFalse(refresh.cancel())
        assertFalse(refresh.finish(1,true,true))
        assertEquals(ContentRefreshStatus(),refresh.snapshot())
    }

    @Test fun providerFailureWithANetworkIsNotOffline() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,true)
        refresh.reveal(1,700)
        refresh.finish(1,true,true)
        assertEquals(ContentRefreshStatus(failed=true),refresh.snapshot())
    }

    @Test fun aLostNetworkIsReportedAsOfflineInsteadOfAServiceFailure() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,true)
        refresh.finish(1,true,false)
        assertEquals(ContentRefreshStatus(offline=true),refresh.snapshot())
    }

    @Test fun unknownConnectivityDoesNotSupportAnOfflineClaim() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,null)
        refresh.finish(1,true,null)
        assertEquals(ContentRefreshStatus(failed=true),refresh.snapshot())
    }

    @Test fun aSuccessfulRefreshClearsEarlierFailureAndOfflineState() {
        val refresh=ContentRefreshState()
        refresh.begin(1,0,false)
        refresh.finish(1,true,false)
        refresh.begin(2,1000,true)
        refresh.finish(2,false,true)
        assertEquals(ContentRefreshStatus(),refresh.snapshot())
    }

    @Test fun statusOnlyUpdatesKeepCardObjectsArtworkOrderAndCacheAge() {
        val cards=listOf(program("first"),program("second"))
        val content=HomeContent(watching=cards,suggestions=cards,trending=cards,discover=cards,newReleases=cards,snapshotAt=1234)
        val result=ContentRefreshStatus(failed=true).applyTo(content)
        assertSame(content.watching,result.watching)
        assertSame(content.suggestions,result.suggestions)
        assertSame(content.trending,result.trending)
        assertSame(content.discover,result.discover)
        assertSame(content.newReleases,result.newReleases)
        assertSame(content.feature,result.feature)
        assertEquals(1234L,result.snapshotAt)
        assertEquals("https://example.test/first.jpg",result.watching.first().art)
    }

    @Test fun failedRefreshStatusDoesNotExtendTheRecommendationCachePolicy() {
        val now=OfflineSnapshot.MAX_AGE+10_000
        val rows=listOf(program("old"))
        val snapshot=OfflineSnapshot(shared=SharedFeedSnapshot(published=rows,watching=rows),
            catalog=CatalogSnapshot(seeds=rows),sharedAt=1,catalogAt=1).eligible(now)
        val content=HomeContent(watching=snapshot.shared.watching,suggestions=snapshot.shared.published,
            trending=snapshot.catalog.seeds,snapshotAt=maxOf(snapshot.sharedAt,snapshot.catalogAt))
        val result=ContentRefreshStatus(failed=true).applyTo(content)
        assertTrue(result.suggestions.isEmpty())
        assertTrue(result.trending.isEmpty())
        assertEquals(rows,result.watching)
        assertEquals(1L,result.snapshotAt)
    }

    private fun program(id:String)=Program(id,id,"","https://example.test/$id.jpg","test.pkg","Test")
}
