package fi.jommi.rivi

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OfflineSnapshotStoreTest {
    @get:Rule val temp=TemporaryFolder()
    private val now=1_800_000_000_000L
    private val program=Program("cinemeta:series:tt1234567","Example","Service · S1 E2","https://example.test/art","app.pkg","App",
        "intent:example",120,1000,true,"https://example.test/source","Summary",true,"Channel",now,"scheme:detail",now,"series",false,"Drama",8,2025,"tt1234567")
    private fun sample()=OfflineSnapshot(
        SharedFeedSnapshot(listOf(program),listOf(program.copy(catalog=false)),mapOf(7L to "Continue watching")),
        CatalogSnapshot(listOf(program),mapOf("series" to listOf(program)),mapOf("movie" to listOf(program.copy(mediaType="movie")))),
        listOf(SportsEvent("event","A @ B","NFL",now+60_000,"pre","Soon","FOX","","https://example.test",now,"",listOf("A","B"),listOf("espn:nfl:1","espn:nfl:2"))),now,now)
    @Test fun roundTripPreservesProgramMetadataAndSportsIdentity() {
        val source=sample(); val restored=OfflineSnapshotStore.decode(OfflineSnapshotStore.encode(source))
        assertEquals(source.shared,restored.shared)
        assertEquals(source.catalog.seeds,restored.catalog.seeds)
        assertEquals(source.catalog.popular["series"],restored.catalog.popular["series"])
        assertEquals(source.games,restored.games)
        assertEquals(now,restored.sharedAt)
    }
    @Test fun missingOrCorruptFileCannotCrashStartup() {
        val file=File(temp.root,"snapshot.bin"); val store=OfflineSnapshotStore(file)
        assertNull(store.load()); file.writeText("not a snapshot"); assertNull(store.load())
    }
    @Test fun truncationAndTrailingGarbageAreRejected() {
        val file=File(temp.root,"snapshot.bin"); val store=OfflineSnapshotStore(file)
        val valid=OfflineSnapshotStore.encode(sample())
        file.writeBytes(valid.copyOf(valid.size-1)); assertNull(store.load())
        file.writeBytes(valid+byteArrayOf(0)); assertNull(store.load())
    }
    @Test fun unsupportedVersionIsRejected() {
        val file=File(temp.root,"snapshot.bin"); val bytes=OfflineSnapshotStore.encode(sample())
        bytes[7]=99; file.writeBytes(bytes); assertNull(OfflineSnapshotStore(file).load())
    }
    @Test fun maliciousCountsCannotAllocateUnboundedLists() {
        val bytes=ByteArrayOutputStream().apply { DataOutputStream(this).apply { writeInt(0x5054534e); writeInt(1); writeLong(now); writeLong(now); writeInt(Int.MAX_VALUE) } }.toByteArray()
        val file=File(temp.root,"snapshot.bin"); file.writeBytes(bytes)
        assertNull(OfflineSnapshotStore(file).load())
    }
    @Test fun oldRecommendationsExpireButWatchingSurvives() {
        val p=program.copy(watchingSource=false)
        val source=sample().copy(shared=SharedFeedSnapshot(listOf(p,program),listOf(program)))
        val expired=source.eligible(now+OfflineSnapshot.MAX_AGE+1)
        assertEquals(listOf(program),expired.shared.published)
        assertEquals(listOf(program),expired.shared.watching)
        assertTrue(expired.catalog.browse().isEmpty())
        assertTrue(expired.catalog.seeds.isEmpty())
    }
    @Test fun futureClockDataCannotLookFresh() {
        val source=sample().eligible(now-1)
        assertTrue(source.catalog.seeds.isEmpty()); assertTrue(source.games.isEmpty())
    }
    @Test fun expiredScoresNeverReturnAsLive() {
        val restored=sample().copy(games=sample().games.map { it.copy(state="in") }).eligible(now+900_001)
        assertTrue(restored.games.isEmpty())
    }
    @Test fun successfulReplacementLeavesNoPendingFile() {
        val file=File(temp.root,"nested/home.bin"); val store=OfflineSnapshotStore(file)
        assertTrue(store.save(sample())); assertTrue(store.save(sample().copy(sharedAt=now+1)))
        assertEquals(now+1,store.load()!!.sharedAt)
        assertFalse(File(file.parentFile,"home.bin.pending").exists())
    }
    @Test fun rejectedOversizeWritePreservesPreviousFile() {
        val file=File(temp.root,"home.bin"); file.writeText("previous")
        try { AtomicPrivateFile.write(file,ByteArray(101),100); fail("Expected bounded write failure") } catch(_:IllegalArgumentException) { }
        assertEquals("previous",file.readText())
    }
    @Test fun blockedParentIsReportedWithoutThrowing() {
        val parent=File(temp.root,"blocked"); parent.writeText("not a directory")
        assertFalse(OfflineSnapshotStore(File(parent,"home.bin")).save(sample()))
        assertEquals("not a directory",parent.readText())
    }
    @Test fun snapshotsBoundProviderRowCounts() {
        val source=sample().copy(shared=SharedFeedSnapshot(List(1400) { program },List(400) { program }))
        val restored=OfflineSnapshotStore.decode(OfflineSnapshotStore.encode(source))
        assertEquals(1200,restored.shared.published.size); assertEquals(300,restored.shared.watching.size)
    }
    @Test fun oversizedMetadataFailsWhileEncodingWithoutReplacingGoodSnapshot() {
        val file=File(temp.root,"home.bin"); val store=OfflineSnapshotStore(file)
        assertTrue(store.save(sample()))
        val huge=program.copy(summary="x".repeat(8000),art="x".repeat(8000))
        assertFalse(store.save(sample().copy(shared=SharedFeedSnapshot(List(1200) { huge }))))
        assertEquals(program,store.load()!!.shared.published.single())
    }
    @Test fun byteBoundCatchesBothSingleAndArrayWrites() {
        val out=BoundedByteArrayOutputStream(3); out.write(byteArrayOf(1,2),0,2); out.write(3)
        try { out.write(4); fail("Single-byte overflow accepted") } catch(_:IllegalArgumentException) { }
        try { out.write(byteArrayOf(4),0,1); fail("Array overflow accepted") } catch(_:IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(1,2,3),out.toByteArray())
    }
}
