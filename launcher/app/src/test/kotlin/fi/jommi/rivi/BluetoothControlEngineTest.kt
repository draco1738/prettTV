package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class BluetoothControlEngineTest {
    private val identity=AppForceStopIdentity("fixture-tv","vendor/tv:14/release")
    private class Tv:BluetoothControlShell {
        var on=true
        var uid="2000";var serial="fixture-tv";var fingerprint="vendor/tv:14/release";var user="0"
        var ignoreWrite=false;var reply="";var dumpOverride:String?=null
        var manufacturer="Other";var feature=true;var output="4";var routeMode=4;var routeDevice="bt_a2dp(80)"
        var ignoreOutputWrite=false;var routeReply="";var audioOverride:String?=null;var remembered:Int?=null
        var rememberWrites=0;var pauses=0
        var before:((String)->Unit)?=null
        val commands=ArrayList<String>()
        override fun run(command:String):String {
            commands+=command;before?.invoke(command)
            return when(command) {
                "id -u"->uid
                "getprop ro.serialno"->serial
                "getprop ro.build.fingerprint"->fingerprint
                "am get-current-user"->user
                "getprop ro.product.manufacturer"->manufacturer
                "pm has-feature com.tcl.software.audio_multidev"->feature.toString()
                "settings get global tcl_device_out_type"->output
                "settings put global tcl_device_out_type 2","settings put global tcl_device_out_type 4","settings put global tcl_device_out_type 16"->{
                    if(!ignoreOutputWrite) {
                        output=command.substringAfterLast(' ');routeMode=output.toInt()
                        routeDevice=when(routeMode) { 2->"speaker(2)";16->"hdmi_arc(40000)";else->"bt_a2dp(80)" }
                    }
                    routeReply
                }
                "dumpsys audio"->audioOverride ?: audio(routeMode,routeDevice)
                "dumpsys bluetooth_manager"->dumpOverride ?: "Bluetooth Status\n  enabled: $on\n  state: ${if(on)"ON" else "OFF"}\nAdapterProperties\n  State: OFF\n"
                "cmd bluetooth_manager enable"->{ if(!ignoreWrite)on=true;reply }
                "cmd bluetooth_manager disable"->{ if(!ignoreWrite)on=false;reply }
                "cmd bluetooth_manager wait-for-state:STATE_ON","cmd bluetooth_manager wait-for-state:STATE_OFF"->""
                else->error("Unexpected command $command")
            }
        }
        fun writes()=commands.filter { it=="cmd bluetooth_manager enable" || it=="cmd bluetooth_manager disable" }
        fun routeWrites()=commands.filter { it.startsWith("settings put") }
        fun audio(mode:Int=2,device:String="speaker(2)")="""
            Stream volumes (device: index)
            - STREAM_MUSIC:
               Muted: false
               Current: 2 (speaker): 37, 80 (bt_a2dp): 59
               Devices: $device
               Volume Group: n/a

            - STREAM_ALARM (aliased to: STREAM_MUSIC):
               Devices: bt_a2dp(80)

            TclDevModeManager Info:
                mbSupportDeviceSelect:true
                mPreDevMode:4
                mCurrentDevMode:$mode
                readOutputDevMode:$mode

            TclAudioDevicesUtils Info:
                Speaker: AudioDeviceAttributes: role:output type:speaker addr:tcl:
                Bluetooth: AudioDeviceAttributes: role:output type:bt_a2dp addr:tcl:
                Arc: AudioDeviceAttributes: role:output type:hdmi_arc addr:tcl:
        """.trimIndent()
    }
    private fun engine(tv:Tv,cancelled:()->Boolean={false},allowed:()->Boolean={true},mutation:()->Unit={})=
        BluetoothControlEngine(identity,0,tv,cancelled,allowed,mutation,{ tv.pauses++ },{ tv.remembered },{ tv.rememberWrites++;tv.remembered=it })
    @Test fun explicitMediaChoicesVerifyEachRouteWithoutChangingRadioOrVolume() {
        for(target in TvAudioOutput.entries) {
            val tv=Tv().apply { manufacturer="TCL";output=if(target==TvAudioOutput.BLUETOOTH)"2" else "4";remembered=4 }
            val result=engine(tv).selectOutput(target)
            assertEquals("${target.label} selected.",result.message)
            assertEquals(true,result.enabled);assertTrue(result.changed)
            assertEquals(listOf(target.command),tv.routeWrites());assertTrue(tv.writes().isEmpty())
            assertNull(tv.remembered);assertEquals(target.mode.toString(),tv.output)
            assertFalse(tv.commands.any { it.contains("volume") })
        }
    }
    @Test fun outputSelectionRequiresBluetoothAlreadyOnButSpeakersWorkWithRadioOff() {
        val tv=Tv().apply { manufacturer="TCL";on=false }
        val rejected=engine(tv).selectOutput(TvAudioOutput.BLUETOOTH)
        assertTrue(rejected.message.contains("Turn Bluetooth on first"))
        assertTrue(tv.routeWrites().isEmpty() && tv.writes().isEmpty())
        val speakers=engine(tv).selectOutput(TvAudioOutput.SPEAKERS)
        assertEquals(false,speakers.enabled);assertEquals("TV speakers selected.",speakers.message)
        assertTrue(tv.writes().isEmpty())
    }
    @Test fun outputChoiceRejectsUnverifiedDeviceUserSupportOrModeBeforeWriting() {
        for(configure in listOf<(Tv)->Unit>({it.uid="0"},{it.serial="wrong"},{it.fingerprint="other"},
            {it.user="10"},{it.manufacturer="Other"},{it.feature=false},{it.output="bogus"})) {
            val tv=Tv().apply { manufacturer="TCL" }.also(configure)
            val result=engine(tv).selectOutput(TvAudioOutput.SPEAKERS)
            assertFalse(result.changed);assertTrue(tv.routeWrites().isEmpty() && tv.writes().isEmpty())
        }
    }
    @Test fun existingOutputStillNeedsActualRouteVerificationWithoutAWrite() {
        val tv=Tv().apply { manufacturer="TCL";output="2";routeMode=2;routeDevice="speaker(2)" }
        assertEquals("TV speakers selected.",engine(tv).selectOutput(TvAudioOutput.SPEAKERS).message)
        assertTrue(tv.routeWrites().isEmpty())
        tv.routeDevice="bt_a2dp(80)"
        assertTrue(engine(tv).selectOutput(TvAudioOutput.SPEAKERS).message.contains("did not confirm"))
        assertTrue(tv.routeWrites().isEmpty())
    }
    @Test fun missingOrAmbiguousOutputSupportRejectsBeforeAnyMutation() {
        for(dump in listOf("",Tv().audio().replace("Arc: AudioDeviceAttributes: role:output type:hdmi_arc addr:tcl:","Arc: null"),
            Tv().audio()+"\nTclAudioDevicesUtils Info:\n  Arc: AudioDeviceAttributes: role:output type:hdmi_arc addr:tcl:\n")) {
            val tv=Tv().apply { manufacturer="TCL";audioOverride=dump }
            assertFalse(engine(tv).selectOutput(TvAudioOutput.HDMI_ARC).changed)
            assertTrue(tv.routeWrites().isEmpty() && tv.writes().isEmpty())
        }
    }
    @Test fun refusedIgnoredAndConflictingRouteWritesNeverClaimSelection() {
        for(configure in listOf<(Tv)->Unit>({it.routeReply="Permission denial"},
            {it.ignoreOutputWrite=true},{it.audioOverride=it.audio(16,"speaker(2)")},
            {it.audioOverride=it.audio(16,"hdmi_arc(40000)")+"\n- STREAM_MUSIC:\n  Devices: speaker(2)\n"})) {
            val tv=Tv().apply { manufacturer="TCL" }.also(configure)
            val result=engine(tv).selectOutput(TvAudioOutput.HDMI_ARC)
            assertTrue(result.changed);assertFalse(result.message=="HDMI ARC selected.")
            assertTrue(tv.writes().isEmpty());assertEquals(1,tv.routeWrites().size)
        }
    }
    @Test fun outputSelectionRechecksCancellationOwnershipAndGateBeforeDispatch() {
        val cancelledTv=Tv().apply { manufacturer="TCL" };var cancelled=false
        assertTrue(engine(cancelledTv,{cancelled},mutation={cancelled=true}).selectOutput(TvAudioOutput.SPEAKERS).cancelled)
        assertTrue(cancelledTv.routeWrites().isEmpty())
        val changedTv=Tv().apply { manufacturer="TCL" }
        assertFalse(engine(changedTv,mutation={changedTv.output="16"}).selectOutput(TvAudioOutput.SPEAKERS).changed)
        assertTrue(changedTv.routeWrites().isEmpty())
        var gates=0;val busyTv=Tv().apply { manufacturer="TCL" }
        assertFalse(engine(busyTv,allowed={++gates==1}).selectOutput(TvAudioOutput.SPEAKERS).changed)
        assertTrue(busyTv.routeWrites().isEmpty())
    }
    @Test fun outputCancellationOrUserChangeAfterDispatchReportsUnverifiedResult() {
        val tv=Tv().apply { manufacturer="TCL" };var cancelled=false
        tv.before={ if(it==TvAudioOutput.SPEAKERS.command)cancelled=true }
        val result=engine(tv,{cancelled}).selectOutput(TvAudioOutput.SPEAKERS)
        assertTrue(result.changed && result.cancelled);assertTrue(result.message.contains("not verified"))
        assertTrue(tv.writes().isEmpty())
        val otherUser=Tv().apply { manufacturer="TCL";before={if(it==TvAudioOutput.SPEAKERS.command)user="10"} }
        val rejected=engine(otherUser).selectOutput(TvAudioOutput.SPEAKERS)
        assertTrue(rejected.changed);assertFalse(rejected.message=="TV speakers selected.")
    }
    @Test fun readOnlyAndAlreadyRequestedStateNeverWrite() {
        val tv=Tv()
        assertEquals(true,engine(tv).execute().enabled)
        assertEquals(true,engine(tv).execute(true).enabled)
        assertTrue(tv.writes().isEmpty())
    }
    @Test fun explicitOffAndOnWaitThenVerifyActualState() {
        val tv=Tv()
        assertEquals(false,engine(tv).execute(false).enabled)
        assertEquals(true,engine(tv).execute(true).enabled)
        assertEquals(listOf("cmd bluetooth_manager disable","cmd bluetooth_manager enable"),tv.writes())
        assertTrue(tv.commands.contains("cmd bluetooth_manager wait-for-state:STATE_OFF"))
        assertTrue(tv.commands.contains("cmd bluetooth_manager wait-for-state:STATE_ON"))
    }
    @Test fun blankAcknowledgementCannotProveSuccess() {
        val tv=Tv().apply { ignoreWrite=true }
        val result=engine(tv).execute(false)
        assertNull(result.enabled);assertTrue(result.changed);assertEquals(1,tv.writes().size)
    }
    @Test fun toggleReadsCurrentRadioStateAndChangesItOnceInEitherDirection() {
        val tv=Tv()
        assertEquals(false,engine(tv).toggle().enabled)
        assertEquals(true,engine(tv).toggle().enabled)
        assertEquals(listOf("cmd bluetooth_manager disable","cmd bluetooth_manager enable"),tv.writes())
        assertEquals(4,tv.commands.count { it=="dumpsys bluetooth_manager" })
    }
    @Test fun toggleRejectsUnknownStateAndRequiresPostWriteReadback() {
        val unknown=Tv().apply { dumpOverride="Bluetooth Status\n  enabled: true\n  state: TURNING_ON\n" }
        assertNull(engine(unknown).toggle().enabled);assertTrue(unknown.writes().isEmpty())
        val ignored=Tv().apply { ignoreWrite=true }
        val result=engine(ignored).toggle()
        assertNull(result.enabled);assertTrue(result.changed);assertEquals(1,ignored.writes().size)
    }
    @Test fun toggleCancellationAndBusyGateDoNotSendRadioCommands() {
        val tv=Tv()
        assertTrue(engine(tv,{true}).toggle().cancelled)
        assertNull(engine(tv,allowed={false}).toggle().enabled)
        assertTrue(tv.writes().isEmpty())
    }
    @Test fun wrongShellIdentityFingerprintAndUserRejectBeforeMutation() {
        for(configure in listOf<(Tv)->Unit>({ it.uid="0" },{ it.serial="other" },{ it.fingerprint="other" },{ it.user="10" })) {
            val tv=Tv().also(configure)
            assertNull(engine(tv).execute(false).enabled);assertTrue(tv.writes().isEmpty())
        }
    }
    @Test fun cancellationBeforeMutationAndBusyRecheckPreventWrite() {
        val tv=Tv();var cancelled=false
        val result=engine(tv,{cancelled},mutation={cancelled=true}).execute(false)
        assertTrue(result.cancelled);assertFalse(result.changed);assertTrue(tv.writes().isEmpty())
        var checks=0
        assertNull(engine(tv,allowed={ ++checks==1 }).execute(false).enabled)
        assertTrue(tv.writes().isEmpty())
    }
    @Test fun cancellationAfterWriteReportsUnverifiedChange() {
        val tv=Tv();var cancelled=false
        tv.before={ if(it=="cmd bluetooth_manager disable")cancelled=true }
        val result=engine(tv,{cancelled}).execute(false)
        assertTrue(result.changed && result.cancelled);assertNull(result.enabled)
    }
    @Test fun refusalAndUserSwitchDoNotClaimSuccess() {
        val refused=Tv().apply { reply="SecurityException: denied" }
        assertNull(engine(refused).execute(false).enabled)
        val switched=Tv().apply { before={ if(it=="cmd bluetooth_manager disable")user="10" } }
        assertNull(engine(switched).execute(false).enabled)
    }
    @Test fun parserUsesOnlyOneConsistentBluetoothStatusHeader() {
        assertEquals(true,BluetoothControlEngine.parseState("Bluetooth Status\n  enabled: true\n  state: ON\nAdapterProperties\n  state: OFF\n"))
        assertEquals(false,BluetoothControlEngine.parseState("Bluetooth Status\r\n  enabled: false\r\n  state: OFF\r\n"))
        for(value in listOf("", "state: ON", "Bluetooth Status\n  enabled: true\n  state: TURNING_ON\n",
            "Bluetooth Status\n  enabled: true\n  state: OFF\n", "Bluetooth Status\n  enabled: true\n  state: ON\n  state: OFF\n",
            "Bluetooth Status\n  enabled: true\n  state: ON\nBluetooth Status\n  enabled: true\n  state: ON\n"))assertNull(BluetoothControlEngine.parseState(value))
    }
    @Test fun explicitTclOffSelectsAndVerifiesSpeakerWithoutChangingVolume() {
        val tv=Tv().apply { manufacturer="TCL" }
        val result=engine(tv).execute(false)
        assertEquals(false,result.enabled);assertTrue(result.changed)
        assertEquals("Bluetooth is off. TV speakers selected.",result.message)
        assertEquals(listOf("settings put global tcl_device_out_type 2"),tv.routeWrites())
        assertEquals(4,tv.remembered)
        assertFalse(tv.commands.any { it.contains("volume") || it.contains("unmute") || it.contains("set-communication") })
    }
    @Test fun alreadyOffRepairsTclRouteButReadOnlyOffDoesNot() {
        val tv=Tv().apply { manufacturer="TCL";on=false }
        assertEquals(false,engine(tv).execute().enabled)
        assertTrue(tv.routeWrites().isEmpty());assertEquals(0,tv.rememberWrites)
        val repaired=engine(tv).execute(false)
        assertEquals(false,repaired.enabled);assertTrue(repaired.changed)
        assertTrue(tv.writes().isEmpty());assertEquals(1,tv.routeWrites().size)
        val again=engine(tv).execute(false)
        assertEquals(false,again.enabled);assertFalse(again.changed)
        assertEquals(1,tv.routeWrites().size);assertEquals(4,tv.remembered)
    }
    @Test fun onlySupportedTclRouteCanBeWritten() {
        val other=Tv()
        assertEquals("Bluetooth is off",engine(other).execute(false).message)
        assertTrue(other.routeWrites().isEmpty())
        for(configure in listOf<(Tv)->Unit>({ it.feature=false },{ it.output="null" },{ it.output="99" })) {
            val tv=Tv().apply { manufacturer="TCL" }.also(configure)
            val result=engine(tv).execute(false)
            assertEquals(false,result.enabled);assertTrue(result.message.contains("could not be confirmed"))
            assertTrue(tv.routeWrites().isEmpty());assertEquals(0,tv.rememberWrites)
        }
    }
    @Test fun speakerWriteMustPassFreshIdentityUserAndBusyGate() {
        for(configure in listOf<(Tv)->Unit>({ it.serial="changed" },{ it.fingerprint="changed" },{ it.user="10" })) {
            val tv=Tv().apply { manufacturer="TCL";before={ if(it=="getprop ro.product.manufacturer")configure(this) } }
            val result=engine(tv).execute(false)
            assertEquals(false,result.enabled);assertTrue(tv.routeWrites().isEmpty())
        }
        val tv=Tv().apply { manufacturer="TCL" };var checks=0
        val denied=engine(tv,allowed={ ++checks<3 }).execute(false)
        assertEquals(false,denied.enabled);assertTrue(tv.routeWrites().isEmpty())
    }
    @Test fun refusedIgnoredAndPartialRouteWritesNeverClaimSpeakerSuccessOrUndoOff() {
        for(configure in listOf<(Tv)->Unit>({ it.routeReply="SecurityException: denied" },
            { it.ignoreOutputWrite=true },{ it.audioOverride=it.audio(4,"bt_a2dp(80)") },
            { it.audioOverride=it.audio(2,"bt_a2dp(80)") },{ it.audioOverride=it.audio(4,"speaker(2)") })) {
            val tv=Tv().apply { manufacturer="TCL" }.also(configure)
            val result=engine(tv).execute(false)
            assertEquals(false,result.enabled);assertFalse(result.message.contains("TV speakers selected"))
            assertFalse(tv.on);assertFalse(tv.commands.contains("cmd bluetooth_manager enable"))
            assertTrue(tv.commands.count { it=="dumpsys audio" }<=3);assertTrue(tv.pauses<=2)
        }
    }
    @Test fun unverifiedSpeakerAttemptCannotOwnLaterManualSpeakerSelection() {
        for(configure in listOf<(Tv)->Unit>({ it.routeReply="SecurityException: denied" },
            { it.ignoreOutputWrite=true },{ it.audioOverride=it.audio(4,"bt_a2dp(80)") },
            { it.audioOverride=it.audio(2,"bt_a2dp(80)") })) {
            // Even a stale marker from an older confirmed handoff must be cleared first.
            val tv=Tv().apply { manufacturer="TCL";remembered=4 }.also(configure)
            val failed=engine(tv).execute(false)
            assertEquals(false,failed.enabled);assertTrue(failed.message.contains("could not be confirmed"))
            assertNull(tv.remembered)
            // The user selects Speaker after our refused/ignored/partial request finished.
            tv.output="2";tv.routeMode=2;tv.routeDevice="speaker(2)"
            tv.routeReply="";tv.ignoreOutputWrite=false;tv.audioOverride=null
            val routeWritesBefore=tv.routeWrites().size
            assertEquals(true,engine(tv).execute(true).enabled)
            assertEquals(routeWritesBefore,tv.routeWrites().size)
            assertEquals("2",tv.output);assertNull(tv.remembered)
        }
    }
    @Test fun speakerOwnershipIsSavedOnlyAfterActualRouteVerification() {
        val tv=Tv().apply { manufacturer="TCL";remembered=4 }
        tv.before={ command->
            if(command=="settings put global tcl_device_out_type 2" || command=="dumpsys audio")assertNull(tv.remembered)
        }
        assertEquals(false,engine(tv).execute(false).enabled)
        assertEquals(4,tv.remembered)
    }
    @Test fun speakerRouteParserRejectsHistoryDuplicatesMixedOutputsAndWrongSections() {
        val tv=Tv();val valid=tv.audio()
        assertTrue(BluetoothControlEngine.parseSpeakerRoute(valid))
        assertTrue(BluetoothControlEngine.parseSpeakerRoute(valid.replace("\n","\r\n")))
        for(value in listOf("",valid.replace("- STREAM_MUSIC:","- STREAM_ALARM:"),
            valid.replace("mCurrentDevMode:2","mCurrentDevMode:2\n    mCurrentDevMode:4"),
            valid.replace("Devices: speaker(2)","Devices: speaker(2), bt_a2dp(80)"),
            valid.replace("Devices: speaker(2)","Devices: speaker(2)\n   Devices: bt_a2dp(80)"),
            valid+"\n- STREAM_MUSIC:\n   Devices: speaker(2)\n",
            valid+"\nTclDevModeManager Info:\n    mCurrentDevMode:2\n")) {
            assertFalse(value,BluetoothControlEngine.parseSpeakerRoute(value))
        }
    }
    @Test fun rememberedBluetoothOutputReturnsOnlyAfterAnOwnedSpeakerHandoff() {
        val tv=Tv().apply { manufacturer="TCL" }
        assertEquals(false,engine(tv).execute(false).enabled)
        val restored=engine(tv).execute(true)
        assertEquals(true,restored.enabled);assertEquals("Bluetooth is on. Bluetooth audio selected.",restored.message)
        assertEquals(listOf("settings put global tcl_device_out_type 2","settings put global tcl_device_out_type 4"),tv.routeWrites())
        assertNull(tv.remembered)
        val noOwnership=Tv().apply { manufacturer="TCL";on=false;output="2";routeMode=2;routeDevice="speaker(2)" }
        assertEquals(true,engine(noOwnership).execute(true).enabled)
        assertTrue(noOwnership.routeWrites().isEmpty())
    }
    @Test fun manualOutputChoiceWinsOnEnableAndIsNotOverwrittenDuringVerification() {
        for(manual in listOf("1","4","16","null","99")) {
            val tv=Tv().apply { manufacturer="TCL";on=false;remembered=4;output=manual }
            assertEquals(true,engine(tv).execute(true).enabled)
            assertTrue(tv.routeWrites().isEmpty());assertNull(tv.remembered)
        }
        val raced=Tv().apply { manufacturer="TCL";on=false;remembered=4;output="2" }
        var mutations=0
        assertEquals(true,engine(raced,mutation={ if(++mutations==2)raced.output="16" }).execute(true).enabled)
        assertTrue(raced.routeWrites().isEmpty());assertNull(raced.remembered)
    }
    @Test fun failedRestoreKeepsVerifiedRadioStateAndMarkerForRetry() {
        val tv=Tv().apply { manufacturer="TCL";on=false;remembered=4;output="2";ignoreOutputWrite=true }
        val result=engine(tv).execute(true)
        assertEquals(true,result.enabled);assertTrue(result.message.contains("could not be restored"));assertEquals(4,tv.remembered)
        assertFalse(tv.commands.contains("cmd bluetooth_manager disable"))
        assertTrue(tv.pauses<=2)
    }
    @Test fun readOnlyNeverConsumesOwnershipAndNonBluetoothModesAreNotRemembered() {
        val tv=Tv().apply { manufacturer="TCL";remembered=4;output="16" }
        assertEquals(true,engine(tv).execute().enabled)
        assertEquals(4,tv.remembered);assertEquals(0,tv.rememberWrites);assertTrue(tv.routeWrites().isEmpty())
        assertEquals(false,engine(tv).execute(false).enabled)
        assertNull(tv.remembered)
    }
    @Test fun observedTclSectionsVerifySelectionWithoutClaimingUnmute() {
        // Minimal sections captured from this TCL's dumpsys audio on 2026-11-03.
        // No accounts, device identity, package history, or addresses are included.
        val observed="""
            - STREAM_MUSIC:
               Muted: true
               Muted Internally: false
               Min: 0
               Max: 100
               streamVolume:59
               Current: 2 (speaker): 37, 80 (bt_a2dp): 59, 400 (hdmi): 20, 80000 (spdif): 20, 800000 (ip): 20, 40000000 (default): 20
               Devices: bt_a2dp(80)
               Volume Group: n/a

            TclDevModeManager Info:
                mbSupportDeviceSelect:true
                isSupportDeviceSelect:true
                mPreDevMode:4
                mCurrentDevMode:4
                readOutputDevMode:4

            TclAudioDevicesUtils Info:
                MusicStrategyId:    20
                Speaker:    null
        """.trimIndent()
        assertFalse(BluetoothControlEngine.parseSpeakerRoute(observed))
        // Simulate the separately verified speaker route, retaining the real muted state.
        val speakers=observed.replace("mCurrentDevMode:4","mCurrentDevMode:2")
            .replace("readOutputDevMode:4","readOutputDevMode:2").replace("Devices: bt_a2dp(80)","Devices: speaker(2)")
        val tv=Tv().apply { manufacturer="TCL";audioOverride=speakers }
        val result=engine(tv).execute(false)
        assertEquals("Bluetooth is off. TV speakers selected.",result.message)
        assertTrue(speakers.contains("Muted: true"))
        assertFalse(result.message.contains("unmut",true))
        assertFalse(tv.commands.any { it.contains("unmute") || it.contains("volume") })
    }
    @Test fun cancellationBeforeRouteAndUserSwitchAfterRouteCannotClaimSpeakerSuccess() {
        val cancelledTv=Tv().apply { manufacturer="TCL" };var cancelled=false;var mutations=0
        val cancelledResult=engine(cancelledTv,{cancelled},mutation={ if(++mutations==2)cancelled=true }).execute(false)
        assertEquals(false,cancelledResult.enabled);assertTrue(cancelledResult.cancelled)
        assertTrue(cancelledTv.routeWrites().isEmpty())
        val switched=Tv().apply {
            manufacturer="TCL";before={ if(it=="settings put global tcl_device_out_type 2")user="10" }
        }
        val result=engine(switched).execute(false)
        assertEquals(false,result.enabled);assertTrue(result.message.contains("could not be confirmed"))
        assertFalse(switched.commands.contains("cmd bluetooth_manager enable"))
    }
    @Test fun unknownOrOversizedAudioDumpCannotProveRouteSuccess() {
        for(dump in listOf("Permission Denial", "x".repeat(524_289))) {
            val tv=Tv().apply { manufacturer="TCL";audioOverride=dump }
            val result=engine(tv).execute(false)
            assertEquals(false,result.enabled);assertTrue(result.message.contains("could not be confirmed"))
        }
    }
}
