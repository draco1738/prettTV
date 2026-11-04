package fi.jommi.rivi

/** OLED-black surfaces shared by prettTV's native screens. Artwork keeps its own colors. */
internal object PrettTvColors {
    const val BACKGROUND = 0xFF000000.toInt()
    const val SURFACE = 0xFF000000.toInt()
    const val RAISED = 0xFF000000.toInt()
    const val SELECTED = 0xFF000000.toInt()
    const val FOCUS = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFFB8B8B8.toInt()
    const val SECONDARY = 0xFFC0C0C0.toInt()
    const val INK = 0xFF0A0A0A.toInt()
    const val EDGE = 0xFF3A3A3A.toInt()
    const val STRONG_EDGE = 0xFF646464.toInt()
    const val DIVIDER = 0xFF303030.toInt()
    const val DISABLED = 0xFF868686.toInt()
}
