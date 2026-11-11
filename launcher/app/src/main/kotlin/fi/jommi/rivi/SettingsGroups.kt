package fi.jommi.rivi

internal object SettingsGroups {
    val names=listOf("Home & apps","Shows & recommendations","TV & audio","Support")
    fun group(label:String):String?=when {
        label.startsWith("Undo ·") || label=="Return to stock Google TV"->null
        label.startsWith("Layout:") || label.startsWith("Home interface size:") || label.startsWith("Focus motion:") || label in setOf("Customize rows","Choose apps one by one","Restore apps to Your apps row")->names[0]
        label.startsWith("Kickoff reminders:") || label.startsWith("Show discovery:") || label in setOf("Followed shows","Favorite teams","Recommendation feedback","Live sports apps","Recommendation services","Hidden titles","Playback tracking")->names[1]
        label in setOf("Weather","TV settings","Inputs","Audio devices")->names[2]
        else->names[3]
    }
}
