package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class RecommendationPolicyTest {
    private val disney="com.disney.disneyplus"
    private val prime="com.amazon.amazonvideo.livingroom"
    private val anime="com.crunchyroll.crunchyroid"
    private val free="com.tubitv"
    private val stremio=RecommendationPolicy.STREMIO
    private fun card(title:String,pkg:String=disney)=Program("$pkg:$title",title,"","",pkg,pkg,mediaType="series",intentUri="intent:$title")
    private fun cards(pkg:String,n:Int)=(1..n).map { card("$pkg $it",pkg) }
    private fun build(previews:List<Program>,popular:List<Program> = emptyList(),watch:List<Program> = emptyList()) =
        RecommendationPolicy.build(previews,popular,popular,watch,emptyList(),setOf(disney,prime,anime))

    @Test fun subscribedServicesSurviveLargeEarlierFreeFeedsAndReachBothRows() {
        val rows=build(cards(free,180)+cards(disney,50)+cards(prime,20))
        for(row in listOf(rows.suggestions,rows.discover)) {
            assertEquals(60,row.size)
            assertEquals(setOf(disney,prime),row.take(2).map { it.packageName }.toSet())
            assertEquals(35,row.takeWhile { it.packageName!=free }.size)
        }
    }
    @Test fun discoverHasMultipleServicesEvenWhenAnimePublishesMostOfTheCards() {
        val rows=build(cards(anime,150)+cards(disney,30)+cards(prime,20))
        for(row in listOf(rows.suggestions,rows.discover))
            assertEquals(listOf(disney,prime,anime),row.take(3).map { it.packageName })
    }
    @Test fun freeAndStremioFollowPreferredServicesAndCatalogIsNotAnEntitlement() {
        val rows=listOf(card("Fallback",stremio),card("Free",free),card("Unconfirmed","other"),
            card("Catalog",disney).copy(catalog=true),card("Subscribed",disney))
        assertEquals(listOf("Subscribed","Unconfirmed","Free","Catalog","Fallback"),
            RecommendationPolicy.balanced(rows,setOf(disney)).map { it.title })
    }
    @Test fun serviceInferenceRequiresActualProgressAndExcludesCatalogAndFreePromotions() {
        val records=listOf(card("Promo"),card("Watching",prime).copy(position=10,duration=100),
            card("Free",free).copy(position=20,duration=100),card("Catalog",disney).copy(catalog=true,position=10,duration=100))
        assertEquals(setOf(prime),RecommendationPolicy.inferredServices(records))
    }
    @Test fun popularMatchingKeepsRealProviderLaunchIntentAndRejectsAmbiguousOrUnknownTypes() {
        val popular=listOf(card("Drama",stremio).copy(catalog=true),card("Unknown",stremio).copy(catalog=true),
            card("Remake",stremio).copy(id="old",catalog=true),card("Remake",stremio).copy(id="new",catalog=true))
        val drama=card("Drama"); val chart=card("Anime",anime).copy(sourceName="Most Popular")
        val accepted=RecommendationPolicy.popularAppCards(listOf(drama,chart,card("Remake"),
            card("Unknown").copy(mediaType="unknown"),card("Drama").copy(mediaType="movie")),popular)
        assertEquals(listOf(drama,chart),accepted)
        assertEquals("intent:Drama",accepted.first().intentUri)
    }
    @Test fun ordinaryRecommendedChannelsAreNotInventedTrending() {
        assertTrue(RecommendationPolicy.popularAppCards(listOf(card("Pick").copy(sourceName="Recommended for you")),emptyList()).isEmpty())
    }
    @Test fun unknownAppTypeKeepsItsDiscoveryLinkInsteadOfBeingDisplacedByCatalogTrending() {
        val app=card("Shared Title").copy(mediaType="unknown")
        val catalog=card("Shared Title",stremio).copy(catalog=true)
        val rows=build(listOf(app),listOf(catalog))
        assertEquals(listOf(app),rows.suggestions+rows.discover)
        assertTrue(rows.trending.isEmpty())
    }
    @Test fun trendingPrefersAppCopiesAndBalancesThemAheadOfFreeCatalog() {
        val catalog=(1..100).map { card("Popular $it",stremio).copy(catalog=true) }
        val app=card("Popular 1",disney)
        val rows=build(cards(anime,20).map { it.copy(sourceName="Most Popular") }+app,catalog)
        assertEquals(app,rows.trending.first())
        assertEquals(60,rows.trending.size)
        assertEquals(1,rows.trending.count { it.titleKey==app.titleKey })
        assertTrue(rows.trending.take(21).none { it.catalog })
    }
    @Test fun exclusionsAndDuplicatesApplyBeforeLimitsAcrossRows() {
        val watch=card(" Watching ")
        val previews=cards(disney,100)+cards(prime,100)+listOf(card("watching"),card("Hidden","hidden"),card("Live").copy(liveSports=true))
        val rows=RecommendationPolicy.build(previews,emptyList(),emptyList(),listOf(watch),emptyList(),setOf(disney),hidden=setOf("hidden"))
        val all=rows.suggestions+rows.discover+rows.trending
        assertEquals(120,all.size)
        assertEquals(all.size,all.map { it.titleKey }.distinct().size)
        assertFalse(all.any { it.titleKey in setOf("watching","hidden","live") })
    }
    @Test fun manualEmptySetDoesNotFallBackToInferredServicesAndFreeCanBeExplicitlyPreferred() {
        val rows=listOf(card("First",free),card("Other",disney))
        assertEquals(disney,RecommendationPolicy.balanced(rows,emptySet()).first().packageName)
        assertEquals(free,RecommendationPolicy.balanced(rows,setOf(free)).first().packageName)
        assertTrue(build(emptyList()).discover.isEmpty())
    }
    @Test fun providerWeightOrdersWithinAServiceWithoutOverridingProviderFairness() {
        val light=card("Light").copy(providerWeight=1)
        val heavy=card("Heavy").copy(providerWeight=9)
        val other=card("Other",prime)
        assertEquals(listOf(heavy,other,light),RecommendationPolicy.balanced(listOf(light,heavy,other),setOf(disney,prime)))
    }
}
