package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/** Exercises real Window/decor creation; policy tests cannot catch vendor Window crashes. */
internal object UpdateAndroidChecks {
    fun run(instrumentation: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val context = instrumentation.targetContext
        check(context.packageName == "dev.zane.pretttv.stability") {
            "Refusing to exercise Updates in the production Home"
        }
        test("Updates opens with a focused control and Back never starts an installation") {
            val installer = context.packageManager.packageInstaller
            val priorSessions = installer.mySessions.map { it.sessionId }.toSet()
            val priorInstallPermission = context.packageManager.canRequestPackageInstalls()
            var activity: UpdateActivity? = null
            try {
                val screen = instrumentation.startActivitySync(
                    Intent(context, UpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ) as UpdateActivity
                activity = screen
                instrumentation.waitForIdleSync()
                waitFor {
                    var ready = false
                    instrumentation.runOnMainSync {
                        val decor = screen.window.decorView
                        val controls = descendants(decor)
                        val checkButton = controls.filterIsInstance<Button>().firstOrNull { it.text.toString() == "Check now" }
                        ready = decor.isAttachedToWindow && screen.hasWindowFocus() &&
                            controls.filterIsInstance<TextView>().any { it.text.toString() == "prettTV updates" && it.isShown } &&
                            checkButton != null && checkButton.isShown && checkButton.isEnabled && checkButton.isFocused
                    }
                    ready
                }
                check(AppUpdater.state.phase !in setOf(AppUpdater.Phase.DOWNLOADING, AppUpdater.Phase.INSTALLING))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                waitFor {
                    var finished = false
                    instrumentation.runOnMainSync { finished = screen.isFinishing || screen.isDestroyed }
                    finished
                }
                check(installer.mySessions.map { it.sessionId }.toSet() == priorSessions) {
                    "Opening and leaving Updates created an installation session"
                }
                check(context.packageManager.canRequestPackageInstalls() == priorInstallPermission) {
                    "Opening Updates changed unknown-source installation permission"
                }
                check(AppUpdater.state.phase != AppUpdater.Phase.INSTALLING)
            } finally {
                activity?.let { screen -> instrumentation.runOnMainSync { if (!screen.isFinishing) screen.finish() } }
            }
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (condition()) return
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Updates did not reach the expected usable or closed state")
    }
}
