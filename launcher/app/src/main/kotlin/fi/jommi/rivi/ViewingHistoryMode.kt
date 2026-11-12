package fi.jommi.rivi

import android.content.Context
import android.content.SharedPreferences

/** Evidence policy, not a viewer/account identity or an access permission. */
enum class ViewingHistoryMode(val label:String) {
    COMBINED("Combined"), THIS_TV_ONLY("This TV only");

    companion object {
        const val PREFS_NAME="pretttv"
        const val KEY="historySource"
        fun parse(value:String?):ViewingHistoryMode=entries.firstOrNull { it.name==value } ?: COMBINED
        fun read(prefs:SharedPreferences):ViewingHistoryMode=parse(prefs.all[KEY] as? String)
        fun read(context:Context):ViewingHistoryMode=read(context.getSharedPreferences(PREFS_NAME,Context.MODE_PRIVATE))
    }
}
