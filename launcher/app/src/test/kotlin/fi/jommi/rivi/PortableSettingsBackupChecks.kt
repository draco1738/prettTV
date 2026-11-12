package fi.jommi.rivi

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CancellationException

class PortableSettingsBackupChecks {
    private fun cancel()=SettingsBackupCancellation()
    private fun rejects(work:()->Unit):Exception {
        try { work() } catch(failure:Exception) { return failure }
        throw AssertionError("Invalid input was accepted")
    }
    private fun fixture(work:(File,Map<String,SettingsBackupChecks.MemoryPreferences>,LocalSettingsBackup)->Unit) {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val state=SettingsBackupGuard.restoreState
        SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.NONE)
        val dir=Files.createTempDirectory("portable-backup-check-").toFile()
        val stores=LocalSettingsBackup.STORE_NAMES.associateWith { SettingsBackupChecks.MemoryPreferences() }
        stores.getValue("home").values.putAll(mapOf("layout" to "BALANCED","historySource" to "THIS_TV_ONLY"))
        try { work(dir,stores,LocalSettingsBackup(dir,stores,{1_700_000_000_000L})) }
        finally { dir.deleteRecursively();SettingsBackupGuard.recordRestore(state) }
    }
    private fun snapshot(bytes:ByteArray)=PortableSettingsBackup.decode(bytes,cancel())
    private fun redigest(bytes:ByteArray):ByteArray {
        val body=bytes.copyOf(bytes.size-32)
        return body+MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun program()=JSONObject().put("id","fixture").put("title","Fixture").put("detail","")
        .put("art","").put("pkg","fixture.app").put("app","Fixture").put("uri","fixture://play")
        .put("pos",12).put("dur",100).put("type","movie")
        .put("imdbId","").put("releaseYear",2020).put("posterArt","")

    @Test fun nestedPlaybackSchemaRejectsCoercionBeforePreviewOrEditors()=fixture { _,stores,backup ->
        val good=backup.exportPortable(cancel())
        val root=JSONObject(String(Base64.getDecoder().decode(snapshot(good).encoded),Charsets.UTF_8))
        fun decodeRow(row:JSONObject) {
            root.getJSONObject("playback").put("known",JSONObject().put("type","string")
                .put("value",org.json.JSONArray().put(row).toString()))
            val encoded=Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
            snapshot(PortableSettingsBackup.encode(LocalSettingsBackup.Snapshot(1,encoded,snapshot(good).plan)))
        }
        for(nested in listOf("localSnapshot","providerProgressBaseline")) {
            for(bad in listOf<Any>(JSONObject.NULL,"not an object",org.json.JSONArray(),3,true))
                rejects { decodeRow(program().put(nested,bad)) }
            for(field in listOf("id","title","detail","art","pkg","app","uri","type","imdbId","posterArt"))
                for(bad in listOf<Any>(7,org.json.JSONArray(),JSONObject.NULL,false))
                    rejects { decodeRow(program().put(nested,program().put(field,bad))) }
            for(field in listOf("pos","dur","releaseYear"))
                for(bad in listOf<Any>("12",1.5,-1,JSONObject.NULL,true))
                    rejects { decodeRow(program().put(nested,program().put(field,bad))) }
            rejects { decodeRow(program().put(nested,program().put("releaseYear",Long.MAX_VALUE))) }
            rejects { decodeRow(program().put(nested,program().put("unexpected",true))) }
            rejects { decodeRow(program().put(nested,JSONObject())) }
        }
        for(bad in listOf<Any>("true",1,JSONObject.NULL,org.json.JSONArray(),JSONObject()))
            rejects { decodeRow(program().put("providerAbsent",bad)) }
        assertEquals(0,stores.values.sumOf { it.editors })
    }

    @Test fun oldAndNewPlaybackExportsRemainAccepted()=fixture { _,stores,backup ->
        val legacy=program().apply { remove("imdbId");remove("releaseYear");remove("posterArt") }
        for(row in listOf(legacy,program(),program().put("localSnapshot",program()).put("providerAbsent",true),
            program().put("localSnapshot",legacy).put("providerAbsent",false).put("providerProgressBaseline",program()))) {
            stores.getValue("playback").values["known"]=org.json.JSONArray().put(row).toString()
            snapshot(backup.exportPortable(cancel()))
        }
        assertEquals(0,stores.values.sumOf { it.editors })
    }

    /** Uncooperative provider ignores interrupts and close, like a stuck Binder/native call. */
    @Test fun blockedProviderOpenReadWriteAndCloseCannotMonopolizeCaller()=fixture { _,_,backup ->
        val bytes=backup.exportPortable(cancel())
        for(stage in listOf("open","write","flush","read","close")) {
            val entered=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            val finished=java.util.concurrent.CountDownLatch(1)
            val token=cancel()
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            fun block() { entered.countDown();while(release.count>0)try { release.await() } catch(_:InterruptedException) {} }
            val output=object:ByteArrayOutputStream() {
                override fun write(b:ByteArray,off:Int,len:Int) { if(stage=="write")block();super.write(b,off,len) }
                override fun flush() { if(stage=="flush")block();super.flush() }
                override fun close() { if(stage=="close")block();super.close() }
            }
            val caller=Thread {
                try { PortableSettingsBackup.writeDocument(bytes,token,{
                    if(stage=="open")block();output
                },{ object:ByteArrayInputStream(bytes) {
                    override fun read(b:ByteArray,off:Int,len:Int):Int { if(stage=="read")block();return super.read(b,off,len) }
                } }) } catch(t:Throwable) { failure.set(t) } finally { finished.countDown() }
            }.apply { isDaemon=true;start() }
            try {
                assertTrue("Provider did not reach $stage",entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
                token.cancel()
                assertTrue("Cancelled $stage monopolized caller",finished.await(1,java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(failure.get() is CancellationException)
                // The same backup worker can immediately perform local/recovery work.
                snapshot(backup.exportPortable(cancel()))
            } finally { release.countDown();caller.join(3_000);documentDrained() }
        }
    }

    private fun documentDrained() {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while(PortableSettingsBackup.documentBusy && System.nanoTime()<end)Thread.sleep(5)
        assertFalse("Provider lease leaked",PortableSettingsBackup.documentBusy)
    }

    @Test fun providerDeadlineBoundsEveryStageAndPreservesUnverifiedReadback()=fixture { _,stores,backup ->
        val bytes=backup.exportPortable(cancel())
        for(stage in listOf("import-open","import-read","export-open","write","flush","close","readback-open","readback-read")) {
            val entered=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            fun block() { entered.countDown();while(release.count>0)try { release.await() } catch(_:InterruptedException) {} }
            val output=object:ByteArrayOutputStream() {
                override fun write(b:ByteArray,off:Int,len:Int) { if(stage=="write")block();super.write(b,off,len) }
                override fun flush() { if(stage=="flush")block() }
                override fun close() { if(stage=="close")block() }
            }
            fun input()=object:ByteArrayInputStream(bytes) {
                override fun read(b:ByteArray,off:Int,len:Int):Int {
                    if(stage in setOf("import-read","readback-read"))block()
                    return super.read(b,off,len)
                }
            }
            val start=System.nanoTime()
            try {
                if(stage.startsWith("import")) {
                    assertTrue(rejects { PortableSettingsBackup.readDocument(cancel(),{
                        if(stage=="import-open")block();input()
                    },200) } is IOException)
                } else {
                    val action={ PortableSettingsBackup.writeDocument(bytes,cancel(),{
                        if(stage=="export-open")block();output
                    },{ if(stage=="readback-open")block();input() },200) }
                    if(stage.startsWith("readback"))assertEquals(PortableSettingsBackup.SaveResult.UNVERIFIED,action())
                    else assertTrue(rejects { action() } is IOException)
                }
                assertEquals("No block reached for $stage",0L,entered.count)
                assertTrue("Deadline failed for $stage",System.nanoTime()-start<java.util.concurrent.TimeUnit.SECONDS.toNanos(2))
                assertTrue(PortableSettingsBackup.documentBusy)
                repeat(20) {
                    rejects { PortableSettingsBackup.readDocument(cancel(),{ fail("Queued behind stuck provider");null },200) }
                }
                // Recovery commits remain serialized on the original worker, not stranded
                // behind the document lease; no provider callback can ever edit preferences.
                val plan=snapshot(bytes)
                stores.getValue("home").values["layout"]="QUIET"
                backup.restore(plan,null,cancel())
                assertEquals("BALANCED",stores.getValue("home").values["layout"])
            } finally { release.countDown();documentDrained() }
        }
    }

    @Test fun cancellingBlockedImportClosesStreamAndRetainsLeaseThroughBlockedCleanup() {
        val readEntered=java.util.concurrent.CountDownLatch(1)
        val releaseRead=java.util.concurrent.CountDownLatch(1)
        val closeEntered=java.util.concurrent.CountDownLatch(1)
        val releaseClose=java.util.concurrent.CountDownLatch(1)
        val finished=java.util.concurrent.CountDownLatch(1)
        val token=cancel()
        val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val input=object:InputStream() {
            override fun read():Int { readEntered.countDown();releaseRead.await();return -1 }
            override fun close() { closeEntered.countDown();releaseClose.await() }
        }
        val caller=Thread {
            try { PortableSettingsBackup.readDocument(token,{ input }) }
            catch(t:Throwable) { failure.set(t) } finally { finished.countDown() }
        }.apply { isDaemon=true;start() }
        try {
            assertTrue(readEntered.await(3,java.util.concurrent.TimeUnit.SECONDS))
            token.cancel()
            assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(failure.get() is CancellationException)
            assertTrue(closeEntered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            releaseRead.countDown()
            // Even after the read thread finishes, an uncooperative close holds the only
            // lease. Repeated clicks cannot accumulate descriptor-cleanup threads.
            Thread.sleep(100)
            assertTrue(PortableSettingsBackup.documentBusy)
            rejects { PortableSettingsBackup.readDocument(cancel(),{ fail("Admitted during cleanup");null }) }
        } finally { releaseRead.countDown();releaseClose.countDown();caller.join(3_000);documentDrained() }
        assertArrayEquals(byteArrayOf(1),PortableSettingsBackup.readDocument(cancel(),{ ByteArrayInputStream(byteArrayOf(1)) }))
    }

    @Test fun lateOpenAfterCancelIsClosedWithoutReadWriteOrReadback()=fixture { _,_,backup ->
        val bytes=backup.exportPortable(cancel())
        for(importing in listOf(true,false)) {
            val entered=java.util.concurrent.CountDownLatch(1)
            val release=java.util.concurrent.CountDownLatch(1)
            val finished=java.util.concurrent.CountDownLatch(1)
            val closed=java.util.concurrent.atomic.AtomicInteger()
            val calls=java.util.concurrent.atomic.AtomicInteger()
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val token=cancel()
            val caller=Thread {
                try {
                    if(importing)PortableSettingsBackup.readDocument(token,{
                        entered.countDown();release.await()
                        object:InputStream() {
                            override fun read():Int { calls.incrementAndGet();return -1 }
                            override fun close() { closed.incrementAndGet() }
                        }
                    }) else PortableSettingsBackup.writeDocument(bytes,token,{
                        entered.countDown();release.await()
                        object:java.io.OutputStream() {
                            override fun write(value:Int) { calls.incrementAndGet() }
                            override fun close() { closed.incrementAndGet() }
                        }
                    },{ calls.incrementAndGet();null })
                } catch(t:Throwable) { failure.set(t) } finally { finished.countDown() }
            }.apply { isDaemon=true;start() }
            try {
                assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));token.cancel()
                assertTrue(finished.await(1,java.util.concurrent.TimeUnit.SECONDS))
                assertTrue(failure.get() is CancellationException)
            } finally { release.countDown();caller.join(3_000);documentDrained() }
            assertEquals(1,closed.get());assertEquals(0,calls.get())
        }
    }

    @Test fun portableExportDoesNotReplacePrivateSlotsAndUsesExactPrivateFormat()=fixture { dir,_,backup ->
        val bytes=backup.exportPortable(cancel())
        assertFalse(File(dir,"saved.snapshot").exists())
        val privateCopy=backup.save(cancel())
        assertArrayEquals(File(dir,"saved.snapshot").readBytes(),bytes)
        assertArrayEquals(PortableSettingsBackup.encode(privateCopy),bytes)
        assertTrue(PortableSettingsBackup.preview(snapshot(bytes)).contains("THIS_TV_ONLY"))
    }

    @Test fun changedProviderBytesAfterPreviewCannotAlterApprovedRestore()=fixture { dir,stores,backup ->
        val bytes=backup.exportPortable(cancel())
        val preview=snapshot(bytes)
        bytes.fill(0)
        stores.getValue("home").values["layout"]="QUIET"
        backup.restore(preview,null,cancel())
        assertEquals("BALANCED",stores.getValue("home").values["layout"])
        assertEquals("QUIET",backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel()).plan.values.getValue("home")["layout"])
        assertTrue(File(dir,"before-restore.snapshot").isFile)
    }

    @Test fun corruptTruncatedOversizeUnsupportedEnvelopeAndMissingStoreFailBeforeEditors()=fixture { _,stores,backup ->
        val good=backup.exportPortable(cancel())
        val unsupported=good.clone().apply { this[11]=2 }
        val noApps=JSONObject(String(Base64.getDecoder().decode(snapshot(good).encoded),Charsets.UTF_8)).apply { remove("apps") }
        val missing=Base64.getEncoder().encodeToString(noApps.toString().toByteArray())
        val malformed=PortableSettingsBackup.encode(LocalSettingsBackup.Snapshot(1,missing,snapshot(good).plan))
        listOf(good.copyOf(20),good+0.toByte(),good.clone().apply { this[30]=0 },redigest(unsupported),malformed,
            ByteArray(LocalSettingsBackup.MAX_FILE+1)).forEach { bytes -> rejects { snapshot(bytes) } }
        assertEquals(0,stores.values.sumOf { it.editors })
    }

    @Test fun providerWithoutSizeCannotStreamPastBound() {
        var read=0
        val input=object:InputStream() {
            override fun read()=0
            override fun read(b:ByteArray,off:Int,len:Int):Int { read+=len;b.fill(0,off,off+len);return len }
        }
        rejects { PortableSettingsBackup.readBounded(input,cancel()) }
        assertEquals(LocalSettingsBackup.MAX_FILE+1,read)
        rejects { PortableSettingsBackup.readBounded(object:InputStream() {
            override fun read()=0
            override fun read(b:ByteArray,off:Int,len:Int)=0
        },cancel()) }
    }

    @Test fun documentSaveRequiresExactReadbackAfterWriteClose()=fixture { _,_,backup ->
        val bytes=backup.exportPortable(cancel())
        var closed=false
        val output=object:ByteArrayOutputStream() { override fun close() { closed=true;super.close() } }
        val result=PortableSettingsBackup.writeDocument(bytes,cancel(),{ output },{
            assertTrue(closed);ByteArrayInputStream(output.toByteArray())
        })
        assertEquals(PortableSettingsBackup.SaveResult.VERIFIED,result)
    }

    @Test fun successfulWriteWithUnreadableProviderIsNotVerified()=fixture { _,_,backup ->
        val bytes=backup.exportPortable(cancel())
        for(read in listOf<()->InputStream?>({ null },{ throw IOException("No read permission") })) {
            assertEquals(PortableSettingsBackup.SaveResult.UNVERIFIED,
                PortableSettingsBackup.writeDocument(bytes,cancel(),{ ByteArrayOutputStream() },read))
        }
        assertEquals(PortableSettingsBackup.SaveResult.MISMATCH,
            PortableSettingsBackup.writeDocument(bytes,cancel(),{ ByteArrayOutputStream() },{ ByteArrayInputStream(byteArrayOf(1)) }))
    }

    @Test fun cancellationRevokesFurtherIoButCannotRetractAdmittedWrite()=fixture { _,_,backup ->
        val token=cancel();val bytes=backup.exportPortable(cancel());var writes=0;var read=false
        val output=object:ByteArrayOutputStream() {
            override fun write(b:ByteArray,off:Int,len:Int) { super.write(b,off,len);writes++;token.cancel() }
        }
        assertTrue(rejects { PortableSettingsBackup.writeDocument(bytes,token,{ output },{ read=true;null }) } is CancellationException)
        assertEquals(1,writes);assertTrue(output.size()>0);assertFalse(read)
    }

    @Test fun cancelledPreviewConsentCannotOpenPreferenceEditor()=fixture { _,stores,backup ->
        val consent=SettingsBackupConfirmation(snapshot(backup.exportPortable(cancel())),0)
        consent.cancel();assertNull(consent.take(2_000))
        val revoked=cancel().apply { cancel() }
        rejects { backup.restore(snapshot(backup.exportPortable(cancel())),null,revoked) }
        assertEquals(0,stores.values.sumOf { it.editors })
    }

    @Test fun portableRestoreRequiresVerifiedPrivatePreRestoreCopy()=fixture { dir,stores,backup ->
        val preview=snapshot(backup.exportPortable(cancel()))
        val broken=LocalSettingsBackup(dir,stores,sync={ throw IOException("Injected disk failure") })
        rejects { broken.restore(preview,null,cancel()) }
        assertEquals(0,stores.values.sumOf { it.editors })
    }

    @Test fun portableFailureRollsBackAndFailedRecoveryRetryRemainsFrozen()=fixture { dir,stores,backup ->
        val preview=snapshot(backup.exportPortable(cancel()))
        stores.getValue("home").values["layout"]="QUIET"
        val apps=stores.getValue("apps")
        apps.failCommitNumbers+=1
        assertTrue((rejects { backup.restore(preview,null,cancel()) } as LocalSettingsTransfer.RestoreFailure).rollbackSucceeded)
        assertEquals("QUIET",stores.getValue("home").values["layout"])
        apps.failCommitNumbers.addAll(listOf(apps.commits+1,apps.commits+2))
        rejects { backup.restore(preview,null,cancel()) }
        assertTrue(SettingsBackupGuard.recoveryRequired)
        val retained=File(dir,"before-restore.snapshot").readBytes()
        apps.failCommitNumbers.clear();apps.failCommitNumbers+=apps.commits+1
        assertTrue((rejects { backup.restore(preview,null,cancel()) } as LocalSettingsTransfer.RestoreFailure).rollbackSucceeded)
        assertTrue(SettingsBackupGuard.recoveryRequired)
        assertFalse(SettingsBackupGuard.tryWrite { fail("Unfroze inconsistent writers") })
        assertArrayEquals(retained,File(dir,"before-restore.snapshot").readBytes())
        assertTrue(File(dir,"before-recovery.snapshot").isFile)
        backup.restore(preview,null,cancel())
        assertFalse(SettingsBackupGuard.recoveryRequired)
        assertEquals("BALANCED",stores.getValue("home").values["layout"])
    }
}
