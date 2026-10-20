package fi.jommi.rivi

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CancellationException

/** Optional catalog facts, not viewing history or a promise of streaming availability. */
internal data class TitleMetadata(
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val directors: List<String> = emptyList(),
    val runtimeMinutes: Int = 0,
    val year: Int = 0,
    val sourceName: String = "",
    val sourceUrl: String = "",
)

internal object DiscoveryMetadataPolicy {
    private val imdbPattern = Regex("tt[0-9]{5,12}", RegexOption.IGNORE_CASE)
    private val mazePattern = Regex("^tvmaze:([0-9]{1,9})$")
    private val hosts = setOf("api.tvmaze.com", "v3-cinemeta.strem.io")

    fun type(program: Program): String? = when (program.mediaType.lowercase(Locale.ROOT)) {
        "movie", "film" -> "movie"
        "series" -> "series"
        else -> null // "tv" can mean a live channel; do not silently turn it into a series.
    }

    fun imdb(program: Program): String? = program.imdbId.takeIf(imdbPattern::matches)?.lowercase(Locale.ROOT)
        ?: program.id.takeIf { it.startsWith("cinemeta:") }?.substringAfterLast(':')
            ?.takeIf(imdbPattern::matches)?.lowercase(Locale.ROOT)

    fun tvmaze(program: Program): Int? = mazePattern.matchEntire(program.id)?.groupValues?.get(1)
        ?.toIntOrNull()?.takeIf { it > 0 && type(program) == "series" }

    /** Strict identities make metadata for a remake or a film/series namesake independent. */
    fun cacheKey(program: Program): String? {
        val type = type(program) ?: return null
        tvmaze(program)?.let { return "tvmaze:$it" }
        imdb(program)?.let { return "imdb:$type:$it" }
        if (type != "series" || program.releaseYear !in 1900..2200 || program.title.length !in 1..300 ||
            program.titleKey.isBlank()) return null
        val nameHash = MessageDigest.getInstance("SHA-256").digest(program.titleKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "title:$type:${program.releaseYear}:$nameHash"
    }

    fun allowedAddress(address: String): Boolean = try {
        val uri = URI(address)
        uri.scheme == "https" && uri.host in hosts && uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null && uri.fragment == null
    } catch (_: Exception) { false }

    fun runtime(text: String): Int {
        val clean = text.trim().lowercase(Locale.ROOT)
        val minutes = Regex("^(\\d{1,4})\\s*(?:m|min|mins|minute|minutes)$").matchEntire(clean)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (minutes != null) return minutes.takeIf { it in 1..1000 } ?: 0
        val hours = Regex("^(\\d{1,2})\\s*(?:h|hr|hrs|hour|hours)(?:\\s*(\\d{1,2})\\s*(?:m|min|mins|minute|minutes))?$")
            .matchEntire(clean) ?: return 0
        val extra = hours.groupValues[2].toIntOrNull() ?: 0
        if (extra !in 0..59) return 0
        return (hours.groupValues[1].toInt() * 60 + extra).takeIf { it in 1..1000 } ?: 0
    }

    fun batch(seed: Program, candidates: List<Program>): List<Program> =
        (listOf(seed) + candidates.take(200).filter { cacheKey(it) != null })
            .distinctBy { cacheKey(it) ?: ContentIdentity.key(it) }.take(13)

    fun names(values: List<String>, limit: Int): List<String> = values.asSequence()
        .map { it.trim() }.filter { it.length in 1..120 && it != "null" }
        .distinctBy { it.lowercase(Locale.ROOT) }.take(limit).toList()
}

/**
 * Synchronous, on-demand work for the discovery Activity's worker only. Nothing is scheduled.
 * A batch considers the seed and up to twelve peers, with eight HTTP requests and a twelve-second
 * elapsed budget. Each response, redirect, read and cache is bounded. close() disconnects an
 * in-flight request; callers also supply a generation/lifecycle check to discard obsolete work.
 */
internal class DiscoveryMetadataRepository(private val directory: File) : AutoCloseable {
    private data class Entry(val checkedAt: Long, val metadata: TitleMetadata)
    private val file = File(directory, "metadata-v1.bin")
    private val entries = linkedMapOf<String, Entry>()
    private var loaded = false
    private var lastMazeRequestNanos: Long? = null
    @Volatile private var closed = false
    @Volatile private var connection: HttpURLConnection? = null

    @Synchronized
    fun load(seed: Program, candidates: List<Program>, active: () -> Boolean): Map<String, TitleMetadata> {
        val budget = Budget(active)
        val result = linkedMapOf<String, TitleMetadata>()
        ensureLoaded()
        for (program in DiscoveryMetadataPolicy.batch(seed, candidates)) {
            if (!budget.current()) break
            val metadata = obtain(program, budget) ?: continue
            result[ContentIdentity.key(program)] = metadata
        }
        if (budget.current()) save()
        return result
    }

    @Synchronized
    fun get(program: Program, active: () -> Boolean = { true }): TitleMetadata? {
        val budget = Budget(active)
        ensureLoaded()
        val result = if (budget.current()) obtain(program, budget) else null
        if (budget.current()) save()
        return result
    }

    /** Reads local, still-valid metadata without causing any network work. */
    @Synchronized
    fun cached(program: Program): TitleMetadata? {
        ensureLoaded()
        return DiscoveryMetadataPolicy.cacheKey(program)?.let { entries[it] }
            ?.takeIf { System.currentTimeMillis() - it.checkedAt in 0..STALE_MS }?.metadata
    }

    override fun close() {
        closed = true
        connection?.disconnect()
    }

    private fun obtain(program: Program, budget: Budget): TitleMetadata? {
        val key = DiscoveryMetadataPolicy.cacheKey(program) ?: return null
        val now = System.currentTimeMillis()
        val cached = entries[key]?.takeIf { now - it.checkedAt in 0..STALE_MS }
        if (cached != null && now - cached.checkedAt <= FRESH_MS) return cached.metadata
        if (!budget.canRequest()) return cached?.metadata
        return try {
            val fresh = resolve(program, budget)
            if (fresh != null && budget.current()) {
                entries.remove(key)
                entries[key] = Entry(now, fresh)
                trim()
            }
            fresh ?: cached?.metadata
        } catch (_: CancellationException) { cached?.metadata }
        catch (_: Exception) { cached?.metadata }
    }

    private fun resolve(program: Program, budget: Budget): TitleMetadata? {
        val type = DiscoveryMetadataPolicy.type(program) ?: return null
        val mazeId = DiscoveryMetadataPolicy.tvmaze(program)
        if (mazeId != null) return tvmaze(mazeId, budget)
        val imdb = DiscoveryMetadataPolicy.imdb(program)
        if (imdb != null) {
            // lastVideos=1 avoids downloading thousands of episode records for a long-running series.
            val suffix = if (type == "series") "/lastVideos=1" else ""
            val address = "https://v3-cinemeta.strem.io/meta/$type/$imdb$suffix.json"
            val meta = JSONObject(request(address, budget)).optJSONObject("meta") ?: return null
            if (meta.optString("id") != imdb || meta.optString("type") != type) return null
            val links = meta.optJSONArray("links")
            fun linked(category: String): List<String> = buildList {
                for (i in 0 until minOf(links?.length() ?: 0, 100)) {
                    val item = links?.optJSONObject(i) ?: continue
                    if (item.optString("category") == category) add(item.optString("name"))
                }
            }
            return TitleMetadata(
                DiscoveryMetadataPolicy.names(strings(meta.optJSONArray("genres")) + linked("genre"), 20),
                DiscoveryMetadataPolicy.names(strings(meta.optJSONArray("cast")) + linked("actor"), 40),
                DiscoveryMetadataPolicy.names(strings(meta.optJSONArray("director")) + linked("director"), 20),
                DiscoveryMetadataPolicy.runtime(meta.optString("runtime")),
                year(meta.optString("releaseInfo")),
                "Cinemeta", address,
            )
        }
        // Exact title AND original year, unique among search results. No fuzzy first-result guesses.
        if (type != "series" || program.releaseYear !in 1900..2200) return null
        val address = "https://api.tvmaze.com/search/shows?q=${URLEncoder.encode(program.title, "UTF-8")}"
        val rows = JSONArray(request(address, budget))
        val matches = buildList {
            for (i in 0 until minOf(rows.length(), 50)) {
                val show = rows.optJSONObject(i)?.optJSONObject("show") ?: continue
                add(Triple(show.optInt("id"), show.optString("name"), year(show.optString("premiered"))))
            }
        }
        val id = EpisodePolicy.exactMatch(program.title, program.releaseYear, matches) ?: return null
        return tvmaze(id, budget)
    }

    private fun tvmaze(id: Int, budget: Budget): TitleMetadata? {
        val data = JSONObject(request("https://api.tvmaze.com/shows/$id?embed%5B%5D=cast&embed%5B%5D=crew", budget))
        if (data.optInt("id") != id) return null
        val embedded = data.optJSONObject("_embedded")
        val cast = embedded?.optJSONArray("cast")
        val crew = embedded?.optJSONArray("crew")
        val castNames = buildList {
            for (i in 0 until minOf(cast?.length() ?: 0, 100))
                cast?.optJSONObject(i)?.optJSONObject("person")?.optString("name")?.let(::add)
        }
        val directors = buildList {
            for (i in 0 until minOf(crew?.length() ?: 0, 300)) {
                val member = crew?.optJSONObject(i) ?: continue
                if (member.optString("type").equals("Director", ignoreCase = true))
                    member.optJSONObject("person")?.optString("name")?.let(::add)
            }
        }
        return TitleMetadata(
            DiscoveryMetadataPolicy.names(strings(data.optJSONArray("genres")), 20),
            DiscoveryMetadataPolicy.names(castNames, 40),
            DiscoveryMetadataPolicy.names(directors, 20),
            data.optInt("runtime").takeIf { it in 1..1000 }
                ?: data.optInt("averageRuntime").takeIf { it in 1..1000 } ?: 0,
            year(data.optString("premiered")),
            "TVmaze", "https://www.tvmaze.com/shows/$id",
        )
    }

    private fun request(initialAddress: String, budget: Budget): String {
        var address = initialAddress
        repeat(3) {
            budget.check()
            require(DiscoveryMetadataPolicy.allowedAddress(address))
            val url = URL(address)
            if (url.host == "api.tvmaze.com") {
                val wait = EpisodePolicy.requestDelayMillis(lastMazeRequestNanos, System.nanoTime())
                val until = System.nanoTime() + wait * 1_000_000
                while (System.nanoTime() < until) {
                    budget.check()
                    Thread.sleep(minOf(40, ((until - System.nanoTime()) / 1_000_000).coerceAtLeast(1)))
                }
                lastMazeRequestNanos = System.nanoTime()
            }
            budget.request()
            val con = url.openConnection() as HttpURLConnection
            connection = con
            try {
                budget.check()
                // Split the remaining budget across connect and initial response-header reads.
                val timeout = minOf(3000, (budget.remainingMillis() / 2).coerceAtLeast(1)).toInt()
                con.connectTimeout = timeout
                con.readTimeout = timeout
                con.instanceFollowRedirects = false
                con.setRequestProperty("User-Agent", "prettTV/Discovery")
                con.setRequestProperty("Accept", "application/json")
                when (val status = con.responseCode) {
                    301, 302, 303, 307, 308 -> {
                        address = URL(url, con.getHeaderField("Location") ?: throw IOException("Missing redirect")).toString()
                        require(DiscoveryMetadataPolicy.allowedAddress(address))
                    }
                    200 -> {
                        if (con.contentLengthLong > RESPONSE_BYTES) throw IOException("Metadata response too large")
                        return con.inputStream.use { input ->
                            BoundedByteArrayOutputStream(RESPONSE_BYTES).use { out ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    budget.check()
                                    con.readTimeout = minOf(3000, budget.remainingMillis()).coerceAtLeast(1).toInt()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                }
                                out.toByteArray().toString(Charsets.UTF_8)
                            }
                        }
                    }
                    429 -> {
                        budget.stopRequests()
                        throw IOException("Metadata rate limit")
                    }
                    else -> throw IOException("Metadata HTTP $status")
                }
            } finally {
                con.disconnect()
                if (connection === con) connection = null
            }
        }
        throw IOException("Too many metadata redirects")
    }

    private inner class Budget(private val active: () -> Boolean) {
        private val started = System.nanoTime()
        private var calls = 0
        fun current() = !closed && !Thread.currentThread().isInterrupted && active()
        fun remainingMillis() = (BATCH_MILLIS - (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000).coerceAtLeast(0)
        fun canRequest() = current() && calls < REQUESTS && remainingMillis() > 0
        fun check() { if (!current() || remainingMillis() <= 0) throw CancellationException() }
        fun request() { check(); if (calls >= REQUESTS) throw CancellationException(); calls++ }
        fun stopRequests() { calls = REQUESTS }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile || file.length() !in 1..CACHE_BYTES) return
            DataInputStream(ByteArrayInputStream(file.inputStream().use { it.readBytesCapped(CACHE_BYTES.toInt()) })).use { input ->
                require(input.readInt() == MAGIC && input.readInt() == 1)
                val now = System.currentTimeMillis()
                repeat(input.count(CACHE_ENTRIES)) {
                    val key = input.readUTF()
                    require(key.length <= 200)
                    val checked = input.readLong()
                    fun names(max: Int) = List(input.count(max)) {
                        input.readUTF().also { require(it.length in 1..120) }
                    }
                    val metadata = TitleMetadata(names(20), names(40), names(20), input.readInt(), input.readInt(),
                        input.readUTF(), input.readUTF())
                    require(metadata.runtimeMinutes in 0..1000 && (metadata.year == 0 || metadata.year in 1900..2200))
                    require(metadata.sourceName in setOf("TVmaze", "Cinemeta") && metadata.sourceUrl.length <= 500)
                    if (now - checked in 0..STALE_MS) entries[key] = Entry(checked, metadata)
                }
                require(input.available() == 0)
            }
        } catch (_: Exception) { entries.clear() }
    }

    private fun trim() {
        val now = System.currentTimeMillis()
        entries.entries.removeAll { now - it.value.checkedAt !in 0..STALE_MS }
        while (entries.size > CACHE_ENTRIES) entries.remove(entries.keys.first())
    }

    private fun save() {
        trim()
        try {
            val bytes = BoundedByteArrayOutputStream(CACHE_BYTES.toInt()).use { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(MAGIC); out.writeInt(1); out.writeInt(entries.size)
                    fun names(values: List<String>) { out.writeInt(values.size); values.forEach(out::writeUTF) }
                    for ((key, value) in entries) {
                        val metadata = value.metadata
                        out.writeUTF(key); out.writeLong(value.checkedAt)
                        names(metadata.genres); names(metadata.cast); names(metadata.directors)
                        out.writeInt(metadata.runtimeMinutes); out.writeInt(metadata.year)
                        out.writeUTF(metadata.sourceName); out.writeUTF(metadata.sourceUrl)
                    }
                }
                buffer.toByteArray()
            }
            AtomicPrivateFile.write(file, bytes, CACHE_BYTES)
        } catch (_: Exception) { /* Catalog facts remain in memory when the private cache is unavailable. */ }
    }

    private fun strings(array: JSONArray?): List<String> = buildList {
        for (i in 0 until minOf(array?.length() ?: 0, 100)) (array?.opt(i) as? String)?.let(::add)
    }
    private fun year(value: String) = value.take(4).toIntOrNull()?.takeIf { it in 1900..2200 } ?: 0

    companion object {
        private const val MAGIC = 0x50544d44
        private const val CACHE_ENTRIES = 96
        private const val CACHE_BYTES = 900_000L
        private const val RESPONSE_BYTES = 600_000
        private const val FRESH_MS = 7L * 86_400_000
        private const val STALE_MS = 30L * 86_400_000
        private const val REQUESTS = 8
        private const val BATCH_MILLIS = 12_000L
    }
}
