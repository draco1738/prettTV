package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ContentPolicyTest {
    private fun show(title:String="Severance",pkg:String="apple",catalog:Boolean=false)=Program("$pkg:$title",title,"","",pkg,pkg,catalog=catalog)
    @Test fun hiddenAppsDoNotLeakThroughAnyRecommendationSource() {
        val result=ContentPolicy.combine(listOf(show(pkg="hidden")),listOf(show("Andor","hidden")),listOf(show("Other","hidden",true)),setOf("hidden"))
        assertTrue(result.watching.isEmpty()); assertTrue(result.suggestions.isEmpty())
    }
    @Test fun realWatchHistoryWinsOverDuplicateCatalogCard() {
        val watch=show().copy(position=30_000,duration=120_000)
        val result=ContentPolicy.combine(listOf(watch),emptyList(),listOf(show(" SEVERANCE ",catalog=true)),emptySet())
        assertEquals(watch,result.feature); assertTrue(result.suggestions.isEmpty())
    }
    @Test fun progressClampsUntrustedProviderValuesAndHandlesUnknownDuration() {
        assertEquals(0f,show().copy(position=-5,duration=100).progress,0f)
        assertEquals(1f,show().copy(position=200,duration=100).progress,0f)
        assertEquals(0f,show().copy(position=Long.MAX_VALUE,duration=0).progress,0f)
        assertEquals(.5f,show().copy(position=Long.MAX_VALUE/2,duration=Long.MAX_VALUE).progress,.001f)
    }
    @Test fun catalogNeverClaimsResumeAndCompletedProgramsAreNotInProgress() {
        assertFalse(show(catalog=true).copy(position=20,duration=100).resume)
        assertFalse(show().copy(position=100,duration=100).resume)
        assertTrue(show().copy(position=20,duration=100).resume)
    }
    @Test fun blankAndDuplicateTitlesAreFilteredButDifferentProvidersRemain() {
        assertEquals(2,ContentPolicy.clean(listOf(show(" "),show(),show("severance"),show(pkg="other"))).size)
    }
    @Test fun deduplicationIsIndependentOfDeviceLocale() {
        val old=Locale.getDefault()
        try { Locale.setDefault(Locale("tr","TR")); assertEquals(1,ContentPolicy.clean(listOf(show("INDIGO"),show("indigo"))).size) }
        finally { Locale.setDefault(old) }
    }
    @Test fun unknownLayoutRestoresBalancedAndKnownLayoutsRoundTrip() {
        assertEquals(HomeLayout.BALANCED,HomeLayout.parse("broken"))
        for(layout in HomeLayout.entries)assertEquals(layout,HomeLayout.parse(layout.name))
    }
    @Test fun discoveryBiasDoesNotOverrideHeroChronology() {
        val stremio=show(pkg="com.stremio.one").copy(position=10,duration=100,engagedAt=999)
        val apple=show(pkg="apple").copy(position=20,duration=100,engagedAt=5)
        assertEquals(listOf(apple,stremio),ContentPolicy.prioritize(listOf(stremio,apple),listOf("com.stremio.one")))
        assertEquals(stremio,HomeContent(watching=ContentPolicy.prioritize(listOf(stremio,apple))).feature)
    }
    @Test fun watchingChronologyIncludesStremioAndPutsMissingTimestampsLast() {
        val old=show("Older","apple").copy(engagedAt=100)
        val latest=show("Latest","com.stremio.one").copy(engagedAt=500)
        val unknown=show("Unknown")
        assertEquals(listOf(latest,old,unknown),ContentPolicy.chronological(listOf(unknown,old,latest)))
    }
    @Test fun heroUsesChronologyRatherThanProviderOrPlaybackPosition() {
        val newer=show("Newer","other").copy(engagedAt=200,position=5,duration=100)
        val older=show("Older","apple").copy(engagedAt=100,position=90,duration=100)
        assertEquals(newer,HomeContent(watching=listOf(older,newer)).feature)
    }
    @Test fun discoverySupportsLongRowsWhileRemainingBounded() {
        assertEquals(60,ContentPolicy.clean((0..90).map { show("Title $it") }).size)
    }
    @Test fun titleHidingSurvivesProviderIdsAndKeepsHeroAndRowsIndependent() {
        val mom=show("Fixer Upper","disney").copy(engagedAt=200)
        val other=show("Other","netflix").copy(engagedAt=100)
        val all=HomeContent(watching=listOf(mom,other))
        assertEquals(other,all.copy(hiddenHeroTitles=setOf(mom.titleKey)).feature)
        assertEquals(2,all.copy(hiddenHeroTitles=setOf(mom.titleKey)).visibleWatching.size)
        val rowHidden=all.copy(hiddenWatchingTitles=setOf(mom.titleKey))
        assertEquals(mom,rowHidden.feature); assertEquals(listOf(other),rowHidden.visibleWatching)
        assertEquals(mom.titleKey,show("  FIXER  upper ","different-app").titleKey)
        assertEquals(mom,all.copy(hiddenHeroTitles=emptySet()).feature)
    }
    @Test fun inProgressAndKnownSourcesWinWithoutInventingLoginState() {
        val idle=show("Idle","other").copy(engagedAt=999)
        val active=show("Active","apple").copy(position=20,duration=100)
        assertEquals(active,ContentPolicy.prioritize(listOf(idle,active)).first())
        assertEquals(active,ContentPolicy.prioritize(listOf(idle,active),listOf("apple")).first())
    }
    @Test fun releasesExcludeUnknownFutureAndOldDatesAndSortNewestFirst() {
        val day=86_400_000L; val now=day*1000
        val fresh=show("Fresh").copy(released=now-day)
        val older=show("Older").copy(released=now-day*119)
        val rows=listOf(older,show("Future").copy(released=now+day),show("Old").copy(released=now-day*121),show("Unknown"),fresh,fresh)
        assertEquals(listOf(fresh,older),ContentPolicy.recentReleases(rows,now))
    }
}
