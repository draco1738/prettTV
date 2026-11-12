package fi.jommi.rivi

import android.content.Context
import android.content.SharedPreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private snapshot storage and guarded restore; portable bytes use the same strict codec. */
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
    enum class Slot(val filename:String) { SAVED("saved.snapshot"), BEFORE_RESTORE("before-restore.snapshot"), BEFORE_RECOVERY("before-recovery.snapshot") }
    class Snapshot internal constructor(val savedAt:Long,internal val encoded:String,internal val plan:LocalSettingsTransfer.Prepared) {
        // Constructed on the worker: never hash or parse a multi-MB preview on the UI thread.
        internal val previewText=PortableSettingsBackup.describe(this)
    }
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
    fun restore(snapshot:Snapshot,source:Slot?,cancel:SettingsBackupCancellation) = synchronized(fileLock) {
        SettingsBackupGuard.exclusive(true,cancel) {
            cancel.check()
            val recoveryRequired=SettingsBackupGuard.recoveryRequired
            check(!recoveryRequired || source==null || source==Slot.BEFORE_RESTORE) { "Recover the pre-restore copy first" }
            // Keep the user's saved snapshot untouched. A separate, verified recovery copy
            // must succeed BEFORE the first preferences edit. Recovering that copy preserves it.
            if(source!=Slot.BEFORE_RESTORE) {
                write(if(recoveryRequired)Slot.BEFORE_RECOVERY else Slot.BEFORE_RESTORE,capture(cancel),cancel)
            }
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

    /** Capture current settings without replacing either private recovery slot. Worker only. */
    fun exportPortable(cancel:SettingsBackupCancellation):ByteArray = synchronized(fileLock) {
        check(!SettingsBackupGuard.recoveryRequired) { "Recover previous settings before export" }
        val snapshot=SettingsBackupGuard.exclusive(false,cancel) { capture(cancel) }
        PortableSettingsBackup.encode(snapshot)
    }

    private fun capture(cancel:SettingsBackupCancellation):Snapshot {
        cancel.check()
        val encoded=LocalSettingsTransfer.export(stores)
        cancel.check()
        return Snapshot(clock().also { require(it>0) },encoded,
            LocalSettingsTransfer.prepareSnapshot(encoded))
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

    private fun encode(snapshot:Snapshot)=PortableSettingsBackup.encode(snapshot)
    private fun decode(bytes:ByteArray,cancel:SettingsBackupCancellation)=PortableSettingsBackup.decode(bytes,cancel)

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
