package fi.jommi.rivi

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import org.xmlpull.v1.XmlPullParser

/** Isolated callbacks and metadata only; does not enable a service or change Google packages. */
internal object HomeCompatibilityAndroidChecks {
    private class RecordingService(context: Context) : HomeKeyService() {
        var launches = 0
        var restoring = false
        init { attachBaseContext(context) }
        override fun openHome() { launches++ }
        override fun redirectPaused() = restoring || super.redirectPaused()
        fun key(event: KeyEvent) = onKeyEvent(event)
    }

    fun run(instrumentation: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == "dev.zane.pretttv.stability")
        test("Home compatibility cannot request key filtering or window content") {
            val info = context.packageManager.getServiceInfo(ComponentName(context, HomeKeyService::class.java), PackageManager.GET_META_DATA)
            check(info.permission == "android.permission.BIND_ACCESSIBILITY_SERVICE")
            val parser = info.loadXmlMetaData(context.packageManager, "android.accessibilityservice")
            try {
                while (parser.next() != XmlPullParser.START_TAG) { }
                val ns = "http://schemas.android.com/apk/res/android"
                check(!parser.getAttributeBooleanValue(ns, "canRequestFilterKeyEvents", false))
                check(!parser.getAttributeBooleanValue(ns, "canRetrieveWindowContent", false))
                check(parser.getAttributeIntValue(ns, "accessibilityFlags", -1) == 1)
                check(parser.getAttributeIntValue(ns, "accessibilityEventTypes", 0) == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                check(parser.getAttributeValue(ns, "packageNames") == "com.google.android.apps.tv.launcherx,com.google.android.tvlauncher")
            } finally { parser.close() }
        }
        test("Home redirect leaves Gemini, settings, unrelated apps and native keys untouched") {
            instrumentation.runOnMainSync {
                val service = RecordingService(context)
                val pkg = "com.google.android.apps.tv.launcherx"
                fun send(owner: String, cls: String, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    val event = AccessibilityEvent.obtain(type).apply { packageName = owner; className = cls }
                    try { service.onAccessibilityEvent(event) } finally { event.recycle() }
                }
                send(pkg, "$pkg.home.HomeActivity")
                check(service.launches == 1)
                for (cls in listOf("$pkg.gemini.chat.ChatActivity", "$pkg.dashboard.DashboardHandler", "$pkg.profile.chooser.ProfileChooserActivity", "$pkg.gemini.MainActivity")) send(pkg, cls)
                send("com.netflix.ninja", "com.netflix.ninja.home.HomeActivity")
                send(pkg, "$pkg.home.HomeActivity", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                check(service.launches == 1)
                for (code in listOf(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_HOME)) {
                    for (repeat in 0..8) check(!service.key(KeyEvent(1, 2, KeyEvent.ACTION_DOWN, code, repeat)))
                    check(!service.key(KeyEvent(KeyEvent.ACTION_UP, code)))
                }
                check(service.launches == 1)
                service.restoring = true
                send(pkg, "$pkg.home.HomeActivity")
                check(service.launches == 1)
                service.restoring = false
                send(pkg, "$pkg.home.HomeActivity")
                check(service.launches == 2)
            }
        }
        test("Persisted stock-restore pause keeps Google Home accessible across helper recreation") {
            val prefs = context.getSharedPreferences("stock-recovery", Context.MODE_PRIVATE)
            val had = prefs.contains("paused")
            val previous = prefs.getBoolean("paused", false)
            try {
                check(prefs.edit().putBoolean("paused", true).commit())
                instrumentation.runOnMainSync {
                    val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                        packageName = "com.google.android.apps.tv.launcherx"
                        className = "com.google.android.apps.tv.launcherx.home.HomeActivity"
                    }
                    try {
                        repeat(2) {
                            val service = RecordingService(context)
                            service.onAccessibilityEvent(event)
                            check(service.launches == 0)
                        }
                    } finally { event.recycle() }
                }
            } finally {
                check(prefs.edit().apply { if (had) putBoolean("paused", previous) else remove("paused") }.commit())
            }
        }
    }
}
