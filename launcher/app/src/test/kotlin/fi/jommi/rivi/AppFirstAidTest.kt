package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class AppFirstAidTest {
    private fun dump(route:String="speaker(2)",mode:Int=2,mute:String="false",internal:String="false")="""
        - STREAM_MUSIC:
           Muted: $mute
           Muted Internally: $internal
           Current: 2 (speaker): 10, 80 (bt_a2dp): 59
           Devices: $route

        - STREAM_ALARM:
           Devices: bt_a2dp(80)
        TclDevModeManager Info:
            mCurrentDevMode: $mode
        Other:
           Devices: hdmi(400)
    """.trimIndent()
    @Test fun selectedRouteIgnoresOtherStreamsAndConnectedDevices() {
        assertEquals(AudioOutputReport("TV speakers",false),AudioOutputReport.parse(dump()))
        assertEquals("Bluetooth audio",AudioOutputReport.parse(dump("bt_a2dp(80)",4)).output)
    }
    @Test fun internalMuteIsShownAndUnknownMuteIsNotInvented() {
        assertEquals(true,AudioOutputReport.parse(dump(internal="true")).muted)
        assertNull(AudioOutputReport.parse(dump(internal="unknown")).muted)
    }
    @Test fun conflictingOrDuplicateEvidenceFailsClosed() {
        for(text in listOf(dump("bt_a2dp(80)",2),dump(mode=4),dump()+"\n"+dump(),dump()+"\nTclDevModeManager Info:\n    mCurrentDevMode: 2",dump().replace("Devices: speaker(2)","Devices: speaker(2)\n   Devices: bt_a2dp(80)")))
            assertEquals(AudioOutputReport(),AudioOutputReport.parse(text))
    }
    @Test fun unknownAndMalformedRoutesAreNotPresentedAsSpeakers() {
        for(route in listOf("default(40000000)","speaker","speaker(2) unknown","","mystery(9)"))
            assertEquals(AudioOutputReport(),AudioOutputReport.parse(dump(route)))
    }
    @Test fun wiredAndCombinedRoutesAreReadable() {
        assertEquals("Optical audio",AudioOutputReport.parse(dump("spdif(80000)",16)).output)
        assertEquals("TV speakers + Bluetooth audio",AudioOutputReport.parse(dump("speaker(2), bt_a2dp(80)",6)).output)
    }
    @Test fun connectionClaimsRequireValidationAndCaptivePortalWins() {
        assertTrue(NetworkHealth.describe(false,true,false,"Wi-Fi").startsWith("No network"))
        assertTrue(NetworkHealth.describe(true,false,false,"Wi-Fi").contains("not confirmed"))
        assertTrue(NetworkHealth.describe(true,true,true,"Wi-Fi").contains("sign-in"))
        assertTrue(NetworkHealth.describe(true,true,false,"Ethernet").contains("Android reports"))
    }
    @Test fun destructiveRecoveryStaysSeparateAndFirstAidIsEasyToFind() {
        assertNull(SettingsGroups.group("Return to stock Google TV"))
        assertNull(SettingsGroups.group("Undo · Hide title"))
        assertEquals("Support",SettingsGroups.group("App first aid"))
        assertEquals("TV & audio",SettingsGroups.group("Audio devices"))
        assertEquals("Home & apps",SettingsGroups.group("Layout: C"))
        assertEquals("Home & apps",SettingsGroups.group("Home interface size: Larger · 104%"))
        assertEquals("Home & apps",SettingsGroups.group("Interface size: Larger · 104%"))
        assertEquals("Support",SettingsGroups.group("Backup and restore"))
        assertEquals("Shows & recommendations",SettingsGroups.group("Hidden titles"))
    }
}
