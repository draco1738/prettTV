package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class BalancedHomeLayoutTest {
    private fun program(id:String,title:String=id)=Program(id,title,"","","provider","Provider")
    @Test fun measuredSplitFitsTheApprovedViewport() {
        assertEquals(1920,2*BalancedHomeLayout.INSET+BalancedHomeLayout.WIDTH)
        assertEquals(BalancedHomeLayout.WIDTH,BalancedHomeLayout.HERO_WIDTH+BalancedHomeLayout.SPLIT_GAP+BalancedHomeLayout.DISCOVERY_WIDTH)
        assertTrue(3*BalancedHomeLayout.POSTER_WIDTH+2*BalancedHomeLayout.POSTER_GAP<=BalancedHomeLayout.DISCOVERY_WIDTH)
        assertEquals(510,BalancedHomeLayout.stageHeight(1f))
        assertEquals(128,BalancedHomeLayout.continuationHeight(1f))
    }
    @Test fun enlargedTextReservesSpaceInsteadOfOverlappingTheBrowseAction() {
        for(font in listOf(1f,1.3f,2f)) {
            assertTrue(BalancedHomeLayout.stageHeight(font)>=BalancedHomeLayout.headingHeight(font)+BalancedHomeLayout.posterHeight(font)+4+BalancedHomeLayout.browseHeight(font))
            assertTrue(BalancedHomeLayout.continuationHeight(font)>=128)
        }
        assertTrue(BalancedHomeLayout.stageHeight(1.3f)>BalancedHomeLayout.stageHeight(1f))
    }
    @Test fun invalidOrSmallFontScaleNeverCollapsesTheStage() {
        for(font in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,.5f))assertEquals(510,BalancedHomeLayout.stageHeight(font))
    }
    @Test fun discoveryKeepsServicePriorityAndSkipsTheFeatureAndDuplicateTitles() {
        val hero=program("hero","The Show").copy(engagedAt=100)
        val a=program("a","First")
        val b=program("b","Second")
        val c=program("c","Third")
        val content=HomeContent(watching=listOf(hero),suggestions=listOf(hero,program("alias"," the  show "),a,program("duplicate","FIRST"),b),discover=listOf(c),trending=listOf(program("d")))
        assertEquals(listOf(a,b,c),BalancedHomeLayout.discovery(content))
        assertEquals(hero,content.feature)
    }
    @Test fun incompleteFeedsStayIncompleteRatherThanFabricatingPosters() {
        assertTrue(BalancedHomeLayout.discovery(HomeContent()).isEmpty())
        val hero=program("hero")
        val only=program("only")
        assertEquals(listOf(only),BalancedHomeLayout.discovery(HomeContent(watching=listOf(hero),suggestions=listOf(program("blank"," "),only))))
    }
    @Test fun combinedShelvesKeepPaidProvidersAheadOfStremio() {
        val hero=program("hero")
        val a=program("a").copy(packageName="com.netflix.ninja")
        val b=program("b").copy(packageName=RecommendationPolicy.STREMIO)
        val c=program("c").copy(packageName="com.netflix.ninja")
        val content=HomeContent(watching=listOf(hero),suggestions=listOf(a,b),discover=listOf(c))
        assertEquals(listOf(a,c,b),BalancedHomeLayout.discovery(content))
    }
    @Test fun appCopiesWinOverCatalogDuplicatesAcrossShelves() {
        val catalog=program("catalog","A title").copy(catalog=true)
        val app=program("app","A title").copy(packageName="com.netflix.ninja")
        val content=HomeContent(watching=listOf(program("hero")),suggestions=listOf(catalog),discover=listOf(app))
        assertEquals(listOf(app),BalancedHomeLayout.discovery(content))
    }
    @Test fun fallbackHeroIsNotRepeatedAsARecommendation() {
        val rows=(0..5).map { program("p$it") }
        assertEquals(rows.drop(1).take(3),BalancedHomeLayout.discovery(HomeContent(suggestions=rows)))
    }
}
