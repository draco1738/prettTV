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
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Two decoders, bounded compressed files and a 12 MiB memory cache. Never decode on the UI thread. */
class ArtworkLoader(private val context: Context) {
    private val ui = Handler(Looper.getMainLooper())
    private val pool = ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, PriorityBlockingQueue<Runnable>(), ThreadPoolExecutor.AbortPolicy())
    private val disk = ArtworkDiskCache(File(context.cacheDir, "art"))
    private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    @Volatile private var generation = 0
    private val lock=Any()
    private val pending=HashMap<String,Work>()
    private var sequence=0L
    interface Ticket { fun cancel(); fun promote() }
    private class Delivery(val callback:(Bitmap?)->Unit) { @Volatile var cancelled=false }
    private inner class Work(val key:String,val url:String,val width:Int,val height:Int,var priority:Int,val order:Long,val epoch:Int):Runnable,Comparable<Work> {
        val deliveries=ArrayList<Delivery>()
        override fun compareTo(other:Work)=compareValuesBy(this,other,{it.priority},{it.order})
        override fun run()=decode(this)
    }
    fun cancel() = synchronized(lock) {
        generation++; pending.values.forEach { work->work.deliveries.forEach { it.cancelled=true } }
        pending.clear(); pool.queue.clear()
    }
    fun close() { cancel(); pool.shutdownNow(); ui.removeCallbacksAndMessages(null); memory.evictAll() }
    fun trimTo(bytes:Int) { memory.trimToSize(bytes.coerceAtLeast(0)) }
    internal fun cachedBytes()=memory.size()
    internal fun pendingCount()=synchronized(lock) { pending.size }

    /** Visible requests overtake speculative neighbours; identical decodes share one bounded job. */
    fun load(url: String, width: Int, height: Int, prefetch:Boolean=false, deliver: (Bitmap?) -> Unit):Ticket {
        val delivery=Delivery(deliver)
        val key = "$url:${width}x$height"
        val ticket=object:Ticket {
            override fun promote() { synchronized(lock) {
                pending[key]?.takeIf { delivery in it.deliveries }?.let { work->
                    if(work.priority!=0 && pool.remove(work)) { work.priority=0; pool.execute(work) }
                }
            } }
            override fun cancel() { synchronized(lock) {
            delivery.cancelled=true
            pending[key]?.let { work->
                work.deliveries.remove(delivery)
                if(work.deliveries.isEmpty() && pool.remove(work))pending.remove(key)
            }
        } } }
        if (url.isBlank() || width <= 0 || height <= 0) { deliver(null); return ticket }
        memory.get(key)?.let { deliver(it); return ticket }
        synchronized(lock) {
            pending[key]?.let { work->
                work.deliveries.add(delivery)
                if(!prefetch && work.priority!=0 && pool.remove(work)) { work.priority=0; pool.execute(work) }
                return ticket
            }
            // PriorityBlockingQueue itself is unbounded. Cap accepted jobs explicitly.
            if(pool.isShutdown || pending.size>=66 || pool.queue.size>=64) { deliver(null); return ticket }
            val work=Work(key,url,width,height,if(prefetch)1 else 0,sequence++,generation)
            work.deliveries.add(delivery); pending[key]=work
            try { pool.execute(work) } catch(_:RejectedExecutionException) { pending.remove(key); deliver(null) }
        }
        return ticket
    }

    private fun decode(work:Work) {
        with(work) {
            if (epoch != generation) return
            var result: Bitmap? = null
            try {
                val bytes = read(url) ?: return
                if (epoch != generation) return
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) { disk.discard(url); return }
                var sample = 1
                while (bounds.outWidth / sample > width * 2 || bounds.outHeight / sample > height * 2) sample *= 2
                // Also cap exceptionally tall posters; decoded working set stays under 8 MiB per worker.
                while (bounds.outWidth.toLong() / sample * (bounds.outHeight / sample) > 2_000_000) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
                val image = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                if (image == null) { disk.discard(url); return }
                synchronized(lock) {
                    if (epoch != generation || deliveries.none { !it.cancelled })return
                    memory.put(key, image); result = image
                }
            } catch (_: Exception) { /* The readable placeholder is the offline/error state. */ }
            finally { val image = result; ui.post {
                val listeners=synchronized(lock) {
                    if(pending[key]===work)pending.remove(key)
                    deliveries.toList()
                }
                if(epoch==generation)listeners.filterNot { it.cancelled }.forEach { it.callback(image) }
            } }
        }
    }

    private fun read(url: String): ByteArray? {
        val uri = Uri.parse(url)
        if (uri.scheme == "content" || uri.scheme == "android.resource")
            return context.contentResolver.openInputStream(uri)?.use { it.readBytesCapped(6_000_000) }
        if (uri.scheme != "https") return null
        disk.read(url)?.let { return it }
        val bytes=download(url) ?: return null
        disk.write(url, bytes)
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
