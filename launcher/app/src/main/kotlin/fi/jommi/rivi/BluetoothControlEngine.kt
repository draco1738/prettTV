package fi.jommi.rivi

internal fun interface BluetoothControlShell { fun run(command:String):String }
internal data class BluetoothControlResult(val enabled:Boolean?,val message:String,val changed:Boolean=false,val cancelled:Boolean=false)

/** Fixed commands only. A command acknowledgement is never proof of the radio's state. */
internal class BluetoothControlEngine(
    private val identity:AppForceStopIdentity,
    private val launcherUser:Int,
    private val shell:BluetoothControlShell,
    private val cancelled:()->Boolean={false},
    private val allowed:()->Boolean={true},
    private val beforeMutation:()->Unit={},
) {
    private var changed=false
    fun execute(desired:Boolean?=null):BluetoothControlResult=run(desired,false)
    fun toggle():BluetoothControlResult=run(null,true)
    private fun run(desired:Boolean?,toggle:Boolean):BluetoothControlResult {
        try {
            check(AppForceStopPolicy.validIdentity(identity) && launcherUser in 0..21_474) { "The saved TV identity is unavailable." }
            check(read("id -u").trim()=="2000") { "The authorized local connection is unavailable." }
            verifyIdentity(); verifyUser()
            check(allowed()) { "Another TV action is running, or prettTV helpers are paused." }
            val before=readState()
            val target=if(toggle)!before else desired
            if(target==null || before==target)return BluetoothControlResult(before,if(before)"Bluetooth is on" else "Bluetooth is off")
            verifyIdentity(); verifyUser()
            check(allowed()) { "Another TV action is running, or prettTV helpers are paused." }
            checkCancelled(); beforeMutation(); checkCancelled()
            changed=true
            val reply=read(if(target)"cmd bluetooth_manager enable" else "cmd bluetooth_manager disable")
            check(!errorReply(reply)) { "Android refused the Bluetooth change." }
            val waited=read(if(target)"cmd bluetooth_manager wait-for-state:STATE_ON" else "cmd bluetooth_manager wait-for-state:STATE_OFF")
            check(!errorReply(waited)) { "Android could not confirm the requested Bluetooth state." }
            verifyUser()
            val after=readState()
            check(after==target) { "The requested Bluetooth state was not reached." }
            return BluetoothControlResult(after,if(after)"Bluetooth is on" else "Bluetooth is off",true)
        } catch(_:Cancelled) {
            return BluetoothControlResult(null,if(changed)"Bluetooth may have changed. Its final state was not verified." else "Cancelled before changing Bluetooth.",changed,true)
        } catch(failure:Exception) {
            return BluetoothControlResult(null,(if(changed)"Bluetooth may have changed. " else "")+
                (failure.message?.take(180) ?: "Bluetooth state could not be verified."),changed)
        }
    }
    private fun verifyIdentity() {
        check(read("getprop ro.serialno").trim()==identity.serial) { "The saved identity does not match this TV." }
        check(read("getprop ro.build.fingerprint").trim()==identity.fingerprint) { "This TV's software changed since local setup." }
    }
    private fun verifyUser() { check(read("am get-current-user").trim()==launcherUser.toString()) { "The current TV user could not be verified." } }
    private fun readState()=parseState(read("dumpsys bluetooth_manager")) ?: error("Bluetooth is changing or its state is unavailable. Try again or manage devices.")
    private fun checkCancelled() { if(cancelled())throw Cancelled() }
    private fun read(command:String):String {
        checkCancelled()
        return try { shell.run(command).also { check(it.length<=524_288) { "Bluetooth returned an oversized response." }; checkCancelled() } }
        catch(failure:Exception) { checkCancelled(); throw failure }
    }
    private class Cancelled:Exception()
    companion object {
        internal fun parseState(output:String):Boolean? {
            val headers=Regex("(?m)^Bluetooth Status\\r?$").findAll(output).toList()
            if(headers.size!=1)return null
            val tail=output.substring(headers.single().range.last+1).trimStart('\r','\n')
            val block=tail.lineSequence().takeWhile { it.isBlank() || it.firstOrNull()?.isWhitespace()==true }.joinToString("\n")
            val states=Regex("(?m)^[ \\t]+state:[ \\t]*(ON|OFF)[ \\t]*\\r?$").findAll(block).toList()
            val enabled=Regex("(?m)^[ \\t]+enabled:[ \\t]*(true|false)[ \\t]*\\r?$").findAll(block).toList()
            if(states.size!=1 || enabled.size!=1)return null
            val on=states.single().groupValues[1]=="ON"
            return on.takeIf { it==(enabled.single().groupValues[1]=="true") }
        }
        private fun errorReply(reply:String)=reply.trim().equals("false",true) || Regex(
            "(?im)^\\s*(?:error|exception|securityexception|java\\.[\\w.]*exception|permission denial|unknown command|failure|failed)"
        ).containsMatchIn(reply)
    }
}
