package fi.jommi.rivi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/** Prefer TCL's native overlay so opening Settings does not stop the foreground app. */
internal object TvSettingsLauncher {
    fun open(context:Context,manufacturer:String=Build.MANUFACTURER):Boolean {
        if(manufacturer.equals("TCL",ignoreCase=true)) {
            val overlay=Intent("com.tcl.settings.SHOW_WINDOW")
                .setComponent(ComponentName("com.android.tv.settings","com.tcl.settings.ShowWindowService"))
                .putExtra("Type","quicksettings").putExtra("exitStartGoogleSettings",false)
            try { if(context.startService(overlay)!=null)return true } catch(_:Exception) { }
        }
        val settings=Intent(Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return try {
            if(context.packageManager.resolveActivity(settings,0)==null)false
            else { context.startActivity(settings);true }
        } catch(_:Exception) { false }
    }
}
