package fi.jommi.rivi

/**
 * Which refinements of the home screen are switched on. The names are the ones used by the
 * motion study in tools/motion-study, which is where each of them can be tried before it is built.
 *
 *   settle  the focused tile overshoots slightly and settles, instead of a plain ease
 *   fade    the focus ring and the label fade in and out instead of appearing
 *   shadow  a soft shadow under the focused tile, drawn behind every tile
 *   hold    holding OK fills a ring, then the tile menu slides in from the right
 *   sound   Android's own click when moving and opening (off by default: Rivi is silent)
 *
 * The default is the study's "Quiet" combination. To try another one on a device without rebuilding:
 *   adb shell am start -n fi.jommi.rivi/.MainActivity --es motion settle,fade
 * ("none" switches everything off, "default" goes back to the built-in set). The choice is remembered.
 */
data class Motion(val settle: Boolean, val fade: Boolean, val shadow: Boolean, val hold: Boolean, val sound: Boolean = false) {

    val names: List<String>
        get() = listOfNotNull("settle".takeIf { settle }, "fade".takeIf { fade }, "shadow".takeIf { shadow }, "hold".takeIf { hold }, "sound".takeIf { sound })

    companion object {
        val DEFAULT = Motion(settle = true, fade = true, shadow = true, hold = true)
        val NONE = Motion(settle = false, fade = false, shadow = false, hold = false)

        /** "settle,fade" -> those two. Unknown names are ignored; null, blank or "default" give [DEFAULT]. */
        fun parse(spec: String?): Motion {
            val s = spec?.trim()?.lowercase().orEmpty()
            if (s.isEmpty() || s == "default") return DEFAULT
            val on = s.split(',', ' ').map { it.trim() }.toHashSet()
            return Motion("settle" in on, "fade" in on, "shadow" in on, "hold" in on, "sound" in on)
        }
    }
}
