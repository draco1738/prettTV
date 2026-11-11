package fi.jommi.rivi

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real parser/filesystem; fake preferences model commit's memory-before-disk behavior. */
class SettingsBackupChecks {
    private fun stores()=linkedMapOf<String,MemoryPreferences>(
        "home" to MemoryPreferences(linkedMapOf("layout" to "BALANCED","interfaceSize" to "STANDARD",
            "hiddenWatchingTitles" to setOf("hidden-title"),"hiddenTitleLabel:hidden-title" to "Hidden title")),
        "apps" to MemoryPreferences(linkedMapOf("fav" to "one,two","hidden" to setOf("three"))),
        "playback" to MemoryPreferences(linkedMapOf("known" to "[]","played:sample" to 123L)),
        PersonalizationStore.PREFS_NAME to MemoryPreferences(linkedMapOf("state" to
            """{"version":1,"watchlist":[{"program":{"title":"Saved show","package":"test.app"},"savedAt":12}],"rows":{"order":["shows"],"hidden":["sports"]}}""")),
    )
    private fun cancel()=SettingsBackupCancellation()
    private fun encoded(root:JSONObject)=Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
    private fun root(stores:Map<String,SharedPreferences>)=JSONObject(String(Base64.getDecoder().decode(LocalSettingsTransfer.export(stores)),Charsets.UTF_8))
    private fun editors(stores:Map<String,MemoryPreferences>)=stores.values.sumOf { it.editors }
    private fun <T> fixture(test:(File,LinkedHashMap<String,MemoryPreferences>,LocalSettingsBackup)->T):T {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val priorState=SettingsBackupGuard.restoreState
        SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.NONE)
        val directory=Files.createTempDirectory("settings-backup-check-").toFile()
        try { val stores=stores();return test(directory,stores,LocalSettingsBackup(directory,stores,{1_700_000_000_000L})) }
        finally { directory.deleteRecursively();SettingsBackupGuard.recordRestore(priorState) }
    }
    private fun rejects(action:()->Unit):Throwable {
        try { action() } catch(failure:Exception) { return failure }
        throw AssertionError("Invalid input was accepted")
    }

    @Test fun validationPreparesEveryStoreWithoutAnEditor() {
        val stores=stores();val text=LocalSettingsTransfer.export(stores)
        LocalSettingsTransfer.prepare(text,stores.keys,true)
        assertEquals(0,editors(stores))
        val invalid=root(stores).apply { getJSONObject("playback").getJSONObject("played:sample").put("type","string").put("value","123") }
        rejects { LocalSettingsTransfer.prepare(encoded(invalid),stores.keys,true) }
        assertEquals(0,editors(stores))
    }

    @Test fun subsetRestoreStillValidatesNonTargetStores() {
        val stores=stores();val invalid=root(stores)
        invalid.getJSONObject("apps").getJSONObject("hidden").put("type","invalid")
        rejects { LocalSettingsTransfer.restore(encoded(invalid),mapOf("home" to stores.getValue("home"))) }
        assertEquals(0,editors(stores))
    }

    @Test fun unknownDeviceStoreMissingStoreVersionAndNestedStateRejectedBeforeEdits() {
        val stores=stores()
        val cases=listOf<(JSONObject)->Unit>(
            { it.put("stock-recovery",JSONObject()) },
            { it.remove("apps") },
            { it.put("version",2) },
            { it.getJSONObject(PersonalizationStore.PREFS_NAME).getJSONObject("state").put("value","{\"watchlist\":[1]}") },
            { it.getJSONObject("home").put("weatherUnits",JSONObject().put("type","string").put("value","K")) },
        )
        cases.forEach { mutate -> rejects { LocalSettingsTransfer.prepare(encoded(root(stores).also(mutate)),stores.keys,true) } }
        assertEquals(0,editors(stores))
    }

    @Test fun invalidBase64Utf8TrailingJsonAndOversizeRejected() {
        val stores=stores()
        val good=String(Base64.getDecoder().decode(LocalSettingsTransfer.export(stores)),Charsets.UTF_8)
        listOf("!not-base64!",Base64.getEncoder().encodeToString(byteArrayOf(0xc3.toByte(),0x28)),
            Base64.getEncoder().encodeToString((good+" garbage").toByteArray()),"A".repeat(LocalSettingsBackup.MAX_PAYLOAD+1)).forEach {
            rejects { LocalSettingsTransfer.prepare(it,stores.keys,true) }
        }
        assertEquals(0,editors(stores))
    }

    @Test fun legacyPersonalizationDefaultsRemainCompatibleButTvSnapshotRequiresIt() {
        val stores=stores();val text=LocalSettingsTransfer.export(stores.filterKeys { it!=PersonalizationStore.PREFS_NAME })
        rejects { LocalSettingsTransfer.prepare(text,stores.keys,true) }
        LocalSettingsTransfer.restore(text,stores)
        assertTrue(stores.getValue(PersonalizationStore.PREFS_NAME).all.isEmpty())
    }

    @Test fun successfulAtomicSaveAndRestorePreserveAllAppOwnedData()=fixture { dir,stores,backup ->
        val expected=stores.mapValues { it.value.all }
        val snapshot=backup.save(cancel())
        assertEquals(1_700_000_000_000L,snapshot.savedAt)
        assertEquals(0,editors(stores))
        stores.getValue("home").values["layout"]="QUIET"
        backup.restore(backup.read(LocalSettingsBackup.Slot.SAVED,cancel()),LocalSettingsBackup.Slot.SAVED,cancel())
        assertEquals(expected,stores.mapValues { it.value.all })
        assertEquals("QUIET",backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel()).plan.values.getValue("home")["layout"])
        assertTrue(File(dir,"saved.snapshot").isFile)
        assertFalse(File(dir,"pending.snapshot").exists())
    }

    @Test fun eachSupportedPreferenceTypeRoundTrips() {
        val prefs=MemoryPreferences(linkedMapOf("s" to "text","b" to true,"i" to 4,"l" to 5L,"f" to 2.5f,"set" to setOf("a","b")))
        val stores=mapOf("apps" to prefs);val old=prefs.all
        val saved=LocalSettingsTransfer.export(stores);prefs.values.clear()
        LocalSettingsTransfer.restore(saved,stores)
        assertEquals(old,prefs.all)
    }

    @Test fun corruptTruncatedAndOversizeFilesCannotBecomePreviews()=fixture { dir,stores,backup ->
        backup.save(cancel());val file=File(dir,"saved.snapshot");val bytes=file.readBytes()
        listOf(bytes.copyOf(bytes.size-1),bytes.clone().apply { this[30]=(this[30].toInt() xor 1).toByte() },bytes+1.toByte()).forEach {
            file.writeBytes(it);rejects { backup.read(LocalSettingsBackup.Slot.SAVED,cancel()) }
            assertTrue(backup.status(LocalSettingsBackup.Slot.SAVED,cancel()).invalid)
        }
        RandomAccessFile(file,"rw").use { it.setLength(LocalSettingsBackup.MAX_FILE.toLong()+1) }
        rejects { backup.read(LocalSettingsBackup.Slot.SAVED,cancel()) }
        assertEquals(0,editors(stores))
    }

    @Test fun failedAtomicReplacementLeavesLastGoodSnapshotByteForByte()=fixture { dir,stores,backup ->
        backup.save(cancel());val file=File(dir,"saved.snapshot");val previous=file.readBytes()
        stores.getValue("home").values["layout"]="QUIET"
        val failing=LocalSettingsBackup(dir,stores,atomicReplace={ _,_ -> throw IOException("Injected rename failure") })
        rejects { failing.save(cancel()) }
        assertArrayEquals(previous,file.readBytes());assertFalse(File(dir,"pending.snapshot").exists())
        assertEquals("BALANCED",backup.read(LocalSettingsBackup.Slot.SAVED,cancel()).plan.values.getValue("home")["layout"])
    }

    @Test fun failedSyncLeavesLastGoodSnapshotAndNeverPublishesTemp()=fixture { dir,stores,backup ->
        backup.save(cancel());val file=File(dir,"saved.snapshot");val previous=file.readBytes()
        val failing=LocalSettingsBackup(dir,stores,sync={ throw IOException("Injected fsync failure") })
        rejects { failing.save(cancel()) }
        assertArrayEquals(previous,file.readBytes());assertFalse(File(dir,"pending.snapshot").exists())
    }

    @Test fun cancellationBeforeSaveAndDuringStagingCannotPublish()=fixture { dir,stores,backup ->
        val revoked=cancel().apply { cancel() }
        assertTrue(rejects { backup.save(revoked) } is CancellationException)
        assertFalse(File(dir,"saved.snapshot").exists())
        backup.save(cancel());val previous=File(dir,"saved.snapshot").readBytes()
        val during=cancel()
        val cancelling=LocalSettingsBackup(dir,stores,sync={ it.fd.sync();during.cancel() })
        assertTrue(rejects { cancelling.save(during) } is CancellationException)
        assertArrayEquals(previous,File(dir,"saved.snapshot").readBytes())
        assertFalse(File(dir,"pending.snapshot").exists())
    }

    @Test fun failedRecoverySnapshotPreventsFirstPreferenceEditor()=fixture { dir,stores,backup ->
        val snapshot=backup.save(cancel());val failing=LocalSettingsBackup(dir,stores,atomicReplace={ _,_ -> throw IOException("No space") })
        rejects { failing.restore(snapshot,LocalSettingsBackup.Slot.SAVED,cancel()) }
        assertEquals(0,editors(stores))
    }

    @Test fun cancelledRestoreBeforeFirstWriteDoesNotOpenEditors()=fixture { _,stores,backup ->
        val snapshot=backup.save(cancel());val revoked=cancel().apply { cancel() }
        assertTrue(rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,revoked) } is CancellationException)
        assertEquals(0,editors(stores))
    }

    @Test fun commitFailureRestoresOriginalMemoryAndReportsRollback()=fixture { _,stores,backup ->
        val snapshot=backup.save(cancel());stores.getValue("home").values["layout"]="QUIET"
        val old=stores.mapValues { it.value.all }
        stores.getValue("apps").failCommitNumbers+=1
        val failure=rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,cancel()) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && failure.rollbackSucceeded)
        assertEquals(old,stores.mapValues { it.value.all })
        assertEquals(0,stores.getValue("playback").editors)
    }

    @Test fun rollbackDiskFailureIsNotReportedAsSuccess()=fixture { _,stores,backup ->
        val snapshot=backup.save(cancel());stores.getValue("apps").failCommitNumbers.addAll(listOf(1,2))
        val failure=rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,cancel()) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && !failure.rollbackSucceeded)
        assertNotNull(backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel()))
        assertTrue(SettingsBackupGuard.recoveryRequired)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Writer ran with unverified rollback") })
        rejects { backup.save(cancel()) }
        rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,cancel()) }
        stores.getValue("apps").failCommitNumbers.clear()
        backup.restore(backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel()),LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel())
        assertFalse(SettingsBackupGuard.recoveryRequired)
    }

    @Test fun cancellationAfterCommitStopsForwardWritesAndRollsBackTouchedStore()=fixture { _,stores,backup ->
        val snapshot=backup.save(cancel());stores.getValue("home").values["layout"]="QUIET"
        val old=stores.mapValues { it.value.all };val token=cancel()
        stores.getValue("home").afterCommit={ token.cancel() }
        val failure=rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,token) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && failure.rollbackSucceeded)
        assertEquals(old,stores.mapValues { it.value.all })
        assertEquals(0,stores.getValue("apps").editors)
    }

    @Test fun recoveryRestoreKeepsBothSavedFilesUnchanged()=fixture { dir,stores,backup ->
        val original=backup.save(cancel());stores.getValue("home").values["layout"]="QUIET"
        backup.restore(original,LocalSettingsBackup.Slot.SAVED,cancel())
        val first=File(dir,"saved.snapshot").readBytes();val second=File(dir,"before-restore.snapshot").readBytes()
        backup.restore(backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel()),LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel())
        assertEquals("QUIET",stores.getValue("home").values["layout"])
        assertArrayEquals(first,File(dir,"saved.snapshot").readBytes());assertArrayEquals(second,File(dir,"before-restore.snapshot").readBytes())
    }

    @Test fun restoreGuardRejectsConcurrentAndOldGenerationWriters() {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val before=SettingsBackupGuard.generation;val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val failure=AtomicReference<Throwable?>()
        val thread=Thread {
            try { SettingsBackupGuard.exclusive(true,cancel()) { entered.countDown();check(release.await(5,TimeUnit.SECONDS)) } }
            catch(error:Throwable) { failure.set(error) }
        }.apply { start() }
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertTrue(SettingsBackupGuard.busy)
            assertFalse(SettingsBackupGuard.tryWrite(before) { fail("Concurrent writer ran") })
        } finally { release.countDown();thread.join(5_000) }
        assertFalse(thread.isAlive);assertNull(failure.get());assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite(before) { fail("Stale provider writer ran") })
        var ran=false;assertTrue(SettingsBackupGuard.tryWrite { ran=true });assertTrue(ran)
    }

    @Test fun unintegratedGuardFailsClosedBeforeFileOrPreferenceWrites()=fixture { dir,stores,backup ->
        val field=SettingsBackupGuard::class.java.getDeclaredField("integrated").apply { isAccessible=true }
        field.setBoolean(SettingsBackupGuard,false)
        try {
            rejects { backup.save(cancel()) }
            assertEquals(0,editors(stores));assertFalse(File(dir,"saved.snapshot").exists())
        } finally { SettingsBackupGuard.enableAfterWriterIntegration() }
    }

    @Test fun stagingCorruptionCannotOverwriteLastGoodSnapshot()=fixture { dir,stores,backup ->
        backup.save(cancel());val previous=File(dir,"saved.snapshot").readBytes()
        val corrupted=LocalSettingsBackup(dir,stores,sync={ it.channel.truncate(1);it.fd.sync() })
        rejects { corrupted.save(cancel()) }
        assertArrayEquals(previous,File(dir,"saved.snapshot").readBytes())
        assertFalse(File(dir,"pending.snapshot").exists())
    }

    @Test fun previewRestoresExactValidatedBytesEvenIfSavedFileLaterChanges()=fixture { dir,stores,backup ->
        val preview=backup.save(cancel())
        stores.getValue("home").values["layout"]="QUIET";backup.save(cancel())
        val second=File(dir,"saved.snapshot").readBytes()
        backup.restore(preview,LocalSettingsBackup.Slot.SAVED,cancel())
        assertEquals("BALANCED",stores.getValue("home").values["layout"])
        assertArrayEquals(second,File(dir,"saved.snapshot").readBytes())
    }

    @Test fun confirmedMemoryMismatchTriggersRollbackNotSuccess()=fixture { _,stores,backup ->
        val snapshot=backup.save(cancel());stores.getValue("home").values["layout"]="QUIET"
        stores.getValue("home").afterCommit={
            if(stores.getValue("home").commits==1)stores.getValue("home").values["layout"]="wrong"
        }
        val failure=rejects { backup.restore(snapshot,LocalSettingsBackup.Slot.SAVED,cancel()) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && failure.rollbackSucceeded)
        assertEquals("QUIET",stores.getValue("home").values["layout"])
    }

    /** Same validated four-store path as MainActivity's explicit diagnostic import. */
    private fun diagnosticImport(text:String,stores:Map<String,SharedPreferences>) {
        val plan=LocalSettingsTransfer.prepare(text,stores.keys)
        SettingsBackupGuard.diagnosticRestore(rollbackVerified={
            it !is LocalSettingsTransfer.RestoreFailure || it.rollbackSucceeded
        }) { LocalSettingsTransfer.restore(plan,stores) }
    }

    /** Enter recovery through a real failed commit/rollback, never by resetting the gate. */
    private fun failedDiagnosticImport(stores:Map<String,MemoryPreferences>):String {
        val exported=LocalSettingsTransfer.export(stores)
        stores.getValue("home").values["layout"]="QUIET"
        val apps=stores.getValue("apps")
        apps.failCommitNumbers.addAll(listOf(apps.commits+1,apps.commits+2))
        val failure=rejects { diagnosticImport(exported,stores) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && !failure.rollbackSucceeded)
        apps.failCommitNumbers.clear()
        assertEquals(SettingsBackupGuard.RestoreState.RECOVERY_REQUIRED,SettingsBackupGuard.restoreState)
        assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Unverified rollback admitted a writer") })
        return exported
    }

    @Test fun ordinaryDiagnosticRollbackStillAllowsFreshWritersWhenVerified()=fixture { _,stores,_ ->
        val exported=LocalSettingsTransfer.export(stores)
        stores.getValue("home").values["layout"]="QUIET"
        val previous=stores.mapValues { it.value.all };val before=SettingsBackupGuard.generation
        stores.getValue("apps").failCommitNumbers+=1
        val failure=rejects { diagnosticImport(exported,stores) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && failure.rollbackSucceeded)
        assertEquals(previous,stores.mapValues { it.value.all })
        assertEquals(SettingsBackupGuard.RestoreState.ROLLED_BACK,SettingsBackupGuard.restoreState)
        assertFalse(SettingsBackupGuard.busy);assertEquals(before+2,SettingsBackupGuard.generation)
        assertFalse(SettingsBackupGuard.tryWrite(before) { fail("Stale writer survived ordinary rollback") })
        assertTrue(SettingsBackupGuard.tryWrite {})
    }

    @Test fun explicitDiagnosticImportRecoversWithoutEitherLocalSnapshot()=fixture { dir,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val expected=LocalSettingsTransfer.prepare(exported,stores.keys).values
        val before=SettingsBackupGuard.generation
        val commits=stores.mapValues { it.value.commits }
        stores.values.forEach { prefs -> prefs.afterCommit={
            assertTrue(SettingsBackupGuard.busy)
            assertEquals(SettingsBackupGuard.RestoreState.RUNNING,SettingsBackupGuard.restoreState)
            assertFalse(SettingsBackupGuard.tryWrite { fail("Writer ran before all stores verified") })
        } }
        diagnosticImport(exported,stores)
        assertEquals(expected,stores.mapValues { it.value.all })
        stores.forEach { (name,prefs)->assertEquals(commits.getValue(name)+1,prefs.commits) }
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
        assertFalse(SettingsBackupGuard.busy)
        assertEquals(before+2,SettingsBackupGuard.generation)
        assertFalse(SettingsBackupGuard.tryWrite(before) { fail("Stale writer survived recovery") })
        assertTrue(SettingsBackupGuard.tryWrite {})
        assertFalse(File(dir,"saved.snapshot").exists())
        assertFalse(File(dir,"before-restore.snapshot").exists())
    }

    @Test fun failedDiagnosticRecoveryStaysFrozenEvenWhenRetryRollbackVerifies()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val previous=stores.mapValues { it.value.all }
        val before=SettingsBackupGuard.generation
        val apps=stores.getValue("apps")
        apps.failCommitNumbers+=apps.commits+1
        val failure=rejects { diagnosticImport(exported,stores) }
        assertTrue("Recovery must attempt the validated restore",failure is LocalSettingsTransfer.RestoreFailure)
        assertTrue((failure as LocalSettingsTransfer.RestoreFailure).rollbackSucceeded)
        assertEquals(previous,stores.mapValues { it.value.all })
        assertEquals(SettingsBackupGuard.RestoreState.RECOVERY_REQUIRED,SettingsBackupGuard.restoreState)
        assertEquals(before+2,SettingsBackupGuard.generation)
        assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Rollback to inconsistent state unfroze writers") })
        diagnosticImport(exported,stores)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun failedDiagnosticRecoveryRollbackDoesNotConsumeTheRecoveryRoute()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val apps=stores.getValue("apps")
        apps.failCommitNumbers.addAll(listOf(apps.commits+1,apps.commits+2))
        val failure=rejects { diagnosticImport(exported,stores) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && !failure.rollbackSucceeded)
        assertTrue(SettingsBackupGuard.recoveryRequired)
        assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Failed recovery unfroze writers") })
        diagnosticImport(exported,stores)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun invalidDiagnosticRecoveryIsRejectedBeforeEditorsAndLeavesGateClosed()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val previous=stores.mapValues { it.value.all };val count=editors(stores)
        val before=SettingsBackupGuard.generation
        val missing=root(stores).apply { remove("apps") }
        val invalid=root(stores).apply { getJSONObject("home").getJSONObject("layout").put("type","invalid") }
        listOf("!not-base64!",encoded(missing),encoded(invalid)).forEach { text ->
            rejects { diagnosticImport(text,stores) }
            assertEquals(count,editors(stores));assertEquals(previous,stores.mapValues { it.value.all })
            assertEquals(before,SettingsBackupGuard.generation)
            assertTrue(SettingsBackupGuard.recoveryRequired)
            assertFalse(SettingsBackupGuard.busy)
            assertFalse(SettingsBackupGuard.tryWrite { fail("Invalid input unfroze writers") })
        }
        diagnosticImport(exported,stores)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun diagnosticRecoveryRequiresReadbackVerificationNotJustSuccessfulCommits()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        // Fail the last store's readback, after every forward commit has returned true.
        val personal=stores.getValue(PersonalizationStore.PREFS_NAME)
        val nextCommit=personal.commits+1
        personal.afterCommit={ if(personal.commits==nextCommit)personal.values.clear() }
        val failure=rejects { diagnosticImport(exported,stores) }
        assertTrue(failure is LocalSettingsTransfer.RestoreFailure && failure.rollbackSucceeded)
        assertTrue(SettingsBackupGuard.recoveryRequired)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Readback mismatch unfroze writers") })
        diagnosticImport(exported,stores)
        assertEquals(LocalSettingsTransfer.prepare(exported,stores.keys).values,stores.mapValues { it.value.all })
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun diagnosticRecoveryFailureBeforeFirstEditStillRequiresRecovery()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val count=editors(stores);val before=SettingsBackupGuard.generation
        val injected=IOException("Injected failure before first edit")
        val failure=rejects { SettingsBackupGuard.diagnosticRestore { throw injected } }
        assertSame(injected,failure)
        assertEquals(count,editors(stores));assertEquals(before+2,SettingsBackupGuard.generation)
        assertTrue(SettingsBackupGuard.recoveryRequired)
        assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Early failure unfroze writers") })
        diagnosticImport(exported,stores)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun diagnosticRecoveryKeepsExclusiveAdmissionAndInvalidatesInFlightEpochs()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val before=SettingsBackupGuard.generation
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val error=AtomicReference<Throwable?>()
        stores.getValue("home").afterCommit={
            entered.countDown();check(release.await(5,TimeUnit.SECONDS))
        }
        val worker=Thread {
            try { diagnosticImport(exported,stores) } catch(failure:Throwable) { error.set(failure) }
        }.apply { start() }
        var during=before
        try {
            assertTrue("Recovery did not reach its first commit",entered.await(5,TimeUnit.SECONDS))
            during=SettingsBackupGuard.generation
            assertTrue(SettingsBackupGuard.busy)
            assertEquals(SettingsBackupGuard.RestoreState.RUNNING,SettingsBackupGuard.restoreState)
            assertFalse(SettingsBackupGuard.tryWrite { fail("Concurrent writer ran during recovery") })
            val count=editors(stores)
            assertTrue(rejects { diagnosticImport(exported,stores) } is IllegalStateException)
            assertEquals(count,editors(stores))
            assertEquals(during,SettingsBackupGuard.generation)
            assertEquals(SettingsBackupGuard.RestoreState.RUNNING,SettingsBackupGuard.restoreState)
        } finally { release.countDown();worker.join(5_000) }
        assertFalse(worker.isAlive);assertNull(error.get())
        assertFalse(SettingsBackupGuard.busy)
        assertFalse(SettingsBackupGuard.tryWrite(before) { fail("Pre-recovery writer ran") })
        assertFalse(SettingsBackupGuard.tryWrite(during) { fail("Mid-recovery writer ran") })
        assertTrue(SettingsBackupGuard.tryWrite {})
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun diagnosticRecoveryDoesNotBypassUnintegratedGate()=fixture { _,stores,_ ->
        val exported=failedDiagnosticImport(stores)
        val count=editors(stores);val before=SettingsBackupGuard.generation
        val field=SettingsBackupGuard::class.java.getDeclaredField("integrated").apply { isAccessible=true }
        field.setBoolean(SettingsBackupGuard,false)
        try {
            rejects { diagnosticImport(exported,stores) }
            assertEquals(count,editors(stores));assertEquals(before,SettingsBackupGuard.generation)
            assertTrue(SettingsBackupGuard.recoveryRequired)
            assertFalse(SettingsBackupGuard.busy)
            assertFalse(SettingsBackupGuard.tryWrite { fail("Unintegrated recovery unfroze writers") })
        } finally { SettingsBackupGuard.enableAfterWriterIntegration() }
        diagnosticImport(exported,stores)
        assertEquals(SettingsBackupGuard.RestoreState.RESTORED,SettingsBackupGuard.restoreState)
    }

    @Test fun confirmationRequiresDelayExpiresCancelsAndIsSingleUse() {
        val early=SettingsBackupConfirmation("snapshot",100)
        assertNull(early.take(101));assertEquals("snapshot",early.take(1_300));assertNull(early.take(1_301))
        val cancelled=SettingsBackupConfirmation("snapshot",100);cancelled.cancel();assertNull(cancelled.take(2_000))
        val expired=SettingsBackupConfirmation("snapshot",100);assertNull(expired.take(120_101))
    }

    internal class MemoryPreferences(val values:MutableMap<String,Any?> = linkedMapOf()):SharedPreferences {
        var editors=0
        var commits=0
        val failCommitNumbers=mutableSetOf<Int>()
        var afterCommit:()->Unit={}
        override fun getAll():MutableMap<String,*> = LinkedHashMap(values)
        override fun contains(key:String)=values.containsKey(key)
        override fun getString(key:String,defValue:String?)=values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST") override fun getStringSet(key:String,defValues:MutableSet<String>?)=(values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key:String,defValue:Int)=values[key] as? Int ?: defValue
        override fun getLong(key:String,defValue:Long)=values[key] as? Long ?: defValue
        override fun getFloat(key:String,defValue:Float)=values[key] as? Float ?: defValue
        override fun getBoolean(key:String,defValue:Boolean)=values[key] as? Boolean ?: defValue
        override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun edit():SharedPreferences.Editor {
            editors++
            return object:SharedPreferences.Editor {
                private val changes=linkedMapOf<String,Any?>();private var clear=false
                override fun clear()=apply { clear=true }
                override fun remove(key:String)=apply { changes[key]=null }
                override fun putString(key:String,value:String?)=apply { changes[key]=value }
                override fun putStringSet(key:String,values:MutableSet<String>?)=apply { changes[key]=values?.toSet() }
                override fun putInt(key:String,value:Int)=apply { changes[key]=value }
                override fun putLong(key:String,value:Long)=apply { changes[key]=value }
                override fun putFloat(key:String,value:Float)=apply { changes[key]=value }
                override fun putBoolean(key:String,value:Boolean)=apply { changes[key]=value }
                override fun apply() { commit() }
                override fun commit():Boolean {
                    if(clear)values.clear()
                    changes.forEach { (key,value) -> if(value==null)values.remove(key) else values[key]=value }
                    commits++;afterCommit()
                    return commits !in failCommitNumbers
                }
            }
        }
    }
}
