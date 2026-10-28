package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppForceStopEngineTest {
    private val identity = AppForceStopIdentity("test-tv", "vendor/test:14/test/release-keys")
    private val target = "com.wbd.stream"
    private val own = "dev.zane.pretttv"

    private inner class Tv : AppForceStopShell {
        val commands = arrayListOf<String>()
        val writes = arrayListOf<String>()
        var uid = "2000"
        var serial = identity.serial
        var fingerprint = identity.fingerprint
        var currentUser = "0"
        var stateUser = 0
        var appId = 10243
        var appIdField = "appId"
        var installed = true
        var enabled = 0
        var hidden = false
        var suspended = false
        var stopped = false
        var flags = "SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA"
        var stoppedOverride: String? = null
        var duplicateUser = false
        var missingPackage = false
        var hiddenSystemCopy = false
        var missingFlags = false
        var home = "$own/fi.jommi.rivi.MainActivity"
        var leanback = "$target/.MainActivity"
        var launcher = "No activity found"
        var stopReply = ""
        var ignoreStop = false
        var throwOnStop = false
        var throwAfterStop = false
        var otherUserFirst = false
        var beforeCommand: ((String) -> Unit)? = null
        var outputOverride: ((String) -> String?)? = null

        override fun run(command: String): String {
            commands += command
            beforeCommand?.invoke(command)
            outputOverride?.invoke(command)?.let { return it }
            return when {
                command == "id -u" -> "$uid\n"
                command == "getprop ro.serialno" -> "$serial\n"
                command == "getprop ro.build.fingerprint" -> "$fingerprint\n"
                command == "am get-current-user" -> "$currentUser\n"
                command == "dumpsys package $target" -> dump()
                command.startsWith("cmd package resolve-activity") && command.endsWith("category.HOME") -> "$home\n"
                command.startsWith("cmd package resolve-activity") && "category.LEANBACK_LAUNCHER" in command -> "$leanback\n"
                command.startsWith("cmd package resolve-activity") && "category.LAUNCHER" in command -> "$launcher\n"
                command.startsWith("am force-stop ") -> {
                    writes += command
                    if (throwOnStop) throw IOException("disconnected")
                    if (!ignoreStop) stopped = true
                    if (throwAfterStop) throw IOException("lost acknowledgement")
                    stopReply
                }
                else -> error("Unexpected shell command $command")
            }
        }

        private fun dump(): String {
            if (missingPackage) return "Unable to find package: $target\n"
            val user = "    User $stateUser: installed=$installed hidden=$hidden suspended=$suspended stopped=${stoppedOverride ?: stopped.toString()} enabled=$enabled\n"
            val other = "    User ${if (stateUser == 10) 0 else 10}: installed=true hidden=false suspended=false stopped=true enabled=0\n"
            return "Packages:\n  Package [$target] (12abcd):\n    $appIdField=$appId\n" +
                (if (missingFlags) "" else "    flags=[ $flags ]\n") +
                (if (otherUserFirst) other + user else user + other) +
                (if (duplicateUser) user else "") +
                (if (hiddenSystemCopy) "Hidden system packages:\n  Package [$target] (old):\n    appId=10001\n    flags=[ SYSTEM ]\n    User 0: installed=true hidden=false suspended=false stopped=false enabled=0\n" else "") +
                "Queries:\n  system apps queryable: false\n"
        }
    }

    private fun engine(tv: Tv, name: String = target, user: Int = 0,
                       cancelled: () -> Boolean = { false }, revalidate: () -> Boolean = { true },
                       before: () -> Unit = {}) = AppForceStopEngine(identity, name, own, user, tv, cancelled, revalidate, before)

    private fun blocked(tv: Tv) {
        val result = engine(tv).stop()
        assertFalse(result.message, result.success)
        assertFalse(result.mutationMayHaveOccurred)
        assertTrue(tv.writes.toString(), tv.writes.isEmpty())
    }

    @Test fun forceStopsExactlyOnePreinstalledStreamingAppAndVerifiesIt() {
        val tv = Tv()
        val result = engine(tv).stop()
        assertTrue(result.message, result.success)
        assertTrue(result.mutationMayHaveOccurred)
        assertEquals(listOf("am force-stop --user 0 $target"), tv.writes)
        assertEquals("dumpsys package $target", tv.commands.last())
        assertFalse(tv.commands.any { "clear" in it || "uninstall" in it || "am start" in it || "kill-all" in it })
    }

    @Test fun usesActualNonzeroUserAndItsStoppedState() {
        val tv = Tv().apply { currentUser = "10"; stateUser = 10; otherUserFirst = true }
        val result = engine(tv, user = 10).stop()
        assertTrue(result.message, result.success)
        assertEquals(listOf("am force-stop --user 10 $target"), tv.writes)
    }

    @Test fun userSwitchBeforeWritePreventsMutation() {
        val tv = Tv()
        var reads = 0
        tv.beforeCommand = { if (it == "am get-current-user" && ++reads == 2) tv.currentUser = "10" }
        blocked(tv)
    }

    @Test fun userSwitchAfterWriteIsNotReportedAsSuccess() {
        val tv = Tv()
        tv.beforeCommand = { if (it == "am get-current-user" && tv.writes.isNotEmpty()) tv.currentUser = "10" }
        val result = engine(tv).stop()
        assertFalse(result.success)
        assertTrue(result.mutationMayHaveOccurred)
    }

    @Test fun rejectsAmbiguousAndInjectedUsersWithoutMutation() {
        listOf("-1", "all", "current", "0; reboot", "0\n10", "000", "99999").forEach { user ->
            blocked(Tv().apply { currentUser = user })
        }
    }

    @Test fun checksShellIdentityAndSavedTvBeforeMutation() {
        blocked(Tv().apply { uid = "0" })
        blocked(Tv().apply { uid = "10145" })
        blocked(Tv().apply { serial = "other-tv" })
        blocked(Tv().apply { fingerprint = "updated-build" })
    }

    @Test fun identityDriftBeforeWriteIsRejected() {
        val tv = Tv()
        var reads = 0
        tv.beforeCommand = { if (it == "getprop ro.serialno" && ++reads == 2) tv.serial = "other-tv" }
        blocked(tv)
    }

    @Test fun injectionAndProtectedPackagesNeverReachShell() {
        listOf("$target; reboot", "$target\nreboot", "$target'", "-all", "all", own,
            "dev.zane.pretttv.stability", "com.google.android.gms", "com.android.settings",
            "com.google.android.apps.tv.launcherx", "com.android.providers.media").forEach { name ->
            val tv = Tv()
            val result = engine(tv, name).stop()
            assertFalse(name, result.success)
            assertTrue(name, tv.commands.isEmpty())
        }
    }

    @Test fun rejectsPersistentCoreAndHomeApps() {
        blocked(Tv().apply { appId = 1000 })
        blocked(Tv().apply { flags += " PERSISTENT" })
        blocked(Tv().apply { home = "$target/.HomeActivity" })
    }

    @Test fun eligibilityIsCheckedAgainImmediatelyBeforeWrite() {
        val tv = Tv()
        var checks = 0
        val result = engine(tv, revalidate = { ++checks == 1 }).stop()
        assertFalse(result.success)
        assertEquals(2, checks)
        assertTrue(tv.writes.isEmpty())
    }

    @Test fun rejectsMissingDisabledHiddenAndSuspendedApps() {
        blocked(Tv().apply { missingPackage = true })
        blocked(Tv().apply { installed = false })
        blocked(Tv().apply { enabled = 3 })
        blocked(Tv().apply { hidden = true })
        blocked(Tv().apply { suspended = true })
        blocked(Tv().apply { missingFlags = true })
    }

    @Test fun handlesUpdatedSystemAppAndLegacyUserIdDump() {
        val tv = Tv().apply { appIdField = "userId"; hiddenSystemCopy = true }
        val result = engine(tv).stop()
        assertTrue(result.message, result.success)
    }

    @Test fun doesNotAcceptAnotherUsersStoppedStateOrMalformedState() {
        val ignored = Tv().apply { ignoreStop = true; otherUserFirst = true }
        val result = engine(ignored).stop()
        assertFalse(result.success)
        assertTrue(result.mutationMayHaveOccurred)
        blocked(Tv().apply { duplicateUser = true })
        blocked(Tv().apply { stoppedOverride = "true stopped=false" })
        blocked(Tv().apply { stateUser = 12 })
    }

    @Test fun cannotStopAnAppWithoutItsOwnLaunchActivity() {
        blocked(Tv().apply { leanback = "No activity found" })
        blocked(Tv().apply { leanback = "android/com.android.internal.app.ResolverActivity" })
        blocked(Tv().apply { leanback = "$target/.One\n$target/.Two" })
        val tv = Tv().apply { leanback = "No activity found"; launcher = "$target/.MobileActivity" }
        assertTrue(engine(tv).stop().success)
    }

    @Test fun noHomeResolutionMeansNoMutation() {
        blocked(Tv().apply { home = "No activity found" })
    }

    @Test fun blankStopReplyIsNotSuccessWithoutAuthoritativeStoppedFlag() {
        val tv = Tv().apply { ignoreStop = true }
        val result = engine(tv).stop()
        assertFalse(result.success)
        assertTrue(result.mutationMayHaveOccurred)
        assertTrue(tv.writes.size == 1)
    }

    @Test fun permissionDenialNeverBecomesSuccessEvenIfAlreadyStopped() {
        val tv = Tv().apply { stopped = true; stopReply = "java.lang.SecurityException: Permission Denial" }
        assertFalse(engine(tv).stop().success)
    }

    @Test fun connectionLossAtWriteHasUnknownOutcomeAndIsNeverRetried() {
        listOf(false, true).forEach { applied ->
            val tv = Tv().apply { throwOnStop = !applied; throwAfterStop = applied }
            val result = engine(tv).stop()
            assertFalse(result.success)
            assertTrue(result.mutationMayHaveOccurred)
            assertEquals(1, tv.writes.size)
        }
    }

    @Test fun cancellationBeforeMutationDoesNotSendAnything() {
        val tv = Tv()
        var cancel = false
        tv.beforeCommand = { if (it.startsWith("cmd package resolve-activity")) cancel = true }
        val result = engine(tv, cancelled = { cancel }).stop()
        assertTrue(result.cancelled)
        assertFalse(result.success)
        assertFalse(result.mutationMayHaveOccurred)
        assertTrue(tv.writes.isEmpty())
    }

    @Test fun cancellationAfterMutationDoesNotClaimUnchanged() {
        val tv = Tv()
        var cancel = false
        tv.beforeCommand = { if (it.startsWith("am force-stop")) cancel = true }
        val result = engine(tv, cancelled = { cancel }).stop()
        assertTrue(result.cancelled)
        assertTrue(result.mutationMayHaveOccurred)
        assertFalse(result.success)
        assertEquals(1, tv.writes.size)
        assertTrue(result.message.contains("may already"))
    }

    @Test fun oversizedReplyAndTransportFailureBeforeWriteAreHarmless() {
        blocked(Tv().apply { outputOverride = { if (it == "id -u") "x".repeat(1_048_577) else null } })
        blocked(Tv().apply { beforeCommand = { throw IOException("gone") } })
    }

    @Test fun packageReplacementAfterWriteCannotProduceFalseSuccess() {
        val tv = Tv()
        tv.beforeCommand = { if (it.startsWith("dumpsys package") && tv.writes.isNotEmpty()) tv.appId = 10244 }
        assertFalse(engine(tv).stop().success)
    }

    @Test fun policyPreservesPreinstalledStreamingButBlocksHomesAndPersistentServices() {
        assertTrue(AppForceStopPolicy.allowed("com.netflix.ninja", own, 10101, false, false))
        assertFalse(AppForceStopPolicy.allowed("app.example.launcher", own, 10101, false, true))
        assertFalse(AppForceStopPolicy.allowed("app.example.service", own, 10101, true, false))
        assertFalse(AppForceStopPolicy.allowed("app.example.core", own, 1000, false, false))
    }
}
