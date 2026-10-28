package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs real Android decoders and worker queues in a separate app ID, never the owner's Home. */
class StabilityInstrumentation:Instrumentation() {
    private var settingsKeysOnly=false
    private var forceStopOnly=false
    private var visualPolishOnly=false
    override fun onCreate(arguments:Bundle?) {
        settingsKeysOnly=arguments?.getString("settingsKeysOnly")=="true"
        forceStopOnly=arguments?.getString("forceStopOnly")=="true"
        visualPolishOnly=arguments?.getString("visualPolishOnly")=="true"
        super.onCreate(arguments);start()
    }
    override fun onStart() {
        var passed=0
        try {
            check(targetContext.packageName=="dev.zane.pretttv.stability") { "Refusing to run against the production Home" }
            fun test(name:String,body:()->Unit) { body();passed++;sendStatus(0,Bundle().apply { putString("stream","PASS $name\n") }) }
            if(visualPolishOnly) {
                CardFocusAndroidChecks.run(this,::test)
                ContentRefreshAndroidChecks.run(targetContext,::test)
                WeatherRepositoryInstrumentationTest.run(this,::test)
                PlaybackHistoryAndroidChecks.run(targetContext,::test)
                HomeVisualAndroidChecks.run(this,::test)
                ForceStopAndroidChecks.run(this,::test)
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","VISUAL POLISH PASS: $passed Android tests\n");putInt("passed",passed) })
                return
            }
            if(forceStopOnly) {
                ForceStopAndroidChecks.run(this,::test)
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","FORCE STOP PASS: $passed Android tests\n");putInt("passed",passed) })
                return
            }
            if(settingsKeysOnly) {
                SettingsKeyAndroidChecks.run(this,::test)
                finish(Activity.RESULT_OK,Bundle().apply { putString("stream","SETTINGS KEYS PASS: $passed Android tests\n");putInt("passed",passed) })
                return
            }
            PersonalizationAndroidChecks.run(targetContext, ::test)
            DiscoveryAndroidChecks.run(targetContext, ::test)
            WeatherRepositoryInstrumentationTest.run(this, ::test)
            ContentRefreshAndroidChecks.run(targetContext, ::test)
            test("real decoder accepts fixture and downsamples large image") {
                withLoader { loader ->
                    check(load(loader,"good")!=null)
                    val large=load(loader,"large") ?: error("Large image did not decode")
                    check(large.width<=640 && large.height<=360 && large.allocationByteCount<=8_000_000)
                }
            }
            test("invalid and oversized artwork fail then recover") {
                withLoader { loader ->
                    check(load(loader,"broken")==null)
                    check(load(loader,"oversized")==null)
                    check(load(loader,"good/recovery")!=null)
                }
            }
            test("saturated queue completes every request, including rejected work") {
                withLoader { loader ->
                    val completed=CountDownLatch(100);val failures=AtomicInteger();val successes=AtomicInteger()
                    runOnMainSync { repeat(100) { i -> loader.load(url("slow/$i"),320,180) {
                        if(it==null)failures.incrementAndGet() else successes.incrementAndGet();completed.countDown()
                    } } }
                    check(completed.await(20,TimeUnit.SECONDS)) { "Some queued/rejected requests never completed" }
                    check(failures.get()>0 && successes.get()>0)
                }
            }
            test("cancelled generation cannot deliver into a replacement screen") {
                withLoader { loader ->
                    val cancelled=AtomicBoolean();val stale=AtomicInteger()
                    runOnMainSync {
                        repeat(20) { i -> loader.load(url("slow/cancel-$i"),320,180) { if(cancelled.get())stale.incrementAndGet() } }
                        cancelled.set(true);loader.cancel()
                    }
                    check(load(loader,"good/after-cancel")!=null)
                    Thread.sleep(300)
                    check(stale.get()==0) { "Cancelled artwork reached the screen" }
                }
            }
            test("closed loader suppresses late callbacks") {
                val deliveries=AtomicInteger();val loader=ArtworkLoader(targetContext)
                runOnMainSync { loader.load(url("slow/close"),320,180) { deliveries.incrementAndGet() };loader.close() }
                Thread.sleep(400)
                check(deliveries.get()==0)
            }
            test("corrupt app tile cache recovers without losing the app") {
                val repo=AppRepository(targetContext)
                val entry=AppEntry("fixture/icon","com.netflix.ninja","Fixture",android.content.Intent(),true,1)
                val file=java.io.File(targetContext.cacheDir,"tiles/${repo.cacheName(entry,320,180,0f)}")
                file.parentFile!!.mkdirs();file.writeBytes(byteArrayOf(1,2,3))
                val bitmap=repo.loadTile(entry,320,180,0f) ?: error("Corrupt cache prevented icon recovery")
                check(bitmap.width==320 && bitmap.height==180)
                check(file.length()>3)
            }
            test("recovery record parses a device-bound setup without connecting") {
                val record=recoveryRecord()
                val parsed=StockRecoveryStore.parse(record.toString())
                check(parsed.deviceSerial=="instrumentation-fixture-tv")
                check(parsed.packages.size==10 && parsed.packages.all { it.previousState==0 && it.managedState==3 })
                check(parsed.dnsMode==null && parsed.dnsSpecifier==null)
                check(parsed.managedDnsMode=="hostname" && parsed.managedDnsSpecifier=="dns.adguard-dns.com")
                val subset=recoveryRecord().put("packages",JSONArray().put(JSONObject()
                    .put("name",StockRestoreEngine.STOCK_PACKAGE).put("previousState",0).put("managedState",3)))
                check(StockRecoveryStore.parse(subset.toString()).packages.size==1)
                check(!StockRecoverySession.busy)
            }
            test("recovery record rejects missing fields and command-like values") {
                val malformed=arrayListOf<String>()
                val duplicate=recoveryRecord()
                val packages=duplicate.getJSONArray("packages")
                packages.put(1,JSONObject(packages.getJSONObject(0).toString()))
                malformed.add(duplicate.toString())
                val unknown=recoveryRecord()
                unknown.getJSONArray("packages").getJSONObject(0).put("name","com.example.unrecorded")
                malformed.add(unknown.toString())
                val injectedPackage=recoveryRecord()
                injectedPackage.getJSONArray("packages").getJSONObject(0).put("name","com.tcl.browser; reboot")
                malformed.add(injectedPackage.toString())
                malformed.add(recoveryRecord().put("stockHome","com.google.android.apps.tv.launcherx/.home.HomeActivity; reboot").toString())
                malformed.add(recoveryRecord().put("dnsSpecifier","dns.example; reboot").toString())
                malformed.add(recoveryRecord().put("dnsMode","hostname; reboot").toString())
                malformed.add(recoveryRecord().put("managedDnsSpecifier","different.example").toString())
                malformed.add(recoveryRecord().put("dnsMode","hostname").toString())
                malformed.add(recoveryRecord().put("version",2).toString())
                for(key in listOf("deviceSerial","fingerprint","stockHome","packages","dnsMode","dnsSpecifier","managedDnsMode","managedDnsSpecifier")) {
                    val missing=recoveryRecord();missing.remove(key);malformed.add(missing.toString())
                }
                malformed.add("{invalid-json")
                malformed.add(" ".repeat(24_577))
                for(record in malformed) {
                    var rejected=false
                    try { StockRecoveryStore.parse(record) } catch(_:Exception) { rejected=true }
                    check(rejected) { "An invalid recovery record was accepted" }
                }
                check(!StockRecoverySession.busy)
            }
            test("recovery ADB identity round trips in private no-backup storage") {
                val first=StockRecoveryStore(targetContext).keyPair()
                val loaded=StockRecoveryStore(targetContext).keyPair()
                check(first.public.encoded.contentEquals(loaded.public.encoded))
                check(first.private.encoded.contentEquals(loaded.private.encoded))
                val public=loaded.public as RSAPublicKey
                check(public.modulus.bitLength()==2048 && public.publicExponent.toInt()==65537)
                val challenge="prettTV isolated recovery key test".toByteArray(Charsets.UTF_8)
                val signature=Signature.getInstance("SHA256withRSA").apply { initSign(loaded.private);update(challenge) }.sign()
                check(Signature.getInstance("SHA256withRSA").apply { initVerify(first.public);update(challenge) }.verify(signature))
                val key=java.io.File(targetContext.noBackupFilesDir,"stock-recovery-adb-key.json")
                check(key.isFile && key.canonicalPath.startsWith(targetContext.noBackupFilesDir.canonicalPath+java.io.File.separator))
                check(!StockRecoverySession.busy)
            }
            test("first recovery confirmation defaults to Cancel in D-pad and touch modes without connecting") {
                val prefs=targetContext.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
                val priorPlan=prefs.getString("plan",null)
                val priorStatus=prefs.getString("status",null)
                var activity:Activity?=null
                try {
                    check(prefs.edit().putString("plan",recoveryRecord().toString()).putString("status","isolated cancellation fixture").commit())
                    for(touchMode in listOf(false,true)) {
                        setInTouchMode(touchMode)
                        runOnMainSync { StockRecoverySession.cancel() }
                        activity=startActivitySync(Intent(targetContext,StockRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        waitForIdleSync()
                        val cancel=waitForNode("Cancel")
                        check(cancel.isFocused) { "Recovery must initially focus Cancel (touchMode=$touchMode)" }
                        check(waitForNode("Return to stock? · 1 of 2").isVisibleToUser)
                        check(StockRecoverySession.state.phase==StockRecoverySession.Phase.IDLE)
                        check(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        waitForIdleSync()
                        check(StockRecoverySession.state.phase==StockRecoverySession.Phase.IDLE)
                        check(prefs.getString("status",null)=="isolated cancellation fixture")
                    }
                } finally {
                    activity?.let { screen -> runOnMainSync { if(!screen.isFinishing)screen.finish() } }
                    setInTouchMode(false)
                    runOnMainSync { StockRecoverySession.cancel() }
                    check(prefs.edit().apply {
                        if(priorPlan==null)remove("plan") else putString("plan",priorPlan)
                        if(priorStatus==null)remove("status") else putString("status",priorStatus)
                    }.commit())
                }
            }
            CardFocusAndroidChecks.run(this, ::test)
            SettingsKeyAndroidChecks.run(this, ::test)
            HomeFeatureAndroidChecks.run(this, ::test)
            PlaybackHistoryAndroidChecks.run(targetContext, ::test)
            HomeVisualAndroidChecks.run(this, ::test)
            UpdateAndroidChecks.run(this, ::test)
            ForceStopAndroidChecks.run(this, ::test)
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","STABILITY PASS: $passed Android fault tests\n");putInt("passed",passed) })
        } catch(t:Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","STABILITY FAIL after $passed: ${t.javaClass.simpleName}: ${t.message}\n");putString("failure",t.stackTraceToString()) })
        }
    }
    private fun url(path:String)="content://${targetContext.packageName}.art/$path"
    private fun recoveryRecord()=JSONObject().put("version",1)
        .put("deviceSerial","instrumentation-fixture-tv").put("fingerprint","fixture/vendor/device:11/test/release-keys")
        .put("stockHome",StockRestoreEngine.STOCK_HOME)
        .put("packages",JSONArray().apply { StockRestoreEngine.ALLOWED_PACKAGES.forEach { name ->
            put(JSONObject().put("name",name).put("previousState",0).put("managedState",3))
        } })
        .put("dnsMode",JSONObject.NULL).put("dnsSpecifier",JSONObject.NULL)
        .put("managedDnsMode","hostname").put("managedDnsSpecifier","dns.adguard-dns.com")
    private fun waitForNode(text:String):AccessibilityNodeInfo {
        repeat(20) {
            uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString()==text }?.let { return it }
            Thread.sleep(100)
        }
        error("Recovery confirmation did not expose $text")
    }
    private fun withLoader(body:(ArtworkLoader)->Unit) {
        val loader=ArtworkLoader(targetContext)
        try { body(loader) } finally { runOnMainSync { loader.close() } }
    }
    private fun load(loader:ArtworkLoader,path:String):Bitmap? {
        val result=AtomicReference<Bitmap?>();val done=CountDownLatch(1)
        runOnMainSync { loader.load(url(path),320,180) { result.set(it);done.countDown() } }
        check(done.await(10,TimeUnit.SECONDS)) { "Artwork request never completed: $path" }
        return result.get()
    }
}

/** No real stop or ADB connection: the only eligible target is our do-nothing fixture,
 * and every positive-action test removes the sandbox recovery plan before opening UI. */
private object ForceStopAndroidChecks {
    private const val FIXTURE="fi.jommi.rivi.testapp"

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        check(!AppForceStopSession.busy && !StockRecoverySession.busy)
        check(AppForceStopSession.canOffer(context,FIXTURE)) {
            "Install the do-nothing :testapp:assembleDebug fixture before force-stop UI tests"
        }
        val recovery=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
        val priorPlan=recovery.getString("plan",null)
        val key=java.io.File(context.noBackupFilesDir,"stock-recovery-adb-key.json")
        val priorKey=if(key.isFile)key.readBytes() else null
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
        )
        val priorSettings=LocalSettingsTransfer.export(stores)
        var activity:Activity?=null
        var home:MainActivity?=null
        fun main(block:()->Unit) {
            val failure=AtomicReference<Throwable?>()
            instrumentation.runOnMainSync { try { block() } catch(problem:Throwable) { failure.set(problem) } }
            failure.get()?.let { throw it }
        }
        fun panel(screen:Activity)=AppForceStopActivity::class.java.getDeclaredField("modal").apply { isAccessible=true }.get(screen) as AlertDialog
        fun open(target:String=FIXTURE):Activity {
            check(StockRecoveryStore(context).plan()==null) { "The isolated fixture must have no recovery setup" }
            return instrumentation.startActivitySync(Intent(context,AppForceStopActivity::class.java)
                .putExtra(AppForceStopActivity.EXTRA_PACKAGE,target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {
                activity=it; instrumentation.waitForIdleSync()
            }
        }
        fun close(screen:Activity) {
            main { if(!screen.isFinishing)screen.finish() }
            instrumentation.waitForIdleSync()
        }
        fun waitFor(condition:()->Boolean) {
            val end=SystemClock.uptimeMillis()+8_000
            do { if(condition())return; SystemClock.sleep(60) } while(SystemClock.uptimeMillis()<end)
            error("Force-stop UI did not reach its expected isolated state")
        }
        fun state(condition:()->Boolean):Boolean { var answer=false; main { answer=condition() }; return answer }
        fun visibleText(root:View,text:String):Boolean =
            root is TextView && root.isShown && root.text.toString()==text ||
                root is ViewGroup && (0 until root.childCount).any { visibleText(root.getChildAt(it),text) }
        fun actionButtons(root:View):List<android.widget.Button> =
            if(root is android.widget.Button)listOf(root)
            else if(root is ViewGroup)(0 until root.childCount).flatMap { actionButtons(root.getChildAt(it)) }
            else emptyList()
        fun accessibleNodes():List<AccessibilityNodeInfo> {
            val root=instrumentation.uiAutomation.rootInActiveWindow ?: return emptyList()
            val queue=java.util.ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
            val result=ArrayList<AccessibilityNodeInfo>()
            while(queue.isNotEmpty() && result.size<160) {
                val node=queue.removeFirst(); result.add(node)
                repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
            }
            return result
        }
        fun click(text:String) {
            try {
                waitFor {
                    var nativeDialog=false
                    var clicked=false
                    var remoteDialog:android.app.Dialog?=null
                    var remoteButtons:List<android.widget.Button> = emptyList()
                    main {
                        val screen=activity
                        if(screen is AppForceStopActivity && !screen.isFinishing && !screen.isDestroyed) {
                            val dialog=panel(screen)
                            if(dialog.isShowing) {
                                nativeDialog=true
                                val button=listOf(AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEUTRAL)
                                    .mapNotNull(dialog::getButton).singleOrNull { it.text.toString().equals(text,ignoreCase=true) }
                                if(button!=null && button.isShown && button.isEnabled)clicked=button.performClick()
                            }
                        }
                        if(!nativeDialog) {
                            val homeScreen=home
                            val actions=homeScreen?.let {
                                MainActivity::class.java.getDeclaredField("appActionPanel").apply { isAccessible=true }.get(it) as? android.app.Dialog
                            }
                            if(actions?.isShowing==true) {
                                nativeDialog=true
                                val decor=actions.window?.decorView
                                if(decor?.hasWindowFocus()==true) {
                                    val buttons=actionButtons(decor).filter { it.isShown && it.isEnabled }
                                    val target=buttons.singleOrNull { it.text.toString().equals(text,ignoreCase=true) }
                                    check(buttons.firstOrNull()?.text.toString()=="Open" && buttons.first().isFocused) {
                                        "App actions did not start focused on Open"
                                    }
                                    if(target!=null) {
                                        if(decor.isInTouchMode) {
                                            // Exercise a touch selection without moving D-pad focus first.
                                            // The live Button invokes the same listener as a real touch.
                                            clicked=target.performClick()
                                        } else {
                                            remoteDialog=actions
                                            remoteButtons=buttons.take(buttons.indexOf(target)+1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if(remoteButtons.isNotEmpty()) {
                        // Inject through the real window input path. Calling Dialog.dispatchKeyEvent
                        // directly bypasses ViewRootImpl's fallback D-pad focus navigation.
                        for(button in remoteButtons.drop(1)) {
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                            waitFor { state {
                                remoteDialog?.isShowing==true && remoteDialog?.window?.decorView?.hasWindowFocus()==true && button.isFocused
                            } }
                        }
                        check(state { remoteButtons.last().isFocused }) { "D-pad did not reach the live '$text' action" }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        clicked=true
                    }
                    if(nativeDialog)clicked else {
                        // Retain a bounded diagnostic fallback for other platform dialogs.
                        var node=accessibleNodes().firstOrNull {
                            it.isVisibleToUser && it.isEnabled && it.text?.toString()?.equals(text,ignoreCase=true)==true
                        }
                        while(node!=null && !node.isClickable)node=node.parent
                        node?.takeIf { it.isVisibleToUser && it.isEnabled }?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
                    }
                }
            } catch(failure:IllegalStateException) {
                val snapshot=runCatching { accessibleNodes().take(60).joinToString("\n") {
                    "${it.packageName}/${it.className}: text=${it.text?.toString()?.take(100)} visible=${it.isVisibleToUser} enabled=${it.isEnabled} clickable=${it.isClickable}"
                } }.getOrElse { "Accessibility snapshot unavailable: ${it.message}" }
                throw IllegalStateException("Could not click '$text'. Active accessibility root:\n${snapshot.ifEmpty { "<none>" }}",failure)
            }
            instrumentation.waitForIdleSync()
        }
        fun recreate(screen:Activity):Activity {
            val monitor=instrumentation.addMonitor(AppForceStopActivity::class.java.name,null,false)
            try {
                main { screen.recreate() }
                val replacement=monitor.waitForActivityWithTimeout(8_000)
                    ?: error("Force-stop activity did not recreate")
                activity=replacement
                instrumentation.waitForIdleSync()
                return replacement
            } finally { instrumentation.removeMonitor(monitor) }
        }
        try {
            check(recovery.edit().remove("plan").commit())
            check(StockRecoveryStore(context).plan()==null)
            test("Recovery reports a recoverable busy result while an app stop is cancelling without starting recovery") {
                val forceState=AppForceStopSession::class.java.getDeclaredField("state").apply { isAccessible=true }
                val recoveryState=StockRecoverySession::class.java.getDeclaredField("state").apply { isAccessible=true }
                fun recoveryField(name:String)=StockRecoverySession::class.java.getDeclaredField(name).apply { isAccessible=true }.get(StockRecoverySession)
                val beforeForce=AppForceStopSession.state
                val beforeRecovery=StockRecoverySession.state
                val beforeGeneration=recoveryField("generation")
                val beforePreferences=recovery.all.toMap()
                check(recoveryField("client")==null && recoveryField("engine")==null && recoveryField("approved")==null)
                try {
                    main {
                        forceState.set(AppForceStopSession,AppForceStopSession.State(
                            phase=AppForceStopSession.Phase.CANCELLING,packageName=FIXTURE,label="Isolated cancellation fixture"))
                        check(AppForceStopSession.busy)
                        StockRecoverySession.check(context)
                        val result=StockRecoverySession.state
                        check(result.phase==StockRecoverySession.Phase.FAILED && !StockRecoverySession.busy)
                        check(result.message.contains("force stop is still finishing") && result.message.contains("Nothing was restored"))
                        check(result.message.contains("open recovery again"))
                        check(AppForceStopSession.state.phase==AppForceStopSession.Phase.CANCELLING)
                        check(recoveryField("generation")==beforeGeneration) { "Busy guard started a recovery worker" }
                        check(recoveryField("client")==null && recoveryField("engine")==null && recoveryField("approved")==null)
                    }
                    check(recovery.all==beforePreferences)
                    check(if(priorKey==null)!key.exists() else key.readBytes().contentEquals(priorKey))
                } finally {
                    main {
                        forceState.set(AppForceStopSession,beforeForce)
                        recoveryState.set(StockRecoverySession,beforeRecovery)
                    }
                    instrumentation.waitForIdleSync()
                }
                check(AppForceStopSession.state===beforeForce && StockRecoverySession.state===beforeRecovery)
                check(!AppForceStopSession.busy && !StockRecoverySession.busy)
            }
            test("Force-stop activity is non-exported and rejects Home, system, missing and malformed targets") {
                @Suppress("DEPRECATION")
                val info=context.packageManager.getActivityInfo(ComponentName(context,AppForceStopActivity::class.java),0)
                check(!info.exported)
                for(target in listOf(context.packageName,"dev.zane.pretttv","android","com.android.systemui",
                    "com.android.tv.settings","com.google.android.apps.tv.launcherx","invalid; reboot","missing.force.stop.fixture")) {
                    check(!AppForceStopSession.canOffer(context,target)) { "Unsafe target was offered: $target" }
                }
                val before=AppForceStopSession.state
                val screen=open(context.packageName)
                // Window focus and the accessibility service's active-window snapshot can
                // update on different frames. Inspect the rendered dialog on the UI thread.
                waitFor { state {
                    val dialog=panel(screen)
                    dialog.isShowing && dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isFocused &&
                        dialog.window?.decorView?.let { visibleText(it,"App cannot be stopped") }==true
                } }
                check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                click("Done"); close(screen)
            }
            test("Force-stop confirmation defaults to Cancel and both Cancel and Back leave the target untouched") {
                val fixtureLabel=context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(FIXTURE,0)).toString().take(100)
                fun describe(value:AppForceStopSession.State)="${System.identityHashCode(value)}:$value"
                for(touchMode in listOf(false,true))for(useBack in listOf(false,true)) {
                    instrumentation.setInTouchMode(touchMode)
                    val before=AppForceStopSession.state
                    val screen=open()
                    waitFor { state {
                        val dialog=panel(screen)
                        val decor=dialog.window?.decorView
                        dialog.isShowing && decor?.hasWindowFocus()==true &&
                            visibleText(decor,"Force stop $fixtureLabel?") &&
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).let { it.isFocused && it.isShown && it.text.toString()=="Cancel" } &&
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).let { it.isShown && it.text.toString()=="Force stop" }
                    } }
                    check(AppForceStopSession.state===before && !AppForceStopSession.busy) {
                        "Confirmation opening changed state (touchMode=$touchMode useBack=$useBack): before=${describe(before)} after=${describe(AppForceStopSession.state)} busy=${AppForceStopSession.busy}"
                    }
                    if(useBack)instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) else click("Cancel")
                    instrumentation.waitForIdleSync()
                    waitFor { state { screen.isFinishing || screen.isDestroyed } }
                    check(AppForceStopSession.state===before && !AppForceStopSession.busy) {
                        "Cancellation changed state (touchMode=$touchMode useBack=$useBack): before=${describe(before)} after=${describe(AppForceStopSession.state)} busy=${AppForceStopSession.busy}"
                    }
                }
                instrumentation.setInTouchMode(false)
            }
            test("Force-stop confirmation keeps both actions fully visible with enlarged text and long app names") {
                val originalConfiguration=android.content.res.Configuration(context.resources.configuration)
                val originalMetrics=android.util.DisplayMetrics().apply { setTo(context.resources.displayMetrics) }
                val fixtureLabel=context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(FIXTURE,0)).toString().take(100)
                val longLabel="Fixture television application with a deliberately long descriptive name for enlarged text validation".take(100)
                val output=java.io.File(checkNotNull(context.getExternalFilesDir(null)),"visual-review").apply { check(isDirectory || mkdirs()) }
                val before=AppForceStopSession.state
                try {
                    for(fontScale in listOf(1f,1.3f))for(label in listOf(fixtureLabel,longLabel)) {
                        val screen=open()
                        try {
                            main {
                                val configuration=android.content.res.Configuration(originalConfiguration).apply { this.fontScale=fontScale }
                                // This changes Resources in the isolated test process, never a TV setting.
                                @Suppress("DEPRECATION")
                                screen.resources.updateConfiguration(configuration,originalMetrics)
                                panel(screen).dismiss()
                                AppForceStopActivity::class.java.getDeclaredField("label").apply { isAccessible=true }.set(screen,label)
                                AppForceStopActivity::class.java.getDeclaredMethod("showConfirmation").apply { isAccessible=true }.invoke(screen)
                            }
                            waitFor { state {
                                val dialog=panel(screen)
                                val decor=dialog.window?.decorView
                                val negative=dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                                val positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                dialog.isShowing && decor?.hasWindowFocus()==true && decor.isLaidOut && !decor.isLayoutRequested &&
                                    negative.isFocused && negative.isLaidOut && positive.isLaidOut &&
                                    !negative.isLayoutRequested && !positive.isLayoutRequested
                            } }
                            main {
                                val dialog=panel(screen)
                                val negative=dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                                val positive=dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                                val minimum=(58*screen.resources.displayMetrics.widthPixels/1920f+.5f).toInt().coerceAtLeast(1)
                                fun completeBounds(view:View):android.graphics.Rect {
                                    val rect=android.graphics.Rect()
                                    check(view.getGlobalVisibleRect(rect)) { "Action is not visible: ${(view as? TextView)?.text}" }
                                    check(view.height>=minimum && rect.height()>=view.height-1 && rect.width()>=view.width-1) {
                                        "Action clipped at fontScale=$fontScale labelLength=${label.length}: ${(view as? TextView)?.text} measured=${view.width}x${view.height} visible=$rect minimumHeight=$minimum"
                                    }
                                    return rect
                                }
                                val cancelBounds=completeBounds(negative)
                                val forceBounds=completeBounds(positive)
                                check(!android.graphics.Rect.intersects(cancelBounds,forceBounds)) { "Confirmation actions overlap" }
                                val message=checkNotNull(dialog.findViewById<TextView>(android.R.id.message))
                                val messageBounds=android.graphics.Rect()
                                check(message.getGlobalVisibleRect(messageBounds)) { "Confirmation explanation is hidden" }
                                check(messageBounds.bottom<=minOf(cancelBounds.top,forceBounds.top)) { "Confirmation message overlaps the actions" }
                                check(negative.text.toString()=="Cancel" && positive.text.toString()=="Force stop" && negative.isFocused)
                            }
                            if(label==longLabel) {
                                instrumentation.waitForIdleSync();SystemClock.sleep(350)
                                val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                                try {
                                    val suffix=if(fontScale==1f)"100" else "130"
                                    java.io.File(output,"force-stop-confirm-long-font-$suffix.png").outputStream().use {
                                        check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it))
                                    }
                                } finally { screenshot.recycle() }
                            }
                            check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                            click("Cancel")
                            waitFor { state { screen.isFinishing || screen.isDestroyed } }
                        } finally {
                            main {
                                @Suppress("DEPRECATION")
                                screen.resources.updateConfiguration(originalConfiguration,originalMetrics)
                            }
                            close(screen)
                        }
                    }
                } finally {
                    main {
                        @Suppress("DEPRECATION")
                        context.resources.updateConfiguration(originalConfiguration,originalMetrics)
                    }
                }
                check(AppForceStopSession.state===before && !AppForceStopSession.busy)
            }
            test("Held remote confirmation keys cannot carry a long press into force stop") {
                val before=AppForceStopSession.state
                val screen=open()
                main {
                    val dialog=panel(screen)
                    check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus())
                    for(keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                        val now=SystemClock.uptimeMillis()
                        check(dialog.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_DOWN,keyCode,2)))
                        check(dialog.dispatchKeyEvent(KeyEvent(now,now,KeyEvent.ACTION_UP,keyCode,2)))
                    }
                    check(dialog.isShowing)
                }
                instrumentation.waitForIdleSync()
                check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                click("Cancel"); close(screen)
            }
            test("Recreating a pending force-stop confirmation preserves cancellation without starting work") {
                val before=AppForceStopSession.state
                val screen=recreate(open())
                waitFor { state { panel(screen).getButton(AlertDialog.BUTTON_NEGATIVE).isFocused } }
                check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                click("Cancel"); close(screen)
            }
            test("Missing local setup fails before connection and recreation does not repeat the operation") {
                check(StockRecoveryStore(context).plan()==null)
                val screen=open()
                click("Force stop")
                waitFor { AppForceStopSession.state.phase==AppForceStopSession.Phase.FAILED }
                val failed=AppForceStopSession.state
                check(failed.packageName==FIXTURE && !failed.mutationMayHaveOccurred && failed.fallbackToAppInfo)
                check(failed.message.contains("has not been configured"))
                waitFor { state { panel(screen).getButton(AlertDialog.BUTTON_POSITIVE).text.toString()=="App info" &&
                    panel(screen).getButton(AlertDialog.BUTTON_NEGATIVE).isFocused } }
                val replacement=recreate(screen)
                waitFor { state { panel(replacement).getButton(AlertDialog.BUTTON_POSITIVE).text.toString()=="App info" } }
                check(AppForceStopSession.state===failed && !AppForceStopSession.busy)
                check(if(priorKey==null)!key.exists() else key.readBytes().contentEquals(priorKey)) {
                    "An app action created or replaced the local recovery key"
                }
                click("Done"); close(replacement)
            }
            test("Your apps and Library force-stop menus restore the originating tile and viewport after Cancel") {
                check(stores.getValue("home").edit().clear().putBoolean("discovery",false).putBoolean("tvPermissionAsked",true).commit())
                check(stores.getValue("apps").edit().clear().commit())
                val screen=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
                home=screen
                fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(screen)
                waitFor { state { field("appDiscoveryComplete")==true } }
                main { (field("contentRepo") as ContentRepository).stop() }
                @Suppress("UNCHECKED_CAST")
                val apps=field("apps") as List<AppEntry>
                val fixture=apps.single { it.packageName==FIXTURE }
                fun viewport():Pair<Int,Map<String,Int>> {
                    @Suppress("UNCHECKED_CAST") val rows=field("horizontalRows") as Map<String,View>
                    return (field("root") as View).scrollY to rows.mapValues { it.value.scrollX }
                }
                fun stableViewport():Pair<Int,Map<String,Int>> {
                    var previous:Pair<Int,Map<String,Int>>?=null
                    var stable=0
                    waitFor {
                        var current:Pair<Int,Map<String,Int>>?=null
                        main { current=viewport() }
                        if(current==previous)stable++ else { previous=current; stable=0 }
                        stable>=4
                    }
                    return checkNotNull(previous)
                }
                for(prefix in listOf("favorite-","app-"))for(touchMenu in listOf(false,true)) {
                    val origin=prefix+fixture.key
                    val before=AppForceStopSession.state
                    instrumentation.setInTouchMode(false)
                    // appDiscoveryComplete is set before render's first pre-draw restores
                    // focus. Do not target an unmeasured or background Home window.
                    waitFor { state {
                        @Suppress("UNCHECKED_CAST") val views=field("focusables") as Map<String,View>
                        val tile=views[origin]
                        screen.hasWindowFocus() && screen.window.decorView.isAttachedToWindow &&
                            field("pendingViewport")==null && tile!=null && tile.isAttachedToWindow &&
                            tile.isLaidOut && !tile.isLayoutRequested && tile.width>0 && tile.height>0
                    } }
                    main {
                        @Suppress("UNCHECKED_CAST") val views=field("focusables") as Map<String,View>
                        val tile=views[origin] ?: error("Fixture tile absent from $origin")
                        check(!tile.isFocusableInTouchMode) { "The app action permanently changed the tile's touch-focus policy" }
                        check(tile.requestFocusFromTouch()) {
                            "Fixture focus failed: origin=$origin window=${screen.hasWindowFocus()} attached=${tile.isAttachedToWindow} touch=${tile.isInTouchMode} shown=${tile.isShown}"
                        }
                    }
                    waitFor { state { screen.currentFocus?.tag==origin && !screen.window.decorView.isInTouchMode } }
                    val beforeViewport=stableViewport()
                    // A touch selection clears ordinary D-pad focus. The app action must
                    // preserve this tile even when its confirmation is then cancelled by OK.
                    instrumentation.setInTouchMode(touchMenu)
                    if(touchMenu)waitFor { state {
                        screen.window.decorView.isInTouchMode && screen.currentFocus?.tag!=origin
                    } }
                    main {
                        @Suppress("UNCHECKED_CAST") val views=field("focusables") as Map<String,View>
                        check(views.getValue(origin).performLongClick())
                    }
                    val monitor=instrumentation.addMonitor(AppForceStopActivity::class.java.name,null,false)
                    try {
                        click("Force stop")
                        val confirmation=monitor.waitForActivityWithTimeout(8_000) ?: error("App menu did not open force-stop confirmation")
                        activity=confirmation
                        waitFor { state { panel(confirmation).getButton(AlertDialog.BUTTON_NEGATIVE).isFocused } }
                        check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                        waitFor { state { screen.hasWindowFocus() } }
                        val afterViewport=stableViewport()
                        check(state { screen.currentFocus?.tag==origin }) {
                            "Cancel lost originating app focus: $origin (touchMenu=$touchMenu)"
                        }
                        check(afterViewport==beforeViewport) {
                            "Cancel moved Home or its rows: $beforeViewport -> $afterViewport (origin=$origin, touchMenu=$touchMenu)"
                        }
                        check(AppForceStopSession.state===before && !AppForceStopSession.busy)
                    } finally { instrumentation.removeMonitor(monitor) }
                }
            }
        } finally {
            activity?.let { close(it) }; home?.let { close(it) }
            instrumentation.setInTouchMode(false)
            // No positive test can get past plan lookup. Keep that invariant even on a UI failure.
            main { AppForceStopSession.cancel() }
            waitFor { !AppForceStopSession.busy }
            check(if(priorKey==null)!key.exists() else key.readBytes().contentEquals(priorKey))
            check(recovery.edit().apply { if(priorPlan==null)remove("plan") else putString("plan",priorPlan) }.commit())
            LocalSettingsTransfer.restore(priorSettings,stores)
        }
    }
}
