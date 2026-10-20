package fi.jommi.rivi

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.TextView

/**
 * A system-started idle handoff into the official EarthCam app, not an embedded EarthCam player.
 * EarthCam owns playback, accounts, camera selection and its first-run guide. Once it opens,
 * Home/Back exit that app; arbitrary remote input does not behave like dismissing a native dream.
 *
 * The real dream window is visible before startActivity, as Android's background-start policy
 * requires. There are no alarms, background polling, overlays, input injections or stream URLs.
 */
class EarthCamDreamService : DreamService() {
    private val handler = Handler(Looper.getMainLooper())
    private var attached = false
    private var dreaming = false
    private var attemptStarted = false
    private var framePending = false
    private var content: TextView? = null
    private val prefs by lazy { getSharedPreferences("prett-earthcam-dream", MODE_PRIVATE) }

    private val firstFrame = Runnable {
        framePending = false
        if (dreaming && attached && !attemptStarted && content?.hasWindowFocus() == true) handOff()
    }
    private val startDeadline = Runnable {
        // An OEM may not focus the dream canvas. Never keep a blank loading screen indefinitely.
        if (dreaming && !attemptStarted) returnHomeAndFinish()
    }
    private val finishAfterDispatch = Runnable {
        // A silently rejected launch leaves this window focused. Recover instead of assuming
        // startActivity returning successfully means another app actually took the screen.
        if (dreaming && attached && content?.hasWindowFocus() == true) {
            prefs.edit().putString("last-result", "EarthCam did not take focus; returning Home").apply()
            returnHomeAndFinish()
        } else {
            dreaming = false
            finish()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        dreaming = false
        attemptStarted = false
        framePending = false
        attached = true
        isFullscreen = true
        isScreenBright = true
        isInteractive = false
        content = TextView(this).apply {
            setText(R.string.earthcam_opening)
            textSize = 26f
            setTextColor(Color.rgb(229, 238, 235))
            setBackgroundColor(Color.rgb(8, 11, 12))
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        setContentView(content!!)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dreaming = true
        handler.postDelayed(startDeadline, 3500)
        scheduleVisibleFrame()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleVisibleFrame()
    }

    private fun scheduleVisibleFrame() {
        val view = content ?: return
        if (!dreaming || !attached || attemptStarted || framePending || !view.hasWindowFocus()) return
        framePending = true
        view.postOnAnimation(firstFrame)
    }

    private fun handOff() {
        if (!dreaming || !attached || attemptStarted) return
        attemptStarted = true
        handler.removeCallbacks(startDeadline)
        val now = SystemClock.elapsedRealtime()
        val remaining = EarthCamDreamPolicy.cooldownRemaining(prefs.getLong("last-attempt-elapsed", -1), now)
        if (remaining > 0) {
            returnHomeAndFinish()
            return
        }
        // Persist before dispatch: a failed or killed launch cannot cause an immediate dream loop.
        prefs.edit().putLong("last-attempt-elapsed", now).apply()
        val target = ComponentName(EarthCamDreamPolicy.PACKAGE, EarthCamDreamPolicy.ACTIVITY)
        val available = try {
            @Suppress("DEPRECATION")
            val activity = packageManager.getActivityInfo(target, 0)
            activity.enabled && activity.applicationInfo.enabled && activity.exported
        } catch (_: PackageManager.NameNotFoundException) { false }
        catch (_: RuntimeException) { false }
        if (!available) {
            prefs.edit().putString("last-result", "EarthCam unavailable").apply()
            returnHomeAndFinish()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            // Dispatch is not proof of playback. Android can silently reject a background launch.
            prefs.edit().putString("last-result", "EarthCam launch dispatched").apply()
            if (dreaming && attached) handler.postDelayed(finishAfterDispatch, 2000)
        } catch (_: RuntimeException) {
            prefs.edit().putString("last-result", "EarthCam launch unavailable").apply()
            returnHomeAndFinish()
        }
    }

    private fun returnHomeAndFinish() {
        attemptStarted = true
        handler.removeCallbacks(startDeadline)
        content?.removeCallbacks(firstFrame)
        framePending = false
        if (dreaming && attached && content?.hasWindowFocus() == true) {
            try {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName(this, MainActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
            } catch (_: RuntimeException) { /* Finishing still releases the system dream window. */ }
        }
        dreaming = false
        finish()
    }

    override fun onDreamingStopped() {
        dreaming = false
        cancelPendingWork()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        attached = false
        cancelPendingWork()
        content = null
        super.onDetachedFromWindow()
    }

    override fun onDestroy() {
        dreaming = false
        attached = false
        cancelPendingWork()
        super.onDestroy()
    }

    private fun cancelPendingWork() {
        content?.removeCallbacks(firstFrame)
        framePending = false
        handler.removeCallbacksAndMessages(null)
    }
}
