package fi.jommi.rivi

import android.view.KeyEvent

/** Tracks only the Settings gesture; unrelated remote buttons always pass through. */
internal class SettingsKeyPolicy(private val manufacturer:String) {
    enum class Decision { PASS, CONSUME, OPEN_SETTINGS }
    private data class Press(val key:Int,val device:Int,val downTime:Long,var canceled:Boolean)
    private var pending:Press?=null

    fun handle(action:Int,keyCode:Int,repeatCount:Int,deviceId:Int,downTime:Long,
               canceled:Boolean=false,paused:Boolean=false):Decision {
        if(keyCode!=KeyEvent.KEYCODE_SETTINGS &&
            !(keyCode==KeyEvent.KEYCODE_NOTIFICATION && manufacturer.equals("TCL",ignoreCase=true)))return Decision.PASS
        if(action==KeyEvent.ACTION_DOWN && repeatCount==0) {
            if(paused)return Decision.PASS
            pending=Press(keyCode,deviceId,downTime,canceled)
            return Decision.CONSUME
        }
        val press=pending ?: return Decision.PASS
        if(press.key!=keyCode || press.device!=deviceId || press.downTime!=downTime)return Decision.PASS
        if(action==KeyEvent.ACTION_DOWN) {
            press.canceled=press.canceled || canceled || paused
            return Decision.CONSUME
        }
        if(action==KeyEvent.ACTION_UP) {
            pending=null
            return if(!press.canceled && !canceled && !paused)Decision.OPEN_SETTINGS else Decision.CONSUME
        }
        return Decision.PASS
    }

    fun cancelPending() { pending?.canceled=true }
    fun reset() { pending=null }
}
