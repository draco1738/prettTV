package fi.jommi.rivi

/** Restores verified TCL app shortcuts without involving the disabled stock Home app. */
internal class AppShortcutKeyPolicy(private val manufacturer:String) {
    enum class Decision { PASS, CONSUME, OPEN_APP }
    private data class Gesture(val key:Int,val device:Int,val downTime:Long)
    private data class Press(val gesture:Gesture,var canceled:Boolean)
    private var pending:Press?=null
    private var completed:Gesture?=null

    // These are TCL remote codes, not the similarly numbered game-controller buttons.
    // Keep unverified vendor keys untouched rather than guessing which app they mean.
    fun packageFor(keyCode:Int,controller:Boolean=false):String? {
        if(controller || !manufacturer.equals("TCL",ignoreCase=true))return null
        return when(keyCode) {
            191,4062 -> "com.netflix.ninja"
            190,4065 -> "com.phlox.tvwebbrowser"
            4020,4158,4184 -> "com.wbd.stream"
            4099 -> "com.amazon.amazonvideo.livingroom"
            4160 -> "com.apple.atve.androidtv.appletv"
            4163 -> "tv.pluto.android"
            else -> null
        }
    }

    fun handle(action:Int,keyCode:Int,repeatCount:Int,deviceId:Int,downTime:Long,
               canceled:Boolean=false,paused:Boolean=false,controller:Boolean=false):Decision {
        if(packageFor(keyCode,controller)==null)return Decision.PASS
        val gesture=Gesture(keyCode,deviceId,downTime)
        val press=pending
        if(action==0 && repeatCount==0) {
            // A duplicated initial down must neither re-arm a completed press nor undo cancellation.
            if(gesture==completed)return Decision.PASS
            if(press?.gesture==gesture) {
                press.canceled=press.canceled || canceled || paused
                return Decision.CONSUME
            }
            if(paused) {
                cancelPending()
                return Decision.PASS
            }
            pending=Press(gesture,canceled)
            return Decision.CONSUME
        }
        if(press==null || press.gesture!=gesture)return Decision.PASS
        if(action==0) {
            press.canceled=press.canceled || canceled || paused
            return Decision.CONSUME
        }
        if(action==1) {
            pending=null
            completed=gesture
            return if(!press.canceled && !canceled && !paused)Decision.OPEN_APP else Decision.CONSUME
        }
        return Decision.PASS
    }

    fun cancelPending() { pending?.canceled=true }
    fun reset() { pending=null;completed=null }
}
