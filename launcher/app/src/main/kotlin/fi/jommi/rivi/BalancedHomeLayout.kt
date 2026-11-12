package fi.jommi.rivi

import kotlin.math.ceil

/** C keeps its split composition, with the owner's larger Discover posters; A/B are unchanged. */
internal object BalancedHomeLayout {
    const val INSET=64
    const val WIDTH=1792
    const val HERO_WIDTH=1000
    const val SPLIT_GAP=40
    const val DISCOVERY_WIDTH=752
    const val POSTER_GAP=16
    const val POSTER_WIDTH=(DISCOVERY_WIDTH-2*POSTER_GAP)/3
    const val POSTER_COUNT=3
    const val TOP_GAP=38
    const val DISCOVERY_ACTION_PADDING=16
    // Leave one reference pixel of slack for independently rounded Larger dimensions.
    const val HEADING_TOP_CLEARANCE=3
    const val HEADING_BOTTOM_CLEARANCE=13

    private fun font(value:Float)=if(value.isFinite())value.coerceAtLeast(1f) else 1f
    fun stackedHeading(fontScale:Float)=font(fontScale)>1.3f
    fun headingHeight(fontScale:Float)=ceil(36*font(fontScale)).toInt()+25+if(stackedHeading(fontScale))discoveryActionHeight(fontScale) else 0
    fun posterHeight(fontScale:Float)=POSTER_WIDTH*3/2+81+ceil(70*(font(fontScale)-1f)).toInt()
    fun discoveryActionHeight(fontScale:Float)=16+ceil(28*font(fontScale)).toInt()
    fun discoveryActionWidth(fontScale:Float)=2*DISCOVERY_ACTION_PADDING+ceil(158*font(fontScale)).toInt()
    fun stageHeight(fontScale:Float)=headingHeight(fontScale)+posterHeight(fontScale)
    fun appCaptionHeight(fontScale:Float)=8+ceil(26*font(fontScale)).toInt()
    fun continuationHeight(fontScale:Float)=128+ceil(100*(font(fontScale)-1f)).toInt()

    /** One scale for both axes: artwork must never inherit the card's aspect ratio. */
    fun artworkScale(sourceWidth:Int,sourceHeight:Int,frameWidth:Float,frameHeight:Float,poster:Boolean):Float {
        // A poster fills its 2:3 frame by cropping, never by independently scaling its axes.
        val contain=!poster && sourceWidth.toFloat()/sourceHeight<1.3f
        return if(contain)minOf(frameWidth/sourceWidth,frameHeight/sourceHeight)
            else maxOf(frameWidth/sourceWidth,frameHeight/sourceHeight)
    }

    /** Re-rank combined shelves before limiting; each source row was independently capped. */
    fun discovery(content:HomeContent,services:Set<String> = RecommendationPolicy.inferredServices(content.watching),
                  recent:List<String> = emptyList(),feedback:RecommendationFeedback = RecommendationFeedback()):List<Program> {
        return BalancedArtworkPolicy.discovery(content,services,recent,feedback)
    }
}
