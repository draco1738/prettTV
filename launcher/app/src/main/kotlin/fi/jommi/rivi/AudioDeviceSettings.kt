package fi.jommi.rivi

import android.content.Context
import android.content.Intent
import android.provider.Settings

/** Hands Bluetooth changes to the TV's own controls; never changes radio state itself. */
internal object AudioDeviceSettings {
    const val TV_DEVICES_ACTION="com.android.tv.settings.ACCESSORIES_CONNECTED_DEVICES"
    enum class Destination { DEVICES, BLUETOOTH, SETTINGS, UNAVAILABLE }

    fun open(context:Context):Destination {
        val choices=listOf(
            Destination.DEVICES to Intent(TV_DEVICES_ACTION).setPackage("com.android.tv.settings"),
            Destination.BLUETOOTH to Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Destination.SETTINGS to Intent(Settings.ACTION_SETTINGS),
        )
        for((destination,intent) in choices) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent); return destination } catch(_:Exception) { }
        }
        return Destination.UNAVAILABLE
    }
}
