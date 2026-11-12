package fi.jommi.rivi

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Explicit, opaque, non-exported utility. Intent extras never save, restore or grant consent. */
class SettingsBackupActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private val backup by lazy { LocalSettingsBackup.create(applicationContext) }
    private var active=false
    private var lifecycle=0L
    private var pageOpenedAt=0L
    private var job:SettingsBackupCancellation?=null
    private var confirmation:SettingsBackupConfirmation<Pair<LocalSettingsBackup.Snapshot,LocalSettingsBackup.Slot>>?=null
    private var saved=LocalSettingsBackup.Status()
    private var recovery=LocalSettingsBackup.Status()
    private var message="Checking private snapshots…"
    private val scale:Float get()=AppReadability.referenceScale(this)
    private fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // Consent/preview is deliberately NOT recreated from a Bundle or intent.
        renderHome()
    }

    override fun onResume() {
        super.onResume();active=true;lifecycle++
        if(job==null)loadStatus() else { message="Finishing the cancelled action safely…";renderHome() }
    }

    override fun onPause() {
        active=false;lifecycle++
        confirmation?.cancel();confirmation=null
        job?.cancel()
        super.onPause()
    }

    override fun onDestroy() {
        job?.cancel();confirmation?.cancel();confirmation=null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun finish() {
        job?.cancel();confirmation?.cancel();confirmation=null
        super.finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if(confirmation!=null)cancelConfirmation() else finish()
    }

    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.keyCode in AppActionStyle.CONFIRM_KEYS &&
            (event.repeatCount>0 || event.downTime<pageOpenedAt))return true
        return super.dispatchKeyEvent(event)
    }

    private fun loadStatus(after:String?=null) {
        runWork("Checking private snapshots…",{ cancel ->
            backup.status(LocalSettingsBackup.Slot.SAVED,cancel) to backup.status(LocalSettingsBackup.Slot.BEFORE_RESTORE,cancel)
        }) { result ->
            result.onSuccess { (first,second) ->
                saved=first;recovery=second
                message=after ?: if(!SettingsBackupGuard.writersIntegrated)
                    "Backup is unavailable until this build’s settings writers are protected. Nothing has changed."
                else when(SettingsBackupGuard.restoreState) {
                    SettingsBackupGuard.RestoreState.RECOVERY_REQUIRED -> "A restore failed and disk rollback could not be verified. Saved snapshots are protected. Review the pre-restore copy to recover previous settings."
                    SettingsBackupGuard.RestoreState.RESTORED -> "The last local restore completed. Return to Home to reload settings."
                    SettingsBackupGuard.RestoreState.ROLLED_BACK -> "The last restore did not finish. Previous settings were recovered."
                    else -> "Choose Save to create a snapshot, or review one before restoring."
                }
            }.onFailure { message=failureMessage(it) }
            renderHome()
        }
    }

    private fun save() {
        runWork("Saving and verifying a private snapshot…",{ cancel -> backup.save(cancel) }) { result ->
            result.onSuccess { loadStatus("Snapshot saved and verified · ${time(it.savedAt)}") }
                .onFailure { message=failureMessage(it);renderHome() }
        }
    }

    private fun preview(slot:LocalSettingsBackup.Slot) {
        runWork("Reading and validating every settings store…",{ cancel -> backup.read(slot,cancel) }) { result ->
            result.onSuccess { snapshot -> showConfirmation(snapshot,slot) }
                .onFailure { message="Snapshot could not be validated. Nothing was restored. Save a new snapshot to replace a damaged saved copy.";renderHome() }
        }
    }

    private fun showConfirmation(snapshot:LocalSettingsBackup.Snapshot,slot:LocalSettingsBackup.Slot) {
        val consent=SettingsBackupConfirmation(snapshot to slot,SystemClock.elapsedRealtime())
        confirmation=consent
        val label=if(slot==LocalSettingsBackup.Slot.SAVED)"saved snapshot" else "pre-restore copy"
        render("Restore local settings?",
            "Validated $label · ${time(snapshot.savedAt)}\n\n"+
                "Replaces your current Home layout, hidden titles, app arrangement, local Watchlist, preferences and local playback records with this snapshot. Changes made since it was saved will be replaced.\n\n"+
                "Android device settings, accessibility helpers, streaming apps and accounts are not restored. Your saved snapshot stays intact.\n\n"+
                (if(slot==LocalSettingsBackup.Slot.SAVED)"A separate pre-restore copy must be saved successfully before any settings change.\n\n" else "The existing pre-restore copy will be kept.\n\n")+
                "Cancel is selected. This confirmation expires after two minutes.",
            listOf("Cancel" to { cancelConfirmation() },"Restore these local settings" to {
                val selected=consent.take(SystemClock.elapsedRealtime())
                if(selected==null) {
                    confirmation=null;message="Confirmation expired or not ready. Review the snapshot again.";renderHome()
                } else {
                    confirmation=null
                    runWork("Restoring local settings… Leaving cancels unfinished work and recovers previous settings.",
                        { cancel -> backup.restore(selected.first,selected.second,cancel) }) { result ->
                        result.onSuccess { loadStatus("Local settings restored. Return to Home to reload them. The saved snapshot is unchanged.") }
                            .onFailure { loadStatus(failureMessage(it)) }
                    }
                }
            }),firstFocus=0)
        // Buttons require a fresh remote press. Positive action stays disabled during guard.
        val restoreButton=findViewById<Button>(RESTORE_BUTTON_ID)
        restoreButton?.isEnabled=false
        ui.postDelayed({
            if(active && confirmation===consent)restoreButton?.isEnabled=true
        },1_200)
        ui.postDelayed({
            if(active && confirmation===consent) {
                consent.cancel();confirmation=null;message="Confirmation expired. Nothing was restored.";renderHome()
            }
        },120_001)
    }

    private fun cancelConfirmation() {
        confirmation?.cancel();confirmation=null
        message="Restore cancelled. Nothing was changed.";renderHome()
    }

    private fun renderHome() {
        val writable=SettingsBackupGuard.writersIntegrated && job==null
        fun summary(name:String,status:LocalSettingsBackup.Status)=when {
            status.invalid -> "$name: damaged or unsupported — restore unavailable."
            status.savedAt!=null -> "$name: ${time(status.savedAt)}"
            else -> "$name: none saved."
        }
        val buttons=mutableListOf<Pair<String,()->Unit>>()
        if(job!=null)buttons+="Cancel and go back" to { finish() }
        else {
            if(writable && !SettingsBackupGuard.recoveryRequired)buttons+=(if(saved.savedAt==null)"Save snapshot on this TV" else "Replace saved snapshot") to { save() }
            if(writable && !SettingsBackupGuard.recoveryRequired && saved.savedAt!=null)buttons+="Review saved snapshot" to { preview(LocalSettingsBackup.Slot.SAVED) }
            if(writable && recovery.savedAt!=null)buttons+="Review pre-restore copy" to { preview(LocalSettingsBackup.Slot.BEFORE_RESTORE) }
            buttons+="Back" to { finish() }
        }
        render("Local settings backup",
            "$message\n\n${summary("Saved snapshot",saved)}\n${summary("Pre-restore copy",recovery)}\n\n"+
                "Includes Home layout, hidden titles, app arrangement, local Watchlist, preferences and local playback records. Only prettTV’s four app-owned settings stores are included.\n\n"+
                "Private on this TV only. No cloud, account or sign-in. This backup will NOT survive uninstalling prettTV or clearing its app data.\n\n"+
                "No Android device settings, helper configuration or streaming-account logins. Saving replaces the saved snapshot only after the new copy is verified and atomically written.",buttons)
    }

    /** Two independently scrolling columns keep long text from clipping remote actions. */
    private fun render(title:String,body:String,actions:List<Pair<String,()->Unit>>,firstFocus:Int=0) {
        pageOpenedAt=SystemClock.uptimeMillis()
        val textColumn=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(label(body,26).apply { accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE })
        }
        val bodyScroll=ScrollView(this).apply {
            id=View.generateViewId();isFocusable=true;addView(textColumn)
            isVerticalScrollBarEnabled=true
        }
        val actionColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val buttons=actions.mapIndexed { index,(name,action) ->
            Button(this).apply {
                id=if(confirmation!=null && index==1)RESTORE_BUTTON_ID else View.generateViewId()
                text=name;AppActionStyle.button(this,scale)
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf()),
                    intArrayOf(PrettTvColors.DISABLED,PrettTvColors.TEXT)))
                setOnClickListener { if(active && !isFinishing)action() }
                actionColumn.addView(this,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(12) })
            }
        }
        buttons.forEachIndexed { index,button ->
            button.nextFocusLeftId=bodyScroll.id;button.nextFocusRightId=button.id
            button.nextFocusUpId=buttons[(index-1).coerceAtLeast(0)].id
            button.nextFocusDownId=buttons[(index+1).coerceAtMost(buttons.lastIndex)].id
        }
        bodyScroll.nextFocusRightId=buttons.first().id
        bodyScroll.nextFocusLeftId=bodyScroll.id
        val columns=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            addView(bodyScroll,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=px(64) })
            addView(ScrollView(this@SettingsBackupActivity).apply { addView(actionColumn) },LinearLayout.LayoutParams(px(580),-1))
        }
        setContentView(LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(PrettTvColors.BACKGROUND)
            setPadding(px(80),px(52),px(80),px(52))
            addView(label(title,42).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) },
                LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(34) })
            addView(columns,LinearLayout.LayoutParams(-1,0,1f))
        })
        buttons[firstFocus].apply { isFocusableInTouchMode=true;requestFocus() }
    }

    private fun label(value:String,size:Int)=TextView(this).apply {
        text=value;setTextColor(PrettTvColors.TEXT);AppActionStyle.textSize(this,size,scale)
        setLineSpacing(px(5).toFloat(),1f)
        layoutParams=ViewGroup.LayoutParams(-1,-2)
    }

    private fun <T> runWork(working:String,work:(SettingsBackupCancellation)->T,done:(Result<T>)->Unit) {
        if(!active || job!=null || isFinishing)return
        val cancel=SettingsBackupCancellation();job=cancel
        val started=lifecycle
        message=working;renderHome()
        try {
            worker.execute {
                val result=runCatching { cancel.check();work(cancel) }
                ui.post {
                    if(job!==cancel)return@post
                    job=null
                    if(isDestroyed || isFinishing)return@post
                    if(active && lifecycle==started)done(result)
                    else if(active)loadStatus("Previous action ended. Review the snapshot status before continuing.")
                }
            }
        } catch(_:java.util.concurrent.RejectedExecutionException) {
            job=null;message="Another backup action is finishing. Go back and try again shortly.";renderHome()
        }
    }

    private fun failureMessage(failure:Throwable):String=when(failure) {
        is CancellationException -> "Action cancelled. No further restore writes will start."
        is LocalSettingsTransfer.RestoreFailure -> if(failure.rollbackSucceeded)
            "Restore did not finish. Previous settings were recovered; your saved snapshot is unchanged."
        else "Restore failed and disk rollback could not be verified. Keep both snapshots. Review the pre-restore copy to recover previous settings; do not save over your backup."
        else -> "Action could not finish. No settings were restored. Reopen Backup to check the saved snapshot. If saving still fails, check available TV storage and try again."
    }

    private fun time(value:Long)=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(value))

    companion object {
        private const val RESTORE_BUTTON_ID=0x00BACC01
        // One running operation and one queued operation across all Activity instances.
        private val worker=ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,ArrayBlockingQueue(1),
            { task -> Thread(task,"prett-settings-backup").apply { isDaemon=true } },ThreadPoolExecutor.AbortPolicy())
            .apply { allowCoreThreadTimeOut(true) }
    }
}
