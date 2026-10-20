package fi.jommi.rivi

/** Elapsed time survives process restarts; a reboot resets it and must not create a long lockout. */
internal object EarthCamDreamPolicy {
    const val PACKAGE = "com.earthcam.earthcamtv.android"
    const val ACTIVITY = "com.earthcam.earthcamtv.home.HomeActivity"
    const val COOLDOWN_MILLIS = 120_000L

    fun cooldownRemaining(lastAttemptElapsed: Long, nowElapsed: Long): Long {
        if (lastAttemptElapsed < 0 || nowElapsed < 0 || nowElapsed < lastAttemptElapsed) return 0
        val elapsed = nowElapsed - lastAttemptElapsed
        return (COOLDOWN_MILLIS - elapsed).coerceIn(0, COOLDOWN_MILLIS)
    }
}
