package fi.jommi.rivi

internal fun interface BluetoothControlShell { fun run(command:String):String }
internal data class BluetoothControlResult(val enabled:Boolean?,val message:String,val changed:Boolean=false,val cancelled:Boolean=false)

/** Fixed media modes from the matched TCL audio service, not arbitrary settings values. */
internal enum class TvAudioOutput(val label:String,val mode:Int,val device:String,val command:String) {
    SPEAKERS("TV speakers",2,"speaker(2)","settings put global tcl_device_out_type 2"),
    BLUETOOTH("Bluetooth audio",4,"bt_a2dp(80)","settings put global tcl_device_out_type 4"),
    HDMI_ARC("HDMI ARC",16,"hdmi_arc(40000)","settings put global tcl_device_out_type 16"),
}

/** Fixed commands only. A command acknowledgement is never proof of the radio's state. */
internal class BluetoothControlEngine(
    private val identity:AppForceStopIdentity,
    private val launcherUser:Int,
    private val shell:BluetoothControlShell,
    private val cancelled:()->Boolean={false},
    private val allowed:()->Boolean={true},
    private val beforeMutation:()->Unit={},
    private val pause:(Long)->Unit={ Thread.sleep(it) },
    private val rememberedOutputMode:()->Int?={null},
    private val rememberOutputMode:(Int?)->Unit={},
) {
    private var changed=false
    fun execute(desired:Boolean?=null):BluetoothControlResult=run(desired,false)
    fun toggle():BluetoothControlResult=run(null,true)
    /** An explicit output choice never enables/disables Bluetooth or writes a volume. */
    fun selectOutput(target:TvAudioOutput):BluetoothControlResult {
        changed=false
        var radio:Boolean?=null
        try {
            check(AppForceStopPolicy.validIdentity(identity) && launcherUser in 0..21_474) { "The saved TV identity is unavailable." }
            check(read("id -u").trim()=="2000") { "The authorized local connection is unavailable." }
            verifyMutationAllowed()
            radio=readState()
            check(read("getprop ro.product.manufacturer").trim().equals("TCL",true) &&
                read("pm has-feature com.tcl.software.audio_multidev").trim()=="true") {
                "Direct output switching is unavailable on this TV. Use its audio settings."
            }
            check(target!=TvAudioOutput.BLUETOOTH || radio==true) { "Turn Bluetooth on first, then choose Bluetooth audio." }
            val previous=read(READ_OUTPUT_MODE).trim().toIntOrNull()
            check(previous in TCL_OUTPUT_MODES) { "The TV's audio output setting is unavailable." }
            check(supportsOutput(read("dumpsys audio"),target)) { "This TV did not report support for ${target.label}. Use its audio settings." }
            if(previous!=target.mode) {
                verifyMutationAllowed()
                checkCancelled();beforeMutation();checkCancelled()
                check(read(READ_OUTPUT_MODE).trim()==previous.toString()) { "The output changed during the request. Try again." }
                if(target==TvAudioOutput.BLUETOOTH)check(readState()) { "Bluetooth turned off before the output change." }
                // An explicit manual choice takes ownership from a prior radio-off handoff.
                rememberOutputMode(null)
                checkCancelled();changed=true
                check(!errorReply(read(target.command))) { "Android refused the audio output change." }
            }
            repeat(3) { attempt ->
                if(attempt>0) { checkCancelled();pause(250);checkCancelled() }
                verifyUser()
                if(read(READ_OUTPUT_MODE).trim()==target.mode.toString() && parseMediaRoute(read("dumpsys audio"),target.mode,target.device)) {
                    radio=readState()
                    rememberOutputMode(null)
                    return BluetoothControlResult(radio,"${target.label} selected.",changed)
                }
            }
            error("The TV did not confirm the selected output. Check its audio settings.")
        } catch(_:Cancelled) {
            return BluetoothControlResult(radio,if(changed)"Audio output may have changed. Its final state was not verified." else "Cancelled before changing audio output.",changed,true)
        } catch(failure:Exception) {
            return BluetoothControlResult(radio,(if(changed)"Audio output may have changed. " else "")+
                (failure.message?.take(180) ?: "Audio output could not be verified."),changed)
        }
    }
    fun inspectAudio():AudioOutputReport {
        check(AppForceStopPolicy.validIdentity(identity) && launcherUser in 0..21_474)
        check(read("id -u").trim()=="2000")
        verifyIdentity();verifyUser();check(allowed())
        return AudioOutputReport.parse(read("dumpsys audio"))
    }
    private fun run(desired:Boolean?,toggle:Boolean):BluetoothControlResult {
        changed=false
        var verifiedState:Boolean?=null
        try {
            check(AppForceStopPolicy.validIdentity(identity) && launcherUser in 0..21_474) { "The saved TV identity is unavailable." }
            check(read("id -u").trim()=="2000") { "The authorized local connection is unavailable." }
            verifyIdentity(); verifyUser()
            check(allowed()) { "Another TV action is running, or prettTV helpers are paused." }
            val before=readState()
            val target=if(toggle)!before else desired
            if(target==null)return BluetoothControlResult(before,if(before)"Bluetooth is on" else "Bluetooth is off")
            if(before!=target) {
                verifyMutationAllowed()
                checkCancelled(); beforeMutation(); checkCancelled()
                changed=true
                val reply=read(if(target)"cmd bluetooth_manager enable" else "cmd bluetooth_manager disable")
                check(!errorReply(reply)) { "Android refused the Bluetooth change." }
                val waited=read(if(target)"cmd bluetooth_manager wait-for-state:STATE_ON" else "cmd bluetooth_manager wait-for-state:STATE_OFF")
                check(!errorReply(waited)) { "Android could not confirm the requested Bluetooth state." }
                verifyUser()
                check(readState()==target) { "The requested Bluetooth state was not reached." }
            }
            verifiedState=target
            val message=if(target)restoreBluetoothOutput() else selectTvSpeakers()
            return BluetoothControlResult(target,message,changed)
        } catch(_:Cancelled) {
            return BluetoothControlResult(verifiedState,when {
                verifiedState==false->"Bluetooth is off. TV speaker selection was not verified."
                verifiedState==true->"Bluetooth is on. Its previous audio output was not verified."
                changed->"Bluetooth may have changed. Its final state was not verified."
                else->"Cancelled before changing Bluetooth."
            },changed,true)
        } catch(failure:Exception) {
            return BluetoothControlResult(verifiedState,(when {
                verifiedState==false->"Bluetooth is off. TV speakers could not be confirmed. "
                verifiedState==true->"Bluetooth is on. Its previous audio output could not be restored. "
                changed->"Bluetooth may have changed. "
                else->""
            })+
                (failure.message?.take(180) ?: "Bluetooth state could not be verified."),changed)
        }
    }
    private fun verifyMutationAllowed() {
        verifyIdentity(); verifyUser()
        check(allowed()) { "Another TV action is running, or prettTV helpers are paused." }
    }
    private fun selectTvSpeakers():String {
        if(!read("getprop ro.product.manufacturer").trim().equals("TCL",true))return "Bluetooth is off"
        check(read("pm has-feature com.tcl.software.audio_multidev").trim()=="true") {
            "Choose TV speakers in the TV's audio settings."
        }
        val previous=read(READ_OUTPUT_MODE).trim().toIntOrNull()
        check(previous in TCL_OUTPUT_MODES) { "The TV's audio output setting is unavailable." }
        if(previous!=2) {
            // A different current output invalidates any older ownership. Establish new
            // ownership only after the speaker handoff is verified, never on dispatch.
            rememberOutputMode(null)
            verifyMutationAllowed()
            checkCancelled(); beforeMutation(); checkCancelled()
            check(read(READ_OUTPUT_MODE).trim()==previous.toString()) { "The audio output changed during the request. Try again." }
            changed=true
            // Same observed setting used by TCL's own speaker selector. Its ContentObserver
            // refreshes the MEDIA strategy and the saved speaker volume; never copy BT volume.
            check(!errorReply(read(SELECT_SPEAKERS))) { "Android refused the TV speaker selection." }
        }
        repeat(3) { attempt->
            if(attempt>0) { checkCancelled(); pause(250); checkCancelled() }
            verifyUser()
            if(read(READ_OUTPUT_MODE).trim()=="2" && parseSpeakerRoute(read("dumpsys audio"))) {
                if(previous==4)rememberOutputMode(4)
                return "Bluetooth is off. TV speakers selected."
            }
        }
        error("The TV did not confirm its speaker output. Check its audio settings.")
    }
    private fun restoreBluetoothOutput():String {
        if(rememberedOutputMode()!=4)return "Bluetooth is on"
        check(read("getprop ro.product.manufacturer").trim().equals("TCL",true) &&
            read("pm has-feature com.tcl.software.audio_multidev").trim()=="true") {
            "Choose an output in the TV's audio settings."
        }
        if(read(READ_OUTPUT_MODE).trim()!="2") {
            // A manual output selection made since our speaker handoff wins.
            rememberOutputMode(null)
            return "Bluetooth is on"
        }
        verifyMutationAllowed()
        checkCancelled(); beforeMutation(); checkCancelled()
        if(read(READ_OUTPUT_MODE).trim()!="2") {
            rememberOutputMode(null)
            return "Bluetooth is on"
        }
        changed=true
        check(!errorReply(read(RESTORE_BLUETOOTH))) { "Android refused the previous Bluetooth audio output." }
        repeat(3) { attempt->
            if(attempt>0) { checkCancelled(); pause(250); checkCancelled() }
            verifyUser()
            if(read(READ_OUTPUT_MODE).trim()=="4" && parseMediaRoute(read("dumpsys audio"),4,"bt_a2dp(80)")) {
                rememberOutputMode(null)
                return "Bluetooth is on. Bluetooth audio selected."
            }
        }
        error("The TV did not confirm its previous Bluetooth audio output.")
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
        private const val READ_OUTPUT_MODE="settings get global tcl_device_out_type"
        private const val SELECT_SPEAKERS="settings put global tcl_device_out_type 2"
        private const val RESTORE_BLUETOOTH="settings put global tcl_device_out_type 4"
        private val TCL_OUTPUT_MODES=setOf(1,2,4,6,8,10,16,18,32,64,128,256,512,514)
        private fun supportsOutput(dump:String,target:TvAudioOutput):Boolean {
            val headers=Regex("(?m)^TclAudioDevicesUtils Info:\\r?$").findAll(dump).toList()
            if(headers.size!=1)return false
            val block=dump.substring(headers.single().range.last+1).trimStart('\r','\n')
                .lineSequence().takeWhile { it.isBlank() || it.first().isWhitespace() }.joinToString("\n")
            val field=when(target) { TvAudioOutput.SPEAKERS->"Speaker";TvAudioOutput.BLUETOOTH->"Bluetooth";TvAudioOutput.HDMI_ARC->"Arc" }
            val rows=Regex("(?m)^[ \\t]+$field:[ \\t]*([^\\r\\n]+)").findAll(block).map { it.groupValues[1].trim() }.toList()
            val value=rows.singleOrNull() ?: return false
            return value.startsWith("AudioDeviceAttributes:") && value.contains("role:output ") &&
                Regex("(?:^|[ \\t])type:${target.device.substringBefore('(')}(?:[ \\t]|$)").containsMatchIn(value)
        }
        internal fun parseSpeakerRoute(output:String)=parseMediaRoute(output,2,"speaker(2)")
        private fun parseMediaRoute(output:String,mode:Int,device:String):Boolean {
            fun block(header:String):String? {
                val headers=Regex("(?m)^${Regex.escape(header)}\\r?$").findAll(output).toList()
                if(headers.size!=1)return null
                return output.substring(headers.single().range.last+1).trimStart('\r','\n')
                    .lineSequence().takeWhile { it.isBlank() || it.firstOrNull()?.isWhitespace()==true }.joinToString("\n")
            }
            val vendor=block("TclDevModeManager Info:") ?: return false
            val music=block("- STREAM_MUSIC:") ?: return false
            val modes=Regex("(?m)^[ \\t]+mCurrentDevMode:[ \\t]*(\\d+)[ \\t]*\\r?$").findAll(vendor).toList()
            val devices=Regex("(?m)^[ \\t]+Devices:[ \\t]*([^\\r\\n]+?)[ \\t]*\\r?$").findAll(music).toList()
            return modes.size==1 && modes.single().groupValues[1]==mode.toString() &&
                devices.size==1 && devices.single().groupValues[1]==device
        }
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
