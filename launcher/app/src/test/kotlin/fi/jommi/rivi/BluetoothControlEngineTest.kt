package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class BluetoothControlEngineTest {
    private val identity=AppForceStopIdentity("fixture-tv","vendor/tv:14/release")
    private class Tv:BluetoothControlShell {
        var on=true
        var uid="2000";var serial="fixture-tv";var fingerprint="vendor/tv:14/release";var user="0"
        var ignoreWrite=false;var reply="";var dumpOverride:String?=null
        var before:((String)->Unit)?=null
        val commands=ArrayList<String>()
        override fun run(command:String):String {
            commands+=command;before?.invoke(command)
            return when(command) {
                "id -u"->uid
                "getprop ro.serialno"->serial
                "getprop ro.build.fingerprint"->fingerprint
                "am get-current-user"->user
                "dumpsys bluetooth_manager"->dumpOverride ?: "Bluetooth Status\n  enabled: $on\n  state: ${if(on)"ON" else "OFF"}\nAdapterProperties\n  State: OFF\n"
                "cmd bluetooth_manager enable"->{ if(!ignoreWrite)on=true;reply }
                "cmd bluetooth_manager disable"->{ if(!ignoreWrite)on=false;reply }
                "cmd bluetooth_manager wait-for-state:STATE_ON","cmd bluetooth_manager wait-for-state:STATE_OFF"->""
                else->error("Unexpected command $command")
            }
        }
        fun writes()=commands.filter { it=="cmd bluetooth_manager enable" || it=="cmd bluetooth_manager disable" }
    }
    private fun engine(tv:Tv,cancelled:()->Boolean={false},allowed:()->Boolean={true},mutation:()->Unit={})=
        BluetoothControlEngine(identity,0,tv,cancelled,allowed,mutation)
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
}
