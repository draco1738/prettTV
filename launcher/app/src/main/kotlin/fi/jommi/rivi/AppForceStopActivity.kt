package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
    private lateinit var progressPanel: LinearLayout
    private val scale:Float get()=resources.displayMetrics.widthPixels/1920f
    private fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        target = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(target,0)).toString().take(100) }
            .getOrDefault("App")
        status = TextView(this).apply {
            AppActionStyle.textSize(this,23,scale); setTextColor(AppActionStyle.IVORY)
            text=getString(R.string.force_stop_title,label); setPadding(px(8),px(8),px(8),px(20))
        }
        cancel = Button(this).apply {
            id=View.generateViewId();setText(android.R.string.cancel);AppActionStyle.button(this,scale)
            setOnClickListener { cancelAndFinish() }
            nextFocusLeftId=id;nextFocusRightId=id;nextFocusUpId=id;nextFocusDownId=id
        }
        progressPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;background=AppActionStyle.surface(scale)
            setPadding(px(22),px(20),px(22),px(20))
            addView(status,LinearLayout.LayoutParams(-1,-2));addView(cancel,LinearLayout.LayoutParams(-1,-2))
        }
        val progressScroll=ScrollView(this).apply { addView(progressPanel) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            addView(progressScroll,FrameLayout.LayoutParams(-2,-2))
        })
        progressPanel.measure(View.MeasureSpec.makeMeasureSpec(px(400),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val place=placement(progressPanel.measuredHeight)
        progressScroll.layoutParams=FrameLayout.LayoutParams(place.width,place.height,Gravity.TOP or Gravity.START).apply {
            leftMargin=place.left;topMargin=place.top
        }
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
                    AppForceStopSession.Phase.CANCELLING -> { progressPanel.visibility=View.VISIBLE;cancel.isEnabled=false; cancel.setText(R.string.force_stop_cancelling) }
                    AppForceStopSession.Phase.RUNNING -> { progressPanel.visibility=View.VISIBLE;cancel.isEnabled=true; cancel.setText(android.R.string.cancel) }
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
        progressPanel.visibility=View.GONE
        val panel=dialogBuilder("Force stop $label?")
            .setMessage("Closes the app and its connections. Open it again to reconnect.\n\nYour login and saved data are kept.")
            .setPositiveButton("Force stop") { _,_ ->
                operationStarted=true
                progressPanel.visibility=View.VISIBLE
                status.text=getString(R.string.force_stop_progress,label)
                cancel.isFocusableInTouchMode=true;cancel.requestFocus()
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
        panel.setOnShowListener { styleDialog(panel) }
        modal=panel; panel.show()
    }

    private fun showResult(title:String,message:String,appInfo:Boolean,success:Boolean=false) {
        modal?.dismiss()
        progressPanel.visibility=View.GONE
        status.text=message
        cancel.visibility=View.GONE
        val builder=dialogBuilder(title).setMessage(message)
            .setNegativeButton("Done") { _,_ -> finish() }
        if(success) builder.setPositiveButton("Open app") { _,_ -> openApp() }
        else if(appInfo) builder.setPositiveButton("App info") { _,_ ->
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$target"))); finish() }
            catch(_:Exception) { showResult("App info unavailable","Open Android Settings → Apps to manage $label.",false) }
        }
        val panel=builder.create()
        panel.setOnCancelListener { finish() }
        panel.setOnShowListener { styleDialog(panel) }
        modal=panel; panel.show()
    }

    private fun dialogBuilder(title:String)=AlertDialog.Builder(this,R.style.Theme_PrettTvActionDialog)
        .setCustomTitle(TextView(this).apply {
            tag="force-stop-title"
            text=title;setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,30,scale)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(px(30),px(24),px(30),px(12))
        })

    private fun placement(height:Int):AppActionGeometry.Box {
        val anchor=intent.getIntArrayExtra(EXTRA_ANCHOR)?.takeIf { it.size==4 }
            ?.let { AppActionGeometry.Box(it[0],it[1],it[2],it[3]) }
        val metrics=resources.displayMetrics
        return AppActionGeometry.place(metrics.widthPixels,metrics.heightPixels,anchor,px(400),height,px(56),px(20))
    }

    /** Preserve real AlertDialog buttons for accessibility and the existing confirmation tests. */
    private fun styleDialog(panel:AlertDialog) {
        val title=panel.window?.decorView?.findViewWithTag<TextView>("force-stop-title")
        val message=panel.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,23,scale)
            setLineSpacing(px(4).toFloat(),1f)
        }
        val negative=panel.getButton(AlertDialog.BUTTON_NEGATIVE)
        val positive=panel.getButton(AlertDialog.BUTTON_POSITIVE).takeIf { it.visibility==View.VISIBLE }
        val buttons=listOfNotNull(negative,positive)

        // Firmware AlertDialog layouts reserve a horizontal button row. Merely changing its
        // orientation leaves the first stacked button clipped under the message. Retain the
        // real buttons and listeners, but measure their column independently of that layout.
        fun detach(view:View) { (view.parent as? ViewGroup)?.removeView(view) }
        val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        title?.let {
            detach(it);it.setPadding(px(8),px(4),px(8),px(18))
            body.addView(it,LinearLayout.LayoutParams(-1,-2))
        }
        message?.let {
            detach(it);it.setPadding(px(8),0,px(8),px(20))
            body.addView(it,LinearLayout.LayoutParams(-1,-2))
        }
        val buttonColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        buttons.forEach { button ->
            detach(button);AppActionStyle.button(button,scale);button.gravity=Gravity.CENTER
            buttonColumn.addView(button,LinearLayout.LayoutParams(-1,-2))
        }
        val contentWidth=(px(400)-px(44)).coerceAtLeast(1)
        val exactWidth=View.MeasureSpec.makeMeasureSpec(contentWidth,View.MeasureSpec.EXACTLY)
        val freeHeight=View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        body.measure(exactWidth,freeHeight);buttonColumn.measure(exactWidth,freeHeight)
        val place=placement(body.measuredHeight+buttonColumn.measuredHeight+px(40))
        val bodyHeight=(place.height-buttonColumn.measuredHeight-px(40)).coerceAtLeast(1)
        val scrollable=body.measuredHeight>bodyHeight
        val bodyScroll=ScrollView(this).apply {
            id=View.generateViewId();isFillViewport=false;isFocusable=scrollable
            isVerticalScrollBarEnabled=scrollable
            addView(body,ViewGroup.LayoutParams(-1,-2))
            nextFocusLeftId=id;nextFocusRightId=id;nextFocusUpId=id;nextFocusDownId=negative.id
            setOnKeyListener { _,key,event ->
                when {
                    event.action!=KeyEvent.ACTION_DOWN->false
                    key==KeyEvent.KEYCODE_DPAD_DOWN && !canScrollVertically(1)->{ negative.requestFocus();true }
                    key==KeyEvent.KEYCODE_DPAD_UP && !canScrollVertically(-1)->true
                    else->false
                }
            }
        }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(px(22),px(20),px(22),px(20))
            background=AppActionStyle.surface(scale)
            addView(bodyScroll,LinearLayout.LayoutParams(-1,bodyHeight))
            addView(buttonColumn,LinearLayout.LayoutParams(-1,buttonColumn.measuredHeight))
        }
        buttons.forEachIndexed { index,button ->
            button.nextFocusLeftId=button.id;button.nextFocusRightId=button.id
            button.nextFocusUpId=if(index==0 && scrollable)bodyScroll.id else buttons[(index-1).coerceAtLeast(0)].id
            button.nextFocusDownId=buttons[(index+1).coerceAtMost(buttons.lastIndex)].id
        }
        panel.setContentView(content)
        AppActionStyle.position(panel.window,place)
        // The translucent activity already dims Home; do not stack a second dim layer.
        panel.window?.let { window -> window.attributes=window.attributes.apply { dimAmount=0f } }
        negative.isFocusableInTouchMode=true;negative.requestFocus()
        negative.post { if(panel.isShowing)negative.requestFocus() }
    }

    private fun openApp() {
        val launch=packageManager.getLeanbackLaunchIntentForPackage(target) ?: packageManager.getLaunchIntentForPackage(target)
        try {
            if(launch==null) error("No launcher activity")
            startActivity(launch); finish()
        } catch(_:Exception) { showResult("Couldn’t open $label","The app was stopped. You can try opening it again from Your apps.",true) }
    }

    companion object {
        const val EXTRA_PACKAGE="appPackage"
        private const val EXTRA_ANCHOR="appAnchor"
        fun putAnchor(intent:Intent,anchor:View):Intent {
            val bounds=Rect()
            if(anchor.getGlobalVisibleRect(bounds))intent.putExtra(EXTRA_ANCHOR,intArrayOf(bounds.left,bounds.top,bounds.right,bounds.bottom))
            return intent
        }
    }
}
