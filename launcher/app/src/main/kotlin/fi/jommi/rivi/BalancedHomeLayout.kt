package fi.jommi.rivi

import kotlin.math.ceil

/** Reference-pixel geometry for the approved C composition; A/B keep their own layout. */
internal object BalancedHomeLayout {
    const val INSET=64
    const val WIDTH=1792
    const val HERO_WIDTH=1112
    const val SPLIT_GAP=40
    const val DISCOVERY_WIDTH=640
    const val POSTER_GAP=16
    const val POSTER_WIDTH=(DISCOVERY_WIDTH-2*POSTER_GAP)/3
    const val POSTER_COUNT=3
    const val TOP_GAP=38

    private fun font(value:Float)=if(value.isFinite())value.coerceAtLeast(1f) else 1f
    fun headingHeight(fontScale:Float)=ceil(36*font(fontScale)).toInt()+25
    fun posterHeight(fontScale:Float)=384+ceil(70*(font(fontScale)-1f)).toInt()
    fun browseHeight(fontScale:Float)=36+ceil(25*font(fontScale)).toInt()
    fun stageHeight(fontScale:Float)=maxOf(510,headingHeight(fontScale)+posterHeight(fontScale)+4+browseHeight(fontScale))
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
