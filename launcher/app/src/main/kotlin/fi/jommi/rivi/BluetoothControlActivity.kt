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
        },{ finish() },{ enableShortcutMessages() },{ target ->
            if(!BluetoothControlSession.selectOutput(this,target))
                controls.bind(BluetoothControlSession.State(message=getString(R.string.bluetooth_busy)))
        })
        requestingMessages=savedInstanceState?.getBoolean("requesting-shortcut-messages",false) ?: false
        messagesDenied=savedInstanceState?.getBoolean("shortcut-messages-denied",false) ?: false
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val scale=AppReadability.referenceScale(this@BluetoothControlActivity)
            val width=(600*scale+.5f).toInt().coerceAtMost(resources.displayMetrics.widthPixels)
            val scroll=ScrollView(this@BluetoothControlActivity).apply {
                isFillViewport=true;isFocusable=false;isVerticalScrollBarEnabled=false
                background=AppActionStyle.surface(scale);clipToOutline=true;addView(controls)
            }
            addView(scroll,FrameLayout.LayoutParams(width,-1,Gravity.END).apply {
                val margin=(36*scale+.5f).toInt()
                topMargin=margin;bottomMargin=margin;marginEnd=margin
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

/** Stable controls: selection describes a verified route; focus only describes remote position. */
@SuppressLint("ViewConstructor")
internal class BluetoothControlView(context:android.content.Context,private val change:(Boolean?)->Unit,
    manage:()->Unit,close:()->Unit,enableMessages:()->Unit={},changeOutput:(TvAudioOutput)->Unit={}):LinearLayout(context) {
    private val scale=AppReadability.referenceScale(context)
    private fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
    private var state=BluetoothControlSession.State()
    private var focusedInitial=false
    private var requestingMessages=false
    private fun label(value:String,size:Int,color:Int=AppActionStyle.IVORY)=TextView(context).apply {
        text=value;setTextColor(color);AppActionStyle.textSize(this,size,scale)
    }
    private fun action(key:String,value:String,run:()->Unit)=Button(context).apply {
        tag=key;id=View.generateViewId();text=value;AppActionStyle.button(this,scale)
        isFocusableInTouchMode=true;setOnClickListener { run() }
    }
    private val status=label("Bluetooth",28).apply { tag="bluetooth-status" }
    private val output=label("",20,AppActionStyle.MUTED).apply { tag="audio-output" }
    private val feedback=label("",20,AppActionStyle.MUTED).apply { tag="audio-feedback";visibility=GONE }
    private val toggle=action("bluetooth-toggle","Check") { if(!state.working)change(state.enabled?.not()) }.apply {
        gravity=Gravity.CENTER
        background=context.getDrawable(R.drawable.oled_control)
    }
    private val outputButtons=listOf(TvAudioOutput.SPEAKERS,TvAudioOutput.HDMI_ARC,TvAudioOutput.BLUETOOTH).associateWith { target ->
        action("audio-output-${target.name.lowercase()}",target.label) {
            if(!state.working) {
                if(target==TvAudioOutput.BLUETOOTH && state.enabled!=true) {
                    feedback.text="Turn Bluetooth on and connect an audio device first."
                    feedback.visibility=VISIBLE
                } else changeOutput(target)
            }
        }.apply {
            compoundDrawablePadding=px(20)
            setPadding(px(18),px(18),px(18),px(18))
            accessibilityDelegate=object:View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host:View,info:android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host,info)
                    info.isCheckable=true;info.isChecked=host.isSelected
                }
            }
        }
    }
    private val messagesNotice=label("",20,AppActionStyle.MUTED).apply {
        tag="bluetooth-messages-notice";visibility=GONE;setText(R.string.bluetooth_messages_notice)
    }
    private val messagesButton=action("bluetooth-messages",context.getString(R.string.bluetooth_enable_messages)) {
        if(!requestingMessages)enableMessages()
    }.apply { visibility=GONE }
    private val manageButton=action("bluetooth-manage",context.getString(R.string.bluetooth_manage_devices),manage).apply {
        gravity=Gravity.CENTER;background=context.getDrawable(R.drawable.oled_control)
    }
    private val closeButton=action("bluetooth-close",context.getString(R.string.bluetooth_back),close).apply { gravity=Gravity.CENTER }
    private fun divider() {
        addView(View(context).apply { setBackgroundColor(PrettTvColors.DIVIDER) },
            LayoutParams(-1,px(1)).apply { topMargin=px(24);bottomMargin=px(24) })
    }
    init {
        orientation=VERTICAL;setPadding(px(32),px(28),px(32),px(24));background=AppActionStyle.surface(scale)
        addView(label("Audio",40),LayoutParams(-1,-2).apply { bottomMargin=px(28) })
        addView(LinearLayout(context).apply {
            orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            addView(LinearLayout(context).apply {
                orientation=VERTICAL
                addView(status)
                addView(label("Headphones and remotes",20,AppActionStyle.MUTED))
            },LayoutParams(0,-2,1f).apply { marginEnd=px(12) })
            addView(toggle,LayoutParams(px(112),-2))
        },LayoutParams(-1,-2))
        divider()
        addView(label("Sound output",30),LayoutParams(-1,-2))
        addView(output,LayoutParams(-1,-2).apply { topMargin=px(6);bottomMargin=px(18) })
        outputButtons.values.forEach { addView(it,LayoutParams(-1,-2).apply { bottomMargin=px(8) }) }
        addView(feedback,LayoutParams(-1,-2).apply { topMargin=px(8);bottomMargin=px(8) })
        addView(manageButton,LayoutParams(-1,-2).apply { topMargin=px(20);bottomMargin=px(12) })
        addView(messagesNotice,LayoutParams(-1,-2).apply { bottomMargin=px(8) })
        addView(messagesButton,LayoutParams(-1,-2))
        addView(closeButton,LayoutParams(-1,-2))
        updateFocusLinks();bind(state)
    }
    private fun updateFocusLinks() {
        val buttons=(listOf(toggle)+outputButtons.values+listOf(manageButton,messagesButton,closeButton)).filter { it.visibility==VISIBLE }
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=button.id;button.nextFocusRightId=button.id
            button.nextFocusUpId=buttons[(i-1).coerceAtLeast(0)].id
            button.nextFocusDownId=buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
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
        if(restoreToggleFocus)toggle.requestFocus()
    }
    fun bind(value:BluetoothControlSession.State) {
        state=value
        val radio=when(value.enabled) { true->"Bluetooth is on";false->"Bluetooth is off";null->value.message }
        toggle.text=when { value.working->"Wait…";value.enabled==true->"On";value.enabled==false->"Off";else->"Check" }
        toggle.contentDescription=when {
            value.working->"Bluetooth. Please wait."
            value.enabled==true->"Bluetooth on. Turn Bluetooth off."
            value.enabled==false->"Bluetooth off. Turn Bluetooth on."
            else->"Retry Bluetooth check"
        }
        status.contentDescription=radio
        output.text=if(value.working)"Checking output…" else value.audio?.description ?: "Output not verified"
        feedback.text=value.message
        feedback.visibility=if(!value.working && value.message.isNotBlank() && value.message!=radio ||
            !value.working && value.enabled==null)VISIBLE else GONE
        for((target,button) in outputButtons) {
            val choice=AudioOutputChoice.describe(target,value.audio,value.enabled,value.working)
            button.isSelected=choice.selected
            button.contentDescription="${target.label}. ${choice.detail}."
            val copy=android.text.SpannableString("${target.label}\n${choice.detail}")
            val start=target.label.length+1
            copy.setSpan(android.text.style.RelativeSizeSpan(.76f),start,copy.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            copy.setSpan(android.text.style.ForegroundColorSpan(AppActionStyle.MUTED),start,copy.length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            button.text=copy;AppActionStyle.textSize(button,28,scale)
            val icon=context.getDrawable(when(target) {
                TvAudioOutput.SPEAKERS->R.drawable.ic_audio_speaker
                TvAudioOutput.HDMI_ARC->R.drawable.ic_audio_hdmi
                TvAudioOutput.BLUETOOTH->R.drawable.ic_audio_headphones
            })?.apply { setBounds(0,0,px(40),px(40)) }
            val checked=if(choice.selected)context.getDrawable(R.drawable.ic_selected_check)?.apply { setBounds(0,0,px(24),px(24)) } else null
            button.setCompoundDrawablesRelative(icon,null,checked,null)
        }
        if(!value.working && value.enabled!=null && !focusedInitial) {
            focusedInitial=true
            if(findFocus()==null)outputButtons.getValue(TvAudioOutput.SPEAKERS).requestFocus()
        }
    }
}
