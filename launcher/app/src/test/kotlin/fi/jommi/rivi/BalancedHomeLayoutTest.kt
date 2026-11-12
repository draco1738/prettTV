package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class BalancedHomeLayoutTest {
    private fun program(id:String,title:String=id)=Program(id,title,"","","provider","Provider")
    @Test fun measuredSplitFitsTheApprovedViewport() {
        assertEquals(1920,2*BalancedHomeLayout.INSET+BalancedHomeLayout.WIDTH)
        assertEquals(BalancedHomeLayout.WIDTH,BalancedHomeLayout.HERO_WIDTH+BalancedHomeLayout.SPLIT_GAP+BalancedHomeLayout.DISCOVERY_WIDTH)
        assertTrue(3*BalancedHomeLayout.POSTER_WIDTH+2*BalancedHomeLayout.POSTER_GAP<=BalancedHomeLayout.DISCOVERY_WIDTH)
        assertEquals(502,BalancedHomeLayout.stageHeight(1f))
        assertEquals(128,BalancedHomeLayout.continuationHeight(1f))
    }
    @Test fun enlargedTextReservesSpaceInsteadOfOverlappingTheBrowseAction() {
        for(font in listOf(1f,1.3f,2f)) {
            assertTrue(BalancedHomeLayout.headingHeight(font)>=BalancedHomeLayout.browseHeight(font))
            assertEquals(BalancedHomeLayout.headingHeight(font)+BalancedHomeLayout.posterHeight(font),BalancedHomeLayout.stageHeight(font))
            assertTrue(BalancedHomeLayout.continuationHeight(font)>=128)
        }
        assertTrue(BalancedHomeLayout.stageHeight(1.3f)>BalancedHomeLayout.stageHeight(1f))
    }
    @Test fun invalidOrSmallFontScaleNeverCollapsesTheStage() {
        for(font in listOf(Float.NaN,Float.POSITIVE_INFINITY,-1f,.5f))assertEquals(502,BalancedHomeLayout.stageHeight(font))
    }
    @Test fun discoverPostersAreLargerWithoutRemovingTheThirdCardOrGrowingTheViewport() {
        assertEquals(240,BalancedHomeLayout.POSTER_WIDTH)
        assertEquals(752,BalancedHomeLayout.DISCOVERY_WIDTH)
        assertEquals(1000,BalancedHomeLayout.HERO_WIDTH)
        assertEquals(3,BalancedHomeLayout.POSTER_COUNT)
        assertEquals(BalancedHomeLayout.DISCOVERY_WIDTH,3*BalancedHomeLayout.POSTER_WIDTH+2*BalancedHomeLayout.POSTER_GAP)
        assertTrue(BalancedHomeLayout.POSTER_WIDTH>=202*1.18f)
    }
    @Test fun largerPortraitsKeepTheirTwoToThreeArtAndOriginalTitleClearance() {
        val imageHeight=BalancedHomeLayout.POSTER_WIDTH*1.5f
        assertEquals(360f,imageHeight,0f)
        for(font in listOf(1f,1.3f,2f)) {
            val originalTitleClearance=81+kotlin.math.ceil(70*(font-1f)).toInt()
            assertTrue(BalancedHomeLayout.posterHeight(font)-imageHeight>=originalTitleClearance)
        }
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

    @Test fun discoveryArtFillsItsPortraitFrameWithOneProportionalScale() {
        for((width,height) in listOf(200 to 300,210 to 295,200 to 400,160 to 90)) {
            val scale=BalancedHomeLayout.artworkScale(width,height,202f,303f,true)
            val drawnWidth=width*scale; val drawnHeight=height*scale
            assertTrue("$width x $height left a horizontal gutter",drawnWidth>=201.99f)
            assertTrue("$width x $height left a vertical gutter",drawnHeight>=302.99f)
            assertEquals(width.toFloat()/height,drawnWidth/drawnHeight,.0001f)
            assertTrue(kotlin.math.abs(drawnWidth-202f)<.01f || kotlin.math.abs(drawnHeight-303f)<.01f)
        }
    }

    @Test fun portraitPicturesInHeroAndContinuationKeepTheirExistingContainment() {
        assertEquals(510f/300f,BalancedHomeLayout.artworkScale(200,300,1112f,510f,false),.0001f)
        assertEquals(1112f/160f,BalancedHomeLayout.artworkScale(160,90,1112f,510f,false),.0001f)
    }
}
