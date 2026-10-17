package fi.jommi.rivi

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.storage.StorageManager
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong

/** Optional official-release updater. Discovery never downloads or launches installation UI. */
internal object AppUpdater {
    enum class Phase { IDLE, CHECKING, AVAILABLE, CURRENT, DOWNLOADING, READY, INSTALLING, FAILED }
    data class State(val phase: Phase = Phase.IDLE, val message: String = "Check for an official prettTV update.", val release: UpdateRelease? = null, val progress: Int = -1)
    @Volatile var state = State(); private set
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "prettTV-updates").apply { isDaemon = true } }
    private val generation = AtomicLong()
    private val listeners = LinkedHashMap<Any, (State) -> Unit>()
    private var work: Future<*>? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var loaded = false
    private var homeCheck = false
    @Volatile private var deadline = Long.MAX_VALUE
    private val installLock = Any()
    @Volatile private var installCommitted = false
    private const val PREFS = "official_release_updates"
    private const val RELEASE_API = "https://api.github.com/repos/draco1738/prettTV/releases/latest"

    fun listen(owner: Any, listener: (State) -> Unit) { listeners[owner] = listener; listener(state) }
    fun unlisten(owner: Any) { listeners.remove(owner) }

    fun checkWhenHomeVisible(context: Context) { if (context.packageName == UpdatePolicy.PACKAGE) { initialize(context); check(context, false) } }
    fun cancelHomeCheck() { if (homeCheck && state.phase == Phase.CHECKING) cancel() }

    fun initialize(context: Context) {
        if (loaded) return
        loaded = true
        val app = context.applicationContext
        executor.execute {
            // Downloads from a killed process are never resumed or trusted.
            runCatching { File(app.cacheDir, "official-updates").listFiles()?.filter { it.extension == "part" }?.forEach { it.delete() } }
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val installed = installed(context)
        val restored = runCatching { parseStored(JSONObject(prefs.getString("release", "") ?: "")) }.getOrNull()
        if (restored != null && UpdatePolicy.manifestError(restored, context.packageName, version(installed), fingerprints(installed)) == null) {
            state = State(Phase.AVAILABLE, "Version ${restored.versionName} is available. Check again for the latest release notes.", restored)
        }
        // Installer callbacks can outlive the Activity or process. Keep their outcome readable.
        val result = prefs.getString("install_result", null)
        if (result != null) {
            state = state.copy(message = result)
            prefs.edit().remove("install_result").apply()
        }
        val sessionId = prefs.getInt("session", -1)
        if (sessionId >= 0) {
            val pending = runCatching { context.packageManager.packageInstaller.getSessionInfo(sessionId) }.getOrNull()
            if (pending != null) {
                installCommitted = true
                state = state.copy(phase = Phase.INSTALLING, message = "An update is waiting in Android’s installer. Confirm or cancel it there.")
            }
            else prefs.edit().remove("session").apply()
        }
    }

    fun check(context: Context, manual: Boolean = true) {
        initialize(context)
        if (state.phase in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.INSTALLING)) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!manual && !UpdatePolicy.checkDue(prefs.getLong("last_attempt", 0), System.currentTimeMillis())) return
        prefs.edit().putLong("last_attempt", System.currentTimeMillis()).apply()
        val token = generation.incrementAndGet()
        homeCheck = !manual
        deadline = SystemClock.elapsedRealtime() + 120_000L
        val previous = state.release
        publish(State(Phase.CHECKING, "Checking official GitHub releases…", previous))
        work = executor.submit {
            try {
                val releaseJson = JSONObject(readText(RELEASE_API, 512 * 1024, token))
                require(!releaseJson.optBoolean("draft") && !releaseJson.optBoolean("prerelease")) { "The latest release is not a stable release." }
                val assets = releaseJson.getJSONArray("assets")
                val urls = HashMap<String, String>()
                for (i in 0 until assets.length()) {
                    val item = assets.getJSONObject(i)
                    urls[item.getString("name")] = item.getString("browser_download_url")
                }
                val manifestUrl = urls["update-manifest.json"] ?: throw IOException("This release does not include updater verification metadata yet. Your installed app is unchanged.")
                require(UpdatePolicy.officialAsset(manifestUrl, "update-manifest.json")) { "The release metadata location is invalid." }
                val metadata = JSONObject(readText(manifestUrl, 32 * 1024, token))
                require(metadata.getInt("schemaVersion") == 1) { "This updater cannot read the release metadata version." }
                val filename = metadata.getString("apkAsset")
                val release = UpdateRelease(metadata.getString("packageName"), metadata.getLong("versionCode"), metadata.getString("versionName"), filename,
                    metadata.getString("apkSha256"), metadata.getString("signingCertificateSha256"), urls[filename] ?: "", releaseJson.optString("body").take(24_000))
                val info = installed(app)
                if (release.packageName == app.packageName && release.versionCode <= version(info)) {
                    ensureCurrent(token)
                    prefs.edit().remove("release").apply()
                    deliver(token, State(Phase.CURRENT, "You’re up to date. Installed version: ${info.versionName}."))
                } else {
                    UpdatePolicy.manifestError(release, app.packageName, version(info), fingerprints(info))?.let { throw IOException(it) }
                    ensureCurrent(token)
                    prefs.edit().putString("release", stored(release).toString()).apply()
                    deliver(token, State(Phase.AVAILABLE, "Version ${release.versionName} is available. Downloading is optional.", release))
                }
            } catch (_: CancellationException) { /* Explicit cancellation already updates the UI. */ }
            catch (error: Exception) { deliver(token, State(Phase.FAILED, readable(error, "Couldn’t check for updates."), previous)) }
        }
    }

    fun download(context: Context) {
        val release = state.release ?: return
        if (state.phase in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.INSTALLING)) return
        val app = context.applicationContext
        val token = generation.incrementAndGet()
        deadline = SystemClock.elapsedRealtime() + 600_000L
        publish(State(Phase.DOWNLOADING, "Downloading ${release.versionName}…", release, 0))
        work = executor.submit {
            val partial = File(File(app.cacheDir, "official-updates"), "download-$token.part")
            try {
                updateDirectory(app)
                val installed = installed(app)
                UpdatePolicy.manifestError(release, app.packageName, version(installed), fingerprints(installed))?.let { throw IOException(it) }
                val net = open(release.apkUrl, token)
                try {
                    val length = net.contentLengthLong
                    require(length <= UpdatePolicy.MAX_APK_BYTES) { "The update exceeds the download limit." }
                    val directory = partial.parentFile ?: throw IOException("Update storage is unavailable.")
                    val storage = app.getSystemService(StorageManager::class.java) ?: throw IOException("Update storage is unavailable.")
                    val volume = storage.getUuidForPath(directory)
                    val needed = (if (length > 0) length else UpdatePolicy.MAX_APK_BYTES) + 16L * 1024 * 1024
                    require(storage.getAllocatableBytes(volume) >= needed) { "There isn’t enough free space to download this update." }
                    storage.allocateBytes(volume, needed)
                    net.inputStream.use { input ->
                        partial.outputStream().use { output ->
                            UpdateTransfer.copy(input, output, length, UpdatePolicy.MAX_APK_BYTES, { ensureCurrent(token) }) { progress ->
                                deliver(token, State(Phase.DOWNLOADING, "Downloading ${release.versionName}…", release, progress))
                            }
                            output.fd.sync()
                        }
                    }
                } finally { net.disconnect(); if (connection === net) connection = null }
                verifyArchive(app, release, partial, token)
                ensureCurrent(token)
                val ready = downloadedFile(app, release)
                if (ready.exists() && !ready.delete()) throw IOException("Couldn’t replace the previous downloaded update.")
                if (!partial.renameTo(ready)) throw IOException("Couldn’t save the verified update.")
                updateDirectory(app).listFiles()?.filter { it != ready && (it.extension == "apk" || it.extension == "part") }?.forEach { it.delete() }
                deliver(token, State(Phase.READY, "Download verified. Choose Install to open Android’s confirmation.", release))
            } catch (_: CancellationException) { /* No install is attempted. */ }
            catch (error: Exception) { deliver(token, State(Phase.FAILED, readable(error, "Download failed."), release)) }
            finally { partial.delete() }
        }
    }

    /** Called only after an explicit Install action and permission check by UpdateActivity. */
    fun install(context: Context) {
        if (state.phase != Phase.READY) return
        val release = state.release ?: return
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            publish(state.copy(message = "Allow prettTV to install updates, then select Install again.")); return
        }
        val token = generation.incrementAndGet()
        deadline = SystemClock.elapsedRealtime() + 120_000L
        installCommitted = false
        publish(State(Phase.INSTALLING, "Verifying the download before Android’s installation confirmation…", release))
        work = executor.submit {
            val installer = app.packageManager.packageInstaller
            var sessionId: Int? = null
            var committed = false
            try {
                val file = downloadedFile(app, release)
                verifyArchive(app, release, file, token)
                val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                sessionId = installer.createSession(parameters)
                installer.openSession(sessionId).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) { ensureCurrent(token); val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count) }
                            session.fsync(output)
                        }
                    }
                    ensureCurrent(token)
                    if (!app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("session", sessionId).commit()) throw IOException("Couldn’t save the installation session.")
                    val callback = Intent(app, UpdateInstallReceiver::class.java).setAction("${app.packageName}.UPDATE_RESULT")
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    val pending = PendingIntent.getBroadcast(app, sessionId, callback, flags)
                    synchronized(installLock) {
                        ensureCurrent(token)
                        session.commit(pending.intentSender)
                        committed = true
                        installCommitted = true
                    }
                }
            } catch (_: CancellationException) { /* An uncommitted session is abandoned below. */ }
            catch (error: Exception) { deliver(token, State(Phase.READY, readable(error, "Couldn’t start installation. Your current app is unchanged."), release)) }
            finally {
                if (!committed && sessionId != null) {
                    runCatching { installer.abandonSession(sessionId) }
                    val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    if (prefs.getInt("session", -1) == sessionId) prefs.edit().remove("session").apply()
                }
            }
        }
    }

    fun cancel() {
        synchronized(installLock) {
            if (state.phase == Phase.INSTALLING && installCommitted) return // Android owns cancellation after commit.
            if (state.phase !in setOf(Phase.CHECKING, Phase.DOWNLOADING, Phase.INSTALLING)) return
            val next = if (state.phase == Phase.INSTALLING) Phase.READY else if (state.release == null) Phase.IDLE else Phase.AVAILABLE
            generation.incrementAndGet(); work?.cancel(true); connection?.disconnect(); connection = null
            publish(State(next, "Canceled. Your installed app is unchanged.", state.release))
        }
    }

    fun installationResult(context: Context, sessionId: Int, status: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (sessionId < 0 || prefs.getInt("session", -1) != sessionId) return false
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            installCommitted = true
            publish(state.copy(phase = Phase.INSTALLING, message = "Confirm or cancel the update in Android’s installer."))
        } else {
            installCommitted = false
            val success = status == PackageInstaller.STATUS_SUCCESS
            val message = when (status) {
                PackageInstaller.STATUS_SUCCESS -> "prettTV was updated successfully."
                PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation canceled. Your installed app and settings are unchanged."
                PackageInstaller.STATUS_FAILURE_STORAGE -> "Android needs more free space. Your installed app is unchanged."
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Android rejected this incompatible update. Your installed app is unchanged."
                else -> "Android couldn’t install the update (status $status). Your installed app is unchanged."
            }
            prefs.edit().remove("session").putString("install_result", message).apply()
            publish(State(if (success) Phase.CURRENT else if (state.release != null) Phase.READY else Phase.FAILED, message, if (success) null else state.release))
        }
        return true
    }

    private fun verifyArchive(context: Context, release: UpdateRelease, file: File, token: Long) {
        require(file.isFile && file.length() in 1..UpdatePolicy.MAX_APK_BYTES) { "The downloaded APK is missing or invalid. Download it again." }
        val info = installed(context)
        UpdatePolicy.manifestError(release, context.packageName, version(info), fingerprints(info))?.let { throw IOException(it) }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(32 * 1024); while (true) { ensureCurrent(token); val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags()) ?: throw IOException("Android could not read the downloaded APK.")
        val debug = signatures(archive).any { bytes ->
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(bytes.inputStream()) as X509Certificate
            certificate.subjectX500Principal.name.split(',').any { it.trim().equals("CN=Android Debug", true) }
        }
        UpdatePolicy.archiveError(release, archive.packageName, version(archive), fingerprints(archive),
            (archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE != 0, debug, hex(digest.digest()), archive.versionName)?.let { throw IOException(it) }
    }

    private fun open(url: String, token: Long): HttpURLConnection {
        var address = url
        repeat(6) {
            ensureCurrent(token)
            require(UpdatePolicy.allowedDownloadHost(address)) { "The download redirected to an unsupported host." }
            val request = URL(address).openConnection() as HttpURLConnection
            connection = request
            request.connectTimeout = 8_000; request.readTimeout = 12_000; request.instanceFollowRedirects = false
            request.setRequestProperty("User-Agent", "prettTV-updater")
            request.setRequestProperty("Accept", "application/vnd.github+json, application/json, application/octet-stream")
            val status = try { request.responseCode } catch (error: Exception) { request.disconnect(); throw error }
            if (status in listOf(301, 302, 303, 307, 308)) {
                val location = request.getHeaderField("Location")
                request.disconnect()
                if (location == null) throw IOException("The download redirect is missing its destination.")
                address = URL(URL(address), location).toString()
            } else {
                if (status !in 200..299) { request.disconnect(); throw IOException(if (status == 403 || status == 429) "GitHub’s request limit was reached. Try again later." else "GitHub returned HTTP $status. Try again later.") }
                return request
            }
        }
        throw IOException("The download redirected too many times.")
    }

    private fun readText(url: String, limit: Int, token: Long): String {
        val net = open(url, token)
        try {
            require(net.contentLengthLong <= limit) { "The release response is too large." }
            val output = java.io.ByteArrayOutputStream()
            net.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) { ensureCurrent(token); val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= limit) { "The release response is too large." }; output.write(buffer, 0, count) }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally { net.disconnect(); if (connection === net) connection = null }
    }

    private fun ensureCurrent(token: Long) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) throw CancellationException()
        if (SystemClock.elapsedRealtime() > deadline) throw IOException("The update operation took too long. Try again later.")
    }
    private fun deliver(token: Long, value: State) { main.post { if (generation.get() == token) publish(value) } }
    private fun publish(value: State) { state = value; listeners.values.toList().forEach { listener -> runCatching { listener(value) } } }
    private fun readable(error: Exception, fallback: String): String = when (error) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException -> "The connection is unavailable or timed out. Try again when the TV is online."
        is IOException, is IllegalArgumentException -> error.message?.take(260) ?: fallback
        else -> fallback
    }
    private fun updateDirectory(context: Context): File = File(context.cacheDir, "official-updates").apply { if (!exists() && !mkdirs()) throw IOException("Update storage is unavailable.") }
    private fun downloadedFile(context: Context, release: UpdateRelease): File = File(updateDirectory(context), "${release.apkSha256.lowercase()}.apk")
    @Suppress("DEPRECATION") private fun installed(context: Context): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    @Suppress("DEPRECATION") private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    @Suppress("DEPRECATION") private fun signatures(info: PackageInfo): List<ByteArray> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toByteArray() }.orEmpty()
    private fun fingerprints(info: PackageInfo): Set<String> = signatures(info).map { hex(MessageDigest.getInstance("SHA-256").digest(it)) }.toSet()
    @Suppress("DEPRECATION") private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun stored(release: UpdateRelease): JSONObject = JSONObject().put("packageName", release.packageName).put("versionCode", release.versionCode).put("versionName", release.versionName)
        .put("apkAsset", release.apkAsset).put("apkSha256", release.apkSha256).put("signingCertificateSha256", release.signingCertificateSha256).put("apkUrl", release.apkUrl).put("notes", release.notes)
    private fun parseStored(value: JSONObject) = UpdateRelease(value.getString("packageName"), value.getLong("versionCode"), value.getString("versionName"), value.getString("apkAsset"),
        value.getString("apkSha256"), value.getString("signingCertificateSha256"), value.getString("apkUrl"), value.optString("notes"))
}
