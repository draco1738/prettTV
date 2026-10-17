package fi.jommi.rivi

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Private provider cache, deliberately separate from saved titles and recovery credentials. */
internal data class OfflineSnapshot(
    val shared:SharedFeedSnapshot=SharedFeedSnapshot(),val catalog:CatalogSnapshot=CatalogSnapshot(),
    val games:List<SportsEvent> = emptyList(),val sharedAt:Long=0,val catalogAt:Long=0
) {
    fun eligible(now:Long):OfflineSnapshot {
        fun fresh(time:Long)=time>0 && now-time in 0..MAX_AGE
        return copy(shared=if(fresh(sharedAt))shared else shared.copy(published=shared.published.filter { it.watchingSource }),
            catalog=if(fresh(catalogAt))catalog else CatalogSnapshot(),games=SportsPolicy.upcoming(games,now))
    }
    companion object { const val MAX_AGE=7*86_400_000L }
}

/** A single bounded atomic replacement leaves the previous snapshot intact on write failure. */
internal class OfflineSnapshotStore(private val file:File) {
    fun load():OfflineSnapshot? = try {
        if(!file.isFile || file.length() !in 1..MAX_BYTES) null else decode(file.inputStream().use { it.readBytesCapped(MAX_BYTES.toInt()) })
    } catch(_:Exception) { null }

    fun save(snapshot:OfflineSnapshot):Boolean = try { AtomicPrivateFile.write(file,encode(snapshot),MAX_BYTES); true } catch(_:Exception) { false }

    companion object {
        private const val MAGIC=0x5054534e
        private const val VERSION=1
        private const val MAX_BYTES=4_000_000L
        internal fun encode(s:OfflineSnapshot):ByteArray = BoundedByteArrayOutputStream(MAX_BYTES.toInt()).use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC); out.writeInt(VERSION); out.writeLong(s.sharedAt); out.writeLong(s.catalogAt)
                ProgramBinaryCodec.writeList(out,s.shared.published,1200); ProgramBinaryCodec.writeList(out,s.shared.watching,300)
                val channels=s.shared.channels.entries.take(1000); out.writeInt(channels.size)
                for((id,name) in channels) { out.writeLong(id); out.writeUTF(name.take(500)) }
                ProgramBinaryCodec.writeList(out,s.catalog.seeds,20)
                for(type in listOf("series","movie")) {
                    ProgramBinaryCodec.writeList(out,s.catalog.popular[type].orEmpty(),100)
                    ProgramBinaryCodec.writeList(out,s.catalog.releases[type].orEmpty(),100)
                }
                val games=s.games.take(60); out.writeInt(games.size)
                for(e in games) {
                    for(value in listOf(e.id,e.title,e.league,e.state,e.status,e.network,e.logo,e.source,e.score))out.writeUTF(value.take(4000))
                    out.writeLong(e.startsAt); out.writeLong(e.fetchedAt)
                    for(values in listOf(e.teams,e.teamIds)) { out.writeInt(values.take(10).size); values.take(10).forEach { out.writeUTF(it.take(500)) } }
                }
            }
            buffer.toByteArray().also { require(it.size<=MAX_BYTES) }
        }
        internal fun decode(bytes:ByteArray):OfflineSnapshot {
            require(bytes.size<=MAX_BYTES)
            return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt()==MAGIC && input.readInt()==VERSION)
                val sharedAt=input.readLong(); val catalogAt=input.readLong()
                val published=ProgramBinaryCodec.readList(input,1200); val watching=ProgramBinaryCodec.readList(input,300)
                val channels=buildMap { repeat(input.count(1000)) { put(input.readLong(),input.readUTF()) } }
                val seeds=ProgramBinaryCodec.readList(input,20)
                val popular=mutableMapOf<String,List<Program>>(); val releases=mutableMapOf<String,List<Program>>()
                for(type in listOf("series","movie")) { popular[type]=ProgramBinaryCodec.readList(input,100); releases[type]=ProgramBinaryCodec.readList(input,100) }
                val games=List(input.count(60)) {
                    val v=List(9) { input.readUTF() }; val start=input.readLong(); val fetched=input.readLong()
                    val teams=List(input.count(10)) { input.readUTF() }; val ids=List(input.count(10)) { input.readUTF() }
                    SportsEvent(v[0],v[1],v[2],start,v[3],v[4],v[5],v[6],v[7],fetched,v[8],teams,ids)
                }
                require(input.available()==0)
                OfflineSnapshot(SharedFeedSnapshot(published,watching,channels),CatalogSnapshot(seeds,popular,releases),games,sharedAt,catalogAt)
            }
        }
    }
}

internal object AtomicPrivateFile {
    fun write(file:File,bytes:ByteArray,maxBytes:Long) {
        require(bytes.size<=maxBytes)
        file.parentFile?.let { require(it.isDirectory || it.mkdirs()) }
        val pending=File(file.parentFile,"${file.name}.pending")
        try {
            pending.outputStream().use { it.write(bytes); it.fd.sync() }
            try { Files.move(pending.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
            catch(_:java.nio.file.AtomicMoveNotSupportedException) { Files.move(pending.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING) }
        } finally { pending.delete() }
    }
}

/** Fixed field order avoids platform JSON stubs in deterministic persistence/failure tests. */
internal object ProgramBinaryCodec {
    fun writeList(out:DataOutputStream,rows:List<Program>,max:Int) { out.writeInt(rows.take(max).size); rows.take(max).forEach { write(out,it) } }
    fun readList(input:DataInputStream,max:Int)=List(input.count(max)) { read(input) }
    fun write(out:DataOutputStream,p:Program) {
        for(value in listOf(p.id,p.title,p.detail,p.art,p.packageName,p.app,p.intentUri,p.sourceUrl,p.summary,p.sourceName,p.detailUri,p.mediaType,p.genres,p.imdbId))out.writeUTF(value.take(8000))
        for(value in listOf(p.position,p.duration,p.released,p.engagedAt,p.providerWeight))out.writeLong(value)
        out.writeBoolean(p.catalog); out.writeBoolean(p.watchingSource); out.writeBoolean(p.liveSports); out.writeInt(p.releaseYear)
    }
    fun read(input:DataInputStream):Program {
        val s=List(14) { input.readUTF() }; val n=List(5) { input.readLong() }
        return Program(s[0],s[1],s[2],s[3],s[4],s[5],s[6],n[0],n[1],input.readBoolean(),s[7],s[8],input.readBoolean(),s[9],n[2],s[10],n[3],s[11],input.readBoolean(),s[12],n[4],input.readInt(),s[13])
    }
}

internal fun DataInputStream.count(max:Int)=readInt().also { require(it in 0..max) }

internal class BoundedByteArrayOutputStream(private val limit:Int):ByteArrayOutputStream() {
    override fun write(value:Int) { require(count<limit) { "Snapshot too large" }; super.write(value) }
    override fun write(bytes:ByteArray,offset:Int,length:Int) {
        require(length>=0 && count.toLong()+length<=limit) { "Snapshot too large" }; super.write(bytes,offset,length)
    }
}
