package fi.jommi.rivi

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Process-owned operation: rotating/leaving a screen cannot enqueue a second stop. */
internal object AppForceStopSession {
    enum class Phase { IDLE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED }
    data class State(
        val phase: Phase = Phase.IDLE,
        val packageName: String = "",
        val label: String = "",
        val message: String = "",
        val mutationMayHaveOccurred: Boolean = false,
        val fallbackToAppInfo: Boolean = false,
    )

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "prettTV-force-stop") }
    private val timer = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "prettTV-force-stop-deadline").apply { isDaemon = true }
    }
    @Volatile var state = State(); private set
    @Volatile private var client: LocalAdbClient? = null
    @Volatile private var cancelRequested = false
    @Volatile private var timedOut = false
    private var generation = 0L
    private var listener: ((State) -> Unit)? = null
    private var listenerOwner: Any? = null
    val busy get() = state.phase == Phase.RUNNING || state.phase == Phase.CANCELLING

    fun listen(owner: Any, callback: (State) -> Unit) { listenerOwner = owner; listener = callback; callback(state) }
    fun unlisten(owner: Any) { if (listenerOwner === owner) { listener = null; listenerOwner = null } }
    private fun publish(value: State) { state = value; ui.post { if (state === value) listener?.invoke(value) } }

    /** PackageManager-only, no disk key reads, shell, network, or debugging prompts. */
    @Suppress("DEPRECATION")
    fun canOffer(context: Context, packageName: String): Boolean = runCatching {
        if (!AppForceStopPolicy.validPackage(packageName)) return@runCatching false
        val pm = context.packageManager
        val info = pm.getApplicationInfo(packageName, 0)
        // A shared UID can share a process; stopping one such package can affect others.
        val uidPackages = pm.getPackagesForUid(info.uid)?.toSet() ?: return@runCatching false
        if (uidPackages != setOf(packageName)) return@runCatching false
        val inputMethod = Settings.Secure.getString(appResolver(context), Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')?.takeIf(AppForceStopPolicy::validPackage) ?: return@runCatching false
        if (inputMethod == packageName) return@runCatching false
        val homeApps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DISABLED_COMPONENTS)
        if (homeApps.isEmpty()) return@runCatching false
        val home = homeApps.any { it.activityInfo?.packageName == packageName }
        val settings = pm.queryIntentActivities(Intent(Settings.ACTION_SETTINGS),
            PackageManager.MATCH_DISABLED_COMPONENTS).any { it.activityInfo?.packageName == packageName }
        val launchable = pm.getLeanbackLaunchIntentForPackage(packageName) != null || pm.getLaunchIntentForPackage(packageName) != null
        info.enabled && info.flags and ApplicationInfo.FLAG_SUSPENDED == 0 && launchable && !settings &&
            AppForceStopPolicy.allowed(packageName, context.packageName, info.uid % 100_000,
                info.flags and ApplicationInfo.FLAG_PERSISTENT != 0, home)
    }.getOrDefault(false)

    /** Returns false if another force-stop/recovery owns the local debugging connection. */
    fun start(context: Context, packageName: String, label: String): Boolean = synchronized(StockRecoverySession) {
        synchronized(this) {
            if (busy || StockRecoverySession.busy) return false
            val app = context.applicationContext
            val safeLabel = label.filterNot(Char::isISOControl).take(100).ifBlank { "App" }
            if (!canOffer(app, packageName)) {
                publish(State(Phase.FAILED, packageName, safeLabel, "This app cannot be force stopped here.", fallbackToAppInfo = false))
                return true
            }
            cancelRequested = false
            timedOut = false
            val token = ++generation
            publish(State(Phase.RUNNING, packageName, safeLabel, "Checking this TV and stopping $safeLabel…"))
            worker.execute { perform(app, packageName, safeLabel, token) }
            true
        }
    }

    private fun perform(app: Context, packageName: String, label: String, token: Long) {
        var connection: LocalAdbClient? = null
        var finalState: State? = null
        val deadline = timer.schedule({ synchronized(this) {
            if (generation == token && busy) {
                timedOut = true
                cancelRequested = true
                client?.close()
            }
        } }, 35, TimeUnit.SECONDS)
        try {
            val plan = StockRecoveryStore(app).plan()
                ?: error("Local debugging recovery has not been configured. Use App info to force stop manually.")
            // Read only the existing key. StockRecoveryStore.keyPair() may create a new one,
            // which is deliberately forbidden for an ordinary app-menu action.
            val connected = LocalAdbClient(existingKey(app), limits = LocalAdbClient.Limits(
                connectMs = 2_000, authorizationMs = 2_000, commandMs = 4_000))
            connection = connected
            synchronized(this) {
                client = connection
                check(!cancelRequested) { "The operation was cancelled before connecting." }
            }
            connected.connect { error("This TV has not authorized the existing key. Use App info to force stop manually.") }
            val result = AppForceStopEngine(
                identity = AppForceStopIdentity(plan.deviceSerial, plan.fingerprint),
                packageName = packageName,
                ownPackage = app.packageName,
                launcherUser = Process.myUid() / 100_000,
                shell = AppForceStopShell { connected.shell(it) },
                cancelled = { cancelRequested },
                revalidateTarget = { !StockRecoverySession.busy && canOffer(app, packageName) },
                beforeMutation = { synchronized(this) {
                    publish(state.copy(mutationMayHaveOccurred = true))
                } },
            ).stop()
            finalState = State(
                phase = when { result.success -> Phase.SUCCEEDED; timedOut -> Phase.FAILED; result.cancelled -> Phase.CANCELLED; else -> Phase.FAILED },
                packageName = packageName,
                label = label,
                message = if (timedOut && !result.success) timeoutMessage(result.mutationMayHaveOccurred) else result.message,
                mutationMayHaveOccurred = result.mutationMayHaveOccurred,
                fallbackToAppInfo = !result.success && !result.cancelled || timedOut,
            )
        } catch (failure: Exception) {
            android.util.Log.w("prettTV-force-stop", "App stop could not be completed", failure)
            val changed = state.mutationMayHaveOccurred
            finalState = State(
                phase = if (cancelRequested && !timedOut) Phase.CANCELLED else Phase.FAILED,
                packageName = packageName,
                label = label,
                message = when {
                    timedOut -> timeoutMessage(changed)
                    cancelRequested -> if (changed) "The app may have stopped; its final state was not verified."
                        else "Cancelled before sending force stop. The app was not changed."
                    changed -> "The app may have stopped, but its final state could not be verified. Use App info to check."
                    else -> "Force stop was not sent. ${failure.message?.take(250) ?: "The local connection is unavailable."}"
                },
                mutationMayHaveOccurred = changed,
                fallbackToAppInfo = !cancelRequested || timedOut,
            )
        } finally {
            deadline.cancel(false)
            connection?.close()
            synchronized(this) {
                client = null
                // Only release busy after all old I/O has ended. Cancellation cannot race a
                // replacement session, nor can an old deadline close the next connection.
                publish(finalState ?: State(Phase.FAILED, packageName, label, "App stop could not be verified.", fallbackToAppInfo = true))
            }
        }
    }

    @Synchronized fun cancel() {
        if (!busy) return
        cancelRequested = true
        publish(state.copy(phase = Phase.CANCELLING, message = "Stopping the check…"))
        client?.close()
    }

    private fun timeoutMessage(changed: Boolean) = if (changed)
        "The connection timed out. The app may have stopped, but its final state was not verified. Use App info to check."
    else "The connection timed out before force stop was sent. Use App info to force stop manually."

    private fun appResolver(context: Context) = context.applicationContext.contentResolver

    private fun existingKey(context: Context): KeyPair {
        val file = AtomicFile(File(context.noBackupFilesDir, "stock-recovery-adb-key.json"))
        check(file.baseFile.isFile && file.baseFile.length() in 1..16_384) {
            "No existing local debugging key is available. Use App info to force stop manually."
        }
        val json = JSONObject(file.openRead().use { input ->
            // Fixed-size bounded read also works on API 26 (InputStream.readNBytes does not).
            val bytes = ByteArray(16_385)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read == -1) break
                check(read > 0) { "The saved local debugging key could not be read." }
                count += read
            }
            check(count in 1..16_384) { "The saved local debugging key is invalid." }
            String(bytes, 0, count, Charsets.UTF_8)
        })
        val factory = KeyFactory.getInstance("RSA")
        return KeyPair(factory.generatePublic(X509EncodedKeySpec(Base64.decode(json.getString("public"), Base64.NO_WRAP))),
            factory.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(json.getString("private"), Base64.NO_WRAP))))
    }
}
