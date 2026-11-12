package fi.jommi.rivi

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/** Stability variant ONLY. Parent registers exported=false; never registered in the release APK. */
class PortableSettingsBackupTestProvider:ContentProvider() {
    override fun onCreate():Boolean {
        check(context?.packageName=="dev.zane.pretttv.stability")
        return true
    }
    // ContentResolver opens typed assets for reads. The framework's default asset
    // overloads discard CancellationSignal before falling back to openFile(uri,mode).
    override fun openAssetFile(uri:Uri,mode:String,signal:android.os.CancellationSignal?) =
        android.content.res.AssetFileDescriptor(openFile(uri,mode,signal),0,android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
    override fun openTypedAssetFile(uri:Uri,mimeTypeFilter:String,opts:android.os.Bundle?,signal:android.os.CancellationSignal?) =
        openAssetFile(uri,"r",signal)
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor = openFile(uri,mode,null)
    override fun openFile(uri:Uri,mode:String,signal:android.os.CancellationSignal?):ParcelFileDescriptor {
        val app=requireNotNull(context)
        check(uri.authority==app.packageName+".portable-backup-fixture")
        require(uri.path in setOf("/readwrite","/writeonly","/mismatch","/blocked-open","/blocked-read","/blocked-write"))
        if(uri.path?.startsWith("/blocked-")==true) {
            val fixture=requireNotNull(blocked) { "Blocking fixture not armed" }
            check(fixture.path==uri.path)
            signal?.setOnCancelListener { fixture.signalCancelled.countDown() }
            if(uri.path=="/blocked-open") {
                fixture.entered.countDown()
                // Deliberately ignore cancellation until test cleanup releases the provider.
                while(fixture.release.count>0)try { fixture.release.await() } catch(_:InterruptedException) {}
                return ParcelFileDescriptor.open(document(app),ParcelFileDescriptor.MODE_READ_ONLY)
            }
            val pipe=ParcelFileDescriptor.createPipe()
            val reading=uri.path=="/blocked-read"
            synchronized(fixture.peers) { fixture.peers+=pipe[if(reading)1 else 0] }
            fixture.entered.countDown()
            return pipe[if(reading)0 else 1] // Empty read pipe / write pipe nobody drains.
        }
        if(mode=="r" && uri.path=="/writeonly")throw FileNotFoundException("Fixture denies readback")
        val file=if(mode=="r" && uri.path=="/mismatch")File(app.noBackupFilesDir,"portable-provider-mismatch.snapshot") else document(app)
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.parseMode(mode))
    }
    override fun getType(uri:Uri)="application/octet-stream"
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor?=null
    override fun insert(uri:Uri,values:ContentValues?):Uri?=null
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?)=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
    class BlockedFixture(val path:String) {
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val signalCancelled=java.util.concurrent.CountDownLatch(1)
        val peers=mutableListOf<ParcelFileDescriptor>()
        fun releaseProvider() {
            release.countDown()
            synchronized(peers) { peers.forEach { runCatching { it.close() } };peers.clear() }
        }
    }
    companion object {
        @Volatile var blocked:BlockedFixture?=null
        fun document(context:Context)=File(context.noBackupFilesDir,"portable-provider-fixture.snapshot")
    }
}
