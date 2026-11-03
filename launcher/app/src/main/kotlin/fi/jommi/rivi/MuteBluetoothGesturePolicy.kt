package fi.jommi.rivi

/** Observes two short speaker-Mute presses. The caller must pass every key through unchanged. */
internal class MuteBluetoothGesturePolicy(manufacturer:String) {
    private val supported=manufacturer.equals("TCL",ignoreCase=true)
    private data class Press(val device:Int,val downTime:Long)
    private data class Tap(val press:Press,val upTime:Long)
    private var pending:Press?=null
    private var first:Tap?=null
    private var completed:Press?=null
    private var latestDownTime=-1L
    private var latestEventTime=-1L
    private var cooldownUntil=-1L

    // TCL's physical speaker-Mute scan 113 maps to Android VOLUME_MUTE (164).
    // Android MUTE (91) is microphone mute and is deliberately not a Bluetooth gesture.
    fun isMuteKey(keyCode:Int)=supported && keyCode==164

    /** True only on a verified second release; never means consume or replay the event. */
    fun handle(action:Int,keyCode:Int,repeatCount:Int,deviceId:Int,downTime:Long,eventTime:Long,
               canceled:Boolean=false,longPress:Boolean=false,paused:Boolean=false,controller:Boolean=false):Boolean {
        if(!isMuteKey(keyCode)) { cancelPending();return false }
        if(eventTime<latestEventTime || downTime<0L || eventTime<downTime) {
            cancelPending();return false
        }
        latestEventTime=eventTime
        if(canceled || longPress || paused || controller || repeatCount!=0 || action !in 0..1) {
            latestDownTime=maxOf(latestDownTime,downTime)
            cancelPending();return false
        }
        val press=Press(deviceId,downTime)
        if(eventTime<cooldownUntil) {
            latestDownTime=maxOf(latestDownTime,downTime)
            cancelPending();return false
        }
        if(action==0) {
            // Duplicate initial downs cannot re-arm completed or cancelled input.
            if(downTime<=latestDownTime)return false
            latestDownTime=downTime
            if(pending!=null)first=null
            val tap=first
            if(tap!=null && (tap.press.device!=deviceId || downTime<tap.upTime || eventTime-tap.upTime>450L))first=null
            pending=press
            return false
        }
        if(press==completed)return false
        if(pending!=press) { cancelPending();return false }
        pending=null
        completed=press
        if(eventTime-downTime>450L) { first=null;return false }
        val tap=first
        if(tap!=null && tap.press.device==deviceId && downTime>=tap.upTime && eventTime-tap.upTime<=450L) {
            first=null
            cooldownUntil=eventTime+1_000L
            return true
        }
        first=Tap(press,eventTime)
        return false
    }

    /** Retain duplicate/cooldown guards when interrupting a gesture. */
    fun cancelPending() { pending=null;first=null }
    fun reset() {
        cancelPending();completed=null;latestDownTime=-1L;latestEventTime=-1L;cooldownUntil=-1L
    }
}
