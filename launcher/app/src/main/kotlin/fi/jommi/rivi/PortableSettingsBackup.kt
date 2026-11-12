package fi.jommi.rivi

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** One bounded codec for private files, SAF documents and the offline PC validator. Not encryption. */
internal object PortableSettingsBackup {
    const val PC_IMPORT_FILENAME="portable-settings-import.snapshot"
    private const val MAGIC=0x5052455454424B31L // PRETTBK1; big-endian v1 envelope
    enum class SaveResult { VERIFIED, UNVERIFIED, MISMATCH }

    fun encode(snapshot:LocalSettingsBackup.Snapshot):ByteArray {
        require(snapshot.encoded.length<=LocalSettingsBackup.MAX_PAYLOAD && snapshot.savedAt>0)
        val bytes=snapshot.encoded.toByteArray(Charsets.US_ASCII)
        val buffer=ByteArrayOutputStream(bytes.size+LocalSettingsBackup.HEADER_BYTES)
        DataOutputStream(buffer).use { output ->
            output.writeLong(MAGIC);output.writeInt(1);output.writeLong(snapshot.savedAt)
            output.writeInt(bytes.size);output.write(bytes)
        }
        val body=buffer.toByteArray()
        return body+MessageDigest.getInstance("SHA-256").digest(body)
    }

    /** Decode owns its bytes and plan; changing the document after preview cannot change consent. */
    fun decode(bytes:ByteArray,cancel:SettingsBackupCancellation):LocalSettingsBackup.Snapshot {
        cancel.check()
        require(bytes.size in (LocalSettingsBackup.HEADER_BYTES+LocalSettingsBackup.DIGEST_BYTES)..LocalSettingsBackup.MAX_FILE) { "Invalid snapshot length" }
        val contentSize=bytes.size-LocalSettingsBackup.DIGEST_BYTES
        val digest=MessageDigest.getInstance("SHA-256").apply { update(bytes,0,contentSize) }.digest()
        require(MessageDigest.isEqual(digest,bytes.copyOfRange(contentSize,bytes.size))) { "Snapshot checksum mismatch" }
        DataInputStream(ByteArrayInputStream(bytes,0,contentSize)).use { input ->
            require(input.readLong()==MAGIC && input.readInt()==1) { "Unsupported snapshot format" }
            val savedAt=input.readLong().also { require(it>0) { "Invalid snapshot time" } }
            val length=input.readInt()
            require(length in 1..LocalSettingsBackup.MAX_PAYLOAD && length==contentSize-LocalSettingsBackup.HEADER_BYTES) { "Invalid snapshot payload size" }
            val payload=ByteArray(length);input.readFully(payload)
            require(payload.all { it.toInt() in 0..127 }) { "Invalid snapshot encoding" }
            val encoded=String(payload,Charsets.US_ASCII)
            val plan=LocalSettingsTransfer.prepareSnapshot(encoded)
            cancel.check()
            return LocalSettingsBackup.Snapshot(savedAt,encoded,plan)
        }
    }

    /** Enforces the bound while streaming, even if a provider lies about length. Does not close input. */
    fun readBounded(input:InputStream,cancel:SettingsBackupCancellation):ByteArray {
        val output=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        while(true) {
            cancel.check()
            val count=input.read(buffer,0,minOf(buffer.size,LocalSettingsBackup.MAX_FILE-output.size()+1))
            if(count<0)break
            require(count>0) { "Provider made no read progress" }
            require(output.size()+count<=LocalSettingsBackup.MAX_FILE) { "Snapshot exceeds 16 MB plus envelope" }
            output.write(buffer,0,count)
        }
        cancel.check()
        return output.toByteArray()
    }

    // Provider calls may ignore CancellationSignal, close and interrupts. Never run them
    // on the preference/rollback worker. One process-wide lease bounds abandoned work;
    // while it is stuck, further document operations fail fast, but local recovery works.
    internal const val DOCUMENT_TIMEOUT_MS=15_000L
    private val documentLease=AtomicBoolean(false)
    private fun pool(size:Int,name:String)=ThreadPoolExecutor(size,size,30,TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(2),{ task -> Thread(task,name).apply { isDaemon=true } },
        ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }
    private val documentWorker=pool(1,"prett-document-io")
    private val documentCleanup=pool(2,"prett-document-close")
    internal val documentBusy:Boolean get()=documentLease.get()
    private class DocumentTimeout:IOException("Document I/O deadline exceeded")

    private class Resource(val value:Closeable) {
        private val closed=AtomicBoolean(false)
        fun close() { if(closed.compareAndSet(false,true))value.close() }
    }
    private class DocumentSession(val caller:SettingsBackupCancellation) {
        val token=SettingsBackupCancellation()
        private var resource:Resource?=null
        private var signal:CancellationSignal?=null
        private var aborted=false
        private var finished=false
        private var released=false
        private var cleanup=0
        fun check() { caller.check();token.check() }
        // Construct Android objects only in the native overload, not the offline codec.
        fun signal():CancellationSignal = synchronized(this) {
            check();signal ?: CancellationSignal().also { signal=it }
        }
        fun <T:Closeable,R> use(value:T,work:(T)->R):R {
            val handle=Resource(value)
            try {
                synchronized(this) { check();resource=handle }
                return work(value)
            } finally {
                try { handle.close() }
                finally { synchronized(this) { if(resource===handle)resource=null } }
            }
        }
        @Synchronized fun finish() { finished=true;releaseIfFinished() }
        private fun releaseIfFinished() {
            if(finished && cleanup==0 && !released) { released=true;documentLease.set(false) }
        }
        @Synchronized fun abort() {
            if(aborted || finished)return
            aborted=true;token.cancel()
            // CancellationSignal.cancel() itself can call a stuck remote provider. Neither
            // it nor stream close may run on the UI or preference worker. The lease stays
            // occupied until BOTH I/O and cleanup drain, bounding threads/descriptors.
            val actions=listOfNotNull(signal?.let { { it.cancel() } },resource?.let { { it.close() } })
            cleanup+=actions.size
            actions.forEach { action -> documentCleanup.execute {
                try { runCatching(action) }
                finally { synchronized(this) { cleanup--;releaseIfFinished() } }
            } }
        }
    }

    private fun <T> document(cancel:SettingsBackupCancellation,timeoutMs:Long,
        work:(DocumentSession)->T):T {
        require(timeoutMs>0)
        cancel.check()
        check(documentLease.compareAndSet(false,true)) { "Previous document provider is still finishing; use a private or PC copy" }
        val session=DocumentSession(cancel)
        val result=CompletableFuture<T>()
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            documentWorker.execute {
                val outcome=runCatching { session.check();work(session) }
                session.finish()
                outcome.fold({ result.complete(it) },{ result.completeExceptionally(it) })
            }
        } catch(failure:Exception) { session.finish();throw failure }
        try {
            while(true) {
                cancel.check()
                val left=deadline-System.nanoTime()
                if(left<=0)throw DocumentTimeout()
                try {
                    val value=result.get(minOf(left,TimeUnit.MILLISECONDS.toNanos(25)),TimeUnit.NANOSECONDS)
                    cancel.check();return value
                } catch(_:TimeoutException) { /* poll cancellation without interrupting commits */ }
                catch(failure:ExecutionException) { throw failure.cause ?: failure }
            }
        } catch(failure:Throwable) { session.abort();throw failure }
    }

    /** Owns open/read/close on the bounded provider lane, never on the restore worker. */
    fun readDocument(cancel:SettingsBackupCancellation,openRead:()->InputStream?,
        timeoutMs:Long=DOCUMENT_TIMEOUT_MS):ByteArray = document(cancel,timeoutMs) { session ->
        session.use(openRead() ?: error("Document cannot be read")) { readBounded(it,session.token) }
    }

    fun readDocument(resolver:ContentResolver,uri:Uri,cancel:SettingsBackupCancellation):ByteArray =
        document(cancel,DOCUMENT_TIMEOUT_MS) { session ->
            val descriptor=resolver.openFileDescriptor(uri,"r",session.signal()) ?: error("Document cannot be read")
            session.use(ParcelFileDescriptor.AutoCloseInputStream(descriptor)) { readBounded(it,session.token) }
        }

    /** Close write before readback. A write alone (including readback timeout) is unverified. */
    fun writeDocument(bytes:ByteArray,cancel:SettingsBackupCancellation,
        openWrite:()->OutputStream?,openRead:()->InputStream?,timeoutMs:Long=DOCUMENT_TIMEOUT_MS):SaveResult =
        writeDocumentWork(bytes,cancel,timeoutMs,{ openWrite() },{ openRead() })

    fun writeDocument(bytes:ByteArray,resolver:ContentResolver,uri:Uri,cancel:SettingsBackupCancellation):SaveResult =
        writeDocumentWork(bytes,cancel,DOCUMENT_TIMEOUT_MS,{ session ->
            resolver.openFileDescriptor(uri,"wt",session.signal())?.let { ParcelFileDescriptor.AutoCloseOutputStream(it) }
        },{ session ->
            resolver.openFileDescriptor(uri,"r",session.signal())?.let { ParcelFileDescriptor.AutoCloseInputStream(it) }
        })

    private fun writeDocumentWork(bytes:ByteArray,cancel:SettingsBackupCancellation,timeoutMs:Long,
        openWrite:(DocumentSession)->OutputStream?,openRead:(DocumentSession)->InputStream?):SaveResult {
        decode(bytes,cancel)
        val written=AtomicBoolean(false)
        try {
            return document(cancel,timeoutMs) { session ->
                session.use(openWrite(session) ?: error("Document cannot be written")) { output ->
                    var offset=0
                    while(offset<bytes.size) {
                        session.check()
                        val count=minOf(8192,bytes.size-offset)
                        output.write(bytes,offset,count);offset+=count
                    }
                    session.check();output.flush()
                }
                written.set(true)
                session.check()
                val returned=try {
                    val input=openRead(session) ?: return@document SaveResult.UNVERIFIED
                    session.use(input) { readBounded(it,session.token) }
                } catch(failure:CancellationException) { throw failure }
                catch(_:Exception) { session.check();return@document SaveResult.UNVERIFIED }
                session.check()
                if(MessageDigest.isEqual(bytes,returned))SaveResult.VERIFIED else SaveResult.MISMATCH
            }
        } catch(failure:DocumentTimeout) {
            cancel.check()
            if(written.get())return SaveResult.UNVERIFIED
            throw failure
        }
    }

    /** Readable bounded summary; deliberately avoids launch URIs, arbitrary preference text and credentials. */
    fun preview(snapshot:LocalSettingsBackup.Snapshot)=snapshot.previewText

    internal fun describe(snapshot:LocalSettingsBackup.Snapshot):String {
        val values=snapshot.plan.values
        val home=values.getValue("home")
        val personal=(values.getValue(PersonalizationStore.PREFS_NAME)["state"] as? String)?.let { org.json.JSONObject(it) }
        val hidden=(home["hiddenWatchingTitles"] as? Set<*>)?.size ?: 0
        val playback=(values.getValue("playback")["known"] as? String)?.let { org.json.JSONArray(it).length() } ?: 0
        fun safe(value:Any?)=value?.toString()?.filter { !it.isISOControl() }?.take(80) ?: "Default"
        return "Format: prettTV snapshot v1 · checksum and all four stores validated\n"+
            "Home layout: ${safe(home["layout"])} · Interface: ${safe(home["interfaceSize"])}\n"+
            "History source: ${safe(home["historySource"] ?: "COMBINED (default)")}\n"+
            "Watchlist: ${personal?.optJSONArray("watchlist")?.length() ?: 0} titles · Followed: ${personal?.optJSONArray("followed")?.length() ?: 0}\n"+
            "Hidden Continue watching titles: $hidden · Playback records: $playback\n"+
            values.entries.joinToString("\n") { (store,items)->"$store: ${items.size} settings" }+
            "\nSHA-256: ${MessageDigest.getInstance("SHA-256").digest(encode(snapshot)).joinToString("") { "%02x".format(it) }}"
    }
}

/** Offline PC entrypoint. Same Android import validator; stdout contains only validated snapshot bytes. */
object PortableSettingsBackupValidator {
    @JvmStatic fun main(args:Array<String>) {
        try {
            require(args.size==1 && args[0] in setOf("diagnostic","snapshot"))
            val cancel=SettingsBackupCancellation()
            val bytes=PortableSettingsBackup.readBounded(System.`in`,cancel)
            val snapshot=if(args[0]=="snapshot")PortableSettingsBackup.decode(bytes,cancel) else {
                require(bytes.all { it.toInt() in 0..127 })
                val data=String(bytes,Charsets.US_ASCII)
                LocalSettingsBackup.Snapshot(System.currentTimeMillis(),data,LocalSettingsTransfer.prepareSnapshot(data))
            }
            System.out.write(PortableSettingsBackup.encode(snapshot));System.out.flush()
        } catch(_:Exception) {
            // Do not echo imported values, paths, tokens or parser exception text.
            System.err.println("Snapshot validation failed; no settings were changed.")
            kotlin.system.exitProcess(2)
        }
    }
}
