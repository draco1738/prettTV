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
        var originalTouchMode:Boolean?=null
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
                instrumentation.runOnMainSync { originalTouchMode=screen.window.decorView.isInTouchMode }
                awaitReady(instrumentation,screen)
                check(AppUpdater.state.phase !in setOf(AppUpdater.Phase.DOWNLOADING, AppUpdater.Phase.INSTALLING))
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                instrumentation.waitForIdleSync()
                waitFor({ "after Back: finishing=${screen.isFinishing}, destroyed=${screen.isDestroyed}, phase=${AppUpdater.state.phase}" }) {
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
                instrumentation.waitForIdleSync()
            }
        }
        test("Updates startup selects Check now in both remote and inherited touch mode") {
            val restoreMode=checkNotNull(originalTouchMode) { "Initial input mode was not captured" }
            val priorSessions=context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()
            var activity:UpdateActivity?=null
            try {
                for(touchMode in listOf(false,true)) {
                    instrumentation.setInTouchMode(touchMode)
                    val screen=instrumentation.startActivitySync(Intent(context,UpdateActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UpdateActivity
                    activity=screen
                    instrumentation.waitForIdleSync()
                    awaitReady(instrumentation,screen)
                    instrumentation.runOnMainSync {
                        check(screen.window.decorView.isInTouchMode==touchMode) { "Startup changed the requested input mode $touchMode" }
                        screen.finish()
                    }
                    instrumentation.waitForIdleSync()
                    activity=null
                }
                check(context.packageManager.packageInstaller.mySessions.map { it.sessionId }.toSet()==priorSessions)
                check(AppUpdater.state.phase !in setOf(AppUpdater.Phase.DOWNLOADING,AppUpdater.Phase.INSTALLING))
            } finally {
                activity?.let { screen -> instrumentation.runOnMainSync { if(!screen.isFinishing)screen.finish() } }
                instrumentation.waitForIdleSync()
                instrumentation.setInTouchMode(restoreMode)
            }
        }
        UpdateSessionAndroidChecks.run(instrumentation,test)
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private fun awaitReady(instrumentation:Instrumentation,screen:UpdateActivity) {
        var diagnostics="No startup observation"
        waitFor({ diagnostics }) {
            var ready=false
            instrumentation.runOnMainSync {
                val decor=screen.window.decorView
                val controls=descendants(decor)
                val button=controls.filterIsInstance<Button>().firstOrNull { it.text.toString()=="Check now" }
                val titleShown=controls.filterIsInstance<TextView>().any { it.text.toString()=="prettTV updates" && it.isShown }
                val focused=screen.currentFocus
                diagnostics="attached=${decor.isAttachedToWindow}, windowFocus=${screen.hasWindowFocus()}, touchMode=${decor.isInTouchMode}, " +
                    "title=$titleShown, button=${button!=null}, shown=${button?.isShown}, enabled=${button?.isEnabled}, " +
                    "focusable=${button?.isFocusable}, touchFocusable=${button?.isFocusableInTouchMode}, focused=${button?.isFocused}, " +
                    "currentFocus=${focused?.javaClass?.simpleName}:${focused?.id}, finishing=${screen.isFinishing}, " +
                    "destroyed=${screen.isDestroyed}, phase=${AppUpdater.state.phase}"
                ready=decor.isAttachedToWindow && screen.hasWindowFocus() && titleShown &&
                    button!=null && button.isShown && button.isEnabled && button.isFocused
            }
            ready
        }
    }

    private fun waitFor(details:()->String,condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        do {
            if (condition()) return
            SystemClock.sleep(80)
        } while (SystemClock.uptimeMillis() < deadline)
        error("Updates did not reach the expected usable or closed state: ${details()}")
    }
}
