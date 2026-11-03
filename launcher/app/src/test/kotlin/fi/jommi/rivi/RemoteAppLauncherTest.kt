package fi.jommi.rivi

import android.view.InputDevice
import org.junit.Assert.*
import org.junit.Test

class RemoteAppLauncherTest {
    @Test fun actualGameControllerSourcesAlwaysPassThrough() {
        for(source in listOf(InputDevice.SOURCE_GAMEPAD,InputDevice.SOURCE_JOYSTICK)) {
            assertTrue(RemoteAppLauncher.isController(source,0,0,0,""))
            assertTrue(RemoteAppLauncher.isController(source,0,0x0417,0x0304,"BT_RC923A_B1 Consumer Control"))
        }
    }
    @Test fun gamepadKeyboardEventsAreStillExcluded() {
        assertTrue(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_GAMEPAD,0,0,"Gamepad"))
    }
    @Test fun tclVolumeAxisDoesNotDisableRemoteKeyboardButtons() {
        assertFalse(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,
            InputDevice.SOURCE_JOYSTICK or InputDevice.SOURCE_DPAD,0x0417,0x0304,"BT_RC923A_B1 Consumer Control"))
    }
    @Test fun remoteExceptionRequiresMatchingIdentity() {
        assertTrue(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_JOYSTICK,0,0,"BT_RC923A"))
        assertTrue(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_JOYSTICK,0x0417,0x0304,"Controller"))
    }
    @Test fun ordinaryKeyboardAndIrRemoteAreNotControllers() {
        assertFalse(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_DPAD,0,0,""))
        assertFalse(RemoteAppLauncher.isController(InputDevice.SOURCE_KEYBOARD,InputDevice.SOURCE_GAMEPAD,0x18d1,0x0100,"virtual-remote"))
    }
}
