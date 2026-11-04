package fi.jommi.rivi

import android.widget.TextView

/** A foreground action, independent of accessibility and remote-key filtering. */
internal class BluetoothQuickSwitch(private val button:TextView,private val toggle:()->Boolean,manage:()->Unit) {
    private var state=BluetoothControlSession.State()
    init {
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
        button.setText(when {
            value.working->R.string.bluetooth_quick_working
            value.enabled==true->R.string.bluetooth_quick_on
            value.enabled==false->R.string.bluetooth_quick_off
            else->R.string.bluetooth_quick_unknown
        })
        button.contentDescription=when {
            value.working->"Changing or checking Bluetooth. Please wait."
            value.enabled==true->"Bluetooth on. Press OK to turn off. Hold OK for audio devices."
            value.enabled==false->"Bluetooth off. Press OK to turn on. Hold OK for audio devices."
            else->"Bluetooth status unavailable. Press OK to check and toggle. Hold OK for audio devices."
        }
        // Remain focusable during work; neither a result nor a failure moves remote focus.
    }
}
