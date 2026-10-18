package fi.jommi.rivi

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
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
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        var passed=0
        try {
            check(targetContext.packageName=="dev.zane.pretttv.stability") { "Refusing to run against the production Home" }
            fun test(name:String,body:()->Unit) { body();passed++;sendStatus(0,Bundle().apply { putString("stream","PASS $name\n") }) }
            PersonalizationAndroidChecks.run(targetContext, ::test)
            DiscoveryAndroidChecks.run(targetContext, ::test)
            WeatherRepositoryInstrumentationTest.run(this, ::test)
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
            test("first recovery confirmation defaults to Cancel and cancels without connecting") {
                val prefs=targetContext.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
                val priorPlan=prefs.getString("plan",null)
                val priorStatus=prefs.getString("status",null)
                var activity:Activity?=null
                try {
                    check(prefs.edit().putString("plan",recoveryRecord().toString()).putString("status","isolated cancellation fixture").commit())
                    runOnMainSync { StockRecoverySession.cancel() }
                    activity=startActivitySync(Intent(targetContext,StockRecoveryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    waitForIdleSync()
                    val cancel=waitForNode("Cancel")
                    check(cancel.isFocused) { "Recovery must initially focus Cancel" }
                    check(waitForNode("Return to stock? · 1 of 2").isVisibleToUser)
                    check(StockRecoverySession.state.phase==StockRecoverySession.Phase.IDLE)
                    check(cancel.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    waitForIdleSync()
                    check(StockRecoverySession.state.phase==StockRecoverySession.Phase.IDLE)
                    check(prefs.getString("status",null)=="isolated cancellation fixture")
                } finally {
                    activity?.let { screen -> runOnMainSync { if(!screen.isFinishing)screen.finish() } }
                    runOnMainSync { StockRecoverySession.cancel() }
                    check(prefs.edit().apply {
                        if(priorPlan==null)remove("plan") else putString("plan",priorPlan)
                        if(priorStatus==null)remove("status") else putString("status",priorStatus)
                    }.commit())
                }
            }
            CardFocusAndroidChecks.run(this, ::test)
            HomeFeatureAndroidChecks.run(this, ::test)
            UpdateAndroidChecks.run(this, ::test)
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
