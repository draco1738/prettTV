package fi.jommi.rivi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteBluetoothGesturePolicyTest {
    private fun MuteBluetoothGesturePolicy.down(time:Long,device:Int=7)=handle(0,164,0,device,time,time)
    private fun MuteBluetoothGesturePolicy.up(down:Long,time:Long=down+30,device:Int=7)=handle(1,164,0,device,down,time)
    private fun MuteBluetoothGesturePolicy.tap(time:Long,device:Int=7):Boolean {
        assertFalse(down(time,device));return up(time,device=device)
    }

    @Test fun oneTapNeverTogglesAndTwoCompletedTapsToggleOnlyOnSecondRelease() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.tap(100))
        assertFalse(policy.down(250))
        assertTrue(policy.up(250))
    }

    @Test fun onlyVerifiedTclManufacturersEnableTheGesture() {
        for(maker in listOf("Sony","Google","","TCL Electronics")) {
            val policy=MuteBluetoothGesturePolicy(maker)
            assertFalse(policy.isMuteKey(164))
            assertFalse(policy.tap(100));assertFalse(policy.tap(200));assertFalse(policy.tap(300))
        }
        for(maker in listOf("TCL","tcl","TcL")) {
            val policy=MuteBluetoothGesturePolicy(maker)
            assertTrue(policy.isMuteKey(164));assertFalse(policy.tap(100));assertTrue(policy.tap(200))
        }
    }

    @Test fun windowIncludes450MillisecondsAfterFirstReleaseButNot451() {
        val boundary=MuteBluetoothGesturePolicy("TCL")
        assertFalse(boundary.tap(100));assertFalse(boundary.down(500))
        assertTrue(boundary.up(500,580))
        val expired=MuteBluetoothGesturePolicy("TCL")
        assertFalse(expired.tap(100));assertFalse(expired.down(500))
        assertFalse(expired.up(500,581))
        assertTrue(expired.tap(650)) // The late tap can start a fresh pair.
    }

    @Test fun thirdAndFourthPressCannotToggleAgainDuringCooldown() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.tap(100));assertTrue(policy.tap(200))
        for(time in listOf(300L,400L,1_100L))assertFalse(policy.tap(time))
        assertFalse(policy.tap(1_230));assertTrue(policy.tap(1_400))
    }

    @Test fun duplicatedDownAndUpCannotBecomeAnotherPressOrUndoCancellation() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.down(100));assertFalse(policy.down(100));assertFalse(policy.up(100))
        assertFalse(policy.handle(0,164,0,7,100,140))
        assertFalse(policy.up(100,150))
        assertTrue(policy.tap(200))
        assertFalse(policy.handle(0,164,0,7,200,1_500))
        assertFalse(policy.up(200,1_530))
    }

    @Test fun repeatsLongPressAndCanceledEventsInvalidateWholePair() {
        for(kind in 0..2)for(action in 0..1) {
            val policy=MuteBluetoothGesturePolicy("TCL")
            assertFalse(policy.tap(100));assertFalse(policy.down(200))
            assertFalse(policy.handle(action,164,if(kind==0)1 else 0,7,200,210,
                canceled=kind==1,longPress=kind==2))
            assertFalse(policy.handle(0,164,0,7,200,220))
            assertFalse(policy.up(200,230));assertFalse(policy.tap(300))
        }
    }

    @Test fun longDurationWithoutARepeatStillCannotArmATap() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.down(100));assertFalse(policy.up(100,551))
        assertFalse(policy.tap(600));assertTrue(policy.tap(700))
    }

    @Test fun pausedAndControllerInputResetThePairAndRemainUnarmed() {
        for(paused in listOf(true,false)) {
            val policy=MuteBluetoothGesturePolicy("TCL")
            assertFalse(policy.tap(100))
            assertFalse(policy.handle(0,164,0,7,200,200,paused=paused,controller=!paused))
            assertFalse(policy.up(200));assertFalse(policy.tap(300));assertTrue(policy.tap(400))
        }
    }

    @Test fun tapsFromDifferentDevicesNeverCombine() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.tap(100,7));assertFalse(policy.tap(200,8));assertTrue(policy.tap(300,8))
    }

    @Test fun orphanOrMismatchedReleaseCancelsRatherThanCompletingAPair() {
        for(wrongDevice in listOf(true,false)) {
            val policy=MuteBluetoothGesturePolicy("TCL")
            assertFalse(policy.up(50,80));assertFalse(policy.tap(100));assertFalse(policy.down(200))
            assertFalse(policy.up(if(wrongDevice)200 else 201,230,if(wrongDevice)8 else 7))
            assertFalse(policy.up(200,240));assertFalse(policy.tap(300))
        }
    }

    @Test fun otherKeysIncludingMicrophoneMuteDoNotToggleAndBreakThePair() {
        for(key in listOf(91,24,25,23,4012)) {
            val policy=MuteBluetoothGesturePolicy("TCL")
            assertFalse(policy.isMuteKey(key));assertFalse(policy.tap(100))
            assertFalse(policy.handle(0,key,0,7,180,180))
            assertFalse(policy.tap(200));assertTrue(policy.tap(300))
        }
    }

    @Test fun backwardsTimesAndOverlappingDownEventsCannotCompleteAnOldPair() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.tap(100))
        assertFalse(policy.handle(0,164,0,7,80,90));assertFalse(policy.tap(200))
        assertFalse(policy.down(300));assertFalse(policy.down(310));assertFalse(policy.up(310,340))
        assertTrue(policy.tap(400))
    }

    @Test fun interruptionAndLifecycleResetNeverLeaveDeferredGestures() {
        val policy=MuteBluetoothGesturePolicy("TCL")
        assertFalse(policy.tap(100));policy.cancelPending()
        assertFalse(policy.tap(200));assertFalse(policy.down(300));policy.cancelPending()
        assertFalse(policy.up(300));assertFalse(policy.tap(400));assertTrue(policy.tap(500))
        policy.cancelPending();assertFalse(policy.tap(600)) // Cancellation retains cooldown.
        policy.reset();assertFalse(policy.tap(700));assertTrue(policy.tap(800))
    }
}
