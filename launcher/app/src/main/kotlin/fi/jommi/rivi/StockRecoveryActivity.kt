package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.TextView

/** Separate non-exported screen so the Home feed cannot rebuild underneath a confirmation. */
class StockRecoveryActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var modal: AlertDialog? = null
    private var rendered: StockRecoverySession.Phase? = null
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        status = TextView(this).apply { textSize = 22f; setTextColor(0xFFF4F1E9.toInt()); setBackgroundColor(0xFF0F1510.toInt()); setPadding(64,64,64,64) }
        setContentView(status)
        if (!StockRecoverySession.busy) { StockRecoverySession.cancel(); showFirstConfirmation() }
    }
    override fun onStart() {
        super.onStart()
        StockRecoverySession.listen(this) { value ->
            status.text = getString(R.string.stock_recovery_status,value.message)
            if (value.phase == rendered || value.phase == StockRecoverySession.Phase.IDLE) return@listen
            rendered = value.phase; modal?.dismiss()
            when (value.phase) {
                StockRecoverySession.Phase.READY -> showSecondConfirmation(value.packageCount)
                StockRecoverySession.Phase.FAILED, StockRecoverySession.Phase.FINISHED -> {
                    modal = AlertDialog.Builder(this).setTitle(if(value.phase == StockRecoverySession.Phase.FINISHED) "Stock restore complete" else "Restore needs attention")
                        .setMessage(value.message).setPositiveButton("Close") { _, _ -> finish() }.create().also { it.show() }
                }
                else -> Unit
            }
        }
    }
    override fun onStop() { StockRecoverySession.unlisten(this); super.onStop() }
    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null); modal?.dismiss()
        if (isFinishing) StockRecoverySession.cancel()
        super.onDestroy()
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (StockRecoverySession.state.phase == StockRecoverySession.Phase.RESTORING) return
        StockRecoverySession.cancel(); finish()
    }
    private fun showFirstConfirmation() {
        status.setText(R.string.stock_recovery_title)
        val store = StockRecoveryStore(this)
        val plan = try { store.plan() } catch (_: Exception) { null }
        if (plan == null) {
            modal = AlertDialog.Builder(this).setTitle("Recovery setup needed")
                .setMessage("The original TV setup record has not been saved in prettTV. Import it from your setup backup before using full restore. Nothing has changed.\n\nSetup instructions: github.com/draco1738/prettTV/blob/main/docs/stock-recovery.md")
                .setPositiveButton("Close") { _,_ -> finish() }.create().also { it.show() }
            return
        }
        val last = store.status()
        val previous = if (last.startsWith("Restore started") || last.startsWith("Restore in progress") || last.startsWith("Recovery stopped") || last.startsWith("Restore did not finish")) "\n\nPrevious attempt: ${last.take(350)}" else ""
        confirm("Return to stock? · 1 of 2",
            "Restore Google TV Home, the ${plan.packages.size} TCL/Google apps changed during setup, and the original Private DNS settings.\n\nYour streaming apps, logins and prettTV settings stay on this TV. This is not a factory reset.\n\nContinue checks the connection first. Android may ask you to allow debugging. You will confirm once more before anything is restored.$previous",
            "Continue") { StockRecoverySession.check(this) }
    }
    private fun showSecondConfirmation(count: Int) {
        confirm("Confirm full restore · 2 of 2",
            "The saved setup matches this TV.\n\nRestore $count recorded TCL/Google apps and the original DNS settings, then make Google TV the Home screen. prettTV’s playback tracking and game helper will pause.\n\nYour streaming apps and logins stay in place. To finish, select Restore stock below.",
            "Restore stock") { StockRecoverySession.restore(this) }
    }
    private fun confirm(title: String, message: String, action: String, accept: () -> Unit) {
        val shown = SystemClock.elapsedRealtime()
        val dialog = AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton(action, null).setNegativeButton("Cancel") { _,_ -> StockRecoverySession.cancel(); finish() }.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { StockRecoverySession.cancel(); finish() }
        // Each screen needs a new press. Auto-repeat from a held remote button is consumed.
        dialog.setOnKeyListener { _, key, event ->
            key in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) &&
                (event.repeatCount > 0 || SystemClock.elapsedRealtime() - shown < 1200)
        }
        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            // Touch input can leave the window in touch mode when this TV dialog opens.
            // Keep the safe default focusable in both modes, including its first layout.
            cancel.isFocusableInTouchMode = true
            cancel.requestFocus()
            cancel.post { if (dialog.isShowing) cancel.requestFocus() }
            ui.postDelayed({ if (dialog.isShowing) positive.isEnabled = true }, 1200)
            positive.setOnClickListener { dialog.dismiss(); accept() }
        }
        modal = dialog; dialog.show()
    }
}
