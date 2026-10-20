package fi.jommi.rivi

import fi.jommi.rivi.SettingsKeyPolicy.Decision.CONSUME
import fi.jommi.rivi.SettingsKeyPolicy.Decision.OPEN_SETTINGS
import fi.jommi.rivi.SettingsKeyPolicy.Decision.PASS
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsKeyPolicyTest {
    companion object {
        private const val DOWN=0
        private const val UP=1
        private const val SETTINGS=176
        private const val NOTIFICATION=83
    }

    private fun SettingsKeyPolicy.event(action:Int,keyCode:Int=SETTINGS,repeatCount:Int=0,
                                        deviceId:Int=7,downTime:Long=100L,
                                        canceled:Boolean=false,paused:Boolean=false) =
        handle(action,keyCode,repeatCount,deviceId,downTime,canceled,paused)

    @Test fun standardSettingsWorksRegardlessOfManufacturer() {
        for(manufacturer in listOf("TCL","tcl","Sony","Google","")) {
            val policy=SettingsKeyPolicy(manufacturer)
            assertEquals(manufacturer,CONSUME,policy.event(DOWN))
            assertEquals(manufacturer,OPEN_SETTINGS,policy.event(UP))
        }
    }

    @Test fun notificationAliasIsLimitedToCaseInsensitiveTclManufacturer() {
        for(manufacturer in listOf("TCL","tcl","TcL")) {
            val policy=SettingsKeyPolicy(manufacturer)
            assertEquals(manufacturer,CONSUME,policy.event(DOWN,NOTIFICATION))
            assertEquals(manufacturer,CONSUME,policy.event(DOWN,NOTIFICATION,repeatCount=1))
            assertEquals(manufacturer,OPEN_SETTINGS,policy.event(UP,NOTIFICATION))
        }
        for(manufacturer in listOf("Sony","Google","","TCL Electronics")) {
            val policy=SettingsKeyPolicy(manufacturer)
            assertEquals(manufacturer,PASS,policy.event(DOWN,NOTIFICATION))
            assertEquals(manufacturer,PASS,policy.event(DOWN,NOTIFICATION,repeatCount=1))
            assertEquals(manufacturer,PASS,policy.event(UP,NOTIFICATION))
        }
    }

    @Test fun heldSettingsKeyOpensOnlyOnceOnReleaseAndFreshPressCanOpenAgain() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        for(repeatCount in 1..20) {
            assertEquals(CONSUME,policy.event(DOWN,repeatCount=repeatCount))
        }
        assertEquals(OPEN_SETTINGS,policy.event(UP))
        assertEquals(PASS,policy.event(UP))
        assertEquals(PASS,policy.event(DOWN,repeatCount=21))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun orphanReleaseAndRepeatDoNotArmASettingsLaunch() {
        val policy=SettingsKeyPolicy("TCL")
        for(keyCode in listOf(SETTINGS,NOTIFICATION)) {
            assertEquals(PASS,policy.event(UP,keyCode))
            assertEquals(PASS,policy.event(DOWN,keyCode,repeatCount=1))
            assertEquals(PASS,policy.event(UP,keyCode))
        }
    }

    @Test fun mismatchedReleaseCannotLaunchOrDiscardTheActualPendingPress() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(PASS,policy.event(UP,deviceId=8,canceled=true,paused=true))
        assertEquals(PASS,policy.event(UP,downTime=99L))
        assertEquals(PASS,policy.event(UP,downTime=101L))
        assertEquals(PASS,policy.event(UP,NOTIFICATION))
        assertEquals(OPEN_SETTINGS,policy.event(UP))
        assertEquals(PASS,policy.event(UP))
    }

    @Test fun freshInitialDownReplacesThePreviousPressAcrossKeysAndDevices() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(DOWN,NOTIFICATION,deviceId=9,downTime=200L))
        assertEquals(PASS,policy.event(UP))
        assertEquals(OPEN_SETTINGS,policy.event(UP,NOTIFICATION,deviceId=9,downTime=200L))
        assertEquals(PASS,policy.event(UP,NOTIFICATION,deviceId=9,downTime=200L))
    }

    @Test fun freshInitialDownWithANewTimestampReplacesAnUnreleasedPress() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(PASS,policy.event(UP))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun canceledReleaseIsConsumedWithoutOpeningAndClearsThePress() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(UP,canceled=true))
        assertEquals(PASS,policy.event(UP))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun cancellationDuringAHoldSuppressesItsEventualRelease() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(DOWN,repeatCount=1,canceled=true))
        assertEquals(CONSUME,policy.event(DOWN,repeatCount=2))
        assertEquals(CONSUME,policy.event(UP))
        assertEquals(PASS,policy.event(UP))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun pausedInitialDownPassesThroughAndCannotLaunchAfterResuming() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(PASS,policy.event(DOWN,paused=true))
        assertEquals(PASS,policy.event(DOWN,repeatCount=1))
        assertEquals(PASS,policy.event(UP))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun pausingDuringAHoldConsumesTheSequenceEvenAfterResuming() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(DOWN,repeatCount=1,paused=true))
        assertEquals(CONSUME,policy.event(DOWN,repeatCount=2))
        assertEquals(CONSUME,policy.event(UP))
        assertEquals(PASS,policy.event(UP))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun pausedMatchingReleaseConsumesAndClearsThePressWithoutOpening() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(UP,paused=true))
        assertEquals(PASS,policy.event(UP))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun freshPressDoesNotInheritSuppressionFromAnEarlierHeldPress() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        assertEquals(CONSUME,policy.event(DOWN,repeatCount=1,paused=true))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(PASS,policy.event(UP))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
    }

    @Test fun cancelPendingPreservesReleaseConsumptionWithoutOpeningSettings() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        policy.cancelPending()
        assertEquals(CONSUME,policy.event(UP))
        assertEquals(PASS,policy.event(UP))
    }

    @Test fun resetDiscardsAnyPendingReleaseAndAllowsTheNextFreshPress() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        policy.reset()
        assertEquals(PASS,policy.event(UP))
        assertEquals(PASS,policy.event(DOWN,repeatCount=1))
        assertEquals(CONSUME,policy.event(DOWN,downTime=200L))
        assertEquals(OPEN_SETTINGS,policy.event(UP,downTime=200L))
        policy.reset()
        policy.reset()
        assertEquals(PASS,policy.event(UP,downTime=200L))
    }

    @Test fun menuHomeBackNavigationAndMediaKeysPassWithoutDisturbingSettings() {
        val policy=SettingsKeyPolicy("TCL")
        assertEquals(CONSUME,policy.event(DOWN))
        for(keyCode in listOf(82,3,4,19,20,21,22,23,24,25,85)) {
            assertEquals(keyCode.toString(),PASS,policy.event(DOWN,keyCode))
            assertEquals(keyCode.toString(),PASS,policy.event(DOWN,keyCode,repeatCount=1))
            assertEquals(keyCode.toString(),PASS,policy.event(UP,keyCode))
        }
        assertEquals(OPEN_SETTINGS,policy.event(UP))
    }
}
