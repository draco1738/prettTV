package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class EarthCamDreamPolicyTest {
    @Test fun aFailedOrRepeatedLaunchHasABoundedRetryCooldown() {
        assertEquals(120_000L, EarthCamDreamPolicy.cooldownRemaining(50_000, 50_000))
        assertEquals(1L, EarthCamDreamPolicy.cooldownRemaining(50_000, 169_999))
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(50_000, 170_000))
    }

    @Test fun processRestartPreservesCooldownButDeviceRebootCannotLockItOut() {
        assertEquals(90_000L, EarthCamDreamPolicy.cooldownRemaining(1_000_000, 1_030_000))
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(1_000_000, 20_000))
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(-1, 20_000))
    }

    @Test fun invalidElapsedTimestampsNeverCreateAnUnboundedWait() {
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(0, Long.MAX_VALUE))
        assertEquals(0L, EarthCamDreamPolicy.cooldownRemaining(100, -1))
        assertEquals(1L, EarthCamDreamPolicy.cooldownRemaining(Long.MAX_VALUE - 119_999, Long.MAX_VALUE))
    }
}
