package fi.jommi.rivi

/**
 * Colours of the home screen. Like [Motion], the themes come from tools/motion-study and use its
 * names and values. Pick one on a device with
 *   adb shell am start -n fi.jommi.rivi/.MainActivity --es theme cinema
 *
 *   midnight  blue-black, the default
 *   cinema    true black with every tile except the focused one at half brightness: the least
 *             stray light from a projector in a dark room
 */
class Theme(
    val name: String,
    val background: Int, val ring: Int, val label: Int, val section: Int, val wordmark: Int,
    /** Brightness of the artwork of tiles that do not have focus, 1 = unchanged. */
    val unfocused: Float,
    val placeholder: Int, val sheet: Int, val sheetHighlight: Int, val sheetInk: Int,
) {
    companion object {
        val MIDNIGHT = Theme("midnight", 0xFF0B0E14.toInt(), 0xFFFFFFFF.toInt(), 0xFFE9EDF4.toInt(), 0xFF7E8798.toInt(), 0xFFB7BECC.toInt(),
            1f, 0xFF161B25.toInt(), 0xFF1C212B.toInt(), 0xFF343B49.toInt(), 0xFFE9EDF4.toInt())
        val CINEMA = Theme("cinema", 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F2F2.toInt(), 0xFF4F5560.toInt(), 0xFF6B7280.toInt(),
            0.5f, 0xFF141414.toInt(), 0xFF101216.toInt(), 0xFF2A2F3A.toInt(), 0xFFE9EDF4.toInt())
        val ALL = listOf(MIDNIGHT, CINEMA)

        /** Unknown, blank or null names give [MIDNIGHT]. */
        fun parse(name: String?): Theme = ALL.firstOrNull { it.name == name?.trim()?.lowercase() } ?: MIDNIGHT
    }
}
