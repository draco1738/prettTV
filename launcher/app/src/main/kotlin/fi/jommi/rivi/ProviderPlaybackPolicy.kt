package fi.jommi.rivi

import kotlin.math.abs

/** Changed provider viewing state is separate from a confirmed local playback event. */
internal object ProviderPlaybackPolicy {
    private val episodePattern=Regex("^S([0-9]{1,4}) E([0-9]{1,4})$")
    fun episode(program:Program):Pair<Int,Int>? {
        val prefix="${program.app} · "
        if(!program.detail.startsWith(prefix))return null
        val token=program.detail.removePrefix(prefix).substringBefore(" · ")
        val match=episodePattern.matchEntire(token) ?: return null
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }
    fun validProgress(p:Program)=!p.catalog && p.position>=15_000 && p.duration>=180_000 && p.position<=p.duration
    fun changed(previous:Program?,current:Program,progressBaseline:Program?=previous):Boolean {
        if(previous==null || progressBaseline==null || !validProgress(current))return false
        if(previous.packageName!=current.packageName || previous.titleKey!=current.titleKey ||
            progressBaseline.packageName!=current.packageName || progressBaseline.titleKey!=current.titleKey)return false
        val beforeEpisode=episode(progressBaseline); val afterEpisode=episode(current)
        if(beforeEpisode!=null && afterEpisode!=null && beforeEpisode!=afterEpisode)return true
        return abs(current.position.toDouble()-progressBaseline.position.toDouble())>=1_000
    }
    fun lateBaseline(p:Program,cutoff:Long,now:Long,initialImport:Boolean):Long? =
        if(!initialImport && validProgress(p) && p.engagedAt>cutoff && p.engagedAt<=now)p.engagedAt else null
    fun nextObservedAt(now:Long,lastObserved:Long):Long=maxOf(now.coerceAtLeast(0),
        if(lastObserved==Long.MAX_VALUE)Long.MAX_VALUE else lastObserved.coerceAtLeast(0)+1)
}
