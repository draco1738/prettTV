package fi.jommi.rivi

import android.app.Activity
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Opening this non-exported screen only reads state. Only its visible button can change it. */
class BluetoothControlActivity:Activity() {
    private lateinit var controls:BluetoothControlView
    private var requestingMessages=false
    private var messagesDenied=false
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        controls=BluetoothControlView(this,{ desired ->
            if(!BluetoothControlSession.start(this,desired,includeAudio=true))controls.bind(BluetoothControlSession.State(message=getString(R.string.bluetooth_busy)))
        },{
            when(AudioDeviceSettings.open(this)) {
                AudioDeviceSettings.Destination.SETTINGS->android.widget.Toast.makeText(this,R.string.bluetooth_settings_fallback,android.widget.Toast.LENGTH_LONG).show()
                AudioDeviceSettings.Destination.UNAVAILABLE->android.widget.Toast.makeText(this,R.string.bluetooth_settings_unavailable,android.widget.Toast.LENGTH_LONG).show()
                else->Unit
            }
        },{ finish() },{ enableShortcutMessages() })
        requestingMessages=savedInstanceState?.getBoolean("requesting-shortcut-messages",false) ?: false
        messagesDenied=savedInstanceState?.getBoolean("shortcut-messages-denied",false) ?: false
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val width=(620*resources.displayMetrics.widthPixels/1920f+.5f).toInt()
            val scroll=ScrollView(this@BluetoothControlActivity).apply { isFillViewport=false;addView(controls) }
            addView(scroll,FrameLayout.LayoutParams(width,-2,Gravity.CENTER).apply {
                val margin=(56*resources.displayMetrics.widthPixels/1920f+.5f).toInt()
                topMargin=margin;bottomMargin=margin
            })
        })
    }
    override fun onResume() {
        super.onResume()
        refreshShortcutMessages()
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putBoolean("requesting-shortcut-messages",requestingMessages)
        outState.putBoolean("shortcut-messages-denied",messagesDenied)
        super.onSaveInstanceState(outState)
    }
    private fun refreshShortcutMessages() {
        val enabled=getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()==true
        if(enabled)messagesDenied=false
        controls.bindShortcutMessages(Build.VERSION.SDK_INT,enabled,requestingMessages,messagesDenied)
    }
    private fun enableShortcutMessages() {
        if(Build.VERSION.SDK_INT<33 || requestingMessages)return
        if(getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()==true) {
            refreshShortcutMessages();return
        }
        // Only this explicit panel action requests the normal Android permission. No radio
        // command, overlay permission, background prompt or settings override is involved.
        requestingMessages=true
        messagesDenied=false
        refreshShortcutMessages()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),SHORTCUT_MESSAGES_REQUEST)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==SHORTCUT_MESSAGES_REQUEST) {
            requestingMessages=false
            messagesDenied=getSystemService(NotificationManager::class.java)?.areNotificationsEnabled()!=true
            refreshShortcutMessages()
        }
    }
    override fun onStart() {
        super.onStart()
        var requested=false
        BluetoothControlSession.listen(this) {
            if(!isFinishing) {
                controls.bind(it)
                if(!it.working && !requested) {
                    requested=true
                    if(!BluetoothControlSession.start(this,includeAudio=true))
                        controls.bind(BluetoothControlSession.State(message=getString(R.string.bluetooth_busy)))
                }
            }
        }
    }
    override fun onStop() {
        BluetoothControlSession.cancel(this);BluetoothControlSession.unlisten(this)
        super.onStop()
    }
    private companion object { const val SHORTCUT_MESSAGES_REQUEST=73 }
}

/** Reuses the existing action palette, with stable controls and no nested confirmation. */
// Created only in code with required action callbacks; this view is never inflated from XML.
@SuppressLint("ViewConstructor")
internal class BluetoothControlView(context:android.content.Context,private val change:(Boolean?)->Unit,
    manage:()->Unit,close:()->Unit,enableMessages:()->Unit={}):LinearLayout(context) {
    private val scale=resources.displayMetrics.widthPixels/1920f
    private fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
    private var state=BluetoothControlSession.State()
    private var focusedInitial=false
    private var requestingMessages=false
    private val status=TextView(context).apply { tag="bluetooth-status";setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,26,scale) }
    private val output=TextView(context).apply { tag="audio-output";setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,23,scale) }
    private val toggle=Button(context).apply {
        tag="bluetooth-toggle";id=View.generateViewId();AppActionStyle.button(this,scale);isFocusableInTouchMode=true
        setOnClickListener { if(!state.working)change(state.enabled?.not()) }
    }
    private val messagesNotice=TextView(context).apply {
        tag="bluetooth-messages-notice";visibility=GONE;setText(R.string.bluetooth_messages_notice)
        setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,20,scale)
    }
    private val messagesButton=Button(context).apply {
        id=View.generateViewId();tag="bluetooth-messages";visibility=GONE
        setText(R.string.bluetooth_enable_messages);AppActionStyle.button(this,scale);isFocusableInTouchMode=true
        setOnClickListener { if(!requestingMessages && visibility==VISIBLE)enableMessages() }
    }
    private val manageButton=Button(context).apply {
        id=View.generateViewId();tag="bluetooth-manage";setText(R.string.bluetooth_manage_devices)
        AppActionStyle.button(this,scale);isFocusableInTouchMode=true;setOnClickListener { manage() }
    }
    private val closeButton=Button(context).apply {
        id=View.generateViewId();tag="bluetooth-close";setText(R.string.bluetooth_back)
        AppActionStyle.button(this,scale);isFocusableInTouchMode=true;setOnClickListener { close() }
    }
    init {
        orientation=VERTICAL;setPadding(px(24),px(22),px(24),px(22));background=AppActionStyle.surface(scale)
        addView(TextView(context).apply { setText(R.string.audio_devices_title);setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,32,scale) },LayoutParams(-1,-2).apply { bottomMargin=px(20) })
        addView(status,LayoutParams(-1,-2).apply { bottomMargin=px(12) })
        addView(output,LayoutParams(-1,-2).apply { bottomMargin=px(18) })
        addView(toggle,LayoutParams(-1,-2))
        addView(TextView(context).apply {
            setText(R.string.bluetooth_off_notice);setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,20,scale)
        },LayoutParams(-1,-2).apply { topMargin=px(4);bottomMargin=px(8) })
        addView(TextView(context).apply {
            setText(R.string.bluetooth_remote_hint);setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,20,scale)
        },LayoutParams(-1,-2).apply { bottomMargin=px(18) })
        addView(messagesNotice,LayoutParams(-1,-2).apply { bottomMargin=px(8) })
        addView(messagesButton,LayoutParams(-1,-2))
        addView(manageButton,LayoutParams(-1,-2));addView(closeButton,LayoutParams(-1,-2))
        updateFocusLinks()
        bind(state)
    }
    private fun updateFocusLinks() {
        val buttons=listOf(toggle,messagesButton,manageButton,closeButton).filter { it.visibility==VISIBLE }
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=button.id;button.nextFocusRightId=button.id
            button.nextFocusUpId=buttons[(i-1).coerceAtLeast(0)].id;button.nextFocusDownId=buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
        }
    }
    fun bindShortcutMessages(apiLevel:Int,notificationsEnabled:Boolean,requesting:Boolean=false,denied:Boolean=false) {
        requestingMessages=requesting
        val show=apiLevel>=33 && !notificationsEnabled
        val restoreToggleFocus=!show && messagesButton.hasFocus()
        messagesNotice.setText(if(denied)R.string.bluetooth_messages_denied else R.string.bluetooth_messages_notice)
        messagesNotice.visibility=if(show)VISIBLE else GONE
        messagesButton.visibility=if(show)VISIBLE else GONE
        updateFocusLinks()
        // Granting the permission removes its action. Keep a deliberate selection elsewhere,
        // or return to the primary toggle only if the removed action itself had focus.
        if(restoreToggleFocus)toggle.requestFocus()
    }
    fun bind(value:BluetoothControlSession.State) {
        state=value;status.text=value.message
        output.text=if(value.working)"Checking media output…" else "Media output: ${value.audio?.description ?: "Couldn’t verify output"}"
        // Keep the remote's focus in place while the click listener ignores repeated input.
        toggle.setText(when { value.working->R.string.bluetooth_working;value.enabled==true->R.string.bluetooth_turn_off;value.enabled==false->R.string.bluetooth_turn_on;else->R.string.bluetooth_retry })
        if(!value.working && value.enabled!=null && !focusedInitial) {
            focusedInitial=true;if(findFocus()==null)toggle.requestFocus()
        }
    }
}
