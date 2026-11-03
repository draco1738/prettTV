package fi.jommi.rivi

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Real test-owned sessions; never touches production sessions or approves an installation. */
internal object UpdateSessionAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        val installer=context.packageManager.packageInstaller
        val prefs=context.getSharedPreferences("official_release_updates",Context.MODE_PRIVATE)
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun restore() {
            val method=AppUpdater::class.java.getDeclaredMethod("restoreInstallation",Context::class.java)
            method.isAccessible=true;method.invoke(AppUpdater,context)
        }
        fun awaitRemoved(id:Int) {
            val until=SystemClock.uptimeMillis()+5_000
            while(installer.getSessionInfo(id)!=null && SystemClock.uptimeMillis()<until)SystemClock.sleep(25)
            check(installer.getSessionInfo(id)==null) { "Test session $id was not removed" }
        }
        fun fixture(body:(Int)->Unit) {
            val oldState=AppUpdater.state
            val committed=AppUpdater::class.java.getDeclaredField("installCommitted").apply { isAccessible=true }
            val oldCommitted=committed.getBoolean(null)
            val hadSession=prefs.contains("session");val oldSession=prefs.getInt("session",-1)
            val priorSessions=installer.mySessions.map { it.sessionId }.toSet()
            val params=PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if(Build.VERSION.SDK_INT>=31)setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val id=installer.createSession(params)
            try {
                check(id !in priorSessions)
                check(prefs.edit().putInt("session",id).commit())
                body(id)
            } finally {
                // Explicit cleanup is restricted to the session created by this fixture.
                if(installer.getSessionInfo(id)!=null)installer.abandonSession(id)
                awaitRemoved(id)
                UpdateSessionTestReceiver.reset()
                main {
                    AppUpdater::class.java.getDeclaredField("state").apply { isAccessible=true }.set(null,oldState)
                    committed.setBoolean(null,oldCommitted)
                }
                check(prefs.edit().apply { if(hadSession)putInt("session",oldSession) else remove("session") }.commit())
                check(installer.mySessions.map { it.sessionId }.toSet()==priorSessions)
            }
        }
        test("Updater clears a real uncommitted session after interrupted staging and allows retry") {
            fixture { id ->
                check(installer.getSessionInfo(id)?.isSealed==false)
                main { restore() }
                // Android removes abandoned sessions on its own handler. Until removal is
                // observed recovery must remain blocked rather than inventing success.
                awaitRemoved(id)
                if(AppUpdater.state.phase==AppUpdater.Phase.RECOVERY_REQUIRED)main { restore() }
                check(!prefs.contains("session"))
                check(AppUpdater.state.phase in setOf(AppUpdater.Phase.IDLE,AppUpdater.Phase.AVAILABLE))
            }
        }
        test("Updater forgets an absent saved session without creating another installation") {
            fixture { id ->
                installer.abandonSession(id)
                awaitRemoved(id)
                main { restore() }
                check(!prefs.contains("session"))
                check(AppUpdater.state.phase in setOf(AppUpdater.Phase.IDLE,AppUpdater.Phase.AVAILABLE))
            }
        }
        // USER_ACTION_REQUIRED makes this fixture explicitly unable to install silently.
        if(Build.VERSION.SDK_INT>=31)test("Updater preserves a real pending Android confirmation and never recommits it") {
            fixture { id ->
                val apk=File(context.applicationInfo.sourceDir)
                check(apk.isFile)
                installer.openSession(id).use { session ->
                    apk.inputStream().use { input -> session.openWrite("base.apk",0,apk.length()).use { output ->
                        input.copyTo(output);session.fsync(output)
                    } }
                    UpdateSessionTestReceiver.expect(id)
                    val callback=Intent(context,UpdateSessionTestReceiver::class.java).setAction(UpdateSessionTestReceiver.ACTION)
                    val result=PendingIntent.getBroadcast(context,id,callback,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                    session.commit(result.intentSender)
                }
                check(UpdateSessionTestReceiver.await()==PackageInstaller.STATUS_PENDING_USER_ACTION) { "Fixture did not wait for required user action" }
                check(installer.getSessionInfo(id)?.isSealed==true)
                main { restore();AppUpdater.cancel() }
                check(installer.getSessionInfo(id)?.isSealed==true)
                check(prefs.getInt("session",-1)==id)
                check(AppUpdater.state.phase==AppUpdater.Phase.INSTALLING)
                check(UpdateSessionTestReceiver.await()==PackageInstaller.STATUS_PENDING_USER_ACTION)
            }
        }
    }
}
