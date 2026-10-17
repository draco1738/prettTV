package fi.jommi.rivi

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Readers see either a complete previous image or a complete replacement, never a partial write. */
internal class ArtworkDiskCache(private val dir: File) {
    companion object { private val lock=Any() }
    init { dir.mkdirs() }
    private fun file(url: String) = File(dir, MessageDigest.getInstance("SHA-256").digest(url.toByteArray())
        .joinToString("") { "%02x".format(it) })

    fun read(url: String): ByteArray? = synchronized(lock) { try {
        val cached = file(url)
        if (cached.length() in 1..6_000_000) cached.inputStream().use { it.readBytesCapped(6_000_000) } else null
    } catch (_: Exception) { null } }

    fun discard(url: String) { synchronized(lock) { file(url).delete() } }

    fun write(url: String, bytes: ByteArray) { synchronized(lock) {
        if (bytes.isEmpty() || bytes.size > 6_000_000) return
        var temporary: File? = null
        try {
            temporary = File.createTempFile("art-", ".tmp", dir)
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file(url).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            val files = dir.listFiles()?.filter { it.name.matches(Regex("[0-9a-f]{64}")) }?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            for (old in files) { if (total <= 24_000_000) break; val size = old.length(); if (old.delete()) total -= size }
        } catch (_: Exception) { /* Cache failures must not discard a successfully downloaded image. */ }
        finally { temporary?.delete() }
    } }
}
