package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class AudioOutputChoiceTest {
    @Test fun bluetoothPowerDoesNotSelectBluetoothAudio() {
        val report=AudioOutputReport("HDMI ARC",false)
        assertTrue(AudioOutputChoice.describe(TvAudioOutput.HDMI_ARC,report,true,false).selected)
        assertFalse(AudioOutputChoice.describe(TvAudioOutput.BLUETOOTH,report,true,false).selected)
        assertFalse(AudioOutputChoice.describe(TvAudioOutput.SPEAKERS,report,true,false).selected)
    }
    @Test fun unknownConflictingOrCombinedRoutesNeverAcquireAFalseCheckmark() {
        for(report in listOf(null,AudioOutputReport(),AudioOutputReport("TV speakers + HDMI ARC")))
            for(target in TvAudioOutput.entries)assertFalse(AudioOutputChoice.describe(target,report,true,false).selected)
    }
    @Test fun checkingDoesNotPresentThePreviousRouteAsCurrent() {
        val choice=AudioOutputChoice.describe(TvAudioOutput.SPEAKERS,AudioOutputReport("TV speakers"),true,true)
        assertFalse(choice.selected);assertEquals("Checking output…",choice.detail)
    }
    @Test fun mutedRouteIsStillSelectedWithoutClaimingItIsPlaying() {
        val choice=AudioOutputChoice.describe(TvAudioOutput.SPEAKERS,AudioOutputReport("TV speakers",true),false,false)
        assertTrue(choice.selected);assertEquals("Currently selected · muted",choice.detail)
    }
    @Test fun bluetoothOffExplainsTheRequiredNextStep() {
        assertEquals("Turn Bluetooth on first",AudioOutputChoice.describe(TvAudioOutput.BLUETOOTH,AudioOutputReport("TV speakers"),false,false).detail)
    }
    @Test fun bluetoothOnDoesNotClaimPairedOrConnectedHeadphones() {
        assertEquals("Use a connected audio device",AudioOutputChoice.describe(TvAudioOutput.BLUETOOTH,null,true,false).detail)
        assertEquals("Check Bluetooth or manage devices",AudioOutputChoice.describe(TvAudioOutput.BLUETOOTH,null,null,false).detail)
    }
}
