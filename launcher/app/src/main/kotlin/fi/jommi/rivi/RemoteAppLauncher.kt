package fi.jommi.rivi

import android.content.Context
import android.content.Intent
import android.view.InputDevice
import android.view.KeyEvent

/** Launch an installed TV app without enabling stock Home, clearing its data or changing tasks. */
internal object RemoteAppLauncher {
    fun open(context:Context,packageName:String):Boolean = try {
        val pm=context.packageManager
        val intent=pm.getLeanbackLaunchIntentForPackage(packageName) ?: pm.getLaunchIntentForPackage(packageName)
        if(intent==null)false else {
            context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
    } catch(_:Exception) { false }

    fun isController(event:KeyEvent):Boolean {
        val device=event.device
        return isController(event.source,device?.sources ?: 0,device?.vendorId ?: 0,
            device?.productId ?: 0,device?.name.orEmpty())
    }

    internal fun isController(source:Int,deviceSources:Int,vendor:Int,product:Int,name:String):Boolean {
        fun controller(s:Int)=s and InputDevice.SOURCE_GAMEPAD==InputDevice.SOURCE_GAMEPAD ||
            s and InputDevice.SOURCE_JOYSTICK==InputDevice.SOURCE_JOYSTICK
        if(controller(source))return true
        // This remote advertises a joystick axis for its volume control. Its keyboard keys are
        // still remote buttons. Do not mistake that descriptor for a game controller.
        if(vendor==0x0417 && product==0x0304 && name.startsWith("BT_RC"))return false
        if(vendor==0x18d1 && product==0x0100 && name=="virtual-remote")return false
        return controller(deviceSources)
    }
}
