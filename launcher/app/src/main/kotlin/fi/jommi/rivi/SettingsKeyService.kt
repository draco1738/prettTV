package fi.jommi.rivi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional global Settings shortcut. Own-window events are discarded; no content access or key logging. */
open class SettingsKeyService:AccessibilityService() {
    private val keys=SettingsKeyPolicy(Build.MANUFACTURER)
    private val ui=Handler(Looper.getMainLooper())
    private var connected=false

    override fun onServiceConnected() { connected=true; clearPending() }

    override fun onKeyEvent(event:KeyEvent):Boolean {
        if(!connected)return false
        val decision=keys.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,event.downTime,
            event.isCanceled,StockRecoveryStore.paused(this))
        if(decision==SettingsKeyPolicy.Decision.OPEN_SETTINGS) {
            // Return the consumed result before launching; no timer or remote-key reinjection.
            ui.post { if(connected && !StockRecoveryStore.paused(this))openSettings() }
        }
        return decision!=SettingsKeyPolicy.Decision.PASS
    }

    protected open fun openSettings() {
        TvSettingsLauncher.open(this)
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) { /* Configuration-only own-window event subscription. */ }
    override fun onInterrupt() { keys.cancelPending();ui.removeCallbacksAndMessages(null) }
    override fun onUnbind(intent:Intent?):Boolean { connected=false;clearPending();return super.onUnbind(intent) }
    override fun onDestroy() { connected=false;clearPending();super.onDestroy() }
    private fun clearPending() { keys.reset();ui.removeCallbacksAndMessages(null) }
}
