package fi.jommi.rivi

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Optional display metadata for one C-visible batch. Call on ContentRepository's serial worker.
 * No account, analytics, periodic polling, episodes scan or changes to Program. cancel() is UI-safe.
 */
internal class PresentationArtworkResolver(private val file:File,
    private val fetch:((String,Int)->String)?=null,private val paceMillis:Long=600,
    private val nanoTime:()->Long=System::nanoTime,private val batchMillis:Long=BATCH_MILLIS,
    private val openConnection:(URL)->HttpURLConnection={ it.openConnection() as HttpURLConnection }) {
    private data class Entry(val checkedAt:Long,val ttl:Long,val artwork:PresentationArtwork)
    private val entries=linkedMapOf<String,Entry>()
    private var loaded=false
    private var retryAfter=0L
    private var lastRequestNanos:Long?=null
    @Volatile private var cancellation=0
    @Volatile private var connection:HttpURLConnection?=null

    fun cancel() { cancellation++; connection?.disconnect() }

    /** Fresh positives only. Negative entries remain internal; no network even after expiration. */
    fun cached(programs:List<Program>,now:Long):Map<String,PresentationArtwork> {
        ensureLoaded(now)
        return programs.take(BalancedArtworkPolicy.CANDIDATE_LIMIT+1).mapNotNull { p ->
            val e=BalancedArtworkPolicy.cacheKey(p)?.let(entries::get)?.takeIf { fresh(it,now) } ?: return@mapNotNull null
            e.artwork.takeIf { it.poster.isNotBlank() || it.backdrop.isNotBlank() }?.let { p.id to it }
        }.toMap()
    }

    /** Up to six ranked candidates, stop after three portraits, then at most the actual visible hero.
     * The callback carries only display hints; callers must still generation-check UI deliveries. */
    fun enrich(candidates:List<Program>,hero:Program?,now:Long,allowNetwork:Boolean,active:()->Boolean,
               update:(Map<String,PresentationArtwork>)->Unit={}):Map<String,PresentationArtwork> {
        val pool=candidates.take(BalancedArtworkPolicy.CANDIDATE_LIMIT).distinctBy { it.id }
        val targets=(pool+listOfNotNull(hero)).distinctBy { it.id }
        ensureLoaded(now)
        val budget=Budget(active,now)
        var result=cached(targets,now)
        if(!allowNetwork || !budget.current())return if(budget.current())result else emptyMap()
        try {
            var portraits=0
            for(p in pool) {
                if(!budget.current() || portraits>=BalancedArtworkPolicy.POSTER_COUNT)break
                obtain(p,now,budget)
                if(!budget.current())break
                val next=cached(targets,now)
                if(next[p.id]?.poster?.isNotBlank()==true)portraits++
                if(next!=result) { result=next; update(result) }
            }
            if(hero!=null && budget.current()) {
                obtain(hero,now,budget)
                if(budget.current()) {
                    val next=cached(targets,now)
                    if(next!=result) { result=next; update(result) }
                }
            }
        } catch(_:CancellationException) { /* Generation, lifecycle or bounded work budget ended. */ }
        if(budget.current())save(now)
        return if(budget.current())result else emptyMap()
    }

    private fun obtain(p:Program,now:Long,budget:Budget) {
        val key=BalancedArtworkPolicy.cacheKey(p) ?: return
        if(entries[key]?.let { fresh(it,now) }==true || !budget.canRequest())return
        try {
            val maze=BalancedArtworkPolicy.tvmazeId(p)
            val imdb=BalancedArtworkPolicy.imdbId(p)
            val show=when {
                maze!=null -> TvmazeArtworkParser.direct(p,request("https://api.tvmaze.com/shows/$maze",budget))
                imdb!=null -> TvmazeArtworkParser.direct(p,request("https://api.tvmaze.com/lookup/shows?imdb=$imdb",budget))
                else -> TvmazeArtworkParser.match(p,request("https://api.tvmaze.com/search/shows?q=${URLEncoder.encode(p.title,"UTF-8")}",budget))
            }
            val art=if(show==null)PresentationArtwork() else TvmazeArtworkParser.images(show.id,show.sourceUrl,
                request("https://api.tvmaze.com/shows/${show.id}/images",budget))
            budget.check()
            val positive=art.poster.isNotBlank() || art.backdrop.isNotBlank()
            remember(key,Entry(now,if(positive)POSITIVE_TTL else NEGATIVE_TTL,art),now)
        } catch(e:CancellationException) { throw e }
        catch(_:Exception) {
            if(budget.current())remember(key,Entry(now,ERROR_TTL,PresentationArtwork()),now)
        }
    }

    private fun remember(key:String,entry:Entry,now:Long) {
        entries.remove(key); entries[key]=entry
        trim(now); save(now)
    }

    private fun request(initial:String,budget:Budget):String {
        fetch?.let { transport ->
            require(BalancedArtworkPolicy.apiAddress(initial)); budget.request()
            val result=transport(initial,RESPONSE_BYTES)
            require(result.toByteArray(Charsets.UTF_8).size<=RESPONSE_BYTES)
            budget.check(); return result
        }
        var address=initial
        repeat(3) {
            budget.check(); require(BalancedArtworkPolicy.apiAddress(address))
            val delay=lastRequestNanos?.let { (paceMillis-(nanoTime()-it).coerceAtLeast(0)/1_000_000).coerceIn(0,paceMillis) } ?: 0
            val until=nanoTime()+delay*1_000_000
            while(nanoTime()<until) { budget.check(); Thread.sleep(minOf(40,((until-nanoTime())/1_000_000).coerceAtLeast(1))) }
            budget.request(); lastRequestNanos=nanoTime()
            val url=URL(address)
            val con=openConnection(url)
            connection=con
            // A socket read timeout is not a whole-response deadline (headers can drip forever).
            // An independent timer disconnects the exact in-flight connection at the batch deadline.
            val deadline=deadlines.schedule({ con.disconnect() },budget.remainingMillis(),TimeUnit.MILLISECONDS)
            try {
                budget.check()
                val timeout=minOf(2500,budget.remainingMillis()/2).coerceAtLeast(1).toInt()
                con.connectTimeout=timeout; con.readTimeout=timeout; con.instanceFollowRedirects=false
                con.setRequestProperty("User-Agent","prettTV/PresentationArtwork")
                con.setRequestProperty("Accept","application/json")
                when(val status=con.responseCode) {
                    301,302,303,307,308 -> {
                        address=URL(url,con.getHeaderField("Location") ?: throw IOException("Missing redirect")).toString()
                        require(BalancedArtworkPolicy.apiAddress(address))
                    }
                    200 -> {
                        if(con.contentLengthLong>RESPONSE_BYTES)throw IOException("Artwork metadata too large")
                        return con.inputStream.use { input ->
                            BoundedByteArrayOutputStream(RESPONSE_BYTES).use { out ->
                                val buffer=ByteArray(8192)
                                while(true) {
                                    budget.check()
                                    con.readTimeout=minOf(2500,budget.remainingMillis()).coerceAtLeast(1).toInt()
                                    val count=input.read(buffer); if(count<0)break
                                    out.write(buffer,0,count)
                                }
                                budget.check(); out.toByteArray().toString(Charsets.UTF_8)
                            }
                        }
                    }
                    429 -> {
                        retryAfter=budget.wallNow+ERROR_TTL; budget.stopRequests()
                        throw IOException("Artwork metadata rate limited")
                    }
                    else -> throw IOException("Artwork metadata HTTP $status")
                }
            } finally { deadline.cancel(false); con.disconnect(); if(connection===con)connection=null }
        }
        throw IOException("Too many artwork redirects")
    }

    private inner class Budget(private val active:()->Boolean,val wallNow:Long) {
        private val token=cancellation
        private val started=nanoTime()
        private var calls=0
        fun current()=token==cancellation && active() && !Thread.currentThread().isInterrupted
        fun remainingMillis()=(batchMillis-(nanoTime()-started).coerceAtLeast(0)/1_000_000).coerceAtLeast(0)
        fun canRequest()=current() && calls<REQUESTS && remainingMillis()>0 && wallNow>=retryAfter
        fun check() { if(!current() || remainingMillis()<=0)throw CancellationException() }
        fun request() { check(); if(!canRequest())throw CancellationException(); calls++ }
        fun stopRequests() { calls=REQUESTS }
    }

    private fun fresh(entry:Entry,now:Long)=now-entry.checkedAt in 0 until entry.ttl
    private fun ensureLoaded(now:Long) {
        if(loaded)return
        loaded=true
        try {
            if(!file.isFile || file.length() !in 1..CACHE_BYTES)return
            DataInputStream(ByteArrayInputStream(file.inputStream().use { it.readBytesCapped(CACHE_BYTES.toInt()) })).use { input ->
                require(input.readInt()==MAGIC && input.readInt()==1)
                retryAfter=input.readLong().takeIf { it-now in 0..ERROR_TTL } ?: 0
                repeat(input.count(CACHE_ENTRIES)) {
                    val key=input.readUTF(); require(key.matches(Regex("[a-f0-9]{64}")))
                    val checked=input.readLong(); val ttl=input.readLong(); require(ttl in setOf(POSITIVE_TTL,NEGATIVE_TTL,ERROR_TTL))
                    val poster=input.readUTF(); val backdrop=input.readUTF(); val source=input.readUTF()
                    require(poster.isEmpty() || BalancedArtworkPolicy.imageAddress(poster))
                    require(backdrop.isEmpty() || BalancedArtworkPolicy.imageAddress(backdrop))
                    if(poster.isNotEmpty() || backdrop.isNotEmpty()) {
                        val id=Regex("https://www[.]tvmaze[.]com/shows/([1-9][0-9]*)(?:/[^/]+)?").matchEntire(source)?.groupValues?.get(1)?.toIntOrNull()
                        require(id!=null && BalancedArtworkPolicy.sourceAddress(source,id))
                    } else require(source.isEmpty())
                    val entry=Entry(checked,ttl,PresentationArtwork(poster,backdrop,source))
                    if(fresh(entry,now))entries[key]=entry
                }
                require(input.available()==0)
            }
        } catch(_:Exception) { entries.clear(); retryAfter=0 }
    }
    private fun trim(now:Long) {
        entries.entries.removeAll { !fresh(it.value,now) }
        while(entries.size>CACHE_ENTRIES)entries.remove(entries.keys.first())
    }
    private fun save(now:Long) {
        trim(now)
        try {
            val bytes=BoundedByteArrayOutputStream(CACHE_BYTES.toInt()).use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC); out.writeInt(1); out.writeLong(retryAfter); out.writeInt(entries.size)
                    for((key,entry) in entries) {
                        out.writeUTF(key); out.writeLong(entry.checkedAt); out.writeLong(entry.ttl)
                        out.writeUTF(entry.artwork.poster); out.writeUTF(entry.artwork.backdrop); out.writeUTF(entry.artwork.sourceUrl)
                    }
                }; buffer.toByteArray()
            }
            AtomicPrivateFile.write(file,bytes,CACHE_BYTES)
        } catch(_:Exception) { /* Optional presentation hints never block Home or overwrite history. */ }
    }
    companion object {
        private val deadlines=ScheduledThreadPoolExecutor(1) { task->
            Thread(task,"pretttv-artwork-deadline").apply { isDaemon=true }
        }.apply { removeOnCancelPolicy=true }
        private const val MAGIC=0x50544152
        const val CACHE_ENTRIES=96
        const val CACHE_BYTES=500_000L
        const val RESPONSE_BYTES=600_000
        const val POSITIVE_TTL=7L*86_400_000
        const val NEGATIVE_TTL=86_400_000L
        const val ERROR_TTL=3_600_000L
        const val REQUESTS=8
        const val BATCH_MILLIS=12_000L
    }
}
