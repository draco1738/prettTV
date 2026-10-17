package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Remote-friendly, non-exported update screen. Home never opens it automatically. */
class UpdateActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var check: Button
    private lateinit var download: Button
    private lateinit var install: Button
    private lateinit var cancel: Button
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(42), dp(26), dp(42), dp(32))
            setBackgroundColor(0xFF0F1510.toInt())
        }
        page.addView(label("prettTV updates", 30f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
        page.addView(label("Official releases · Your apps and settings stay in place", 17f, true))
        status = label("", 20f).apply { setPadding(0, dp(22), 0, dp(14)); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        page.addView(status)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        check = action("Check now") { AppUpdater.check(this) }
        download = action("Download") { AppUpdater.download(this) }
        install = action("Install") { requestInstall() }
        cancel = action("Cancel download") { AppUpdater.cancel() }
        listOf(check, download, install, cancel).forEach { controls.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(52)).apply { marginEnd = dp(12) }) }
        page.addView(controls)
        page.addView(label("What’s new", 24f).apply { setPadding(0, dp(25), 0, dp(8)) })
        notes = label("Check for updates to see the latest release notes.", 18f, true)
        page.addView(notes)
        page.addView(label("Downloads are verified before Android asks you to confirm installation. RBTV and other apps are managed separately.", 16f, true).apply { setPadding(0, dp(22), 0, dp(18)) })
        page.addView(action("Back") { AppUpdater.cancel(); finish() }, LinearLayout.LayoutParams(dp(120), dp(52)))
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
        // Some TV Window implementations crash when their controller is requested before
        // setContentView creates the decor. A posted decor callback also waits for attachment.
        val decor = window.decorView
        decor.post {
            if (!isFinishing && !isDestroyed && decor.isAttachedToWindow) {
                if (Build.VERSION.SDK_INT >= 30) {
                    decor.windowInsetsController?.apply {
                        hide(WindowInsets.Type.systemBars())
                        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else legacyFullscreen()
            }
        }
        AppUpdater.initialize(this)
        check.requestFocus()
    }

    override fun onStart() { super.onStart(); AppUpdater.listen(this, ::render) }
    override fun onStop() { AppUpdater.unlisten(this); super.onStop() }
    override fun onDestroy() { dialog?.dismiss(); if (isFinishing) AppUpdater.cancel(); super.onDestroy() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { AppUpdater.cancel(); finish() }

    private fun render(state: AppUpdater.State) {
        status.text = if (state.phase == AppUpdater.Phase.DOWNLOADING && state.progress >= 0) getString(R.string.update_download_progress, state.message, state.progress) else state.message
        val busy = state.phase in setOf(AppUpdater.Phase.CHECKING, AppUpdater.Phase.DOWNLOADING, AppUpdater.Phase.INSTALLING)
        check.isEnabled = !busy
        download.visibility = if (state.release != null && state.phase in setOf(AppUpdater.Phase.AVAILABLE, AppUpdater.Phase.FAILED)) View.VISIBLE else View.GONE
        install.visibility = if (state.phase == AppUpdater.Phase.READY) View.VISIBLE else View.GONE
        cancel.visibility = if (state.phase == AppUpdater.Phase.CHECKING || state.phase == AppUpdater.Phase.DOWNLOADING) View.VISIBLE else View.GONE
        cancel.text = if (state.phase == AppUpdater.Phase.CHECKING) "Cancel check" else "Cancel download"
        notes.text = state.release?.let { release -> "Version ${release.versionName}\n\n${plainNotes(release.notes)}" } ?: "No update release notes are available."
        val focused = currentFocus
        if (focused == null || focused.visibility != View.VISIBLE || !focused.isEnabled) {
            when { cancel.visibility == View.VISIBLE -> cancel; install.visibility == View.VISIBLE -> install; else -> check }.requestFocus()
        }
    }

    private fun requestInstall() {
        if (packageManager.canRequestPackageInstalls()) { AppUpdater.install(this); return }
        dialog = AlertDialog.Builder(this).setTitle("Allow update installation")
            .setMessage("Android needs your permission for prettTV to install its update. Enable “Allow from this source,” return here, then choose Install again.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Open Android settings") { _, _ ->
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                catch (_: Exception) { status.setText(R.string.update_permissions_unavailable) }
            }.create().also { it.show(); it.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus() }
    }

    private fun action(title: String, clicked: () -> Unit): Button = Button(this).apply {
        text = title; textSize = 17f; isAllCaps = false; setPadding(dp(18), 0, dp(18), 0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(0xFF7C897E.toInt(), 0xFF0F1510.toInt(), 0xFFF4F1E9.toInt())))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_enabled), ground(0xFFCDD9C7.toInt()))
            addState(intArrayOf(), ground(0xFF1B2922.toInt()))
        }
        setOnClickListener { clicked() }
    }
    private fun ground(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(8).toFloat(); setStroke(dp(1), 0xFF4B584B.toInt()) }
    private fun label(value: String, size: Float, muted: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(if (muted) 0xFFB2C0B5.toInt() else 0xFFF4F1E9.toInt()); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    // Android 8–10 do not expose WindowInsetsController.
    @Suppress("DEPRECATION")
    private fun legacyFullscreen() { window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY }
    private fun plainNotes(value: String): String = value.lineSequence().map { it.trimStart().removePrefix("### ").removePrefix("## ").removePrefix("# ") }.joinToString("\n")
        .replace(Regex("\\[([^]\\n]+)]\\(https?://[^)]+\\)"), "$1").replace("**", "").replace("`", "").ifBlank { "No release notes were supplied." }
}

/** Explicit mutable PendingIntent target; not exported, and only the recorded session is accepted. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "${context.packageName}.UPDATE_RESULT") return
        val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (!AppUpdater.installationResult(context, session, status)) return
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            try {
                if (confirmation == null) throw IllegalStateException("Missing installation confirmation")
                context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
                runCatching { context.packageManager.packageInstaller.abandonSession(session) }
                AppUpdater.installationResult(context, session, PackageInstaller.STATUS_FAILURE_ABORTED)
            }
        }
    }
}
