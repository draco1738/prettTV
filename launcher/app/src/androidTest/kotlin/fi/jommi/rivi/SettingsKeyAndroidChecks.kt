package fi.jommi.rivi

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.xmlpull.v1.XmlPullParser

/** Calls the isolated service directly: never enables accessibility or injects a real remote key. */
internal object SettingsKeyAndroidChecks {
    private class RecordingService(context:Context):SettingsKeyService() {
        var launches=0
        init { attachBaseContext(context) }
        override fun openSettings() { launches++ }
        fun connect() { onServiceConnected() }
        fun send(event:KeyEvent):Boolean=onKeyEvent(event)
    }

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        test("TCL Settings uses the native overlay and falls back only when service startup fails") {
            class RecordingContext:ContextWrapper(context) {
                val services=ArrayList<Intent>()
                val activities=ArrayList<Intent>()
                var failService=false
                var throwService=false
                override fun startService(intent:Intent):ComponentName? {
                    services+=Intent(intent)
                    if(throwService)throw SecurityException("Isolated fixture")
                    return if(failService)null else intent.component
                }
                override fun startActivity(intent:Intent) { activities+=Intent(intent) }
            }
            val recording=RecordingContext()
            check(TvSettingsLauncher.open(recording,"TCL"))
            check(recording.activities.isEmpty() && recording.services.size==1)
            val overlay=recording.services.single()
            check(overlay.component==ComponentName("com.android.tv.settings","com.tcl.settings.ShowWindowService"))
            check(overlay.action=="com.tcl.settings.SHOW_WINDOW" && overlay.getStringExtra("Type")=="quicksettings")
            check(!overlay.getBooleanExtra("exitStartGoogleSettings",true))
            for(throws in listOf(false,true)) {
                recording.failService=true;recording.throwService=throws
                check(TvSettingsLauncher.open(recording,"TCL"))
                check(recording.activities.last().action==Settings.ACTION_SETTINGS)
            }
            val serviceCount=recording.services.size
            check(TvSettingsLauncher.open(recording,"Other"))
            check(recording.services.size==serviceCount)
            val flags=Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            check(recording.activities.all { it.flags and flags==flags })
        }
        test("Settings helper declares key filtering without window access and stays disabled in the test app") {
            val info=context.packageManager.getServiceInfo(ComponentName(context,SettingsKeyService::class.java),
                PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS)
            check(info.permission=="android.permission.BIND_ACCESSIBILITY_SERVICE" && !info.enabled)
            val parser=info.loadXmlMetaData(context.packageManager,"android.accessibilityservice")
                ?: error("Settings helper metadata missing")
            try {
                while(parser.eventType!=XmlPullParser.START_TAG && parser.eventType!=XmlPullParser.END_DOCUMENT)parser.next()
                val ns="http://schemas.android.com/apk/res/android"
                check(parser.getAttributeBooleanValue(ns,"canRequestFilterKeyEvents",false))
                check(!parser.getAttributeBooleanValue(ns,"canRetrieveWindowContent",true))
                check(parser.getAttributeIntValue(ns,"accessibilityEventTypes",0)==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                check(parser.getAttributeValue(ns,"packageNames")=="dev.zane.pretttv")
            } finally { parser.close() }
        }
        test("Global Settings callback opens once after release and cancels queued work when paused or interrupted") {
            val prefs=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
            val hadPaused=prefs.contains("paused")
            val priorPaused=prefs.getBoolean("paused",false)
            var service:RecordingService?=null
            fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
            fun event(action:Int,time:Long,repeat:Int=0)=KeyEvent(time,time+repeat+1,action,KeyEvent.KEYCODE_SETTINGS,repeat)
            try {
                check(prefs.edit().putBoolean("paused",false).commit())
                main {
                    val helper=RecordingService(context)
                    service=helper
                    helper.connect()
                    check(!helper.send(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_HOME)))
                    val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,time)))
                    check(helper.send(event(KeyEvent.ACTION_DOWN,time,1)))
                    check(helper.launches==0)
                    check(helper.send(event(KeyEvent.ACTION_UP,time)))
                    check(helper.launches==0) { "Settings launched inside the key-filter callback" }
                    check(!helper.send(event(KeyEvent.ACTION_UP,time))) { "Duplicate release was consumed" }
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.launches==1) }
                main {
                    val helper=service!!
                    val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,time)))
                    check(helper.send(event(KeyEvent.ACTION_UP,time)))
                    check(prefs.edit().putBoolean("paused",true).commit())
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!
                    check(helper.launches==1) { "Recovery pause did not cancel the queued launch" }
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,SystemClock.uptimeMillis())))
                    check(prefs.edit().putBoolean("paused",false).commit())
                    val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,time)))
                    check(helper.send(event(KeyEvent.ACTION_UP,time)))
                    helper.onInterrupt()
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.launches==1) { "Interrupted helper launched Settings" } }
            } finally {
                main { service?.onDestroy() }
                check(prefs.edit().apply { if(hadPaused)putBoolean("paused",priorPaused) else remove("paused") }.commit())
            }
        }
    }
}
