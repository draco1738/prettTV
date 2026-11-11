package fi.jommi.rivi

import android.widget.TextView
import android.os.Build

/** A foreground action, independent of accessibility and remote-key filtering. */
internal class BluetoothQuickSwitch(private val button:TextView,private val toggle:()->Boolean,manage:()->Unit,
                                   unit:Float=button.resources.displayMetrics.widthPixels/1920f) {
    private var state=BluetoothControlSession.State()
    private val icon=BluetoothStatusDrawable((36*unit+.5f).toInt().coerceAtLeast(1))
    init {
        button.text=""
        icon.setBounds(0,0,icon.intrinsicWidth,icon.intrinsicHeight)
        button.setCompoundDrawables(icon,null,null,null)
        button.setOnClickListener {
            if(!state.working) {
                val previous=state
                // Close the repeated-OK gap before the asynchronous session publishes its state.
                bind(previous.copy(working=true))
                if(!toggle())bind(previous)
            }
        }
        button.setOnLongClickListener { manage();true }
        bind(state)
    }
    fun bind(value:BluetoothControlSession.State) {
        state=value
        icon.bind(value)
        val label=button.context.getString(when {
            value.working->R.string.bluetooth_quick_working
            value.enabled==true->R.string.bluetooth_quick_on
            value.enabled==false->R.string.bluetooth_quick_off
            else->R.string.bluetooth_quick_unknown
        })
        if(Build.VERSION.SDK_INT>=30)button.stateDescription=label
        button.contentDescription=when {
            value.working->"Changing or checking Bluetooth. Please wait."
            value.enabled==true->"Bluetooth on. Press OK to turn off. Hold OK for audio devices."
            value.enabled==false->"Bluetooth off. Press OK to turn on. Hold OK for audio devices."
            else->"Bluetooth status unavailable. Press OK to check and toggle. Hold OK for audio devices."
        }
        // Remain focusable during work; neither a result nor a failure moves remote focus.
    }
}
