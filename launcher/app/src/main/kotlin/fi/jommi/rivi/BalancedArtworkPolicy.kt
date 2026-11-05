package fi.jommi.rivi

import java.net.URI
import java.security.MessageDigest

/** Display-only artwork, keyed by Program.id in HomeContent. Blank means use the provider fallback. */
data class PresentationArtwork(val poster:String="",val backdrop:String="",val sourceUrl:String="")

/** A C-only selection policy; A/B and ordinary shelves retain the original recommendation policy. */
internal object BalancedArtworkPolicy {
    const val CANDIDATE_LIMIT=6
    const val POSTER_COUNT=3
    private val imdbPattern=Regex("tt[0-9]{5,12}")
    private val mazePattern=Regex("tvmaze:([1-9][0-9]{0,8})")
    private val sports=Regex("\\b(sport|sports|nfl|nba|nhl|mlb|football|soccer|cricket|rugby|wrestling)\\b",RegexOption.IGNORE_CASE)
    private val versus=Regex("\\b(vs\\.?|versus)\\s",RegexOption.IGNORE_CASE)

    fun isSports(p:Program)=p.liveSports || p.mediaType=="sports" || sports.containsMatchIn(p.genres+" "+p.sourceName) ||
        (p.mediaType=="unknown" && versus.containsMatchIn(p.title))

    /** Stable series-first partition of the existing service/feedback ranking, then a hard pool cap.
     * Do not scan further down the feed when matches fail; the same pool drives rendering and lookup. */
    fun discoveryCandidates(content:HomeContent,services:Set<String> = RecommendationPolicy.inferredServices(content.watching),
                            recent:List<String> = emptyList(),feedback:RecommendationFeedback = RecommendationFeedback()):List<Program> {
        val featured=content.feature
        val eligible=(content.suggestions.take(60)+content.discover.take(60)+content.trending.take(60))
            .filter { it.title.isNotBlank() && it.id!=featured?.id && it.titleKey!=featured?.titleKey && !isSports(it) }
        val ranked=RecommendationPolicy.balanced(eligible,services,recent,lowerPriority=setOf("tv.pluto.android"),feedback=feedback)
        return ranked.groupBy { RecommendationPolicy.tier(it,services,setOf("tv.pluto.android")) }.values
            .flatMap { tier->tier.filter { it.mediaType=="series" }+tier.filter { it.mediaType!="series" } }.take(CANDIDATE_LIMIT)
    }

    /** Prefer successfully verified portraits only within that small ranked pool. Never alter Program. */
    fun discovery(content:HomeContent,services:Set<String> = RecommendationPolicy.inferredServices(content.watching),
                  recent:List<String> = emptyList(),feedback:RecommendationFeedback = RecommendationFeedback()):List<Program> {
        val candidates=discoveryCandidates(content,services,recent,feedback)
        return candidates.groupBy { RecommendationPolicy.tier(it,services,setOf("tv.pluto.android")) }.values.flatMap { tier->
            val (portraits,fallback)=tier.partition { content.presentationArtwork[it.id]?.poster?.isNotBlank()==true }
            portraits+fallback
        }.take(POSTER_COUNT)
    }

    fun tvmazeId(p:Program)=mazePattern.matchEntire(p.id)?.groupValues?.get(1)?.toIntOrNull()
    fun imdbId(p:Program):String? = p.imdbId.takeIf(imdbPattern::matches)
        ?: Regex("cinemeta:series:(tt[0-9]{5,12})").matchEntire(p.id)?.groupValues?.get(1)

    /** Hash complete matching evidence, not a mutable provider row ID. Malformed explicit IDs never
     * fall back to title guessing. Missing years permit only one exact name across the full search. */
    fun cacheKey(p:Program):String? {
        if(p.mediaType!="series" || isSports(p) || p.title.length !in 1..300 || p.titleKey.isBlank() ||
            (p.releaseYear!=0 && p.releaseYear !in 1900..2200))return null
        if(p.imdbId.isNotEmpty() && !imdbPattern.matches(p.imdbId))return null
        if(p.id.startsWith("tvmaze:") && tvmazeId(p)==null)return null
        if(p.id.startsWith("cinemeta:") && !Regex("cinemeta:series:tt[0-9]{5,12}").matches(p.id))return null
        val evidence=listOf("artwork-v1",p.mediaType,p.titleKey,p.releaseYear.toString(),tvmazeId(p)?.toString().orEmpty(),imdbId(p).orEmpty())
        val unambiguous=evidence.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(unambiguous.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun apiAddress(address:String)=trusted(address,"api.tvmaze.com",allowQuery=true)
    fun imageAddress(address:String)=trusted(address,"static.tvmaze.com") &&
        try { URI(address).path.startsWith("/uploads/images/") } catch(_:Exception) { false }
    fun sourceAddress(address:String,id:Int):Boolean = trusted(address,"www.tvmaze.com") &&
        try { Regex("/shows/$id(?:/[^/]+)?").matches(URI(address).path) } catch(_:Exception) { false }
    private fun trusted(address:String,host:String,allowQuery:Boolean=false):Boolean = try {
        val uri=URI(address)
        address.length in 1..2000 && uri.scheme=="https" && uri.host==host && uri.port in setOf(-1,443) &&
            uri.rawUserInfo==null && uri.fragment==null && (allowQuery || uri.rawQuery==null)
    } catch(_:Exception) { false }
}
