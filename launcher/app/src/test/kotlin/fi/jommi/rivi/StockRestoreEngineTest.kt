package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class StockRestoreEngineTest {
    private fun plan() = StockRestorePlan(
        deviceSerial = "test-tv", fingerprint = "vendor/test/device:11/test/release-keys",
        stockHome = StockRestoreEngine.STOCK_HOME,
        packages = StockRestoreEngine.ALLOWED_PACKAGES.map { RestorePackage(it, 0) },
        dnsMode = null, dnsSpecifier = null,
        managedDnsMode = "hostname", managedDnsSpecifier = "dns.adguard-dns.com"
    )

    private class FakeTv(private val saved: StockRestorePlan) : RestoreShell {
        val commands = arrayListOf<String>()
        val writes = arrayListOf<String>()
        val states = saved.packages.associate { it.name to it.managedState }.toMutableMap()
        var uid = "2000"
        var serial = saved.deviceSerial
        var fingerprint = saved.fingerprint
        var user = "0"
        var home = StockRestoreEngine.PRETT_HOME
        var dnsMode = saved.managedDnsMode
        var dnsSpecifier = saved.managedDnsSpecifier
        var missingPackage: String? = null
        var notInstalledPackage: String? = null
        var hiddenPackage: String? = null
        var suspendedPackage: String? = null
        var stockComponentPresent = true
        var stockUserZeroDetails = ""
        var stockOtherUserDetails = ""
        var otherUserBeforeZero = false
        var failAtMutation = 0
        var ignoreAtMutation = 0
        var applyBeforeFailure = false
        var launchOutput = "Starting: Intent { cmp=${saved.stockHome} }\nStatus: ok\nActivity: ${saved.stockHome}\nComplete\n"
        var beforeCommand: ((String) -> Unit)? = null

        override fun run(command: String): String {
            commands.add(command)
            beforeCommand?.invoke(command)
            return when {
                command == "id -u" -> "$uid\n"
                command == "getprop ro.serialno" -> "$serial\n"
                command == "getprop ro.build.fingerprint" -> "$fingerprint\n"
                command == "am get-current-user" -> "$user\n"
                command.startsWith("dumpsys package ") -> {
                    val name = command.removePrefix("dumpsys package ")
                    if (name == missingPackage) "Unable to find package: $name\n" else {
                        val activity = if (name == StockRestoreEngine.STOCK_PACKAGE && stockComponentPresent)
                            "Activity Resolver Table:\n  123456 ${saved.stockHome} filter 123\n" else ""
                        val originalUser = "    User 0: installed=${name != notInstalledPackage} hidden=${name == hiddenPackage} suspended=${name == suspendedPackage} enabled=${states.getValue(name)} stopped=false\n"
                        val users = if (name == StockRestoreEngine.STOCK_PACKAGE) {
                            if (otherUserBeforeZero) stockOtherUserDetails + originalUser + stockUserZeroDetails
                            else originalUser + stockUserZeroDetails + stockOtherUserDetails
                        } else originalUser
                        "${activity}Packages:\n  Package [$name] (123abc):\n$users"
                    }
                }
                command == "settings get global private_dns_mode" -> "${dnsMode ?: "null"}\n"
                command == "settings get global private_dns_specifier" -> "${dnsSpecifier ?: "null"}\n"
                command.startsWith("cmd package resolve-activity ") -> "priority=0 preferredOrder=0 match=0x108000\n$home\n"
                else -> mutate(command)
            }
        }

        private fun mutate(command: String): String {
            writes.add(command)
            val number = writes.size
            if (number == failAtMutation && !applyBeforeFailure) throw IOException("Test connection lost before acknowledgement")
            if (number != ignoreAtMutation) when {
                command.startsWith("pm ") -> {
                    val pieces = command.split(' ')
                    assertEquals("--user", pieces[2])
                    assertEquals("0", pieces[3])
                    states[pieces[4]] = mapOf("default-state" to 0, "enable" to 1, "disable" to 2,
                        "disable-user" to 3, "disable-until-used" to 4).getValue(pieces[1])
                }
                command.startsWith("settings delete global ") -> when (command.substringAfterLast(' ')) {
                    "private_dns_mode" -> dnsMode = null
                    "private_dns_specifier" -> dnsSpecifier = null
                    else -> fail("Unsupported mutation: $command")
                }
                command.startsWith("settings put global ") -> {
                    val pieces = command.split(' ')
                    when (pieces[3]) {
                        "private_dns_mode" -> dnsMode = pieces[4]
                        "private_dns_specifier" -> dnsSpecifier = pieces[4]
                        else -> fail("Unsupported mutation: $command")
                    }
                }
                command == "cmd package set-home-activity --user 0 ${saved.stockHome}" -> home = saved.stockHome
                command == "am start -W --user 0 -n ${saved.stockHome}" -> Unit
                else -> fail("Unsupported mutation: $command")
            }
            if (number == failAtMutation) throw IOException("Test acknowledgement lost after write")
            return if (command.startsWith("am start")) launchOutput else "Success\n"
        }
    }

    @Test fun preflightOnlyReadsAndCapturesTheApprovedState() {
        val saved = plan()
        val tv = FakeTv(saved)
        val snapshot = StockRestoreEngine(saved, tv).preflight()
        assertEquals(StockRestoreEngine.PRETT_HOME, snapshot.currentHome)
        assertEquals(10, snapshot.packageStates.size)
        assertTrue(snapshot.packageStates.values.all { it == 3 })
        assertEquals("hostname", snapshot.dnsMode)
        assertEquals("dns.adguard-dns.com", snapshot.dnsSpecifier)
        assertTrue(tv.writes.isEmpty())
    }

    @Test fun fullRestoreVerifiesPackagesAndDnsBeforeChangingHome() {
        val saved = plan()
        val tv = FakeTv(saved)
        val checkpoints = arrayListOf<StockRestoreCheckpoint>()
        val engine = StockRestoreEngine(saved, tv)
        val result = engine.restore(engine.preflight(), checkpoint = { checkpoints.add(it) })
        assertTrue(result.message, result.success)
        assertTrue(result.changesMade)
        assertTrue(result.stockHomeActive)
        assertEquals("pm default-state --user 0 ${StockRestoreEngine.STOCK_PACKAGE}", tv.writes.first())
        assertEquals(14, tv.writes.size)
        assertTrue(tv.writes.take(10).all { it.startsWith("pm default-state --user 0 ") })
        assertEquals(listOf("settings delete global private_dns_mode", "settings delete global private_dns_specifier"),
            tv.writes.subList(10, 12))
        assertEquals("cmd package set-home-activity --user 0 ${saved.stockHome}", tv.writes[12])
        assertEquals("am start -W --user 0 -n ${saved.stockHome}", tv.writes[13])
        assertTrue(tv.states.values.all { it == 0 })
        assertNull(tv.dnsMode)
        assertNull(tv.dnsSpecifier)
        assertEquals(14, result.completedSteps.size)
        assertEquals("home:launched", result.completedSteps.last())
        assertEquals("Stock troubleshooting restore complete", checkpoints.last().stage)
        assertFalse(tv.commands.any { it.contains("uninstall") || it.contains("clear ") || it.contains("reboot") })
    }

    @Test fun incompatibleIdentityUserOrAuthorityNeverWrites() {
        val cases: List<(FakeTv) -> Unit> = listOf(
            { it.uid = "10123" }, { it.uid = "0" }, { it.serial = "another-tv" },
            { it.fingerprint = "vendor/new/firmware" }, { it.user = "10" }
        )
        for (setup in cases) {
            val saved = plan()
            val tv = FakeTv(saved).also(setup)
            val result = StockRestoreEngine(saved, tv).restore()
            assertFalse(result.success)
            assertFalse(result.changesMade)
            assertTrue(tv.writes.isEmpty())
        }
    }

    @Test fun missingDisabledOrExternallyChangedDependenciesNeverWrite() {
        val cases: List<(FakeTv) -> Unit> = listOf(
            { it.missingPackage = StockRestoreEngine.STOCK_PACKAGE },
            { it.notInstalledPackage = "com.tcl.browser" },
            { it.hiddenPackage = StockRestoreEngine.STOCK_PACKAGE },
            { it.suspendedPackage = StockRestoreEngine.STOCK_PACKAGE },
            { it.stockComponentPresent = false },
            { it.states["com.tcl.browser"] = 2 },
            { it.dnsMode = "off" }, { it.dnsSpecifier = "different.example" },
            { it.home = "com.android.tv.settings/.system.FallbackHome" },
            { it.home = "other.launcher/.Home" }, { it.home = "No activity found" }
        )
        for (setup in cases) {
            val saved = plan()
            val tv = FakeTv(saved).also(setup)
            val result = StockRestoreEngine(saved, tv).restore()
            assertFalse(result.message, result.success)
            assertTrue("Unexpected writes: ${tv.writes}", tv.writes.isEmpty())
        }
    }

    @Test fun changesAfterConfirmationRequireReviewAgain() {
        val saved = plan()
        val tv = FakeTv(saved)
        val engine = StockRestoreEngine(saved, tv)
        val approved = engine.preflight()
        tv.states["com.tcl.browser"] = 0 // A valid but newly changed value still invalidates review.
        val result = engine.restore(approved)
        assertFalse(result.success)
        assertTrue(result.message.contains("Review and confirm"))
        assertTrue(tv.writes.isEmpty())
    }

    @Test fun disabledStockHomeComponentIsCheckedOnlyInsideTheOriginalUserBlock() {
        val saved = plan()
        val stockClass = "${StockRestoreEngine.STOCK_PACKAGE}.home.HomeActivity"
        for (component in listOf(stockClass, ".home.HomeActivity", StockRestoreEngine.STOCK_HOME)) {
            val tv = FakeTv(saved).apply {
                stockUserZeroDetails = "      disabledComponents:\n        $component\n"
                stockOtherUserDetails = "    User 10: installed=true hidden=false suspended=false enabled=0\n" +
                    "      enabledComponents:\n        $stockClass\n"
            }
            val result = StockRestoreEngine(saved, tv).restore()
            assertFalse(result.success)
            assertTrue(result.message.contains("disabled separately"))
            assertTrue(tv.writes.isEmpty())
        }
        for (otherUserFirst in listOf(false, true)) {
            val tv = FakeTv(saved).apply {
                otherUserBeforeZero = otherUserFirst
                stockUserZeroDetails = "      disabledComponents:\n        ${StockRestoreEngine.STOCK_PACKAGE}.OtherActivity\n" +
                    "      enabledComponents:\n        $stockClass\n"
                stockOtherUserDetails = "    User 10: installed=true hidden=true suspended=true enabled=2\n" +
                    "      disabledComponents:\n        $stockClass\n"
            }
            val snapshot = StockRestoreEngine(saved, tv).preflight()
            assertEquals(3, snapshot.packageStates[StockRestoreEngine.STOCK_PACKAGE])
            assertTrue(tv.writes.isEmpty())
        }
    }

    @Test fun interruptedWritesAtEveryStepAreTruthfulAndRetryable() {
        for (afterWrite in listOf(false, true)) for (position in 1..14) {
            val saved = plan()
            val tv = FakeTv(saved).apply {
                failAtMutation = position
                applyBeforeFailure = afterWrite
            }
            val engine = StockRestoreEngine(saved, tv)
            val failed = engine.restore()
            assertFalse("Must not report success at $position/$afterWrite", failed.success)
            assertTrue("Lost acknowledgement may have changed state", failed.changesMade)
            if (position <= 12) {
                assertEquals(StockRestoreEngine.PRETT_HOME, tv.home)
                assertFalse(tv.writes.any { it.startsWith("cmd package set-home-activity") })
            }
            // Never try to disable a restored launcher as a purported rollback.
            assertFalse(tv.writes.any { it.startsWith("pm disable") })
            tv.failAtMutation = 0
            val retried = engine.restore()
            assertTrue("Retry $position/$afterWrite: ${retried.message}", retried.success)
            assertEquals(saved.stockHome, tv.home)
            assertTrue(tv.states.values.all { it == 0 })
            assertNull(tv.dnsMode)
            assertNull(tv.dnsSpecifier)
        }
    }

    @Test fun silentMutationFailuresAreCaughtByReadbackAndDoNotSwitchHomeEarly() {
        for (position in 1..13) {
            val saved = plan()
            val tv = FakeTv(saved).apply { ignoreAtMutation = position }
            val result = StockRestoreEngine(saved, tv).restore()
            assertFalse("Silent ignored write $position", result.success)
            assertEquals(StockRestoreEngine.PRETT_HOME, tv.home)
            assertFalse(tv.writes.any { it.startsWith("am start") })
        }
    }

    @Test fun launchFailureDoesNotClaimCompletionOrDisableEitherLauncher() {
        for (output in listOf("Error: Activity class does not exist\n", "Status: timeout\n", "", "Status: ok\nError: launch failed\n")) {
            val saved = plan()
            val tv = FakeTv(saved).apply { launchOutput = output }
            val result = StockRestoreEngine(saved, tv).restore()
            assertFalse(result.success)
            assertTrue(result.stockHomeActive)
            assertFalse(result.completedSteps.contains("home:launched"))
            assertEquals(0, tv.states[StockRestoreEngine.STOCK_PACKAGE])
            assertFalse(tv.writes.any { it.contains("disable") || it.contains("uninstall") })
        }
    }

    @Test fun repeatedRestoreSkipsAlreadyRestoredSettingsAndVerifiesHomeLaunchAgain() {
        val saved = plan()
        val tv = FakeTv(saved)
        val engine = StockRestoreEngine(saved, tv)
        assertTrue(engine.restore().success)
        tv.writes.clear()
        val result = engine.restore()
        assertTrue(result.success)
        assertFalse(result.changesMade)
        assertEquals(listOf("am start -W --user 0 -n ${saved.stockHome}"), tv.writes)
    }

    @Test fun strictDnsRestoresProviderBeforeEnablingStrictMode() {
        val saved = plan().copy(dnsMode = "hostname", dnsSpecifier = "dns.example.org")
        val tv = FakeTv(saved).apply { dnsMode = "hostname" }
        val result = StockRestoreEngine(saved, tv).restore()
        assertTrue(result.message, result.success)
        assertEquals("dns.example.org", tv.dnsSpecifier)
        assertEquals("hostname", tv.dnsMode)
        assertTrue(tv.writes.contains("settings put global private_dns_specifier dns.example.org"))
        assertFalse(tv.writes.any { it.endsWith(" null") })
    }

    @Test fun savedExplicitPackageStatesAreRestoredWithoutBroadEnableAll() {
        val base = plan()
        val saved = base.copy(packages = base.packages.mapIndexed { i, entry ->
            entry.copy(previousState = if (entry.name == StockRestoreEngine.STOCK_PACKAGE) 0 else i % 5)
        })
        val tv = FakeTv(saved)
        val result = StockRestoreEngine(saved, tv).restore()
        assertTrue(result.message, result.success)
        for (entry in saved.packages) assertEquals(entry.previousState, tv.states[entry.name])
    }

    @Test fun externalStateChangeDuringRestoreStopsBeforeOverwritingItOrSwitchingHome() {
        val saved = plan()
        val tv = FakeTv(saved)
        var browserReads = 0
        tv.beforeCommand = { command ->
            if (command == "dumpsys package com.tcl.browser" && ++browserReads == 2) tv.states["com.tcl.browser"] = 2
        }
        val result = StockRestoreEngine(saved, tv).restore()
        assertFalse(result.success)
        assertEquals(2, tv.states["com.tcl.browser"])
        assertEquals(StockRestoreEngine.PRETT_HOME, tv.home)
        assertFalse(tv.writes.any { it.endsWith(" com.tcl.browser") })
    }

    @Test fun failureToPersistInitialCheckpointPreventsAllWrites() {
        val saved = plan()
        val tv = FakeTv(saved)
        val result = StockRestoreEngine(saved, tv).restore(checkpoint = { throw IOException("Storage full") })
        assertFalse(result.success)
        assertFalse(result.changesMade)
        assertTrue(tv.writes.isEmpty())
    }

    @Test fun failureToPersistAfterAWriteReportsPartialRecoveryAndKeepsHomeUsable() {
        val saved = plan()
        val tv = FakeTv(saved)
        val result = StockRestoreEngine(saved, tv).restore(checkpoint = {
            if (it.completedSteps.isNotEmpty()) throw IOException("Storage full")
        })
        assertFalse(result.success)
        assertTrue(result.changesMade)
        assertEquals(0, tv.states[StockRestoreEngine.STOCK_PACKAGE])
        assertEquals(StockRestoreEngine.PRETT_HOME, tv.home)
        assertEquals(1, tv.writes.size)
    }

    @Test fun detachedProgressListenerCannotInterruptRecovery() {
        val saved = plan()
        val tv = FakeTv(saved)
        assertTrue(StockRestoreEngine(saved, tv).restore(progress = { throw IllegalStateException("Screen detached") }).success)
    }

    @Test fun malformedPlansCannotBecomeShellCommands() {
        val valid = plan()
        val invalid = listOf(
            valid.copy(deviceSerial = ""), valid.copy(fingerprint = "unsafe\nidentity"),
            valid.copy(stockHome = "untrusted.package/.Home"),
            valid.copy(packages = valid.packages.dropLast(1)),
            valid.copy(packages = valid.packages + valid.packages.first()),
            valid.copy(packages = valid.packages.mapIndexed { i, item -> if (i == 0) item.copy(name = "com.tcl.browser; reboot") else item }),
            valid.copy(packages = valid.packages.map { it.copy(managedState = 1) }),
            valid.copy(packages = valid.packages.map { it.copy(previousState = 9) }),
            valid.copy(packages = valid.packages.map { if (it.name == StockRestoreEngine.STOCK_PACKAGE) it.copy(previousState = 3) else it }),
            valid.copy(dnsMode = "hostname; reboot"), valid.copy(dnsMode = "hostname"),
            valid.copy(dnsSpecifier = "dns.example; reboot"), valid.copy(dnsSpecifier = "dns.example\nreboot"),
            valid.copy(dnsSpecifier = "-bad.example"), valid.copy(dnsSpecifier = "bad..example"),
            valid.copy(managedDnsMode = "off"), valid.copy(managedDnsSpecifier = "unrecognized.example")
        )
        for (candidate in invalid) {
            try {
                StockRestoreEngine(candidate, FakeTv(valid))
                fail("Invalid plan was accepted: $candidate")
            } catch (_: IllegalArgumentException) { /* The shell is never opened. */ }
        }
    }
}
