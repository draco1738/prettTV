package fi.jommi.rivi

/** Rank shared app cards without claiming to know an account's login or entitlement. */
object RecommendationPolicy {
    const val STREMIO="com.stremio.one"
    private const val ANIME_SERVICE="com.crunchyroll.crunchyroid"
    private val freeServices=setOf("tv.pluto.android","com.tubitv","com.google.android.youtube.tv",
        "com.plexapp.android","com.roku.web.trc","com.amazon.imdb.tv","com.xumo.xumo")
    val knownServices=freeServices+setOf(STREMIO,ANIME_SERVICE,"com.netflix.ninja","com.disney.disneyplus",
        "com.amazon.amazonvideo.livingroom","com.apple.atve.androidtv.appletv","com.wbd.stream",
        "com.hbo.hbonow","com.hulu.livingroomplus","com.cbs.ott","com.peacocktv.peacockandroid")
    private val popularChannel=Regex("\\b(trending|most popular|popular|most watched|top\\s*10)\\b",RegexOption.IGNORE_CASE)

    data class Rows(val suggestions:List<Program>,val discover:List<Program>,val trending:List<Program>)

    fun inferredServices(watching:List<Program>):Set<String> = watching.filter {
        !it.catalog && it.position>0 && it.duration>0 && it.packageName !in freeServices && it.packageName!=STREMIO
    }.map { it.packageName }.toSet()

    private fun tier(p:Program,services:Set<String>):Int = when {
        p.packageName==STREMIO -> 4
        p.catalog -> 3
        p.packageName in services -> 0
        p.packageName in freeServices -> 2
        else -> 1
    }

    /** Round-robin providers within each priority tier before imposing any row limit. */
    fun balanced(rows:List<Program>,services:Set<String>,recent:List<String> = emptyList()):List<Program> {
        val result=ArrayList<Program>()
        for((_,tierRows) in rows.filter { it.title.isNotBlank() }.groupBy { tier(it,services) }.toSortedMap()) {
            val groups=tierRows.groupBy { it.packageName }.entries.sortedWith(
                compareBy<Map.Entry<String,List<Program>>> { it.key==ANIME_SERVICE }
                    .thenBy { recent.indexOf(it.key).let { i -> if(i<0)Int.MAX_VALUE else i } })
                .map { (_,cards) -> cards.sortedByDescending { it.providerWeight } }
            for(index in 0 until (groups.maxOfOrNull { it.size } ?: 0))
                for(cards in groups)cards.getOrNull(index)?.let(result::add)
        }
        return result.distinctBy { it.titleKey }
    }

    /** Use a provider's explicit chart, or an unambiguous same-title/same-type popular catalog match.
     * Matching changes rank only: the app's identity, art and launch intent are retained. */
    fun popularAppCards(previews:List<Program>,popular:List<Program>):List<Program> {
        val catalog=popular.groupBy { it.titleKey }
        return previews.filter { p ->
            !p.catalog && (popularChannel.containsMatchIn(p.sourceName) ||
                (p.mediaType in setOf("movie","series") && catalog[p.titleKey]?.distinctBy { it.id }?.singleOrNull()?.mediaType==p.mediaType))
        }
    }

    fun build(previews:List<Program>,catalog:List<Program>,popular:List<Program>,watching:List<Program>,
              releases:List<Program>,services:Set<String>,recent:List<String> = emptyList(),hidden:Set<String> = emptySet()):Rows {
        val blocked=watching.map { it.titleKey }.toSet()
        val eligible=previews.filter { it.packageName !in hidden && it.titleKey !in blocked && !it.liveSports }
        val appTitles=eligible.filterNot { it.catalog }.map { it.titleKey }.toSet()
        // If a provider omits its content type, keep that app card in discovery instead of
        // allowing a catalog Trending copy to reserve the title and displace its app link.
        val fallback=popular.filter { it.packageName !in hidden && it.titleKey !in blocked && it.titleKey !in appTitles }
        val trending=balanced(popularAppCards(eligible,popular)+fallback,services,recent).take(60)
        val used=(watching+trending+releases).map { it.titleKey }.toSet()
        val candidates=balanced(eligible+catalog.filter { it.packageName !in hidden },services,recent)
            .filter { it.titleKey !in used }
        val suggestions=ArrayList<Program>(); val discover=ArrayList<Program>()
        val perSource=HashMap<String,Int>()
        // Reserve alternating cards from each service for BOTH rows. A large first feed cannot
        // consume Suggestions and leave only an anime provider for Discover.
        for(p in candidates) {
            val n=perSource[p.packageName] ?: 0; perSource[p.packageName]=n+1
            val first=if(n%2==0)suggestions else discover
            val second=if(n%2==0)discover else suggestions
            if(first.size<60)first.add(p) else if(second.size<60)second.add(p)
            if(suggestions.size==60 && discover.size==60)break
        }
        return Rows(suggestions,discover,trending)
    }
}
