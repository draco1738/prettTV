package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Explicit, non-exported app action. Never accepts shell text or starts a stop from an intent. */
class AppForceStopActivity : Activity() {
    private var target = ""
    private var label = "App"
    private var operationStarted = false
    private var modal: AlertDialog? = null
    private var rendered: AppForceStopSession.State? = null
    private lateinit var status: TextView
    private lateinit var cancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(target,0)).toString().take(100) }
            .getOrDefault("App")
        val padding = (32 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { textSize=22f; setTextColor(0xFFF4F1E9.toInt()); text=getString(R.string.force_stop_title,label) }
        cancel = Button(this).apply { setText(android.R.string.cancel); setOnClickListener { cancelAndFinish() } }
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setBackgroundColor(0xFF0F1510.toInt())
            setPadding(padding,padding,padding,padding)
            addView(status,LinearLayout.LayoutParams(-1,0,1f))
            addView(cancel,LinearLayout.LayoutParams(-2,-2))
        })
        val priorOperation = savedInstanceState?.getBoolean("operationStarted") == true
        operationStarted = priorOperation ||
            (AppForceStopSession.busy && AppForceStopSession.state.packageName == target)
        if (priorOperation && (AppForceStopSession.state.packageName != target || AppForceStopSession.state.phase == AppForceStopSession.Phase.IDLE)) {
            operationStarted=false
            showResult("Check interrupted","The app’s stopped state could not be verified. Check App info or try again.",AppForceStopSession.canOffer(this,target))
        } else if (!operationStarted) {
            if (!AppForceStopSession.canOffer(this,target)) {
                showResult("App cannot be stopped", "This action is available for streaming apps and other launchable apps. TV system controls and Home are protected.", false)
            } else showConfirmation()
        }
    }

    override fun onStart() {
        super.onStart()
        AppForceStopSession.listen(this) { state ->
            if (operationStarted && state.packageName == target && rendered != state && !isFinishing) {
                rendered=state
                status.text=state.message
                when(state.phase) {
                    AppForceStopSession.Phase.SUCCEEDED -> showResult("$label stopped", "Open it again to reconnect. Your login and saved data are kept.", false, true)
                    AppForceStopSession.Phase.FAILED -> showResult("Couldn’t confirm $label stopped",state.message,state.fallbackToAppInfo)
                    AppForceStopSession.Phase.CANCELLED -> showResult("Check cancelled",state.message,state.fallbackToAppInfo)
                    AppForceStopSession.Phase.CANCELLING -> { cancel.isEnabled=false; cancel.setText(R.string.force_stop_cancelling) }
                    AppForceStopSession.Phase.RUNNING -> { cancel.isEnabled=true; cancel.setText(android.R.string.cancel) }
                    AppForceStopSession.Phase.IDLE -> if(operationStarted) showResult("Check interrupted","Open the app menu and try again. The app’s current state was not verified.",true)
                }
            }
        }
    }

    override fun onStop() {
        AppForceStopSession.unlisten(this)
        // Leaving the screen must not queue a late stop after the owner opens another app.
        if (!isChangingConfigurations && operationStarted && AppForceStopSession.state.packageName == target && AppForceStopSession.busy)
            AppForceStopSession.cancel()
        super.onStop()
    }
    override fun onDestroy() { modal?.dismiss(); modal=null; super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("operationStarted",operationStarted); super.onSaveInstanceState(outState) }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = cancelAndFinish()

    private fun cancelAndFinish() {
        if (operationStarted && AppForceStopSession.state.packageName == target) AppForceStopSession.cancel()
        finish()
    }

    private fun showConfirmation() {
        val panel=AlertDialog.Builder(this).setTitle("Force stop $label?")
            .setMessage("Closes the app and its connections. Open it again to reconnect.\n\nYour login and saved data are kept.")
            .setPositiveButton("Force stop") { _,_ ->
                operationStarted=true
                status.text=getString(R.string.force_stop_progress,label)
                if (!AppForceStopSession.start(this,target,label)) {
                    operationStarted=false
                    showResult("Another action is running","Wait for the current app action or stock recovery check to finish, then try again.",false)
                }
            }
            .setNegativeButton("Cancel") { _,_ -> finish() }.create()
        panel.setOnCancelListener { finish() }
        panel.setCanceledOnTouchOutside(false)
        panel.setOnKeyListener { _, key, event ->
            key in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER) && event.repeatCount>0
        }
        panel.setOnShowListener {
            panel.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                isFocusableInTouchMode=true; requestFocus(); post { if(panel.isShowing)requestFocus() }
            }
        }
        modal=panel; panel.show()
    }

    private fun showResult(title:String,message:String,appInfo:Boolean,success:Boolean=false) {
        modal?.dismiss()
        status.text=message
        cancel.visibility=View.GONE
        val builder=AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Done") { _,_ -> finish() }
        if(success) builder.setPositiveButton("Open app") { _,_ -> openApp() }
        else if(appInfo) builder.setPositiveButton("App info") { _,_ ->
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$target"))); finish() }
            catch(_:Exception) { showResult("App info unavailable","Open Android Settings → Apps to manage $label.",false) }
        }
        val panel=builder.create()
        panel.setOnCancelListener { finish() }
        panel.setOnShowListener {
            panel.getButton(AlertDialog.BUTTON_NEGATIVE).apply { isFocusableInTouchMode=true; requestFocus() }
        }
        modal=panel; panel.show()
    }

    private fun openApp() {
        val launch=packageManager.getLeanbackLaunchIntentForPackage(target) ?: packageManager.getLaunchIntentForPackage(target)
        try {
            if(launch==null) error("No launcher activity")
            startActivity(launch); finish()
        } catch(_:Exception) { showResult("Couldn’t open $label","The app was stopped. You can try opening it again from Your apps.",true) }
    }

    companion object { const val EXTRA_PACKAGE="appPackage" }
}
