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
    private class RecordingService(context:Context,manufacturer:String=android.os.Build.MANUFACTURER):SettingsKeyService(manufacturer) {
        var launches=0
        var bluetoothToggles=0
        val appLaunches=ArrayList<String>()
        var forbidContextLookup=false
        var pauseReads=0
        var deviceReads=0
        init { attachBaseContext(context) }
        override fun openSettings() { launches++ }
        override fun openApp(packageName:String) { appLaunches+=packageName }
        override fun toggleBluetooth() { bluetoothToggles++ }
        override fun shortcutsPaused():Boolean {
            check(!forbidContextLookup) { "Unrelated key read recovery preferences" }
            pauseReads++
            return super.shortcutsPaused()
        }
        override fun isControllerKey(event:KeyEvent):Boolean {
            check(!forbidContextLookup) { "Unrelated key resolved controller/device metadata" }
            deviceReads++
            return super.isControllerKey(event)
        }
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
        test("Navigation, OK, volume and unknown keys bypass context lookup without disturbing pending shortcuts") {
            val prefs=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
            val hadPaused=prefs.contains("paused");val priorPaused=prefs.getBoolean("paused",false)
            var service:RecordingService?=null
            fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
            fun event(action:Int,key:Int,time:Long,repeat:Int=0)=KeyEvent(time,time+repeat+1,action,key,repeat)
            fun unrelated(helper:RecordingService,time:Long) {
                val before=helper.pauseReads to helper.deviceReads
                helper.forbidContextLookup=true
                try {
                    for(key in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_VOLUME_DOWN,KeyEvent.KEYCODE_MUTE,
                        KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,4141,4999)) {
                        check(!helper.send(event(KeyEvent.ACTION_DOWN,key,time)))
                        check(!helper.send(event(KeyEvent.ACTION_DOWN,key,time,1)))
                        check(!helper.send(event(KeyEvent.ACTION_UP,key,time)))
                    }
                    check(before==(helper.pauseReads to helper.deviceReads))
                } finally { helper.forbidContextLookup=false }
            }
            try {
                check(prefs.edit().putBoolean("paused",false).commit())
                main {
                    val helper=RecordingService(context);service=helper;helper.connect()
                    val time=SystemClock.uptimeMillis()
                    unrelated(helper,time)
                    check(helper.pauseReads==0 && helper.deviceReads==0)
                    check(helper.send(event(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_SETTINGS,time+1)))
                    unrelated(helper,time+2)
                    check(helper.send(event(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_SETTINGS,time+1)))
                    check(helper.deviceReads==0) { "Settings unnecessarily resolved a controller descriptor" }
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!;check(helper.launches==1)
                    val time=SystemClock.uptimeMillis()+10
                    check(helper.send(event(KeyEvent.ACTION_DOWN,190,time)))
                    unrelated(helper,time+1)
                    helper.onInterrupt()
                    check(helper.send(event(KeyEvent.ACTION_UP,190,time)))
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.appLaunches.isEmpty()) { "Fast pass lost pending shortcut cancellation" } }
            } finally {
                main { service?.onDestroy() }
                check(prefs.edit().apply { if(hadPaused)putBoolean("paused",priorPaused) else remove("paused") }.commit())
            }
        }
        test("App shortcuts open installed TV apps without clearing tasks or changing app data") {
            class RecordingContext:ContextWrapper(context) {
                val activities=ArrayList<Intent>()
                var fail=false
                override fun startActivity(intent:Intent) {
                    if(fail)throw SecurityException("Isolated fixture")
                    activities+=Intent(intent)
                }
            }
            val recording=RecordingContext()
            // These packages are installed on the validation TV; the context records, never launches.
            for(pkg in listOf("com.netflix.ninja","com.amazon.amazonvideo.livingroom","com.wbd.stream","com.phlox.tvwebbrowser")) {
                check(RemoteAppLauncher.open(recording,pkg)) { "No installed TV activity for $pkg" }
                val intent=recording.activities.last()
                check(intent.component?.packageName==pkg)
                check(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0)
                check(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK==0)
            }
            check(!RemoteAppLauncher.open(recording,"dev.zane.pretttv.fixture.absent"))
            recording.fail=true
            check(!RemoteAppLauncher.open(recording,"com.phlox.tvwebbrowser"))
        }
        test("Double speaker Mute passes every event immediately and queues one Bluetooth toggle after release") {
            val prefs=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
            val hadPaused=prefs.contains("paused");val priorPaused=prefs.getBoolean("paused",false)
            var service:RecordingService?=null
            fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
            fun event(action:Int,down:Long,at:Long)=KeyEvent(down,at,action,KeyEvent.KEYCODE_VOLUME_MUTE,0,0,-1,113,0,
                android.view.InputDevice.SOURCE_KEYBOARD)
            try {
                check(prefs.edit().putBoolean("paused",false).commit())
                main {
                    val helper=RecordingService(context);service=helper;helper.connect()
                    val time=SystemClock.uptimeMillis()
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,time,time)))
                    check(!helper.send(event(KeyEvent.ACTION_UP,time,time+30)))
                    check(helper.bluetoothToggles==0)
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,time+150,time+150)))
                    check(!helper.send(event(KeyEvent.ACTION_UP,time+150,time+180)))
                    check(helper.bluetoothToggles==0) { "Bluetooth ran inside the key-filter callback" }
                    check(!helper.send(event(KeyEvent.ACTION_UP,time+150,time+180)))
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,time+250,time+250)))
                    check(!helper.send(event(KeyEvent.ACTION_UP,time+250,time+280)))
                    check(helper.launches==0 && helper.appLaunches.isEmpty())
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.bluetoothToggles==1) { "Double/triple Mute must schedule exactly one toggle" } }
            } finally {
                main { service?.onDestroy() }
                check(prefs.edit().apply { if(hadPaused)putBoolean("paused",priorPaused) else remove("paused") }.commit())
            }
        }
        test("Non-TCL Mute remains native and never reads shortcut state or schedules Bluetooth") {
            var service:RecordingService?=null
            try {
                instrumentation.runOnMainSync {
                    val helper=RecordingService(context,"Sony");service=helper;helper.connect()
                    helper.forbidContextLookup=true
                    val time=SystemClock.uptimeMillis()
                    for(offset in listOf(0L,150L))for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                        check(!helper.send(KeyEvent(time+offset,time+offset+if(action==KeyEvent.ACTION_UP)30 else 0,
                            action,KeyEvent.KEYCODE_VOLUME_MUTE,0)))
                    }
                    check(helper.pauseReads==0 && helper.deviceReads==0)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync { check(service!!.bluetoothToggles==0) }
            } finally { instrumentation.runOnMainSync { service?.onDestroy() } }
        }
        test("Mute gesture ignores controllers and cancels queued toggles on recovery pause or service interruption") {
            val prefs=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
            val hadPaused=prefs.contains("paused");val priorPaused=prefs.getBoolean("paused",false)
            var service:RecordingService?=null
            fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
            fun tap(helper:RecordingService,time:Long,source:Int=android.view.InputDevice.SOURCE_KEYBOARD) {
                for(action in listOf(KeyEvent.ACTION_DOWN,KeyEvent.ACTION_UP)) {
                    check(!helper.send(KeyEvent(time,time+if(action==KeyEvent.ACTION_UP)30 else 0,action,
                        KeyEvent.KEYCODE_VOLUME_MUTE,0,0,-1,113,0,source)))
                }
            }
            try {
                check(prefs.edit().putBoolean("paused",false).commit())
                main {
                    val helper=RecordingService(context);service=helper;helper.connect()
                    val time=SystemClock.uptimeMillis()
                    tap(helper,time,android.view.InputDevice.SOURCE_GAMEPAD)
                    tap(helper,time+150,android.view.InputDevice.SOURCE_GAMEPAD)
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!;check(helper.bluetoothToggles==0) { "Controller input scheduled a Bluetooth toggle" }
                    helper.connect()
                    val time=SystemClock.uptimeMillis()
                    tap(helper,time);tap(helper,time+150)
                    check(prefs.edit().putBoolean("paused",true).commit())
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!;check(helper.bluetoothToggles==0)
                    check(prefs.edit().putBoolean("paused",false).commit());helper.connect()
                    val time=SystemClock.uptimeMillis()
                    tap(helper,time);tap(helper,time+150);helper.onInterrupt()
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!;check(helper.bluetoothToggles==0);helper.connect()
                    val time=SystemClock.uptimeMillis()
                    tap(helper,time);tap(helper,time+150);helper.onUnbind(Intent())
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.bluetoothToggles==0) }
            } finally {
                main { service?.onDestroy() }
                check(prefs.edit().apply { if(hadPaused)putBoolean("paused",priorPaused) else remove("paused") }.commit())
            }
        }
        test("App-key service queues one launch, leaves Disney and controllers alone, and cancels on interruption") {
            val prefs=context.getSharedPreferences("stock-recovery",Context.MODE_PRIVATE)
            val hadPaused=prefs.contains("paused")
            val priorPaused=prefs.getBoolean("paused",false)
            var service:RecordingService?=null
            fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
            fun event(action:Int,key:Int,time:Long,repeat:Int=0,source:Int=android.view.InputDevice.SOURCE_KEYBOARD)=
                KeyEvent(time,time+repeat+1,action,key,repeat,0,-1,0,0,source)
            try {
                check(prefs.edit().putBoolean("paused",false).commit())
                main {
                    val helper=RecordingService(context);service=helper;helper.connect()
                    val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,4020,time)))
                    check(helper.send(event(KeyEvent.ACTION_DOWN,4020,time,1)))
                    check(helper.send(event(KeyEvent.ACTION_UP,4020,time)))
                    check(helper.appLaunches.isEmpty()) { "App launched inside key-filter callback" }
                    check(!helper.send(event(KeyEvent.ACTION_UP,4020,time)))
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,4141,time+1)))
                    check(!helper.send(event(KeyEvent.ACTION_DOWN,190,time+2,source=android.view.InputDevice.SOURCE_GAMEPAD)))
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.appLaunches==listOf("com.wbd.stream")) }
                main {
                    val helper=service!!;val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,190,time)))
                    check(helper.send(event(KeyEvent.ACTION_UP,190,time)))
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.appLaunches.last()=="com.phlox.tvwebbrowser") }
                main {
                    val helper=service!!;val time=SystemClock.uptimeMillis()
                    check(helper.send(event(KeyEvent.ACTION_DOWN,4099,time)))
                    check(helper.send(event(KeyEvent.ACTION_UP,4099,time)))
                    helper.onInterrupt()
                }
                instrumentation.waitForIdleSync()
                main {
                    val helper=service!!;check(helper.appLaunches.size==2)
                    val time=SystemClock.uptimeMillis()+10
                    check(helper.send(event(KeyEvent.ACTION_DOWN,4062,time)))
                    check(helper.send(event(KeyEvent.ACTION_UP,4062,time)))
                    check(prefs.edit().putBoolean("paused",true).commit())
                }
                instrumentation.waitForIdleSync()
                main { check(service!!.appLaunches.size==2) }
            } finally {
                main { service?.onDestroy() }
                check(prefs.edit().apply { if(hadPaused)putBoolean("paused",priorPaused) else remove("paused") }.commit())
            }
        }
    }
}
