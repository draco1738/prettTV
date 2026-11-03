package fi.jommi.rivi

import android.app.Activity
import android.annotation.SuppressLint
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
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        controls=BluetoothControlView(this,{ desired ->
            if(!BluetoothControlSession.start(this,desired))controls.bind(BluetoothControlSession.State(message=getString(R.string.bluetooth_busy)))
        },{
            when(AudioDeviceSettings.open(this)) {
                AudioDeviceSettings.Destination.SETTINGS->android.widget.Toast.makeText(this,R.string.bluetooth_settings_fallback,android.widget.Toast.LENGTH_LONG).show()
                AudioDeviceSettings.Destination.UNAVAILABLE->android.widget.Toast.makeText(this,R.string.bluetooth_settings_unavailable,android.widget.Toast.LENGTH_LONG).show()
                else->Unit
            }
        },{ finish() })
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
    override fun onStart() {
        super.onStart()
        BluetoothControlSession.listen(this) { if(!isFinishing)controls.bind(it) }
        if(!BluetoothControlSession.busy && !BluetoothControlSession.start(this))
            controls.bind(BluetoothControlSession.State(message=getString(R.string.bluetooth_busy)))
    }
    override fun onStop() {
        BluetoothControlSession.cancel(this);BluetoothControlSession.unlisten(this)
        super.onStop()
    }
}

/** Reuses the existing action palette, with stable controls and no nested confirmation. */
// Created only in code with required action callbacks; this view is never inflated from XML.
@SuppressLint("ViewConstructor")
internal class BluetoothControlView(context:android.content.Context,private val change:(Boolean?)->Unit,
    manage:()->Unit,close:()->Unit):LinearLayout(context) {
    private val scale=resources.displayMetrics.widthPixels/1920f
    private fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
    private var state=BluetoothControlSession.State()
    private var focusedInitial=false
    private val status=TextView(context).apply { tag="bluetooth-status";setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,26,scale) }
    private val toggle=Button(context).apply {
        tag="bluetooth-toggle";id=View.generateViewId();AppActionStyle.button(this,scale);isFocusableInTouchMode=true
        setOnClickListener { if(!state.working)change(state.enabled?.not()) }
    }
    init {
        orientation=VERTICAL;setPadding(px(24),px(22),px(24),px(22));background=AppActionStyle.surface(scale)
        addView(TextView(context).apply { setText(R.string.audio_devices_title);setTextColor(AppActionStyle.IVORY);AppActionStyle.textSize(this,32,scale) },LayoutParams(-1,-2).apply { bottomMargin=px(20) })
        addView(status,LayoutParams(-1,-2).apply { bottomMargin=px(12) })
        addView(toggle,LayoutParams(-1,-2))
        addView(TextView(context).apply {
            setText(R.string.bluetooth_off_notice);setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,20,scale)
        },LayoutParams(-1,-2).apply { topMargin=px(4);bottomMargin=px(8) })
        addView(TextView(context).apply {
            setText(R.string.bluetooth_remote_hint);setTextColor(AppActionStyle.MUTED);AppActionStyle.textSize(this,20,scale)
        },LayoutParams(-1,-2).apply { bottomMargin=px(18) })
        val manageButton=Button(context).apply { id=View.generateViewId();tag="bluetooth-manage";setText(R.string.bluetooth_manage_devices);AppActionStyle.button(this,scale);setOnClickListener { manage() } }
        val closeButton=Button(context).apply { id=View.generateViewId();tag="bluetooth-close";setText(R.string.bluetooth_back);AppActionStyle.button(this,scale);setOnClickListener { close() } }
        addView(manageButton,LayoutParams(-1,-2));addView(closeButton,LayoutParams(-1,-2))
        val buttons=listOf(toggle,manageButton,closeButton)
        buttons.forEachIndexed { i,button ->
            button.nextFocusLeftId=button.id;button.nextFocusRightId=button.id
            button.nextFocusUpId=buttons[(i-1).coerceAtLeast(0)].id;button.nextFocusDownId=buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
        }
        bind(state)
    }
    fun bind(value:BluetoothControlSession.State) {
        state=value;status.text=value.message
        // Keep the remote's focus in place while the click listener ignores repeated input.
        toggle.setText(when { value.working->R.string.bluetooth_working;value.enabled==true->R.string.bluetooth_turn_off;value.enabled==false->R.string.bluetooth_turn_on;else->R.string.bluetooth_retry })
        if(!value.working && value.enabled!=null && !focusedInitial) {
            focusedInitial=true;if(findFocus()==null)toggle.requestFocus()
        }
    }
}
