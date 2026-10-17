package fi.jommi.rivi

/** The caller supplies an authenticated, local ADB shell; an application UID is insufficient. */
internal fun interface RestoreShell {
    fun run(command: String): String
}

internal data class RestorePackage(val name: String, val previousState: Int, val managedState: Int = 3)

internal data class StockRestorePlan(
    val deviceSerial: String,
    val fingerprint: String,
    val stockHome: String,
    val packages: List<RestorePackage>,
    val dnsMode: String?,
    val dnsSpecifier: String?,
    val managedDnsMode: String?,
    val managedDnsSpecifier: String?
) {
    fun validate() {
        require(deviceSerial.isNotBlank() && deviceSerial.length <= 128 && deviceSerial.none(Char::isISOControl)) {
            "The saved TV identity is missing or invalid."
        }
        require(fingerprint.isNotBlank() && fingerprint.length <= 1024 && fingerprint.none(Char::isISOControl)) {
            "The saved TV software identity is missing or invalid."
        }
        require(stockHome == StockRestoreEngine.STOCK_HOME) { "The saved stock Home component is unsupported." }
        val names = packages.map { it.name }
        require(names.isNotEmpty() && names.toSet().size == names.size &&
            names.all { it in StockRestoreEngine.ALLOWED_PACKAGES } && StockRestoreEngine.STOCK_PACKAGE in names) {
            "The restore record must contain only recorded setup apps, including Google TV Home, without duplicates."
        }
        require(packages.all { it.previousState in 0..4 && it.managedState == 3 }) {
            "The saved app states are invalid."
        }
        require(packages.single { it.name == StockRestoreEngine.STOCK_PACKAGE }.previousState in 0..1) {
            "The saved stock Home state must be enabled."
        }
        require(dnsMode in setOf(null, "off", "opportunistic", "hostname")) { "The saved DNS mode is invalid." }
        require(validDnsHost(dnsSpecifier)) { "The saved DNS hostname is invalid." }
        require(dnsMode != "hostname" || dnsSpecifier != null) { "The saved DNS hostname is missing." }
        require(managedDnsMode == "hostname" && managedDnsSpecifier == "dns.adguard-dns.com") {
            "The managed DNS record does not match this TV setup."
        }
    }

    private fun validDnsHost(value: String?): Boolean = value == null ||
        (value.length in 1..253 && value.split('.').all {
            it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' }
        })
}

internal data class StockRestoreSnapshot(
    val currentHome: String,
    val packageStates: Map<String, Int>,
    val dnsMode: String?,
    val dnsSpecifier: String?
)

internal data class StockRestoreCheckpoint(
    val stage: String,
    val completedSteps: List<String>,
    val changesMade: Boolean
)

internal data class StockRestoreResult(
    val success: Boolean,
    val message: String,
    val changesMade: Boolean,
    val stockHomeActive: Boolean,
    val completedSteps: List<String>
)

internal class StockRestoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A narrow undo operation for the recorded setup, never a general-purpose shell runner.
 * No writes occur in preflight. The UI owns the two confirmations; restore repeats all
 * preflight checks immediately before writing and can require the exact approved state.
 *
 * A failed attempt deliberately keeps already-restored apps enabled. Rolling back by
 * disabling Home can strand the TV. Each completed write is verified, and a later
 * attempt accepts a mixture of original and managed values so recovery is retryable.
 */
internal class StockRestoreEngine(plan: StockRestorePlan, private val shell: RestoreShell) {
    // Do not let a mutable import list alter the commands after validation.
    private val plan = plan.copy(packages = plan.packages.toList()).also { it.validate() }

    fun preflight(): StockRestoreSnapshot {
        requireCheck(read("id -u").trim() == "2000", "An authorized Android debugging connection is required.")
        requireCheck(read("getprop ro.serialno").trim() == plan.deviceSerial, "This restore record belongs to a different TV.")
        requireCheck(read("getprop ro.build.fingerprint").trim() == plan.fingerprint,
            "The TV software has changed since this restore record was saved. Create a new verified recovery record.")
        requireCheck(read("am get-current-user").trim() == "0", "Stock restore is only available for the original TV user.")
        val states = linkedMapOf<String, Int>()
        for (entry in plan.packages) {
            val state = packageState(entry.name, checkStockComponent = entry.name == STOCK_PACKAGE)
            requireCheck(state == entry.managedState || state == entry.previousState,
                "${entry.name} has changed outside the recorded setup. No restore was started.")
            states[entry.name] = state
        }
        val dnsMode = setting("private_dns_mode")
        val dnsSpecifier = setting("private_dns_specifier")
        requireCheck(dnsMode == plan.dnsMode || dnsMode == plan.managedDnsMode,
            "Private DNS mode has changed outside the recorded setup. No restore was started.")
        requireCheck(dnsSpecifier == plan.dnsSpecifier || dnsSpecifier == plan.managedDnsSpecifier,
            "Private DNS hostname has changed outside the recorded setup. No restore was started.")
        val home = currentHome()
        requireCheck(home == canonicalHome(PRETT_HOME) || home == canonicalHome(STOCK_HOME),
            "The default Home app has changed. Open prettTV as the default Home before retrying.")
        return StockRestoreSnapshot(home, states.toMap(), dnsMode, dnsSpecifier)
    }

    fun restore(
        approvedSnapshot: StockRestoreSnapshot? = null,
        progress: (String) -> Unit = {},
        checkpoint: (StockRestoreCheckpoint) -> Unit = {}
    ): StockRestoreResult {
        val completed = arrayListOf<String>()
        var changesMade = false
        var stage = "Checking recovery prerequisites"
        fun record(next: String) {
            stage = next
            checkpoint(StockRestoreCheckpoint(stage, completed.toList(), changesMade))
            // A detached screen must not interrupt a recovery already in progress.
            runCatching { progress(stage) }
        }
        fun mutate(command: String) {
            // A lost connection can make a write's outcome unknown. Report possible
            // changes even if the transport throws before its acknowledgement arrives.
            changesMade = true
            val output = read(command)
            requireCheck(!hasCommandError(output), "Android could not complete: $stage")
        }
        try {
            val snapshot = preflight()
            requireCheck(approvedSnapshot == null || approvedSnapshot == snapshot,
                "The TV changed after you reviewed the restore. Review and confirm it again.")
            record("Recovery checks passed")
            // Restore Home's package first, but leave the selected Home untouched until
            // every package and DNS setting has been independently read back.
            val ordered = plan.packages.sortedBy { if (it.name == STOCK_PACKAGE) 0 else 1 }
            for (entry in ordered) {
                record("Restoring ${entry.name}")
                val now = packageState(entry.name)
                requireCheck(now == snapshot.packageStates[entry.name] || now == entry.previousState,
                    "${entry.name} changed during recovery. Retry after checking its settings.")
                if (now != entry.previousState) mutate(packageCommand(entry))
                requireCheck(packageState(entry.name) == entry.previousState,
                    "The original state of ${entry.name} could not be verified.")
                completed.add("package:${entry.name}")
                record("Restored ${entry.name}")
            }
            // When restoring a strict provider, save its hostname before selecting
            // hostname mode. Otherwise stop strict DNS first, then clear its hostname.
            val dns = if (plan.dnsMode == "hostname") listOf(
                Triple("private_dns_specifier", snapshot.dnsSpecifier, plan.dnsSpecifier),
                Triple("private_dns_mode", snapshot.dnsMode, plan.dnsMode)
            ) else listOf(
                Triple("private_dns_mode", snapshot.dnsMode, plan.dnsMode),
                Triple("private_dns_specifier", snapshot.dnsSpecifier, plan.dnsSpecifier)
            )
            for ((key, before, desired) in dns) {
                record("Restoring $key")
                val now = setting(key)
                requireCheck(now == before || now == desired, "Private DNS changed during recovery. Review it before retrying.")
                if (now != desired) mutate(if (desired == null) "settings delete global $key"
                    else "settings put global $key $desired")
                requireCheck(setting(key) == desired, "The original Private DNS settings could not be verified.")
                completed.add("setting:$key")
                record("Restored $key")
            }
            // Detect state drift before the last operation which changes the user's UI.
            requireCheck(plan.packages.all { packageState(it.name) == it.previousState } &&
                setting("private_dns_mode") == plan.dnsMode && setting("private_dns_specifier") == plan.dnsSpecifier,
                "A restored setting changed before the Home switch. Recovery can be retried.")
            val homeBeforeSwitch = currentHome()
            requireCheck(homeBeforeSwitch == snapshot.currentHome || homeBeforeSwitch == canonicalHome(STOCK_HOME),
                "The default Home changed during recovery. It was left untouched.")
            record("Switching to Google TV Home")
            if (homeBeforeSwitch != canonicalHome(STOCK_HOME)) {
                mutate("cmd package set-home-activity --user 0 $STOCK_HOME")
            }
            requireCheck(currentHome() == canonicalHome(STOCK_HOME), "Google TV could not be verified as the default Home.")
            completed.add("home:default")
            record("Opening Google TV Home")
            val launch = read("am start -W --user 0 -n $STOCK_HOME")
            requireCheck(!hasCommandError(launch) && Regex("(?m)^Status:\\s*ok\\s*$").containsMatchIn(launch),
                "Google TV is selected as Home, but its screen could not be verified as started.")
            requireCheck(currentHome() == canonicalHome(STOCK_HOME), "The default Home changed while Google TV was opening.")
            completed.add("home:launched")
            record("Stock troubleshooting restore complete")
            return StockRestoreResult(true,
                "Google TV Home, the recorded app states and the original DNS settings are restored. Your streaming apps and logins are retained.",
                changesMade, true, completed.toList())
        } catch (failure: Exception) {
            val active = runCatching { currentHome() == canonicalHome(STOCK_HOME) }.getOrDefault(false)
            val reason = failure.message?.take(500) ?: "The debugging connection was interrupted."
            val message = if (changesMade) "Recovery stopped during $stage. Some settings may already be restored. $reason Retry to complete recovery."
                else "Recovery was not started. $reason"
            runCatching { checkpoint(StockRestoreCheckpoint("Recovery incomplete: $stage", completed.toList(), changesMade)) }
            return StockRestoreResult(false, message, changesMade, active, completed.toList())
        }
    }

    private fun packageState(name: String, checkStockComponent: Boolean = false): Int {
        requireCheck(name in ALLOWED_PACKAGES, "An unsupported package was requested.")
        val dump = read("dumpsys package $name")
        val packageHeader = Regex("(?m)^\\s*Package \\[${Regex.escape(name)}\\].*$").find(dump)
            ?: throw StockRestoreException("The required app $name is missing.")
        val section = dump.substring(packageHeader.range.last + 1)
            .split(Regex("(?m)^\\s*Package \\["), limit = 2).first()
        val userMatch = Regex("(?m)^[ \\t]*User 0:[ \\t]+[^\\r\\n]*").find(section)
            ?: throw StockRestoreException("The original user's state for $name is unavailable.")
        val user = userMatch.value
        requireCheck(Regex("\\binstalled=true\\b").containsMatchIn(user), "The required app $name is not installed for the original user.")
        requireCheck(!Regex("\\b(?:hidden|suspended)=true\\b").containsMatchIn(user),
            "$name is hidden or suspended outside this setup. Its state was left untouched.")
        val state = Regex("\\benabled=([0-4])\\b").find(user)?.groupValues?.get(1)?.toInt()
            ?: throw StockRestoreException("The enabled state for $name is unavailable.")
        if (checkStockComponent) {
            requireCheck(dump.contains(STOCK_HOME) || dump.contains(canonicalHome(STOCK_HOME)),
                "The saved Google TV Home activity is missing.")
            val userDetails = section.substring(userMatch.range.last + 1)
                .split(Regex("(?m)^[ \\t]*User [0-9]+:"), limit = 2).first()
            requireCheck(!stockComponentDisabled(userDetails),
                "Google TV Home was disabled separately for the original user. Its state was left untouched.")
        }
        return state
    }

    private fun stockComponentDisabled(userDetails: String): Boolean {
        var disabledIndent: Int? = null
        for (line in userDetails.lineSequence()) {
            val value = line.trim()
            if (value.isEmpty()) continue
            val indent = line.indexOfFirst { !it.isWhitespace() }
            if (value == "disabledComponents:") {
                disabledIndent = indent
                continue
            }
            val listIndent = disabledIndent ?: continue
            if (indent <= listIndent) {
                disabledIndent = null
                continue
            }
            val component = if ('/' in value) value else "$STOCK_PACKAGE/$value"
            if (canonicalHome(component) == canonicalHome(STOCK_HOME)) return true
        }
        return false
    }

    private fun setting(key: String): String? {
        requireCheck(key == "private_dns_mode" || key == "private_dns_specifier", "An unsupported setting was requested.")
        val value = read("settings get global $key").trim()
        requireCheck(value.isNotEmpty() && '\n' !in value && '\r' !in value && !hasCommandError(value),
            "The current Private DNS settings could not be read.")
        return value.takeUnless { it == "null" }
    }

    private fun currentHome(): String {
        val result = read("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN -c android.intent.category.HOME")
        requireCheck(!hasCommandError(result), "The current Home app could not be read.")
        val components = result.lineSequence().map { it.trim() }
            .filter { Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(it) }.toList()
        requireCheck(components.size == 1, "The TV does not have one verified default Home app.")
        return canonicalHome(components.single())
    }

    private fun packageCommand(entry: RestorePackage): String {
        val action = when (entry.previousState) {
            0 -> "default-state"
            1 -> "enable"
            2 -> "disable"
            3 -> "disable-user"
            4 -> "disable-until-used"
            else -> throw StockRestoreException("The saved app state is invalid.")
        }
        return "pm $action --user 0 ${entry.name}"
    }

    private fun read(command: String): String = try {
        shell.run(command).also { requireCheck(it.length <= 2 * 1024 * 1024, "The TV returned an unexpectedly large response.") }
    } catch (failure: StockRestoreException) {
        throw failure
    } catch (failure: Exception) {
        throw StockRestoreException("The authorized debugging connection was interrupted.", failure)
    }

    private fun requireCheck(condition: Boolean, message: String) {
        if (!condition) throw StockRestoreException(message)
    }

    private fun canonicalHome(component: String): String {
        val parts = component.split('/', limit = 2)
        return if (parts.size == 2 && parts[1].startsWith('.')) "${parts[0]}/${parts[0]}${parts[1]}" else component
    }

    private fun hasCommandError(output: String): Boolean = Regex(
        "(?im)^\\s*(?:error(?::|\\s)|exception(?::|\\s)|securityexception(?::|\\s)|java\\.[\\w.]*exception(?::|\\s)|permission denial|unknown command|failure(?:\\s|\\[|:))"
    ).containsMatchIn(output)

    companion object {
        const val STOCK_PACKAGE = "com.google.android.apps.tv.launcherx"
        const val STOCK_HOME = "$STOCK_PACKAGE/.home.HomeActivity"
        const val PRETT_HOME = "dev.zane.pretttv/fi.jommi.rivi.MainActivity"
        val ALLOWED_PACKAGES: Set<String> = setOf(
            "com.tcl.tv.plus", "com.tcl.browser", "com.tcl.smartalexa", "com.tcl.esticker",
            "com.tcl.interactive", "com.tcl.logkit", "com.google.android.feedback", "com.tcl.channelplus",
            "com.google.android.tungsten.setupwraith", STOCK_PACKAGE
        )
    }
}
