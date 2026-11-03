package fi.jommi.rivi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/** Optional remote shortcuts. Own-window events are discarded; no content access or key logging. */
open class SettingsKeyService(manufacturer:String=Build.MANUFACTURER):AccessibilityService() {
    private val keys=SettingsKeyPolicy(manufacturer)
    private val appKeys=AppShortcutKeyPolicy(manufacturer)
    private val muteGesture=MuteBluetoothGesturePolicy(manufacturer)
    private val ui=Handler(Looper.getMainLooper())
    private var connected=false

    override fun onServiceConnected() { connected=true; clearPending() }

    override fun onKeyEvent(event:KeyEvent):Boolean {
        if(!connected)return false
        if(muteGesture.isMuteKey(event.keyCode)) {
            val toggle=muteGesture.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,
                event.downTime,event.eventTime,event.isCanceled,event.isLongPress,shortcutsPaused(),isControllerKey(event))
            if(toggle)ui.post { if(connected && !shortcutsPaused())toggleBluetooth() }
            // Both presses reach Android immediately, preserving ordinary single-Mute behavior
            // and restoring the original mute state after the completed double press.
            return false
        }
        muteGesture.cancelPending()
        val target=appKeys.packageFor(event.keyCode)
        // Navigation/OK/volume adjustment are the common path. Do not touch preferences or InputDevice
        // metadata for them, including while a distinct shortcut press is pending.
        if(target==null && !keys.isSettingsKey(event.keyCode))return false
        val paused=shortcutsPaused()
        if(target!=null) {
            val appDecision=appKeys.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,event.downTime,
                event.isCanceled,paused,isControllerKey(event))
            if(appDecision==AppShortcutKeyPolicy.Decision.OPEN_APP) {
                ui.post { if(connected && !shortcutsPaused())openApp(target) }
            }
            return appDecision!=AppShortcutKeyPolicy.Decision.PASS
        }
        val decision=keys.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,event.downTime,
            event.isCanceled,paused)
        if(decision==SettingsKeyPolicy.Decision.OPEN_SETTINGS) {
            // Return the consumed result before launching; no timer or remote-key reinjection.
            ui.post { if(connected && !shortcutsPaused())openSettings() }
        }
        return decision!=SettingsKeyPolicy.Decision.PASS
    }

    protected open fun shortcutsPaused():Boolean=StockRecoveryStore.paused(this)
    protected open fun isControllerKey(event:KeyEvent):Boolean=RemoteAppLauncher.isController(event)
    protected open fun toggleBluetooth() { BluetoothControlSession.toggle(this) }

    protected open fun openSettings() {
        TvSettingsLauncher.open(this)
    }

    protected open fun openApp(packageName:String) {
        if(!RemoteAppLauncher.open(this,packageName))
            android.widget.Toast.makeText(this,"This shortcut's app is unavailable",android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) { /* Configuration-only own-window event subscription. */ }
    override fun onInterrupt() { keys.cancelPending();appKeys.cancelPending();muteGesture.cancelPending();ui.removeCallbacksAndMessages(null) }
    override fun onUnbind(intent:Intent?):Boolean { connected=false;clearPending();return super.onUnbind(intent) }
    override fun onDestroy() { connected=false;clearPending();super.onDestroy() }
    private fun clearPending() { keys.reset();appKeys.reset();muteGesture.reset();ui.removeCallbacksAndMessages(null) }
}
