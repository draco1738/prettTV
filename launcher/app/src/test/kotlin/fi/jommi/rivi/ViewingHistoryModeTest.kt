package fi.jommi.rivi

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

/** Real history/guard/JSON with in-memory Android preferences. No sessions or account access. */
class ViewingHistoryModeTest {
    private val local=ViewingHistoryMode.THIS_TV_ONLY
    private val a=Program("first:1","First show","First service · S1 E1 · 9 min left","first-art",
        "com.netflix.ninja","First service",intentUri="first://episode/1",position=60_000,duration=600_000,
        watchingSource=true,engagedAt=100,mediaType="series",posterArt="first-poster")
    private val b=a.copy(id="second:1",title="Second show",packageName="com.disney.disneyplus",engagedAt=200)
    private fun key(p:Program)="${p.packageName}|${p.titleKey}"

    @Test fun providerOnlyRowsAndTimestampsNeverBecomeLocalHistory() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        assertTrue(history.merge(listOf(a,b),mode=local).isEmpty())
        assertTrue(history.merge(listOf(a.copy(position=200_000,engagedAt=900),b),mode=local).isEmpty())
        assertFalse(prefs.contains("played:${key(a)}"))
    }
    @Test fun providerProgressCannotOverwriteLocalPositionRecencyEpisodeOrDestination() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a,b));history.confirm(a,90_000,600_000,123)
        val stamp=prefs.getLong("played:${key(a)}",0)
        val remote=a.copy(position=450_000,duration=900_000,engagedAt=Long.MAX_VALUE,
            detail="First service · S9 E2 · 7 min left",intentUri="first://episode/92",art="new-art")
        val result=history.merge(listOf(remote,b),mode=local).single()
        assertEquals(90_000L,result.position);assertEquals(600_000L,result.duration)
        assertEquals(stamp,result.engagedAt);assertTrue(result.detail.contains("S1 E1"))
        assertFalse(result.detail.contains("S9 E2"));assertEquals(a.intentUri,result.intentUri)
        assertEquals(setOf(a.packageName),RecommendationPolicy.inferredServices(listOf(result)))
    }
    @Test fun newProviderOnlyRowsCannotStealLocalHeroOrServiceInference() {
        val history=PlaybackHistory(HistoryPreferences());history.merge(listOf(a));history.confirm(a)
        val result=history.merge(listOf(b.copy(engagedAt=System.currentTimeMillis()),a),mode=local)
        assertEquals(listOf(a.title),result.map { it.title })
        assertEquals(a.title,HomeContent(watching=result).feature?.title)
        assertEquals(setOf(a.packageName),RecommendationPolicy.inferredServices(result))
    }
    @Test fun modesSwitchWithoutDeletingProviderOrLocalEvidence() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a,b));history.confirm(a,90_000,600_000,123)
        val changed=a.copy(position=300_000)
        assertEquals(90_000L,history.merge(listOf(changed,b),mode=local).single().position)
        assertTrue(prefs.getLong("providerObserved:${key(a)}",0)>0)
        val combined=history.merge(listOf(changed,b))
        assertEquals(2,combined.size);assertEquals(300_000L,combined.first().position)
        val restored=PlaybackHistory(prefs).merge(listOf(changed,b),mode=local).single()
        assertEquals(90_000L,restored.position);assertEquals(123L,prefs.getLong("event:${key(a)}",0))
    }
    @Test fun confirmedTitleSurvivesProviderDisappearanceAndRecreation() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a,b));history.confirm(a,75_000,600_000,123)
        val absent=history.merge(listOf(b),mode=local).single()
        assertEquals(a.title,absent.title);assertEquals(75_000L,absent.position)
        val again=PlaybackHistory(prefs).merge(emptyList(),mode=local).single()
        assertEquals(absent,again)
        assertTrue(history.merge(emptyList()).isEmpty()) // combined remains provider-backed
    }
    @Test fun explicitCorrectionWithoutAnyProviderRowPersistsAndReorders() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.confirm(a,0,0,11);history.confirm(b,120_000,600_000,22)
        assertEquals(listOf(b.title,a.title),history.merge(emptyList(),mode=local).map { it.title })
        history.confirm(a,30_000,600_000,33)
        val corrected=PlaybackHistory(prefs).merge(emptyList(),mode=local)
        assertEquals(a.title,corrected.first().title);assertEquals(30_000L,corrected.first().position)
        assertEquals(33L,prefs.getLong("event:${key(a)}",0))
    }
    @Test fun supportedTrackerMatchingStaysAvailableWithoutPromotingUnknownPlayback() {
        val history=PlaybackHistory(HistoryPreferences())
        history.merge(listOf(a,b),mode=local)
        assertNull(history.match("different.package",listOf(a.title)))
        assertNull(history.match(a.packageName,listOf("First")))
        assertNull(history.match(a.packageName,emptyList()))
        val match=history.match(a.packageName,listOf("First show: S1 E1"))!!
        assertTrue(history.merge(listOf(a,b),mode=local).isEmpty())
        assertTrue(history.observe(match,45_000,600_000,99))
        assertEquals(45_000L,history.merge(listOf(a,b),mode=local).single().position)
        history.merge(listOf(a,a.copy(id="other",title="First show extended")),mode=local)
        assertNull(history.match(a.packageName,listOf("First show extended S2 E1")))
    }
    private fun sharedEpisode()=a.copy(id="provider:episode:92",title="Example show",
        detail="First service · S9 E2 · 7 min left",intentUri="first://episode/92",
        position=450_000,duration=900_000)

    @Test fun titleOnlyMatchCannotClaimProviderEpisodeProgressOrDestination() {
        val history=PlaybackHistory(HistoryPreferences());val remote=sharedEpisode()
        history.merge(listOf(remote))
        val match=history.match(remote.packageName,listOf("Example show:S1E1"))!!
        assertEquals(remote.title,match.title);assertEquals(remote.app,match.app)
        assertEquals(remote.art,match.art);assertEquals(remote.posterArt,match.posterArt)
        assertEquals("Title-only match must not claim a provider episode",remote.app,match.detail)
        assertEquals("",match.intentUri);assertEquals(0L,match.position);assertEquals(0L,match.duration)
        assertNotEquals(remote.id,match.id)
    }
    @Test fun automaticTitleMatchCannotPersistSharedProviderEpisodeAsLocalEvidence() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
        history.merge(listOf(remote))
        val match=history.match(remote.packageName,listOf("Example show:S1E1"))!!
        // Reproduce the pre-fix tracker path; even its matched Program must be title-only.
        history.confirm(match,45_000,600_000,99)
        val result=PlaybackHistory(prefs).merge(listOf(remote),mode=local).single()
        assertEquals("First service · 9 min left",result.detail)
        assertEquals("",result.intentUri);assertEquals(45_000L,result.position);assertEquals(600_000L,result.duration)
        assertEquals(99L,prefs.getLong("event:${key(remote)}",0))
        val saved=org.json.JSONArray(prefs.getString("known","[]")).getJSONObject(0).getJSONObject("localSnapshot")
        assertFalse(saved.toString().contains("S9 E2"));assertFalse(saved.toString().contains("first://episode/92"))
        assertEquals(result,PlaybackHistory(prefs).merge(emptyList(),mode=local).single())
    }
    @Test fun automaticTrackerBoundaryCannotPromoteEvenAnUnsanitizedProviderRow() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
        history.merge(listOf(remote))
        assertTrue(PlaybackObservation.record(history,remote,15_000,45_000,600_000,{ 88 }))
        val result=history.merge(listOf(remote),mode=local).single()
        assertEquals("First service · 9 min left",result.detail);assertEquals("",result.intentUri)
        assertEquals(45_000L,result.position);assertEquals(600_000L,result.duration)
        assertEquals(remote.art,result.art);assertEquals(remote.posterArt,result.posterArt)
    }
    @Test fun automaticMissingSessionDurationPreservesObservedPositionWithoutProviderFallback() {
        for(duration in listOf(null,0L,-1L)) {
            val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
            history.merge(listOf(remote))
            assertTrue(PlaybackObservation.record(history,remote,15_000,950_000,duration,{ 77 }))
            val result=PlaybackHistory(prefs).merge(emptyList(),mode=local).single()
            assertEquals("Observed position must not be clamped to shared-provider duration",950_000L,result.position)
            assertEquals("Missing duration stays unknown",0L,result.duration)
            assertEquals(remote.app,result.detail);assertEquals("",result.intentUri)
        }
    }
    @Test fun trackerThresholdsRequireSustainedPlaybackAndRejectKnownShortMedia() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
        history.merge(listOf(remote));val before=prefs.all
        val unexpected={ fail("Rejected sample must not create an event");0L }
        assertFalse(PlaybackObservation.record(history,remote,14_999,45_000,600_000,unexpected))
        assertFalse(PlaybackObservation.record(history,remote,15_000,14_999,null,unexpected))
        assertFalse(PlaybackObservation.record(history,remote,15_000,-1,600_000,unexpected))
        assertFalse(PlaybackObservation.record(history,remote,15_000,45_000,179_999,unexpected))
        assertEquals(before,prefs.all)
        assertTrue(PlaybackObservation.record(history,remote,15_000,700_000,600_000,{ 55 }))
        val result=history.merge(emptyList(),mode=local).single()
        assertEquals(600_000L,result.position);assertEquals(600_000L,result.duration)
    }
    @Test fun observedPlaybackReplacesPriorEpisodeClaimsAndRoundTripsWithoutInventingEquivalence() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
        history.merge(listOf(remote));history.confirm(remote)
        val match=history.match(remote.packageName,listOf("Example show:S1E1"))!!
        assertTrue(PlaybackObservation.record(history,match,15_000,45_000,600_000,{ 66 }))
        val next=remote.copy(detail="First service · S10 E4",intentUri="first://episode/104",position=750_000)
        val result=history.merge(listOf(next),mode=local).single()
        assertEquals("First service · 9 min left",result.detail);assertEquals("",result.intentUri)
        assertFalse(result.detail.contains("S1E1"));assertFalse(result.detail.contains("S10 E4"))
        assertEquals(45_000L,result.position);assertEquals(600_000L,result.duration)
        val provider=org.json.JSONArray(prefs.getString("known","[]")).getJSONObject(0)
        assertEquals(next.intentUri,provider.getString("uri"));assertEquals(next.position,provider.getLong("pos"))
        val restored=HistoryPreferences()
        LocalSettingsTransfer.restore(LocalSettingsTransfer.export(mapOf("playback" to prefs)),mapOf("playback" to restored))
        assertEquals(result,PlaybackHistory(restored).merge(emptyList(),mode=local).single())
        history.confirm(remote)
        assertTrue(history.merge(emptyList(),mode=local).single().detail.contains("S9 E2"))
    }
    @Test fun observationApiHasNoProviderProgressDefaultsAndHonorsWriterGuard() {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val remote=sharedEpisode()
        assertTrue(history.observe(remote,45_000,eventAt=44))
        val result=history.merge(emptyList(),mode=local).single()
        assertEquals(45_000L,result.position);assertEquals(0L,result.duration)
        assertEquals(remote.app,result.detail);assertEquals("",result.intentUri)
        val epoch=SettingsBackupGuard.generation
        SettingsBackupGuard.diagnosticRestore { }
        val before=prefs.all
        assertFalse(history.observe(remote,90_000,600_000,settingsGeneration=epoch))
        SettingsBackupGuard.exclusive(false,SettingsBackupCancellation()) {
            assertFalse(history.observe(remote,90_000,600_000))
            assertFalse(PlaybackObservation.record(history,remote,15_000,90_000,600_000,{ 45 }))
        }
        assertEquals(before,prefs.all)
    }
    @Test fun deliberateUserConfirmationStillAcceptsChosenProviderEpisode() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs);val chosen=sharedEpisode()
        history.merge(listOf(chosen));assertTrue(history.confirm(chosen,eventAt=123))
        val result=PlaybackHistory(prefs).merge(emptyList(),mode=local).single()
        assertTrue(result.detail.contains("S9 E2"));assertEquals(chosen.intentUri,result.intentUri)
        assertEquals(chosen.position,result.position);assertEquals(chosen.duration,result.duration)
    }
    @Test fun legacyConfirmedPositionsAreNotUpgradedToProviderEpisodeClaims() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a))
        prefs.edit().putLong("played:${key(a)}",300).putLong("position:${key(a)}",80_000)
            .putLong("duration:${key(a)}",600_000).apply()
        val result=history.merge(listOf(a.copy(position=400_000,detail="First service · S8 E2")),mode=local).single()
        assertEquals(80_000L,result.position);assertEquals(300L,result.engagedAt)
        assertFalse(result.detail.contains("S8 E2"));assertTrue(result.intentUri.isEmpty())
        assertEquals(result,PlaybackHistory(prefs).merge(emptyList(),mode=local).single())
    }
    @Test fun missingLocalProgressDoesNotBorrowProviderProgress() {
        val prefs=HistoryPreferences();prefs.edit().putLong("played:${key(a)}",300).apply()
        val result=PlaybackHistory(prefs).merge(listOf(a),mode=local).single()
        assertEquals(0L,result.position);assertEquals(0L,result.duration)
        assertTrue(RecommendationPolicy.inferredServices(listOf(result)).isEmpty())
    }
    @Test fun settingDefaultsRestoresAndInvalidValuesFailToCombined() {
        val prefs=HistoryPreferences()
        assertEquals(ViewingHistoryMode.COMBINED,ViewingHistoryMode.read(prefs))
        prefs.edit().putString(ViewingHistoryMode.KEY,local.name).apply()
        val restored=HistoryPreferences(prefs.all.toMutableMap())
        assertEquals(local,ViewingHistoryMode.read(restored))
        for(value in listOf("this_tv_only","THIS TV ONLY","", "UNKNOWN")) {
            restored.edit().putString(ViewingHistoryMode.KEY,value).apply()
            assertEquals(ViewingHistoryMode.COMBINED,ViewingHistoryMode.read(restored))
        }
        restored.edit().putInt(ViewingHistoryMode.KEY,1).apply()
        assertEquals(ViewingHistoryMode.COMBINED,ViewingHistoryMode.read(restored))
    }
    @Test fun staleGenerationNeitherPublishesNorChangesHistory() {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a));history.confirm(a)
        val old=SettingsBackupGuard.generation
        SettingsBackupGuard.diagnosticRestore { }
        val before=prefs.all
        assertFalse(history.confirm(b,settingsGeneration=old))
        assertTrue(history.merge(listOf(a.copy(position=200_000),b),old,local).isEmpty())
        assertEquals(before,prefs.all)
        assertEquals(a.title,history.merge(listOf(a,b),mode=local).single().title)
    }
    @Test fun busyRestoreRejectsBothModesAndConfirmation() {
        SettingsBackupGuard.enableAfterWriterIntegration()
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        SettingsBackupGuard.exclusive(false,SettingsBackupCancellation()) {
            for(mode in ViewingHistoryMode.entries)assertTrue(history.merge(listOf(a),mode=mode).isEmpty())
            assertFalse(history.confirm(a));assertTrue(prefs.all.isEmpty())
        }
    }
    @Test fun combinedStillImportsChronologyIgnoresTimestampChurnAndPromotesProgress() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        assertEquals(listOf(b.title,a.title),history.merge(listOf(a,b)).map { it.title })
        assertEquals(listOf(200L,100L),history.merge(listOf(a.copy(engagedAt=999),b)).map { it.engagedAt })
        history.confirm(a,90_000,600_000,123)
        val changed=a.copy(position=61_000)
        val updated=history.merge(listOf(changed,b)).first()
        assertEquals(61_000L,updated.position);assertTrue(updated.engagedAt>prefs.getLong("played:${key(a)}",0))
        history.confirm(a,100_000,600_000,456)
        assertEquals(100_000L,history.merge(listOf(changed,b)).first().position)
    }

    @Test fun combinedReappearanceDoesNotMistakeRetainedLocalMetadataForContinuousProviderProgress() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.merge(listOf(a));history.confirm(a,90_000)
        history.merge(emptyList(),mode=local)
        val back=history.merge(listOf(a.copy(position=400_000))).single()
        assertEquals(90_000L,back.position)
        assertFalse(prefs.contains("providerObserved:${key(a)}"))
        history.merge(listOf(a.copy(position=401_000)))
        assertTrue(prefs.contains("providerObserved:${key(a)}"))
    }
    @Test fun onlyMatchingArtworkCanEnrichLocalMetadata() {
        val history=PlaybackHistory(HistoryPreferences())
        val original=a.copy(imdbId="tt1234567",releaseYear=2020)
        history.merge(listOf(original));history.confirm(original,80_000)
        val enriched=history.merge(listOf(original.copy(art="new-art",posterArt="new-poster",position=400_000)),mode=local).single()
        assertEquals("new-art",enriched.art);assertEquals("new-poster",enriched.posterArt)
        assertEquals(80_000L,enriched.position)
        val conflict=history.merge(listOf(original.copy(imdbId="tt7654321",releaseYear=2026,art="wrong-art")),mode=local).single()
        assertEquals(original.art,conflict.art);assertEquals(original.imdbId,conflict.imdbId)
        assertEquals(original.releaseYear,conflict.releaseYear)
    }
    @Test fun automaticObservationRetainsClassificationWhenNoProviderRowRemains() {
        for(type in listOf("series","movie")) {
            val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
            val provider=sharedEpisode().copy(mediaType=type)
            assertTrue(PlaybackObservation.record(history,provider,15_000,45_000,600_000,{ 123L }))
            val result=PlaybackHistory(prefs).merge(emptyList(),mode=local).single()
            assertEquals(type,result.mediaType);assertEquals("",result.intentUri)
            assertEquals("First service · 9 min left",result.detail);assertEquals(45_000L,result.position)
        }
    }
    @Test fun legacyUnknownClassificationRecoversAndPersistsWithoutBorrowingEpisodeEvidence() {
        for(type in listOf("series","movie")) {
            val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
            val provider=sharedEpisode().copy(mediaType=type)
            history.merge(listOf(provider))
            // Recreate the saved shape produced by the previous title-only observer.
            val saved=provider.copy(id="local:${key(provider)}",detail=provider.app,intentUri="",mediaType="unknown")
            history.confirm(saved,45_000,600_000,123)
            val played=prefs.getLong("played:${key(provider)}",0)
            val repaired=history.merge(emptyList(),mode=local).single()
            assertEquals(type,repaired.mediaType)
            assertEquals(saved.id,repaired.id);assertEquals("",repaired.intentUri)
            assertEquals("First service · 9 min left",repaired.detail)
            assertEquals(45_000L,repaired.position);assertEquals(600_000L,repaired.duration)
            assertEquals(played,repaired.engagedAt);assertEquals(123L,prefs.getLong("event:${key(provider)}",0))
            val stored=org.json.JSONArray(prefs.getString("known","[]")).getJSONObject(0).getJSONObject("localSnapshot")
            assertEquals(type,stored.getString("type"))
            assertEquals(repaired,PlaybackHistory(prefs).merge(emptyList(),mode=local).single())
        }
    }
    @Test fun newlyAvailableProviderClassificationRepairsAnOlderUnknownSnapshot() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        val unknown=a.copy(mediaType="unknown",detail=a.app,intentUri="")
        history.merge(listOf(unknown));history.confirm(unknown,45_000,600_000,123)
        val repaired=history.merge(listOf(a.copy(mediaType="movie",intentUri="shared://new-episode",position=500_000)),mode=local).single()
        assertEquals("movie",repaired.mediaType);assertEquals("",repaired.intentUri);assertEquals(45_000L,repaired.position)
        assertEquals("movie",PlaybackHistory(prefs).merge(emptyList(),mode=local).single().mediaType)
    }
    @Test fun classificationRepairRejectsConflictingTitleIdentityAndUnclassifiedProviders() {
        val unknown=a.copy(mediaType="unknown",imdbId="tt1234567",releaseYear=2020)
        val candidates=listOf(unknown.copy(mediaType="movie",imdbId="tt7654321"),
            unknown.copy(mediaType="series",releaseYear=2026),unknown,unknown.copy(mediaType="clip"))
        for(provider in candidates) {
            val history=PlaybackHistory(HistoryPreferences())
            history.merge(listOf(unknown));history.confirm(unknown,45_000,600_000,123)
            val result=history.merge(listOf(provider),mode=local).single()
            assertEquals("unknown",result.mediaType);assertEquals(45_000L,result.position)
            assertEquals(unknown.imdbId,result.imdbId);assertEquals(unknown.releaseYear,result.releaseYear)
        }
    }
    @Test fun knownLocalClassificationSurvivesProviderReclassification() {
        val history=PlaybackHistory(HistoryPreferences())
        history.merge(listOf(a));history.confirm(a,45_000,600_000,123)
        assertEquals("series",history.merge(listOf(a.copy(mediaType="movie")),mode=local).single().mediaType)
    }
    @Test fun noSnapshotWithoutPositiveLocalEvidenceCanCreateHistory() {
        val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        history.confirm(a)
        prefs.edit().remove("played:${key(a)}").apply()
        assertTrue(history.merge(listOf(a),mode=local).isEmpty())
        assertTrue(history.merge(emptyList(),mode=local).isEmpty())
    }
    @Test fun realSettingsTransferRestoresModeLocalSnapshotAndBothEvidenceKinds() {
        val home=HistoryPreferences();val prefs=HistoryPreferences();val history=PlaybackHistory(prefs)
        val stores=mapOf("home" to home,"playback" to prefs)
        home.edit().putString(ViewingHistoryMode.KEY,local.name).apply()
        history.merge(listOf(a,b));history.confirm(a,90_000,600_000,123)
        val changed=a.copy(position=400_000)
        history.merge(listOf(changed,b),mode=local)
        val before=prefs.all;val exported=LocalSettingsTransfer.export(stores)
        home.edit().clear().apply();prefs.edit().clear().apply()
        LocalSettingsTransfer.restore(exported,stores)
        assertEquals(local,ViewingHistoryMode.read(home));assertEquals(before,prefs.all)
        assertEquals(90_000L,PlaybackHistory(prefs).merge(emptyList(),mode=local).single().position)
        val invalid=HistoryPreferences().apply { edit().putString(ViewingHistoryMode.KEY,"INVALID").apply() }
        assertTrue(runCatching { LocalSettingsTransfer.prepare(LocalSettingsTransfer.export(mapOf("home" to invalid)),setOf("home")) }.isFailure)
        assertEquals(local,ViewingHistoryMode.read(home))
        val missing=LocalSettingsTransfer.export(mapOf("home" to HistoryPreferences()))
        LocalSettingsTransfer.restore(missing,mapOf("home" to home))
        assertEquals(ViewingHistoryMode.COMBINED,ViewingHistoryMode.read(home))
    }
}

internal class HistoryPreferences(private val values:MutableMap<String,Any?> = linkedMapOf()):SharedPreferences {
    override fun getAll():MutableMap<String,*> = LinkedHashMap(values)
    override fun contains(key:String)=values.containsKey(key)
    override fun getString(key:String,defValue:String?)=values[key]?.let { it as String } ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key:String,defValues:MutableSet<String>?)=(values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key:String,defValue:Int)=values[key] as? Int ?: defValue
    override fun getLong(key:String,defValue:Long)=values[key] as? Long ?: defValue
    override fun getFloat(key:String,defValue:Float)=values[key] as? Float ?: defValue
    override fun getBoolean(key:String,defValue:Boolean)=values[key] as? Boolean ?: defValue
    override fun registerOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener:SharedPreferences.OnSharedPreferenceChangeListener) {}
    override fun edit():SharedPreferences.Editor = object:SharedPreferences.Editor {
        private val changes=linkedMapOf<String,Any?>();private var clear=false
        override fun clear()=apply { clear=true }
        override fun remove(key:String)=apply { changes[key]=null }
        override fun putString(key:String,value:String?)=apply { changes[key]=value }
        override fun putStringSet(key:String,values:MutableSet<String>?)=apply { changes[key]=values?.toSet() }
        override fun putInt(key:String,value:Int)=apply { changes[key]=value }
        override fun putLong(key:String,value:Long)=apply { changes[key]=value }
        override fun putFloat(key:String,value:Float)=apply { changes[key]=value }
        override fun putBoolean(key:String,value:Boolean)=apply { changes[key]=value }
        override fun apply() { commit() }
        override fun commit():Boolean {
            if(clear)values.clear()
            changes.forEach { (key,value)->if(value==null)values.remove(key) else values[key]=value }
            return true
        }
    }
}
