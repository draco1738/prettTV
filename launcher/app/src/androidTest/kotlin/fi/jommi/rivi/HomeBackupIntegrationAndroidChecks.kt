package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Real Home -> backup -> Home flow, confined to the disposable stability application's stores. */
internal object HomeBackupIntegrationAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE))
        val original=LocalSettingsTransfer.export(stores)
        val originalState=SettingsBackupGuard.restoreState
        val originalIntegrated=SettingsBackupGuard.writersIntegrated
        val folder=File(context.noBackupFilesDir,"settings-backup")
        val files=LocalSettingsBackup.Slot.entries.associateWith { slot ->
            File(folder,slot.filename).takeIf(File::exists)?.let { check(it.length()<=LocalSettingsBackup.MAX_FILE);it.readBytes() }
        }
        var home:MainActivity?=null
        var panel:SettingsBackupActivity?=null
        try {
            stores.values.forEach { check(it.edit().clear().commit()) }
            check(stores.getValue("home").edit().putBoolean("discovery",false).putBoolean("weatherEnabled",false)
                .putBoolean("tvPermissionAsked",true).putString("layout",HomeLayout.BALANCED.name)
                .putString("interfaceSize",HomeInterfaceSize.STANDARD.name).commit())
            LocalSettingsBackup.Slot.entries.forEach { File(folder,it.filename).delete() }
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            home=activity
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
            fun call(name:String,vararg args:Any)=MainActivity::class.java.declaredMethods.single {
                it.name==name && it.parameterTypes.size==args.size
            }.apply { isAccessible=true }.invoke(activity,*args)
            fun personal()=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
            waitFor(instrumentation) { activity.hasWindowFocus() && field("ready")==true && field("appDiscoveryComplete")==true }
            main(instrumentation) {
                MainActivity::class.java.getDeclaredField("fixtureRefreshSuspended").apply { isAccessible=true }.setBoolean(activity,true)
                MainActivity::class.java.getDeclaredField("generation").apply { isAccessible=true }
                    .setInt(activity,(field("generation") as Int)+1)
                (field("contentRepo") as ContentRepository).stop()
                (field("weatherRepo") as WeatherRepository).stop()
            }
            fun button(view:View,label:String):Button? {
                if(view is Button && view.text.toString()==label && view.visibility==View.VISIBLE)return view
                if(view is ViewGroup)for(i in 0 until view.childCount)button(view.getChildAt(i),label)?.let { return it }
                return null
            }
            fun panelReady(target:SettingsBackupActivity) {
                waitFor(instrumentation) {
                    target.hasWindowFocus() && SettingsBackupActivity::class.java.getDeclaredField("job")
                        .apply { isAccessible=true }.get(target)==null
                }
            }
            fun open():SettingsBackupActivity {
                val monitor=instrumentation.addMonitor(SettingsBackupActivity::class.java.name,null,false)
                try {
                    main(instrumentation) { call("openSettingsBackup") }
                    val target=monitor.waitForActivityWithTimeout(12_000) as? SettingsBackupActivity
                        ?: error("Home did not open Backup")
                    panel=target;panelReady(target)
                    waitFor(instrumentation) { field("visible")==false && field("backupDeparted")==true }
                    return target
                } finally { instrumentation.removeMonitor(monitor) }
            }
            fun click(target:SettingsBackupActivity,label:String) {
                waitFor(instrumentation) { button(target.window.decorView,label)?.isEnabled==true }
                main(instrumentation) { check(button(target.window.decorView,label)!!.requestFocusFromTouch()) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                instrumentation.waitForIdleSync()
            }
            fun close(target:SettingsBackupActivity) {
                click(target,"Back")
                waitFor(instrumentation) { activity.hasWindowFocus() && field("backupDeparted")==false && !SettingsBackupGuard.busy }
                panel=null
            }
            fun capture(name:String) {
                instrumentation.waitForIdleSync()
                val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    val dir=File(checkNotNull(context.getExternalFilesDir(null)),"visual-review").apply { check(isDirectory || mkdirs()) }
                    File(dir,"$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
                } finally { image.recycle() }
            }
            test("Home opens protected local backup and saves a verified snapshot using the remote") {
                check(SettingsBackupGuard.writersIntegrated)
                val target=open()
                capture("home-backup-standard")
                click(target,"Save snapshot on this TV")
                panelReady(target)
                waitFor(instrumentation) { button(target.window.decorView,"Review saved snapshot")!=null }
                val snapshot=LocalSettingsBackup.create(context).read(LocalSettingsBackup.Slot.SAVED,SettingsBackupCancellation())
                check(snapshot.savedAt>0)
                close(target)
            }
            test("Remote restore reloads Home size and Watchlist, clears Undo and rejects stale generation writes") {
                val added=Program("backup-ui-fixture","Saved after snapshot","","","fixture.app","Fixture app")
                main(instrumentation) {
                    call("applyInterfaceSize",HomeInterfaceSize.LARGER)
                    check(personal().save(added));check(personal().canUndo())
                }
                waitFor(instrumentation) { field("ready")==true }
                val stale=SettingsBackupGuard.generation
                val target=open()
                capture("home-backup-larger")
                click(target,"Review saved snapshot")
                waitFor(instrumentation) {
                    (target.currentFocus as? Button)?.text=="Cancel" && button(target.window.decorView,"Restore these local settings")?.isEnabled==true
                }
                capture("home-backup-confirmation-larger")
                click(target,"Restore these local settings")
                panelReady(target)
                waitFor(instrumentation) { SettingsBackupGuard.restoreState==SettingsBackupGuard.RestoreState.RESTORED }
                close(target)
                waitFor(instrumentation) {
                    field("ready")==true && field("settingsGeneration")==SettingsBackupGuard.generation && activity.currentFocus!=null
                }
                main(instrumentation) {
                    check(stores.getValue("home").getString("interfaceSize",null)==HomeInterfaceSize.STANDARD.name)
                    check(kotlin.math.abs((field("scale") as Float)-activity.resources.displayMetrics.widthPixels/1920f)<.001f)
                    check(!personal().isSaved(added) && !personal().canUndo())
                    check(field("layout")==HomeLayout.BALANCED)
                    check(activity.currentFocus!!.isAttachedToWindow)
                    check(!SettingsBackupGuard.tryWrite(stale) { stores.getValue("home").edit().putString("interfaceSize","LARGER").apply() })
                }
                check(File(folder,LocalSettingsBackup.Slot.BEFORE_RESTORE.filename).isFile)
                capture("home-after-backup-restore")
            }
        } finally {
            panel?.let { main(instrumentation) { it.finish() } }
            home?.let { main(instrumentation) { it.finish() } }
            waitFor(instrumentation) { !SettingsBackupGuard.busy }
            LocalSettingsTransfer.restore(original,stores)
            files.forEach { (slot,bytes) ->
                val file=File(folder,slot.filename)
                if(bytes==null)file.delete() else { folder.mkdirs();file.writeBytes(bytes) }
            }
            SettingsBackupGuard.recordRestore(originalState)
            SettingsBackupGuard::class.java.getDeclaredField("integrated").apply { isAccessible=true }
                .setBoolean(SettingsBackupGuard,originalIntegrated)
        }
    }
    private fun waitFor(instrumentation:Instrumentation,condition:()->Boolean) {
        val end=SystemClock.uptimeMillis()+15_000
        do { if(main(instrumentation,condition))return;SystemClock.sleep(40) } while(SystemClock.uptimeMillis()<end)
        error("Timed out waiting for Home/Backup integration")
    }
    private fun <T> main(instrumentation:Instrumentation,body:()->T):T {
        val result=AtomicReference<T>();val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(body()) } catch(error:Throwable) { failure.set(error) } }
        failure.get()?.let { throw it };return result.get()
    }
}
