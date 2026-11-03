package fi.jommi.rivi

import fi.jommi.rivi.AppShortcutKeyPolicy.Decision.CONSUME
import fi.jommi.rivi.AppShortcutKeyPolicy.Decision.OPEN_APP
import fi.jommi.rivi.AppShortcutKeyPolicy.Decision.PASS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppShortcutKeyPolicyTest {
    private fun AppShortcutKeyPolicy.event(action:Int,key:Int=4062,repeat:Int=0,
                                          device:Int=7,time:Long=100L,canceled:Boolean=false,
                                          paused:Boolean=false,controller:Boolean=false) =
        handle(action,key,repeat,device,time,canceled,paused,controller)

    @Test fun verifiedTclAliasesResolveToTheirOwnApps() {
        val apps=mapOf(191 to "com.netflix.ninja",4062 to "com.netflix.ninja",
            190 to "com.phlox.tvwebbrowser",4065 to "com.phlox.tvwebbrowser",
            4020 to "com.wbd.stream",4099 to "com.amazon.amazonvideo.livingroom",
            4158 to "com.wbd.stream",4184 to "com.wbd.stream",
            4160 to "com.apple.atve.androidtv.appletv",4163 to "tv.pluto.android")
        for(maker in listOf("TCL","tcl","TcL"))for((key,app) in apps) {
            val policy=AppShortcutKeyPolicy(maker)
            assertEquals(app,policy.packageFor(key))
            assertEquals(CONSUME,policy.event(0,key))
            assertEquals(OPEN_APP,policy.event(1,key))
        }
    }

    @Test fun otherManufacturersAndControllersKeepEveryKey() {
        for(maker in listOf("Sony","Google","","TCL Electronics")) {
            val policy=AppShortcutKeyPolicy(maker)
            for(key in listOf(190,191,4020,4062,4065,4099,4158,4160,4163,4184)) {
                assertNull(policy.packageFor(key))
                assertEquals(PASS,policy.event(0,key))
                assertEquals(PASS,policy.event(1,key))
            }
        }
        val policy=AppShortcutKeyPolicy("TCL")
        for(key in listOf(190,191,4020,4062,4065,4099,4158,4160,4163,4184)) {
            assertNull(policy.packageFor(key,controller=true))
            assertEquals(PASS,policy.event(0,key,controller=true))
            assertEquals(PASS,policy.event(1,key,controller=true))
        }
    }

    @Test fun unknownNavigationMediaAndSettingsKeysStayUntouched() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(0))
        for(key in listOf(3,4,19,20,21,22,23,24,25,26,82,83,85,176,178,297,313,4135,4136,4141)) {
            assertNull(policy.packageFor(key))
            assertEquals(PASS,policy.event(0,key))
            assertEquals(PASS,policy.event(1,key))
        }
        assertEquals(OPEN_APP,policy.event(1))
    }

    @Test fun holdingAKeyLaunchesOnceAndOnlyOnItsRelease() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(0))
        for(repeat in 1..30)assertEquals(CONSUME,policy.event(0,repeat=repeat))
        assertEquals(OPEN_APP,policy.event(1))
        assertEquals(PASS,policy.event(1))
        assertEquals(PASS,policy.event(0,repeat=31))
        assertEquals(CONSUME,policy.event(0,time=200L))
        assertEquals(OPEN_APP,policy.event(1,time=200L))
    }

    @Test fun orphanEventsAndWrongDeviceOrTimestampCannotLaunch() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(PASS,policy.event(1))
        assertEquals(PASS,policy.event(0,repeat=1))
        assertEquals(PASS,policy.event(1))
        assertEquals(CONSUME,policy.event(0))
        assertEquals(PASS,policy.event(1,device=8))
        assertEquals(PASS,policy.event(1,time=99L))
        assertEquals(PASS,policy.event(1,key=191))
        assertEquals(PASS,policy.event(1,controller=true))
        assertEquals(OPEN_APP,policy.event(1))
    }

    @Test fun newPressReplacesOldPendingPress() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(0))
        assertEquals(CONSUME,policy.event(0,key=4099,device=8,time=200L))
        assertEquals(PASS,policy.event(1))
        assertEquals(OPEN_APP,policy.event(1,key=4099,device=8,time=200L))
    }

    @Test fun repeatedInitialDownDoesNotUndoCancellationOrRelaunchCompletedGesture() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(0,canceled=true))
        assertEquals(CONSUME,policy.event(0))
        assertEquals(CONSUME,policy.event(1))
        assertEquals(PASS,policy.event(0))
        assertEquals(PASS,policy.event(1))
        assertEquals(CONSUME,policy.event(0,time=200L))
        assertEquals(OPEN_APP,policy.event(1,time=200L))
        assertEquals(PASS,policy.event(0,time=200L))
        assertEquals(PASS,policy.event(1,time=200L))
    }

    @Test fun cancellationAtAnyStageSuppressesLaunch() {
        for(stage in 0..2) {
            val policy=AppShortcutKeyPolicy("TCL")
            assertEquals(CONSUME,policy.event(0,canceled=stage==0))
            assertEquals(CONSUME,policy.event(0,repeat=1,canceled=stage==1))
            assertEquals(CONSUME,policy.event(1,canceled=stage==2))
            assertEquals(PASS,policy.event(1))
        }
    }

    @Test fun pausedPressNeverArmsAndPausingAnArmedPressSuppressesItsRelease() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(PASS,policy.event(0,paused=true))
        assertEquals(PASS,policy.event(0,repeat=1))
        assertEquals(PASS,policy.event(1))
        assertEquals(CONSUME,policy.event(0,time=200L))
        assertEquals(CONSUME,policy.event(0,repeat=1,time=200L,paused=true))
        assertEquals(CONSUME,policy.event(1,time=200L))
        assertEquals(CONSUME,policy.event(0,time=300L))
        assertEquals(CONSUME,policy.event(1,time=300L,paused=true))
    }

    @Test fun cancelAndResetHandleServiceLifecycleWithoutDeferredLaunches() {
        val policy=AppShortcutKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(0))
        policy.cancelPending()
        assertEquals(CONSUME,policy.event(1))
        assertEquals(CONSUME,policy.event(0,time=200L))
        policy.reset()
        assertEquals(PASS,policy.event(1,time=200L))
        assertEquals(CONSUME,policy.event(0,time=300L))
        assertEquals(OPEN_APP,policy.event(1,time=300L))
    }
}
