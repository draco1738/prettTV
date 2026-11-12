package fi.jommi.rivi

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock

/** Revoked on Pause/Back/finish; never interrupt a filesystem commit halfway through. */
internal class SettingsBackupCancellation {
    private val cancelled=AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    fun check() { if(cancelled.get())throw CancellationException("Settings action cancelled") }
}

/**
 * Integration contract (one app process):
 * - Wrap the WHOLE read/modify/write in tryWrite, before taking PlaybackHistory/historyLock
 *   or PersonalizationStore/stateLock. Guard home/apps writes and diagnostic imports too.
 * - Capture generation before scheduling provider/playback work; pass that original value
 *   to tryWrite at publication. Drop, never replay, rejected stale writes. New events use a
 *   fresh generation. Do not hold a SharedPreferences.Editor across the guarded boundary.
 * - Stop/invalidate Home repositories before opening the Activity. Do not restart or consume
 *   provider results while busy. On return, wait asynchronously until !busy, clear Undo,
 *   discard cached content/viewport, re-read preferences and refresh if generation changed.
 *   If recoveryRequired, keep automatic writers stopped and offer this Activity for recovery.
 *   restoreState retains the outcome across Activity dismissal in this process, not reboot.
 * - Call enableAfterWriterIntegration() only after every writer obeys this contract. The
 *   Activity fails closed without it; an intent extra cannot enable writes.
 *
 * tryWrite never waits on restore disk I/O (important for the main thread). Exclusive work
 * runs only on the backup worker. An in-flight OS commit may finish after cancellation;
 * subsequent forward writes are revoked and rollback drains under this same exclusion.
 */
object SettingsBackupGuard {
    private val lock=ReentrantReadWriteLock(true)
    private val epoch=AtomicLong(0)
    private val exclusive=AtomicBoolean(false)
    @Volatile private var integrated=false
    enum class RestoreState { NONE, RUNNING, RESTORED, ROLLED_BACK, RECOVERY_REQUIRED }
    @Volatile var restoreState=RestoreState.NONE
        private set
    internal fun recordRestore(state:RestoreState) { restoreState=state }
    val recoveryRequired:Boolean get()=restoreState==RestoreState.RECOVERY_REQUIRED
    val generation:Long get()=epoch.get()
    val busy:Boolean get()=exclusive.get()
    val writersIntegrated:Boolean get()=integrated
    fun enableAfterWriterIntegration() { integrated=true }

    fun tryWrite(expectedGeneration:Long=generation,write:()->Unit):Boolean {
        // A restore announces busy before waiting for admitted writers. Let an already
        // admitted read/modify/write finish its nested store calls as one operation.
        val admitted=lock.readHoldCount>0
        if((busy && !admitted) || recoveryRequired || !lock.readLock().tryLock())return false
        try {
            if((busy && !admitted) || recoveryRequired || expectedGeneration!=generation)return false
            write()
            return true
        } finally { lock.readLock().unlock() }
    }

    /** Hold exclusion around the entire read/modify/write, before any store-specific lock. */
    internal fun <T> writeResult(fallback:T,expectedGeneration:Long=generation,write:()->T):T {
        var result=fallback
        tryWrite(expectedGeneration) { result=write() }
        return result
    }

    /**
     * Explicit diagnostic import: caller validates and restores all stores before returning.
     * May recover without a local snapshot; never wait for another writer on the caller.
     */
    internal fun diagnosticRestore(rollbackVerified:(Exception)->Boolean={true},work:()->Unit) {
        check(integrated && exclusive.compareAndSet(false,true)) { "Settings are busy" }
        try {
            check(lock.writeLock().tryLock()) { "Settings writers are finishing" }
            try {
                val recovering=recoveryRequired
                epoch.incrementAndGet()
                recordRestore(RestoreState.RUNNING)
                try { work();recordRestore(RestoreState.RESTORED) }
                catch(failure:Exception) {
                    // A verified rollback to an already-inconsistent state is not recovery.
                    recordRestore(if(recovering || !rollbackVerified(failure))
                        RestoreState.RECOVERY_REQUIRED else RestoreState.ROLLED_BACK)
                    throw failure
                } finally { epoch.incrementAndGet() }
            } finally { lock.writeLock().unlock() }
        } finally { exclusive.set(false) }
    }

    internal fun <T> exclusive(restoring:Boolean,cancel:SettingsBackupCancellation,work:()->T):T {
        check(integrated) { "Settings writers have not been integrated" }
        check(exclusive.compareAndSet(false,true)) { "Another settings operation is running" }
        try {
            // This is a worker-only wait. Writers already in progress may finish first.
            lock.writeLock().lock()
            try {
                cancel.check()
                if(restoring)epoch.incrementAndGet()
                try { return work() }
                finally { if(restoring)epoch.incrementAndGet() }
            } finally { lock.writeLock().unlock() }
        } finally { exclusive.set(false) }
    }
}

/** A preview has no authority after cancel, departure, use, or the two-minute timeout. */
internal class SettingsBackupConfirmation<T>(private val value:T,private val openedAt:Long) {
    private var available=true
    fun ready(now:Long)=available && now-openedAt in 1_200L..120_000L
    fun take(now:Long):T? {
        if(!ready(now))return null
        available=false
        return value
    }
    fun cancel() { available=false }
}
