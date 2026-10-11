package fi.jommi.rivi

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Optional "Home button" mode that disables nothing on the device.
 *
 * Google TV always resolves HOME to its own launcher (intent-filter priority 2; third-party apps
 * are capped at 0). When the owner switches this service on in Settings → Accessibility, it
 *   1. consumes the Home key and opens Rivi instead, and
 *   2. opens Rivi when the stock home screen appears by itself (after boot, or when an app exits).
 * The stock launcher stays installed and enabled, but it is never on screen, so it does not render.
 * Its process stays cached in memory (about 100–145 MB); a third-party app cannot release it on
 * Android 14, where killBackgroundProcesses() only affects the caller's own processes (measured:
 * 27 calls, the process survived all of them), so no attempt is made.
 *
 * It does no polling and holds no state: it only runs code when Home is pressed or when a window of
 * the stock launcher package changes (the event filter in res/xml/home_key_service.xml).
 */
class HomeKeyService : AccessibilityService() {

    override fun onServiceConnected() {
        connected = true
        // Bound during boot: the stock home screen may already be showing.
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) openHome()
    }

    override fun onUnbind(intent: Intent?): Boolean { connected = false; return super.onUnbind(intent) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) keysSeen++
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            allowStockHome = false
            homeKeyPresses++
            openHome()
        }
        return true   // swallow down, repeats and up so the stock launcher never sees Home
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (allowStockHome) return
        if (isStockHomeScreen(event.packageName?.toString(), event.className?.toString())) { redirects++; openHome() }
    }

    override fun onInterrupt() {}

    private fun openHome() {
        try {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (_: Exception) { }
    }

    companion object {
        private const val BOOT_WINDOW_MS = 180_000L
        @Volatile var connected = false; private set
        /** Set by the launcher menu ("Open Google TV home"); cleared by the next Home press. */
        @Volatile var allowStockHome = false
        @Volatile var homeKeyPresses = 0; private set
        @Volatile var redirects = 0; private set

        @Volatile var keysSeen = 0; private set

        private val stockPackages = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")

        /**
         * True only for the stock launcher's full home screen. Its other screens (the quick-settings
         * dashboard, profile chooser, re-auth, detail pages) must stay reachable.
         */
        fun isStockHomeScreen(pkg: String?, cls: String?): Boolean {
            if (pkg == null || cls == null || pkg !in stockPackages) return false
            return cls.endsWith(".home.HomeActivity") || cls.endsWith(".home.VanillaModeHomeActivity") || cls.endsWith(".MainActivity")
        }
    }
}
