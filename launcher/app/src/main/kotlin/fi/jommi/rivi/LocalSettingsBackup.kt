package fi.jommi.rivi

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Private, bounded, checksummed snapshots. No URI, intent, network or external path input. */
internal class LocalSettingsBackup(
    private val directory:File,
    private val stores:Map<String,SharedPreferences>,
    private val clock:()->Long=System::currentTimeMillis,
    private val atomicReplace:(File,File)->Unit={ source,target ->
        // Fail closed if the filesystem cannot atomically replace; never delete target first.
        Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
    private val sync:(FileOutputStream)->Unit={ it.fd.sync() },
) {
    enum class Slot(val filename:String) { SAVED("saved.snapshot"), BEFORE_RESTORE("before-restore.snapshot") }
    class Snapshot internal constructor(val savedAt:Long,internal val encoded:String,internal val plan:LocalSettingsTransfer.Prepared)
    data class Status(val savedAt:Long?=null,val invalid:Boolean=false)

    init { require(stores.keys==STORE_NAMES) { "TV backup requires the four app-owned stores" } }

    fun status(slot:Slot,cancel:SettingsBackupCancellation):Status = synchronized(fileLock) {
        cancel.check()
        if(!file(slot).exists())return@synchronized Status()
        try { Status(read(slot,cancel).savedAt) }
        catch(failure:java.util.concurrent.CancellationException) { throw failure }
        catch(_:Exception) { Status(invalid=true) }
    }

    /** Validation occurs before the UI can construct a restore confirmation. No editors. */
    fun read(slot:Slot,cancel:SettingsBackupCancellation):Snapshot = synchronized(fileLock) {
        decode(readBounded(file(slot),cancel),cancel)
    }

    fun save(cancel:SettingsBackupCancellation):Snapshot = synchronized(fileLock) {
        check(!SettingsBackupGuard.recoveryRequired) { "Recover previous settings before replacing a backup" }
        val snapshot=SettingsBackupGuard.exclusive(false,cancel) { capture(cancel) }
        write(Slot.SAVED,snapshot,cancel)
    }

    /** Restores the exact immutable, validated preview, never a newly reread file. */
    fun restore(snapshot:Snapshot,source:Slot,cancel:SettingsBackupCancellation) = synchronized(fileLock) {
        SettingsBackupGuard.exclusive(true,cancel) {
            cancel.check()
            val recoveryRequired=SettingsBackupGuard.recoveryRequired
            check(!recoveryRequired || source==Slot.BEFORE_RESTORE) { "Recover the pre-restore copy first" }
            // Keep the user's saved snapshot untouched. A separate, verified recovery copy
            // must succeed BEFORE the first preferences edit. Recovering that copy preserves it.
            if(source!=Slot.BEFORE_RESTORE)write(Slot.BEFORE_RESTORE,capture(cancel),cancel)
            cancel.check()
            SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.RUNNING)
            try {
                LocalSettingsTransfer.restore(snapshot.plan,stores,cancel::check)
                SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.RESTORED)
            } catch(failure:Exception) {
                SettingsBackupGuard.recordRestore(if(recoveryRequired ||
                    (failure is LocalSettingsTransfer.RestoreFailure && !failure.rollbackSucceeded))
                    SettingsBackupGuard.RestoreState.RECOVERY_REQUIRED else SettingsBackupGuard.RestoreState.ROLLED_BACK)
                throw failure
            }
        }
    }

    private fun capture(cancel:SettingsBackupCancellation):Snapshot {
        cancel.check()
        val encoded=LocalSettingsTransfer.export(stores)
        cancel.check()
        return Snapshot(clock().also { require(it>0) },encoded,
            LocalSettingsTransfer.prepare(encoded,STORE_NAMES,requireAllStores=true))
    }

    private fun write(slot:Slot,snapshot:Snapshot,cancel:SettingsBackupCancellation):Snapshot {
        cancel.check()
        if(!directory.isDirectory && !directory.mkdirs())throw IOException("Private backup directory unavailable")
        val target=file(slot)
        val temporary=File(directory,"pending.snapshot")
        val bytes=encode(snapshot)
        try {
            FileOutputStream(temporary,false).use { output ->
                var offset=0
                while(offset<bytes.size) {
                    cancel.check()
                    val count=minOf(8192,bytes.size-offset)
                    output.write(bytes,offset,count);offset+=count
                }
                cancel.check();sync(output)
            }
            // Check the staged bytes and full settings schema before atomic publication.
            val verified=decode(readBounded(temporary,cancel),cancel)
            check(verified.savedAt==snapshot.savedAt && verified.encoded==snapshot.encoded)
            cancel.check()
            atomicReplace(temporary,target)
            // Verify the exact target, not just a successful rename return. Cancellation after
            // the atomic publication cannot retract that completed save; it suppresses the UI.
            val published=decode(readBounded(target,SettingsBackupCancellation()),SettingsBackupCancellation())
            check(published.savedAt==snapshot.savedAt && published.encoded==snapshot.encoded)
            return published
        } finally { temporary.delete() }
    }

    private fun encode(snapshot:Snapshot):ByteArray {
        require(snapshot.encoded.length<=MAX_PAYLOAD)
        val bytes=snapshot.encoded.toByteArray(Charsets.US_ASCII)
        val buffer=ByteArrayOutputStream(bytes.size+HEADER_BYTES+DIGEST_BYTES)
        DataOutputStream(buffer).use { output ->
            output.writeLong(MAGIC);output.writeInt(1);output.writeLong(snapshot.savedAt)
            output.writeInt(bytes.size);output.write(bytes)
        }
        val body=buffer.toByteArray()
        return body+MessageDigest.getInstance("SHA-256").digest(body)
    }

    private fun decode(bytes:ByteArray,cancel:SettingsBackupCancellation):Snapshot {
        cancel.check()
        require(bytes.size in (HEADER_BYTES+DIGEST_BYTES)..MAX_FILE) { "Invalid snapshot length" }
        val contentSize=bytes.size-DIGEST_BYTES
        val digest=MessageDigest.getInstance("SHA-256").apply { update(bytes,0,contentSize) }.digest()
        require(MessageDigest.isEqual(digest,bytes.copyOfRange(contentSize,bytes.size))) { "Snapshot checksum mismatch" }
        DataInputStream(ByteArrayInputStream(bytes,0,contentSize)).use { input ->
            require(input.readLong()==MAGIC && input.readInt()==1) { "Unsupported snapshot format" }
            val savedAt=input.readLong().also { require(it>0) { "Invalid snapshot time" } }
            val length=input.readInt()
            require(length in 1..MAX_PAYLOAD && length==contentSize-HEADER_BYTES) { "Invalid snapshot payload size" }
            val payload=ByteArray(length);input.readFully(payload)
            require(payload.all { it.toInt() in 0..127 }) { "Invalid snapshot encoding" }
            val encoded=String(payload,Charsets.US_ASCII)
            val plan=LocalSettingsTransfer.prepare(encoded,STORE_NAMES,requireAllStores=true)
            cancel.check()
            return Snapshot(savedAt,encoded,plan)
        }
    }

    private fun readBounded(file:File,cancel:SettingsBackupCancellation):ByteArray {
        require(file.length() in 1..MAX_FILE.toLong()) { "Snapshot missing or exceeds 16 MB" }
        val output=ByteArrayOutputStream(minOf(file.length(),8192).toInt())
        FileInputStream(file).use { input ->
            val buffer=ByteArray(8192)
            var total=0
            while(true) {
                cancel.check()
                val count=input.read(buffer)
                if(count<0)break
                total+=count
                require(total<=MAX_FILE) { "Snapshot exceeds 16 MB" }
                output.write(buffer,0,count)
            }
        }
        return output.toByteArray()
    }

    private fun file(slot:Slot)=File(directory,slot.filename)

    companion object {
        private val fileLock=Any()
        internal val STORE_NAMES=setOf("home","apps","playback",PersonalizationStore.PREFS_NAME)
        internal const val MAX_PAYLOAD=16*1024*1024
        internal const val HEADER_BYTES=24
        internal const val DIGEST_BYTES=32
        internal const val MAX_FILE=MAX_PAYLOAD+HEADER_BYTES+DIGEST_BYTES
        private const val MAGIC=0x5052455454424B31L // PRETTBK1

        /** noBackupFilesDir also excludes these viewing/preferences snapshots from OS backup. */
        fun create(context:Context):LocalSettingsBackup {
            val app=context.applicationContext
            return LocalSettingsBackup(File(app.noBackupFilesDir,"settings-backup"),linkedMapOf(
                "home" to app.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
                "apps" to app.getSharedPreferences("rivi",Context.MODE_PRIVATE),
                "playback" to app.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
                PersonalizationStore.PREFS_NAME to app.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
            ))
        }
    }
}
