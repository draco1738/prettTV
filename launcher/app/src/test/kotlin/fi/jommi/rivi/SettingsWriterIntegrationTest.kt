package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real writer APIs with memory preferences. No Android runtime/filesystem claims. */
class SettingsWriterIntegrationTest {
    private val p=Program("fixture","Fixture show","","","fixture.provider","Fixture",mediaType="series")
    private class Stores {
        val home=SettingsBackupChecks.MemoryPreferences()
        val apps=SettingsBackupChecks.MemoryPreferences()
        val playback=SettingsBackupChecks.MemoryPreferences()
        val personal=SettingsBackupChecks.MemoryPreferences()
        val all=listOf(home,apps,playback,personal)
        val appPrefs=Prefs(apps)
        val weather=WeatherPreferences(home)
        val history=PlaybackHistory(playback)
        val personalization=PersonalizationStore(personal,playback)
    }
    private fun prepare() {
        SettingsBackupGuard.enableAfterWriterIntegration()
        check(!SettingsBackupGuard.busy)
        SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.NONE)
    }
    private fun awaitBusy() {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!SettingsBackupGuard.busy && System.nanoTime()<end)Thread.yield()
        assertTrue("Exclusive writer did not start",SettingsBackupGuard.busy)
    }
    private fun restoreHeld(body:()->Unit) {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val error=AtomicReference<Throwable?>()
        val worker=Thread {
            try { SettingsBackupGuard.exclusive(true,SettingsBackupCancellation()) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) } }
            catch(failure:Throwable) { error.set(failure) }
        }
        worker.start()
        try { assertTrue(entered.await(5,TimeUnit.SECONDS));body() }
        finally { release.countDown();worker.join(5_000) }
        assertFalse(worker.isAlive);assertNull(error.get())
    }
    @Test fun actualAppsWeatherPlaybackAndPersonalizationWritersRejectBusyRestoreWithoutEditors() {
        prepare();val s=Stores()
        restoreHeld {
            s.appPrefs.favourites=listOf("new");s.appPrefs.hidden=setOf("new")
            s.appPrefs.favouritesInitialised=true;s.appPrefs.lastFocus="new";s.appPrefs.theme="new";s.appPrefs.motion="new"
            s.appPrefs.touchRecent("new")
            assertFalse(s.weather.units("C"));assertFalse(s.weather.enabled(true))
            assertFalse(s.weather.location(WeatherLocation("10001","Fixture","NY",40.0,-74.0)))
            assertFalse(s.history.confirm(p));assertEquals(emptyList<Program>(),s.history.merge(listOf(p)))
            assertFalse(s.personalization.save(p));assertFalse(s.personalization.follow(p))
            s.personalization.setRowPreferences(RowPreferences(hidden=setOf("watchlist")))
            s.personalization.setFavoriteTeams(setOf("fixture"));s.personalization.setKickoffRemindersEnabled(true)
            s.personalization.dismissRecommendation(p)
            assertFalse(s.personalization.importJson("{\"version\":1}"))
            assertTrue(s.all.all { it.editors==0 })
        }
        assertTrue(s.all.all { it.values.isEmpty() }) // rejected actions never replay
        assertTrue(s.personalization.save(p));assertTrue(s.history.confirm(p))
        assertTrue(s.weather.units("C"));s.appPrefs.touchRecent("fresh")
        assertTrue(s.all.all { it.editors>0 })
    }
    @Test fun staleScheduledProviderAndPlaybackWorkCannotEditRestoredStores() {
        prepare();val s=Stores();val old=SettingsBackupGuard.generation
        SettingsBackupGuard.exclusive(true,SettingsBackupCancellation()) {}
        assertEquals(emptyList<Program>(),s.history.merge(listOf(p),old))
        assertFalse(s.history.confirm(p,settingsGeneration=old))
        assertFalse(s.weather.location(WeatherLocation("10001","Fixture","NY",40.0,-74.0),old))
        assertEquals(0,s.personalization.consumePlaybackHistory(listOf(p),old))
        s.personalization.rememberDismissedTitles(listOf(p),old)
        assertTrue(s.all.all { it.editors==0 })
        assertTrue(s.history.confirm(p,settingsGeneration=SettingsBackupGuard.generation))
    }
    @Test fun lateUndoBookkeepingCannotReintroducePreRestoreActions() {
        prepare();val s=Stores();val old=SettingsBackupGuard.generation
        SettingsBackupGuard.exclusive(true,SettingsBackupCancellation()) {}
        s.personalization.recordUndo("stale",old) { fail("Stale undo ran") }
        assertNull(s.personalization.undoLabel());assertNull(s.personalization.undo())
        s.personalization.recordUndo("fresh") {}
        assertEquals("fresh",s.personalization.undo())
    }
    @Test fun restoreEpochInvalidatesUndoAcrossAllPersonalizationInstances() {
        prepare();val s=Stores();val other=PersonalizationStore(s.personal,s.playback)
        assertTrue(s.personalization.save(p));assertTrue(other.follow(p))
        assertNotNull(s.personalization.undoLabel());assertNotNull(other.undoLabel())
        SettingsBackupGuard.exclusive(true,SettingsBackupCancellation()) {}
        assertNull(s.personalization.undoLabel());assertNull(other.undoLabel());assertNull(other.undo())
        assertTrue(s.personalization.isSaved(p));assertTrue(other.isFollowed(p))
        assertTrue(s.personalization.removeSaved(p));assertEquals("Remove from Watchlist",s.personalization.undo())
        assertTrue(s.personalization.isSaved(p))
    }
    @Test fun recoveryRequiredStopsActualAutomaticAndInteractiveWriters() {
        prepare();val s=Stores()
        SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.RECOVERY_REQUIRED)
        try {
            s.appPrefs.touchRecent("blocked");assertFalse(s.weather.enabled(true))
            assertFalse(s.history.confirm(p));assertFalse(s.personalization.save(p))
            assertTrue(s.all.all { it.editors==0 })
        } finally { SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.NONE) }
    }
    @Test fun inFlightReadModifyWriteCanFinishNestedStoreCallsBeforeRestoreAcquiresLock() {
        prepare();val entered=CountDownLatch(1);val error=AtomicReference<Throwable?>()
        val worker=Thread {
            try { SettingsBackupGuard.exclusive(true,SettingsBackupCancellation()) { entered.countDown() } }
            catch(failure:Throwable) { error.set(failure) }
        }
        try {
            assertTrue(SettingsBackupGuard.tryWrite {
                worker.start();awaitBusy()
                assertEquals(1L,entered.count)
                assertTrue("Already admitted writer must finish its nested store mutation",SettingsBackupGuard.tryWrite {})
            })
        } finally { worker.join(5_000) }
        assertFalse(worker.isAlive);assertNull(error.get());assertEquals(0L,entered.count)
    }
    @Test fun diagnosticRestoreUsesExclusiveEpochAndRejectsAConcurrentRestore() {
        prepare();val before=SettingsBackupGuard.generation
        SettingsBackupGuard.diagnosticRestore {
            assertTrue(SettingsBackupGuard.busy)
            assertFalse(SettingsBackupGuard.tryWrite { fail("Diagnostic restore admitted a writer") })
        }
        assertTrue(SettingsBackupGuard.generation>before)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
        restoreHeld { assertTrue(runCatching { SettingsBackupGuard.diagnosticRestore { fail("Concurrent restore ran") } }.isFailure) }
    }
}
