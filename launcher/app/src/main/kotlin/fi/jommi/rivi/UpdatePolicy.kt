package fi.jommi.rivi

import java.net.URI

/** Pure policy shared by discovery, download and the final pre-install check. */
internal object UpdatePolicy {
    const val PACKAGE = "dev.zane.pretttv"
    const val DAY_MS = 86_400_000L
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    private val digest = Regex("[a-fA-F0-9]{64}")
    private val asset = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,149}\\.apk")

    fun checkDue(lastAttempt: Long, now: Long): Boolean =
        lastAttempt <= 0 || now - lastAttempt >= DAY_MS || lastAttempt > now + 300_000L

    fun manifestError(release: UpdateRelease, installedPackage: String, installedVersion: Long, installedSigners: Set<String>): String? = when {
        installedPackage != PACKAGE || release.packageName != installedPackage -> "This release is for a different app."
        release.versionCode <= installedVersion -> "This TV already has this version or a newer version."
        release.versionName.isBlank() || release.versionName.length > 80 -> "The release version is invalid."
        !asset.matches(release.apkAsset) -> "The release APK name is invalid."
        !digest.matches(release.apkSha256) || !digest.matches(release.signingCertificateSha256) -> "The release verification metadata is invalid."
        installedSigners.size != 1 || !installedSigners.single().equals(release.signingCertificateSha256, true) -> "This release uses a different signing certificate from the installed app."
        !officialAsset(release.apkUrl, release.apkAsset) -> "The release APK is not an official prettTV asset."
        else -> null
    }

    fun archiveError(release: UpdateRelease, packageName: String?, versionCode: Long, signers: Set<String>, debuggable: Boolean, debugCertificate: Boolean, actualDigest: String, archiveVersionName: String?): String? = when {
        !release.apkSha256.equals(actualDigest, true) -> "The APK checksum does not match the release."
        packageName != release.packageName || versionCode != release.versionCode || archiveVersionName != release.versionName -> "The APK identity does not match the release."
        debuggable || debugCertificate -> "Debug builds cannot be installed through the release updater."
        signers.size != 1 || !signers.single().equals(release.signingCertificateSha256, true) -> "The APK signing certificate does not match the installed app."
        else -> null
    }

    fun officialAsset(url: String, filename: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.startsWith("/draco1738/prettTV/releases/download/") &&
            uri.path.substringAfterLast('/') == filename && uri.path.split('/').none { it == ".." || it == "." }
    } catch (_: Exception) { false }

    fun allowedDownloadHost(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" && uri.port == -1 && uri.userInfo == null && uri.host in setOf(
            "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com"
        )
    } catch (_: Exception) { false }
}

internal data class UpdateRelease(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkAsset: String,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val apkUrl: String,
    val notes: String
)
