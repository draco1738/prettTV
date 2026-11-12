package fi.jommi.rivi

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Parent runner calls run(...). Fixture prefs only; never dispatches ADB or touches production. */
internal object PortableSettingsBackupAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Portable checks require isolated stability app" }
        val provider=context.packageName+".portable-backup-fixture"
        val info=context.packageManager.resolveContentProvider(provider,0)
        check(info!=null && !info.exported && !info.grantUriPermissions) { "Register private stability-only PortableSettingsBackupTestProvider" }
        val stores=LocalSettingsBackup.STORE_NAMES.associateWith {
            context.getSharedPreferences("portable-backup-fixture-$it",Context.MODE_PRIVATE)
        }
        val prior=LocalSettingsTransfer.export(stores)
        val directory=File(context.noBackupFilesDir,"portable-backup-checks")
        val staging=File(requireNotNull(context.getExternalFilesDir(null)),PortableSettingsBackup.PC_IMPORT_FILENAME)
        val files=listOf(staging,PortableSettingsBackupTestProvider.document(context),File(context.noBackupFilesDir,"portable-provider-mismatch.snapshot"))
        val filesBefore=files.associateWith { file -> if(file.exists())file.inputStream().use { PortableSettingsBackup.readBounded(it,SettingsBackupCancellation()) } else null }
        val actualStores=mapOf("home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE))
        var actualBefore=LocalSettingsTransfer.export(actualStores)
        val state=SettingsBackupGuard.restoreState
        val integrated=SettingsBackupGuard.writersIntegrated
        val integration=SettingsBackupGuard::class.java.getDeclaredField("integrated").apply { isAccessible=true }
        var activity:SettingsBackupActivity?=null
        fun field(name:String)=SettingsBackupActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
        fun main(work:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { work() } catch(error:Throwable) { failure.set(error) } }
            failure.get()?.let { throw it }
        }
        fun idle(target:SettingsBackupActivity,timeout:Long=10_000) {
            val end=SystemClock.elapsedRealtime()+timeout
            while(true) {
                var ready=false
                main { ready=field("job").get(target)==null }
                if(ready)break
                check(SystemClock.elapsedRealtime()<end) { "Portable worker failed to settle" }
                SystemClock.sleep(20)
            }
            instrumentation.waitForIdleSync()
        }
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        fun click(target:SettingsBackupActivity,label:String) {
            main { check(views(target.window.decorView).filterIsInstance<Button>().single { it.text.toString()==label }.performClick()) }
        }
        fun result(target:SettingsBackupActivity,code:Int,result:Int,uri:Uri?) {
            SettingsBackupActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
                .apply { isAccessible=true }.invoke(target,code,result,uri?.let { Intent().setData(it) })
        }
        fun unchanged()=check(LocalSettingsTransfer.export(actualStores)==actualBefore) { "Non-fixture preferences changed" }
        try {
            SettingsBackupGuard.enableAfterWriterIntegration()
            SettingsBackupGuard.recordRestore(SettingsBackupGuard.RestoreState.NONE)
            stores.values.forEach { check(it.edit().clear().commit()) }
            check(stores.getValue("home").edit().putString("layout","BALANCED").putString("historySource","THIS_TV_ONLY").commit())
            val backup=LocalSettingsBackup(directory,stores)
            val bytes=backup.exportPortable(SettingsBackupCancellation())
            val target=instrumentation.startActivitySync(Intent(context,SettingsBackupActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse("content://$provider/readwrite"))
                .putExtra("restore",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SettingsBackupActivity
            activity=target;idle(target)
            main { check(field("confirmation").get(target)==null);check(field("documentResult").get(target)==null) }
            actualBefore=LocalSettingsTransfer.export(actualStores) // Covering Activity may legitimately save focus on Pause.
            main { field("backup\$delegate").set(target,lazyOf(backup)) }
            val uri=Uri.parse("content://$provider/readwrite")

            test("portable_provider_readback_verified_unavailable_and_mismatch") {
                val resolver=context.contentResolver
                fun write(path:String)=Uri.parse("content://$provider/$path").let { destination ->
                    PortableSettingsBackup.writeDocument(bytes,resolver,destination,SettingsBackupCancellation())
                }
                check(write("readwrite")==PortableSettingsBackup.SaveResult.VERIFIED)
                check(write("writeonly")==PortableSettingsBackup.SaveResult.UNVERIFIED)
                File(context.noBackupFilesDir,"portable-provider-mismatch.snapshot").writeBytes(byteArrayOf(0))
                check(write("mismatch")==PortableSettingsBackup.SaveResult.MISMATCH)
                unchanged()
            }
            for(path in listOf("blocked-open","blocked-read","blocked-write"))test("portable_${path.replace('-','_')}_pause_frees_backup_worker") {
                PortableSettingsBackupTestProvider.document(context).writeBytes(bytes)
                val fixture=PortableSettingsBackupTestProvider.BlockedFixture("/$path")
                PortableSettingsBackupTestProvider.blocked=fixture
                // Exceed any ordinary Android pipe capacity; small exports fit without blocking.
                val large=if(path=="blocked-write") {
                    check(stores.getValue("home").edit().putString("hiddenTitleLabel:pipe-fixture","x".repeat(2*1024*1024)).commit())
                    try { backup.exportPortable(SettingsBackupCancellation()) }
                    finally { check(stores.getValue("home").edit().remove("hiddenTitleLabel:pipe-fixture").commit()) }
                } else bytes
                try {
                    main {
                        val request=if(path=="blocked-write")0xB101 else 0xB102
                        field("chooser").set(target,request)
                        if(request==0xB101)field("exportBytes").set(target,large)
                        result(target,request,Activity.RESULT_OK,Uri.parse("content://$provider/$path"))
                    }
                    check(fixture.entered.await(3,java.util.concurrent.TimeUnit.SECONDS)) { "Provider was not reached" }
                    SystemClock.sleep(100)
                    main {
                        check(field("job").get(target)!=null) { "Fixture did not block" }
                        instrumentation.callActivityOnPause(target)
                        instrumentation.callActivityOnResume(target)
                    }
                    // Real Activity worker, not just a transport thread. Old code remains stuck.
                    idle(target,2_000)
                    main { check(field("confirmation").get(target)==null) }
                    if(path=="blocked-open") {
                        check(fixture.signalCancelled.await(1,java.util.concurrent.TimeUnit.SECONDS)) { "Provider open did not receive cancellation" }
                        check(PortableSettingsBackup.documentBusy) { "Uncooperative open lost its bounded lease" }
                    }
                    // While the provider remains held, the same worker must still read a local
                    // recovery input and show consent; cancelled provider callbacks cannot do so.
                    staging.writeBytes(bytes)
                    click(target,"Review PC copy");idle(target,2_000)
                    main { check(field("confirmation").get(target)!=null) }
                    click(target,"Cancel");unchanged()
                } finally {
                    fixture.releaseProvider()
                    val end=SystemClock.elapsedRealtime()+3_000
                    while(PortableSettingsBackup.documentBusy && SystemClock.elapsedRealtime()<end)SystemClock.sleep(10)
                    check(!PortableSettingsBackup.documentBusy) { "Native descriptor fixture did not drain" }
                    PortableSettingsBackupTestProvider.blocked=null
                }
                main { check(field("confirmation").get(target)==null) }
            }
            test("portable_native_open_deadline_without_pause") {
                val fixture=PortableSettingsBackupTestProvider.BlockedFixture("/blocked-open")
                PortableSettingsBackupTestProvider.blocked=fixture
                val end=SystemClock.elapsedRealtime()+PortableSettingsBackup.DOCUMENT_TIMEOUT_MS+3_000
                try {
                    main {
                        field("chooser").set(target,0xB102)
                        result(target,0xB102,Activity.RESULT_OK,Uri.parse("content://$provider/blocked-open"))
                    }
                    check(fixture.entered.await(3,java.util.concurrent.TimeUnit.SECONDS))
                    idle(target,PortableSettingsBackup.DOCUMENT_TIMEOUT_MS+2_000)
                    check(SystemClock.elapsedRealtime()<end)
                    main { check(field("confirmation").get(target)==null) }
                    check(fixture.signalCancelled.await(1,java.util.concurrent.TimeUnit.SECONDS))
                    unchanged()
                } finally {
                    fixture.releaseProvider()
                    val drain=SystemClock.elapsedRealtime()+3_000
                    while(PortableSettingsBackup.documentBusy && SystemClock.elapsedRealtime()<drain)SystemClock.sleep(10)
                    check(!PortableSettingsBackup.documentBusy)
                    PortableSettingsBackupTestProvider.blocked=null
                }
            }
            test("portable_native_malformed_nested_program_never_reaches_consent") {
                val valid=PortableSettingsBackup.decode(bytes,SettingsBackupCancellation())
                val root=org.json.JSONObject(String(java.util.Base64.getDecoder().decode(valid.encoded),Charsets.UTF_8))
                for(bad in listOf(
                    org.json.JSONObject().put("localSnapshot",org.json.JSONObject().put("id",9).put("uri",org.json.JSONArray()).put("pos","12")),
                    org.json.JSONObject().put("providerAbsent","true"))) {
                    root.getJSONObject("playback").put("known",org.json.JSONObject().put("type","string")
                        .put("value",org.json.JSONArray().put(bad).toString()))
                    val encoded=java.util.Base64.getEncoder().encodeToString(root.toString().toByteArray(Charsets.UTF_8))
                    PortableSettingsBackupTestProvider.document(context).writeBytes(
                        PortableSettingsBackup.encode(LocalSettingsBackup.Snapshot(1,encoded,valid.plan)))
                    main { field("chooser").set(target,0xB102);result(target,0xB102,Activity.RESULT_OK,uri) }
                    idle(target)
                    main { check(field("confirmation").get(target)==null) }
                    unchanged()
                }
            }
            test("portable_chooser_cancel_after_pause_does_not_restore") {
                main {
                    field("chooser").set(target,0xB102)
                    instrumentation.callActivityOnPause(target)
                    result(target,0xB102,Activity.RESULT_CANCELED,null)
                    instrumentation.callActivityOnResume(target)
                }
                idle(target)
                main { check(field("confirmation").get(target)==null) }
                unchanged()
            }
            test("portable_chooser_return_survives_pause_but_requires_cancel_first_preview") {
                PortableSettingsBackupTestProvider.document(context).writeBytes(bytes)
                main {
                    field("chooser").set(target,0xB102)
                    instrumentation.callActivityOnPause(target)
                    result(target,0xB102,Activity.RESULT_OK,uri)
                    check(field("confirmation").get(target)==null)
                    instrumentation.callActivityOnResume(target)
                }
                idle(target)
                main { check((target.currentFocus as? Button)?.text=="Cancel");check(field("confirmation").get(target)!=null) }
                click(target,"Cancel")
                main { check(field("confirmation").get(target)==null) }
                unchanged()
            }
            test("portable_unexpected_result_or_file_open_intent_cannot_grant_consent") {
                main { result(target,0xB102,Activity.RESULT_OK,uri);check(field("confirmation").get(target)==null) }
                unchanged()
            }
            test("portable_pc_preview_pause_revokes_consent") {
                staging.writeBytes(bytes)
                click(target,"Review PC copy");idle(target)
                main {
                    check((target.currentFocus as? Button)?.text=="Cancel")
                    instrumentation.callActivityOnPause(target)
                    check(field("confirmation").get(target)==null)
                    instrumentation.callActivityOnResume(target)
                }
                idle(target)
                main { check(field("confirmation").get(target)==null) }
                unchanged()
            }
            test("portable_pc_immutable_preview_restores_fixture_with_private_pre_restore_copy") {
                check(stores.getValue("home").edit().putString("layout","QUIET").commit())
                staging.writeBytes(bytes)
                click(target,"Review PC copy");idle(target)
                staging.writeBytes(byteArrayOf(0)) // Must not re-read this changed file after consent.
                SystemClock.sleep(1_300)
                click(target,"Restore these local settings");idle(target)
                check(stores.getValue("home").getString("layout",null)=="BALANCED")
                check(backup.read(LocalSettingsBackup.Slot.BEFORE_RESTORE,SettingsBackupCancellation()).plan.values.getValue("home")["layout"]=="QUIET")
                unchanged()
            }
            test("portable_pc_corrupt_payload_never_becomes_confirmation") {
                staging.writeBytes(byteArrayOf(0))
                click(target,"Review PC copy");idle(target)
                main { check(field("confirmation").get(target)==null) }
                unchanged()
            }
        } finally {
            activity?.let { target -> main { target.finish() } }
            val worker=field("worker").get(null) as java.util.concurrent.ThreadPoolExecutor
            val end=SystemClock.elapsedRealtime()+10_000
            while(worker.activeCount>0 || worker.queue.isNotEmpty() || SettingsBackupGuard.busy) {
                check(SystemClock.elapsedRealtime()<end) { "Cancelled fixture work did not drain; retain fixture files" }
                SystemClock.sleep(20)
            }
            LocalSettingsTransfer.restore(prior,stores)
            filesBefore.forEach { (file,data) -> if(data==null)file.delete() else file.writeBytes(data) }
            directory.deleteRecursively()
            SettingsBackupGuard.recordRestore(state)
            integration.setBoolean(SettingsBackupGuard,integrated)
        }
    }
}
