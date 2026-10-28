package fi.jommi.rivi

/** A deliberately narrow shell operation: the selected app only, never its data or settings. */
internal fun interface AppForceStopShell { fun run(command: String): String }

internal data class AppForceStopIdentity(val serial: String, val fingerprint: String)
internal data class AppForceStopResult(
    val success: Boolean,
    val message: String,
    val mutationMayHaveOccurred: Boolean = false,
    val cancelled: Boolean = false,
)

internal object AppForceStopPolicy {
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val protectedPackages = setOf(
        "android", "dev.zane.pretttv", "dev.zane.pretttv.stability",
        "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.shell", "com.android.bluetooth", "com.android.networkstack",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
        "com.google.android.katniss", "com.google.android.tungsten.setupwraith",
        "com.tcl.tv.settings", "com.tcl.settings", "com.tcl.tv", "com.tcl.tvservice",
    )

    fun validPackage(name: String) = name.length in 3..255 && packagePattern.matches(name)

    fun allowed(name: String, ownPackage: String, appId: Int, persistent: Boolean, home: Boolean): Boolean =
        validPackage(name) && name != ownPackage && name !in protectedPackages &&
            appId in 10_000..99_999 && !persistent && !home &&
            !name.startsWith("com.android.providers.") && !name.startsWith("com.google.android.providers.")

    /** SYSTEM is deliberately not rejected: Netflix/Max can ship preinstalled on a TV. */
    fun validIdentity(identity: AppForceStopIdentity) =
        identity.serial.length in 1..128 && identity.serial.isNotBlank() && identity.serial.none(Char::isISOControl) &&
            identity.fingerprint.length in 1..1024 && identity.fingerprint.isNotBlank() && identity.fingerprint.none(Char::isISOControl)
}

/**
 * All reads and the one possible write run on the caller's bounded worker. A legacy ADB
 * shell has no exit status, so a blank force-stop reply is never treated as success.
 */
internal class AppForceStopEngine(
    private val identity: AppForceStopIdentity,
    private val packageName: String,
    private val ownPackage: String,
    private val launcherUser: Int,
    private val shell: AppForceStopShell,
    private val cancelled: () -> Boolean = { false },
    private val revalidateTarget: () -> Boolean = { true },
    private val beforeMutation: () -> Unit = {},
) {
    private var mutationMayHaveOccurred = false

    fun stop(): AppForceStopResult {
        try {
            requireCheck(AppForceStopPolicy.validIdentity(identity), "The saved TV identity is unavailable.")
            requireCheck(AppForceStopPolicy.allowed(packageName, ownPackage, 10_000, false, false),
                "This system app cannot be force stopped here.")
            requireCheck(launcherUser in 0..21_474, "The launcher user could not be verified.")
            requireCheck(read("id -u").trim() == "2000", "An existing authorized local debugging connection is required.")
            verifyIdentity()
            val user = currentUser()
            requireCheck(user == launcherUser, "The TV user changed. Return to your Home screen and try again.")
            requireCheck(revalidateTarget(), "This app is no longer available for force stop.")
            val before = packageState(user)
            requireCheck(AppForceStopPolicy.allowed(packageName, ownPackage, before.appId, before.persistent, false),
                "This system app cannot be force stopped here.")
            val home = component(read("cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.HOME"))
            requireCheck(home != null, "The current Home app could not be verified.")
            requireCheck(home!!.substringBefore('/') != packageName, "A Home app cannot be force stopped here.")
            requireCheck(launchable(user), "The selected app no longer has an enabled launch activity.")
            // Recheck identity/user and PackageManager eligibility immediately before the only write.
            verifyIdentity()
            requireCheck(currentUser() == user && revalidateTarget(), "The TV or selected app changed. Try again from Home.")
            checkCancelled()
            beforeMutation()
            mutationMayHaveOccurred = true
            val response = read("am force-stop --user $user $packageName")
            requireCheck(!commandError(response), "Android refused to force stop this app.")
            requireCheck(currentUser() == user, "The TV user changed before the stop could be verified.")
            val after = packageState(user)
            requireCheck(after.appId == before.appId && after.stopped,
                "Android did not confirm that the selected app stopped. It may have restarted or changed.")
            return AppForceStopResult(true, "App stopped. Open it again to reconnect. Your login and app data are kept.", true)
        } catch (_: Cancelled) {
            return AppForceStopResult(false, if (mutationMayHaveOccurred)
                "Stopped checking. The app may already have stopped; its final state was not verified."
            else "Cancelled before sending force stop. The app was not changed.", mutationMayHaveOccurred, true)
        } catch (failure: Exception) {
            val reason = failure.message?.take(250) ?: "The local debugging connection was interrupted."
            return AppForceStopResult(false, if (mutationMayHaveOccurred)
                "The app may have stopped, but its final state could not be verified. $reason"
            else "Force stop was not sent. $reason", mutationMayHaveOccurred)
        }
    }

    private fun verifyIdentity() {
        requireCheck(read("getprop ro.serialno").trim() == identity.serial, "The saved identity does not match this TV.")
        requireCheck(read("getprop ro.build.fingerprint").trim() == identity.fingerprint,
            "The TV software has changed since local recovery was configured.")
    }

    private fun currentUser(): Int {
        val value = read("am get-current-user").trim()
        requireCheck(Regex("0|[1-9][0-9]{0,4}").matches(value), "The current TV user could not be verified.")
        return value.toInt().also { requireCheck(it in 0..21_474, "The current TV user is unsupported.") }
    }

    private data class PackageState(val appId: Int, val persistent: Boolean, val stopped: Boolean)

    private fun packageState(user: Int): PackageState {
        val dump = read("dumpsys package $packageName")
        // Updated preinstalled apps can also appear under "Hidden system packages".
        // Only the active Packages section describes the installed running version.
        val activeSections = Regex("(?ms)^Packages:[ \\t]*\\r?\\n(.*?)(?=^[^ \\t\\r\\n]|\\z)").findAll(dump).toList()
        requireCheck(activeSections.size == 1, "The active installed-app section could not be verified.")
        val active = activeSections.single().groupValues[1]
        val headers = Regex("(?m)^[ \\t]*Package \\[${Regex.escape(packageName)}\\] \\([^\\r\\n]+\\):[ \\t]*$").findAll(active).toList()
        requireCheck(headers.size == 1, "The selected app's installed state could not be verified.")
        val section = active.substring(headers.single().range.last + 1)
            .split(Regex("(?m)^[ \\t]*Package \\["), limit = 2).first()
        val appIds = Regex("(?m)^[ \\t]*(?:userId|appId)=([0-9]+)[ \\t]*$").findAll(section).toList()
        requireCheck(appIds.size == 1, "The selected app's identity could not be verified.")
        val appId = appIds.single().groupValues[1].toIntOrNull()
            ?: throw IllegalStateException("The app identity is invalid.")
        val userLines = Regex("(?m)^[ \\t]*User $user:[ \\t]+[^\\r\\n]*").findAll(section).toList()
        requireCheck(userLines.size == 1, "The selected app is unavailable for this TV user.")
        val details = userLines.single().value
        fun field(name: String): String? = Regex("\\b$name=([^\\s]+)").findAll(details).toList()
            .takeIf { it.size == 1 }?.single()?.groupValues?.get(1)
        requireCheck(field("installed") == "true" && field("hidden") == "false" && field("suspended") == "false" &&
            field("enabled") in setOf("0", "1"), "The selected app is not installed and enabled for this user.")
        val stopped = field("stopped")
        requireCheck(stopped in setOf("true", "false"), "The app's stopped state could not be read.")
        val flags = Regex("(?m)^[ \\t]*flags=\\[([^\\r\\n]*)\\][ \\t]*$").findAll(section).toList()
        requireCheck(flags.size == 1, "The selected app's system flags could not be verified.")
        return PackageState(appId, Regex("\\bPERSISTENT\\b").containsMatchIn(flags.single().groupValues[1]), stopped == "true")
    }

    private fun launchable(user: Int): Boolean = listOf("LEANBACK_LAUNCHER", "LAUNCHER").any { category ->
        component(read("cmd package resolve-activity --brief --user $user -a android.intent.action.MAIN -c android.intent.category.$category -p $packageName"))
            ?.substringBefore('/') == packageName
    }

    private fun component(output: String): String? {
        if (commandError(output)) return null
        val components = output.lineSequence().map(String::trim)
            .filter { Regex("[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+").matches(it) }.toList()
        return components.singleOrNull()
    }

    private fun read(command: String): String {
        checkCancelled()
        return try {
            shell.run(command).also { requireCheck(it.length <= 1_048_576, "The TV returned an oversized response.") }
        } catch (failure: Exception) {
            checkCancelled()
            throw IllegalStateException("The local debugging connection was interrupted.", failure)
        }
    }

    private fun commandError(output: String) = Regex(
        "(?im)^\\s*(?:error(?::|\\s)|exception(?::|\\s)|securityexception(?::|\\s)|java\\.[\\w.]*exception(?::|\\s)|permission denial|unknown command|failure(?:\\s|\\[|:))"
    ).containsMatchIn(output)
    private fun requireCheck(condition: Boolean, message: String) { check(condition) { message } }
    private fun checkCancelled() { if (cancelled()) throw Cancelled() }
    private class Cancelled : Exception()
}
