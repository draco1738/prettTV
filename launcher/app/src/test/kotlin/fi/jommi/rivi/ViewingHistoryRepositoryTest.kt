package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

/** Exercises the repository's evidence boundary and real HomeContent policy, not Android queues. */
class ViewingHistoryRepositoryTest {
    private val local=ViewingHistoryMode.THIS_TV_ONLY
    private val a=Program("first","First show","Service · S1 E1","art","com.netflix.ninja","Netflix",
        position=30_000,duration=600_000,engagedAt=100,watchingSource=true,mediaType="series")
    private val b=a.copy(id="second",title="Second show",packageName="com.disney.disneyplus",engagedAt=200)
    private fun select(history:PlaybackHistory,rows:List<Program>,available:Set<String> = setOf(a.packageName,b.packageName),
        excluded:Set<String> = emptySet(),epoch:Long=SettingsBackupGuard.generation)=
        ContentRepository.selectViewingHistory(history,rows,local,available,excluded,epoch)

    private fun automaticallyObserved(type:String):List<Program> {
        val history=PlaybackHistory(HistoryPreferences())
        val provider=a.copy(mediaType=type,intentUri="netflix://shared-episode",position=300_000)
        history.merge(listOf(provider))
        val matched=history.match(provider.packageName,listOf(provider.title))!!
        assertEquals(type,matched.mediaType)
        assertTrue(PlaybackObservation.record(history,matched,15_000,45_000,600_000,{ 123L }))
        val rows=select(history,listOf(provider))
        assertEquals(1,rows.size)
        assertEquals("",rows.single().intentUri)
        assertEquals(45_000L,rows.single().position)
        return rows
    }
    @Test fun automaticSeriesStillAppearsInShowsRow() {
        assertEquals(1,automaticallyObserved("series").filter { it.mediaType=="series" }.size)
    }
    @Test fun automaticMovieStillAppearsInMoviesRow() {
        assertEquals(1,automaticallyObserved("movie").filter { it.mediaType=="movie" }.size)
    }

    @Test fun noLocalHistoryMeansNoInferredServicesOrRecommendedHeroButDiscoveryRemains() {
        val rows=select(PlaybackHistory(HistoryPreferences()),listOf(a,b))
        assertTrue(rows.isEmpty());assertTrue(RecommendationPolicy.inferredServices(rows).isEmpty())
        val content=HomeContent(watching=rows,suggestions=listOf(b),discover=listOf(a),viewingHistoryMode=local)
        assertNull(content.feature);assertEquals(listOf(b),content.suggestions);assertEquals(listOf(a),content.discover)
    }
    @Test fun hiddenLocalHeroDoesNotFallBackToRecommendationsAndCopiesPreserveMode() {
        val content=HomeContent(watching=listOf(a),suggestions=listOf(b),viewingHistoryMode=local)
        assertEquals(a,content.feature)
        val hidden=content.copy(hiddenHeroTitles=setOf(a.titleKey))
        assertNull(hidden.feature)
        val status=ContentRefreshState().snapshot().applyTo(hidden)
        assertEquals(local,status.viewingHistoryMode);assertNull(status.feature)
        assertEquals(b,hidden.copy(viewingHistoryMode=ViewingHistoryMode.COMBINED).feature)
    }
    @Test fun retainedHistoryMustStillHaveAnInstalledNonhiddenDestination() {
        val history=PlaybackHistory(HistoryPreferences());history.confirm(a);history.confirm(b)
        assertEquals(listOf(a.title),select(history,emptyList(),available=setOf(a.packageName)).map { it.title })
        assertEquals(listOf(b.title),select(history,emptyList(),excluded=setOf(a.packageName)).map { it.title })
        assertTrue(select(history,emptyList(),available=emptySet()).isEmpty())
        assertEquals(2,select(history,emptyList()).size) // eligibility did not delete history
    }
    @Test fun policyIsAppliedBeforeTheSixtyTitleDisplayCap() {
        val history=PlaybackHistory(HistoryPreferences());history.confirm(a)
        val providerOnly=(1..60).map { b.copy(id="provider:$it",title="Provider title $it",engagedAt=1000L+it) }
        val rows=select(history,providerOnly+a)
        assertEquals(listOf(a.title),rows.map { it.title })
        assertEquals(setOf(a.packageName),RecommendationPolicy.inferredServices(rows))
    }
    @Test fun staleRepositorySelectionCannotRepublishRetainedLocalHistory() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);history.confirm(a)
        SettingsBackupGuard.enableAfterWriterIntegration();val old=SettingsBackupGuard.generation
        SettingsBackupGuard.diagnosticRestore { }
        val before=prefs.all
        assertTrue(select(history,listOf(a,b),epoch=old).isEmpty());assertEquals(before,prefs.all)
        assertEquals(a.title,select(history,emptyList()).single().title)
    }
    @Test fun restoredModeIsReadFromPreferencesNotACachedSelection() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(HistoryPreferences())
        fun result()=ContentRepository.selectViewingHistory(history,listOf(a,b),ViewingHistoryMode.read(prefs),
            setOf(a.packageName,b.packageName),emptySet(),SettingsBackupGuard.generation)
        assertEquals(2,result().size)
        prefs.edit().putString(ViewingHistoryMode.KEY,local.name).apply()
        assertTrue(result().isEmpty())
        prefs.edit().clear().apply()
        assertEquals(2,result().size)
    }
    @Test fun providerChurnCannotChangeLocalChronologyOrInferredServicesAtRepositoryBoundary() {
        val history=PlaybackHistory(HistoryPreferences());history.merge(listOf(a,b));history.confirm(a,50_000)
        val first=select(history,listOf(a,b)).single()
        val next=select(history,listOf(b.copy(position=400_000),a.copy(position=500_000,duration=800_000))).single()
        assertEquals(first.position,next.position);assertEquals(first.duration,next.duration)
        assertEquals(first.engagedAt,next.engagedAt)
        assertEquals(setOf(a.packageName),RecommendationPolicy.inferredServices(listOf(next)))
    }
    @Test fun decodedOfflineProviderSnapshotIsReevaluatedUsingTheLiveMode() {
        val bytes=OfflineSnapshotStore.encode(OfflineSnapshot(shared=SharedFeedSnapshot(watching=listOf(a,b))))
        val decoded=OfflineSnapshotStore.decode(bytes)
        val home=HistoryPreferences();val history=PlaybackHistory(HistoryPreferences())
        fun rows()=ContentRepository.selectViewingHistory(history,decoded.shared.watching,ViewingHistoryMode.read(home),
            setOf(a.packageName,b.packageName),emptySet(),SettingsBackupGuard.generation)
        assertEquals(2,rows().size)
        home.edit().putString(ViewingHistoryMode.KEY,local.name).apply()
        assertTrue(rows().isEmpty())
        history.confirm(a,45_000)
        val localRows=rows();assertEquals(45_000L,localRows.single().position)
        assertEquals(a.title,HomeContent(watching=localRows,suggestions=listOf(b),viewingHistoryMode=ViewingHistoryMode.read(home)).feature?.title)
        assertEquals(2,decoded.shared.watching.size) // changing the mode never deletes the raw cache
    }
}
