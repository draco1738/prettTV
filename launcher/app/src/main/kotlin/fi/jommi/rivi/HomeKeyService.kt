package fi.jommi.rivi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/** Optional compatibility with TVs whose stock Home takes priority over the selected Home role.
 * Only stock Home window events are observed. No keys or screen content are requested, so native
 * volume repeats and voice input stay with Android. Google's result/detail screens stay open.
 */
open class HomeKeyService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!isStockHomeScreen(event.packageName?.toString(), event.className?.toString())) return
        if (redirectPaused()) return
        openHome()
    }

    // During the double-confirmed stock restore, Google Home must remain visible. After success
    // the persisted pause keeps this true across restarts until helpers are resumed.
    protected open fun redirectPaused(): Boolean = StockRecoveryStore.paused(this) ||
        StockRecoverySession.state.phase == StockRecoverySession.Phase.RESTORING

    protected open fun openHome() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (failure: Exception) {
            android.util.Log.w("prettTV-home", "Could not return to prettTV", failure)
        }
    }

    override fun onInterrupt() {}

    companion object {
        fun isStockHomeScreen(pkg: String?, cls: String?): Boolean = when (pkg) {
            "com.google.android.apps.tv.launcherx" -> cls in setOf(
                "$pkg.home.HomeActivity", "$pkg.home.VanillaModeHomeActivity")
            "com.google.android.tvlauncher" -> cls == "$pkg.MainActivity"
            else -> false
        }
    }
}
