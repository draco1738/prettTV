package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class UpdatePolicyTest {
    private val hash = "a".repeat(64)
    private val cert = "b".repeat(64)
    private val release = UpdateRelease(UpdatePolicy.PACKAGE, 12, "0.2.0", "prettTV.apk", hash, cert,
        "https://github.com/draco1738/prettTV/releases/download/v0.2.0/prettTV.apk", "Notes")
    private fun manifest(value: UpdateRelease = release, installed: Long = 11, signers: Set<String> = setOf(cert)) =
        UpdatePolicy.manifestError(value, UpdatePolicy.PACKAGE, installed, signers)
    private fun archive(value: UpdateRelease = release, packageName: String = UpdatePolicy.PACKAGE, version: Long = 12,
                        signers: Set<String> = setOf(cert), debuggable: Boolean = false, debugCert: Boolean = false, actualDigest: String = hash) =
        UpdatePolicy.archiveError(value, packageName, version, signers, debuggable, debugCert, actualDigest, value.versionName)

    @Test fun dailyCheckUsesAttemptTimeAndToleratesClockReset() {
        val now = 2 * UpdatePolicy.DAY_MS
        assertTrue(UpdatePolicy.checkDue(0, now))
        assertFalse(UpdatePolicy.checkDue(now - 60_000, now))
        assertTrue(UpdatePolicy.checkDue(now - UpdatePolicy.DAY_MS, now))
        assertFalse(UpdatePolicy.checkDue(now + 1_000, now))
        assertTrue(UpdatePolicy.checkDue(now + UpdatePolicy.DAY_MS, now))
    }
    @Test fun validOfficialReleaseWithInstalledSignerIsAccepted() { assertNull(manifest()); assertNull(archive()) }
    @Test fun wrongAppAndStabilityBuildAreRejected() {
        assertNotNull(manifest(release.copy(packageName = "other.app")))
        assertNotNull(UpdatePolicy.manifestError(release, "dev.zane.pretttv.stability", 11, setOf(cert)))
        assertNotNull(archive(packageName = "other.app"))
    }
    @Test fun sameVersionAndDowngradesAreRejected() { assertNotNull(manifest(installed = 12)); assertNotNull(manifest(installed = 13)) }
    @Test fun archiveVersionMustMatchVerifiedMetadata() { assertNotNull(archive(version = 13)); assertNotNull(archive(version = 11)) }
    @Test fun misleadingReleaseVersionNameIsRejected() {
        assertNotNull(UpdatePolicy.archiveError(release, UpdatePolicy.PACKAGE, 12, setOf(cert), false, false, hash, "different-version"))
        assertNotNull(UpdatePolicy.archiveError(release, UpdatePolicy.PACKAGE, 12, setOf(cert), false, false, hash, null))
    }
    @Test fun wrongSignerCannotPassMetadataOrArchive() {
        assertNotNull(manifest(signers = setOf("c".repeat(64))))
        assertNotNull(archive(signers = setOf("c".repeat(64))))
        assertNotNull(manifest(signers = emptySet()))
        assertNotNull(archive(signers = emptySet()))
    }
    @Test fun AdditionalSignersAreRejectedRatherThanPartiallyMatched() {
        assertNotNull(manifest(signers = setOf(cert, hash)))
        assertNotNull(archive(signers = setOf(cert, hash)))
    }
    @Test fun debugApksAreRejectedEvenWithMatchingHashAndCertificate() {
        assertNotNull(archive(debuggable = true)); assertNotNull(archive(debugCert = true))
    }
    @Test fun incorrectChecksumIsRejected() { assertNotNull(archive(actualDigest = "d".repeat(64))) }
    @Test fun uppercaseDigestsWorkButMalformedDigestsFail() {
        assertNull(manifest(release.copy(apkSha256 = hash.uppercase(), signingCertificateSha256 = cert.uppercase())))
        assertNull(archive(actualDigest = hash.uppercase()))
        assertNotNull(manifest(release.copy(apkSha256 = "a".repeat(63))))
        assertNotNull(manifest(release.copy(signingCertificateSha256 = "z".repeat(64))))
    }
    @Test fun pathsCannotEscapeUpdateDirectory() {
        for (name in listOf("../prettTV.apk", "/prettTV.apk", "..\\prettTV.apk", "prettTV.apk.exe", "x%2fprettTV.apk")) assertNotNull(name, manifest(release.copy(apkAsset = name)))
    }
    @Test fun releaseAssetsMustBelongToOfficialRepository() {
        for (url in listOf(release.apkUrl.replace("https:", "http:"), release.apkUrl.replace("draco1738", "someone"),
            release.apkUrl.replace("github.com", "github.com.evil.example"), release.apkUrl + "?download=1", release.apkUrl.replace("prettTV.apk", "other.apk"),
            release.apkUrl.replace("github.com/", "github.com:444/"))) assertNotNull(url, manifest(release.copy(apkUrl = url)))
        assertTrue(UpdatePolicy.officialAsset("https://github.com/draco1738/prettTV/releases/download/v0.2.0/update-manifest.json", "update-manifest.json"))
    }
    @Test fun redirectsCannotLeaveHttpsOrGithubAssetHosts() {
        assertTrue(UpdatePolicy.allowedDownloadHost("https://release-assets.githubusercontent.com/asset?signature=value"))
        assertFalse(UpdatePolicy.allowedDownloadHost("http://release-assets.githubusercontent.com/asset"))
        assertFalse(UpdatePolicy.allowedDownloadHost("https://release-assets.githubusercontent.com.evil.example/asset"))
        assertFalse(UpdatePolicy.allowedDownloadHost("https://user:password@github.com/asset"))
        assertFalse(UpdatePolicy.allowedDownloadHost("file:///tmp/update.apk"))
    }
    @Test fun boundedTransferReportsCompletion() {
        val bytes = ByteArray(75_000) { (it % 127).toByte() }; val result = ByteArrayOutputStream(); val progress = mutableListOf<Int>()
        assertEquals(bytes.size.toLong(), UpdateTransfer.copy(bytes.inputStream(), result, bytes.size.toLong(), 100_000, {}, progress::add))
        assertArrayEquals(bytes, result.toByteArray()); assertEquals(100, progress.last())
    }
    @Test fun unknownLengthCannotExceedLimit() {
        val result = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { UpdateTransfer.copy(ByteArray(70_000).inputStream(), result, -1, 40_000, {}) }
        assertTrue(result.size() <= 40_000)
    }
    @Test fun declaredOversizeFailsBeforeReading() {
        val input = object : InputStream() { override fun read(): Int = throw AssertionError("Must not read oversized input") }
        assertThrows(IOException::class.java) { UpdateTransfer.copy(input, ByteArrayOutputStream(), 200, 100, {}) }
    }
    @Test fun truncatedDownloadIsNotAccepted() {
        assertThrows(IOException::class.java) { UpdateTransfer.copy(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 2, 100, {}) }
    }
    @Test fun cancellationAfterReadDoesNotWriteTheNextChunk() {
        var canceled = false
        val input = object : ByteArrayInputStream(ByteArray(40_000)) { override fun read(bytes: ByteArray, offset: Int, length: Int): Int { val result = super.read(bytes, offset, length); canceled = true; return result } }
        val result = ByteArrayOutputStream()
        assertThrows(CancellationException::class.java) { UpdateTransfer.copy(input, result, 40_000, 100_000, { if (canceled) throw CancellationException() }) }
        assertEquals(0, result.size())
    }
    @Test fun readAndStorageFailuresPropagateWithoutSuccess() {
        val brokenInput = object : InputStream() { override fun read(): Int = throw IOException("Disconnected") }
        assertThrows(IOException::class.java) { UpdateTransfer.copy(brokenInput, ByteArrayOutputStream(), -1, 100, {}) }
        val brokenOutput = object : ByteArrayOutputStream() { override fun write(bytes: ByteArray, offset: Int, count: Int) { throw IOException("No space") } }
        assertThrows(IOException::class.java) { UpdateTransfer.copy(byteArrayOf(1).inputStream(), brokenOutput, 1, 100, {}) }
    }
}
