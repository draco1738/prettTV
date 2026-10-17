package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class ArtworkRequestTest {
    @Test fun leavingAndReturningHomeRetriesCancelledWork() {
        val request=ArtworkRequest()
        assertTrue(request.begin(100))
        assertFalse(request.begin(101))
        request.cancel()
        assertTrue(request.begin(102))
    }
    @Test fun failedOrRejectedWorkRetriesAfterBackoffWithoutBusyLoop() {
        val request=ArtworkRequest()
        request.begin(100)
        request.complete(false,200)
        assertFalse(request.begin(30_199))
        assertTrue(request.begin(30_200))
        request.complete(true,30_201)
        assertTrue(request.begin(30_202))
    }
    @Test fun lifecycleChangesDoNotBypassOfflineBackoff() {
        val request=ArtworkRequest()
        request.begin(1)
        request.complete(false,2)
        request.cancel()
        assertFalse(request.begin(3))
        assertTrue(request.begin(30_002))
    }
}
