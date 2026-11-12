package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Button
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Register run(...) in the parent's isolated runner. Never runs against the production app. */
internal object SettingsBackupAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Backup UI checks require the isolated app" }
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("settings-backup-fixture-home",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("settings-backup-fixture-apps",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("settings-backup-fixture-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences("settings-backup-fixture-personal",Context.MODE_PRIVATE),
        )
        val folder=File(context.noBackupFilesDir,"settings-backup-ui-fixture")
        val actualStores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
        )
        val actualBefore=LocalSettingsTransfer.export(actualStores)
        fun actualFiles()=LocalSettingsBackup.Slot.entries.associateWith { slot ->
            File(context.noBackupFilesDir,"settings-backup/${slot.filename}").takeIf { it.exists() }?.let {
                check(it.length()<=LocalSettingsBackup.MAX_FILE)
                java.security.MessageDigest.getInstance("SHA-256").digest(it.readBytes()).toList()
            }
        }
        val filesBefore=actualFiles()
        fun unchanged() {
            check(LocalSettingsTransfer.export(actualStores)==actualBefore) { "Unexpected app preference write" }
            check(actualFiles()==filesBefore) { "Unexpected snapshot write" }
        }
        val original=LocalSettingsTransfer.export(stores)
        val integrationField=SettingsBackupGuard::class.java.getDeclaredField("integrated").apply { isAccessible=true }
        val integrated=SettingsBackupGuard.writersIntegrated
        var activity:SettingsBackupActivity?=null
        try {
            SettingsBackupGuard.enableAfterWriterIntegration()
            stores.values.forEach { check(it.edit().clear().commit()) }
            check(stores.getValue("home").edit().putString("layout","BALANCED").commit())
            val backup=LocalSettingsBackup(folder,stores)
            val snapshot=backup.save(SettingsBackupCancellation())
            fun field(name:String)=SettingsBackupActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
            fun main(action:()->Unit) {
                val failure=AtomicReference<Throwable?>()
                instrumentation.runOnMainSync { try { action() } catch(error:Throwable) { failure.set(error) } }
                failure.get()?.let { throw it }
            }
            fun idle(target:SettingsBackupActivity) {
                val end=SystemClock.elapsedRealtime()+10_000
                while(true) {
                    var ready=false
                    main { ready=field("job").get(target)==null }
                    if(ready)break
                    check(SystemClock.elapsedRealtime()<end) { "Backup worker did not settle" }
                    SystemClock.sleep(25)
                }
                instrumentation.waitForIdleSync()
            }
            fun preview(target:SettingsBackupActivity) {
                main {
                    SettingsBackupActivity::class.java.getDeclaredMethod("showConfirmation",LocalSettingsBackup.Snapshot::class.java,LocalSettingsBackup.Slot::class.java)
                        .apply { isAccessible=true }.invoke(target,snapshot,LocalSettingsBackup.Slot.SAVED)
                }
            }
            val target=instrumentation.startActivitySync(Intent(context,SettingsBackupActivity::class.java)
                .putExtra("restore",true).putExtra("backup",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsBackupActivity
            activity=target;idle(target)
            test("settings_backup_intent_cannot_grant_restore_consent") {
                main { check(field("confirmation").get(target)==null);check(target.currentFocus is Button) }
                check(stores.getValue("home").getString("layout",null)=="BALANCED")
                unchanged()
            }
            test("settings_backup_confirmation_cancel_first_and_held_ok_safe") {
                preview(target)
                main {
                    check((target.currentFocus as? Button)?.text=="Cancel")
                    val now=SystemClock.uptimeMillis()
                    check(target.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,3)))
                    check(field("confirmation").get(target)!=null)
                    target.onBackPressed()
                    check(field("confirmation").get(target)==null)
                }
                check(stores.getValue("home").getString("layout",null)=="BALANCED")
                unchanged()
            }
            test("settings_backup_pause_discards_confirmation_without_restore") {
                preview(target)
                main { instrumentation.callActivityOnPause(target);check(field("confirmation").get(target)==null) }
                main { instrumentation.callActivityOnResume(target) }
                idle(target)
                main { check(field("confirmation").get(target)==null) }
                check(stores.getValue("home").getString("layout",null)=="BALANCED")
                unchanged()
            }
        } finally {
            activity?.let { opened -> instrumentation.runOnMainSync { opened.finish() };instrumentation.waitForIdleSync() }
            LocalSettingsTransfer.restore(original,stores)
            folder.deleteRecursively()
            integrationField.setBoolean(SettingsBackupGuard,integrated)
        }
    }
}
