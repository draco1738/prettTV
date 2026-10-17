package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Two decoders, bounded compressed files and a 12 MiB memory cache. Never decode on the UI thread. */
class ArtworkLoader(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(64), ThreadPoolExecutor.DiscardPolicy())
    private val dir = File(context.cacheDir, "art").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var generation = 0
    fun cancel() { generation++; pool.queue.clear() }
    fun close() { cancel(); pool.shutdownNow(); memory.evictAll() }

    fun load(url: String, width: Int, height: Int, deliver: (Bitmap) -> Unit) {
        if (url.isBlank()) return
        val key = "$url:${width}x$height"
        memory.get(key)?.let { deliver(it); return }
        val g = generation
        pool.execute {
            if (g != generation) return@execute
            try {
                val bytes = read(url) ?: return@execute
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@execute
                var sample = 1
                while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
                // Also cap exceptionally tall posters; decoded working set stays under 8 MiB per worker.
                while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return@execute
                memory.put(key, image)
                ui.post { if (g == generation) deliver(image) }
            } catch (_: Exception) { /* The readable placeholder is the offline/error state. */ }
        }
    }

    private fun read(url: String): ByteArray? {
        val uri = Uri.parse(url)
        if (uri.scheme == "content" || uri.scheme == "android.resource")
            return context.contentResolver.openInputStream(uri)?.use { it.readBytesCapped(6_000_000) }
        if (uri.scheme != "https") return null
        val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(dir, hash)
        if (file.exists() && file.length() <= 6_000_000) return file.readBytes()
        val bytes=download(url) ?: return null
        synchronized(dir) {
            file.writeBytes(bytes)
            val files = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
            var total = files.sumOf { it.length() }
            for (old in files) { if (total <= 24_000_000) break; val n = old.length(); if (old.delete()) total -= n }
        }
        return bytes
    }

    private fun download(original:String):ByteArray? {
        var address=URL(original)
        repeat(4) {
        if(address.protocol!="https" || address.userInfo!=null)return null
        val con = address.openConnection() as HttpURLConnection
        try {
            con.connectTimeout = 4000; con.readTimeout = 4000; con.instanceFollowRedirects = false
            con.setRequestProperty("User-Agent", "prettTV/0.1.1")
            val status=con.responseCode
            if(status in listOf(301,302,303,307,308)) {
                val next=con.getHeaderField("Location") ?: return null
                address=URL(address,next)
            } else {
                if(status!=200)return null
                return con.inputStream.use { it.readBytesCapped(6_000_000) }
            }
        } finally { con.disconnect() }
        }
        return null
    }
}
