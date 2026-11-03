package fi.jommi.rivi

import fi.jommi.rivi.UpdateSessionRecovery.Outcome.*
import org.junit.Assert.*
import org.junit.Test

class UpdateSessionRecoveryTest {
    private val own="dev.zane.pretttv"
    private class Fixture(var session:UpdateSessionRecovery.Session?):UpdateSessionRecovery.Sessions {
        val abandoned=ArrayList<Int>()
        var reads=0
        var failReadAt=0
        var failAbandon=false
        var retainAfterAbandon=false
        var beforeRead:((Int)->Unit)?=null
        override fun read(id:Int):UpdateSessionRecovery.Session? {
            reads++;beforeRead?.invoke(reads)
            if(reads==failReadAt)throw IllegalStateException("Installer unavailable")
            return session
        }
        override fun abandon(id:Int) {
            abandoned+=id
            if(failAbandon)throw IllegalStateException("Cannot abandon")
            if(!retainAfterAbandon)session=null
        }
    }
    private fun fixture(sealed:Boolean=false)=Fixture(UpdateSessionRecovery.Session(own,own,sealed))
    private fun recover(f:Fixture)=UpdateSessionRecovery.recover(42,own,f)

    @Test fun interruptedStagingIsAbandonedAndItsRemovalVerified() {
        val f=fixture()
        assertEquals(RECOVERED,recover(f));assertEquals(listOf(42),f.abandoned)
        assertNull(f.session);assertEquals(3,f.reads)
    }
    @Test fun committedConfirmationIsPreservedWithoutAbandoningOrRecommitting() {
        val f=fixture(sealed=true)
        assertEquals(WAITING_FOR_ANDROID,recover(f));assertTrue(f.abandoned.isEmpty());assertTrue(f.session!!.sealed)
    }
    @Test fun missingOrUnrecordedSessionDoesNotAbandonAnything() {
        val f=Fixture(null)
        assertEquals(ABSENT,recover(f));assertTrue(f.abandoned.isEmpty())
        assertEquals(ABSENT,UpdateSessionRecovery.recover(-1,own,f));assertEquals(1,f.reads)
    }
    @Test fun failedReadNeverLooksLikeAbsenceAndCanBeRetried() {
        for(at in listOf(1,2)) {
            val f=fixture().apply { failReadAt=at }
            assertEquals(RETRY_REQUIRED,recover(f));assertTrue(f.abandoned.isEmpty());assertNotNull(f.session)
            f.failReadAt=0
            assertEquals(RECOVERED,recover(f))
        }
    }
    @Test fun failedAbandonOrUnverifiedRemovalRemainsRetryable() {
        val failed=fixture().apply { failAbandon=true }
        assertEquals(RETRY_REQUIRED,recover(failed));assertNotNull(failed.session)
        val retained=fixture().apply { retainAfterAbandon=true }
        assertEquals(RETRY_REQUIRED,recover(retained));assertNotNull(retained.session)
        val unreadable=fixture().apply { failReadAt=3 }
        assertEquals(RETRY_REQUIRED,recover(unreadable))
    }
    @Test fun sessionBecomingSealedBeforeCleanupIsLeftForAndroid() {
        val f=fixture()
        f.beforeRead={ if(it==2)f.session=f.session!!.copy(sealed=true) }
        assertEquals(WAITING_FOR_ANDROID,recover(f));assertTrue(f.abandoned.isEmpty())
    }
    @Test fun sessionDisappearingBeforeCleanupNeedsNoAbandon() {
        val f=fixture()
        f.beforeRead={ if(it==2)f.session=null }
        assertEquals(ABSENT,recover(f));assertTrue(f.abandoned.isEmpty())
    }
    @Test fun foreignInstallerAndOtherTargetAppAreNeverAbandoned() {
        for(session in listOf(UpdateSessionRecovery.Session("other",own,false),
            UpdateSessionRecovery.Session(own,"other",false),UpdateSessionRecovery.Session(null,own,false))) {
            val f=Fixture(session)
            assertEquals(RETRY_REQUIRED,recover(f));assertTrue(f.abandoned.isEmpty())
        }
    }
}
