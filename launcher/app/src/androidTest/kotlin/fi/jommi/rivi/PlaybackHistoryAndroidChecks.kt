package fi.jommi.rivi

import android.content.Context
import org.json.JSONArray

/** Actual preference-backed history with isolated provider fixtures, never account or media-session data. */
internal object PlaybackHistoryAndroidChecks {
    fun run(context:Context,test:(String,()->Unit)->Unit) {
        check(context.packageName=="dev.zane.pretttv.stability")
        val playback=context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE)
        val personal=context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE)
        val stores=mapOf("playback" to playback,PersonalizationStore.PREFS_NAME to personal)
        val backup=LocalSettingsTransfer.export(stores)
        val a=Program("fixture:a","First show","First service · S1 E1 · 9 min left","","fixture.first","First service",
            position=60_000,duration=600_000,watchingSource=true,engagedAt=100,mediaType="series")
        val b=a.copy(id="fixture:b",title="Second show",detail="Second service · S1 E1 · 9 min left",packageName="fixture.second",app="Second service",engagedAt=200)
        fun key(p:Program)="${p.packageName}|${p.titleKey}"
        fun reset():PlaybackHistory { check(playback.edit().clear().commit()); check(personal.edit().clear().commit());return PlaybackHistory(context) }
        try {
            test("Provider history import and timestamp-only churn preserve original chronology") {
                val history=reset(); check(history.merge(listOf(a,b)).map { it.title }==listOf(b.title,a.title))
                val result=history.merge(listOf(a.copy(engagedAt=System.currentTimeMillis(),art="different"),b))
                check(result.map { it.engagedAt }==listOf(200L,100L))
                check(!playback.contains("providerLastObserved"))
            }
            test("A changed provider playback position promotes the row and hero and survives recreation") {
                val history=reset();history.merge(listOf(a,b))
                val progressed=a.copy(position=61_000)
                val result=history.merge(listOf(progressed,b))
                check(result.first().title==a.title && HomeContent(watching=result).feature?.title==a.title)
                val stamp=playback.getLong("providerObserved:${key(a)}",0);check(stamp>200)
                val restored=PlaybackHistory(context).merge(listOf(progressed,b))
                check(restored.first().engagedAt==stamp && playback.getLong("providerLastObserved",0)==stamp)
                check(!playback.contains("played:${key(a)}") && !playback.contains("event:${key(a)}"))
                check(PersonalizationStore(context).save(a,stamp-1))
                check(PersonalizationStore(context).consumePlaybackHistory(restored)==0)
            }
            test("Explicit episode changes promote only after nonzero playback exists") {
                val history=reset();history.merge(listOf(a,b))
                val next=a.copy(detail="First service · S1 E2 · 9 min left")
                val result=history.merge(listOf(next,b));check(result.first().title==a.title)
                val stamp=result.first().engagedAt
                val unplayed=next.copy(detail="First service · S1 E3",position=0)
                check(history.merge(listOf(unplayed,b)).first().engagedAt==stamp)
                check(history.merge(listOf(unplayed.copy(position=15_000),b)).first().engagedAt>stamp)
            }
            test("Small provider position updates accumulate while the latest raw sample is retained") {
                val history=reset();history.merge(listOf(a,b))
                for(delta in listOf(250L,500L,750L)) {
                    history.merge(listOf(a.copy(position=a.position+delta),b));check(!playback.contains("providerObserved:${key(a)}"))
                }
                check(history.merge(listOf(a.copy(position=61_000),b)).first().title==a.title)
                val raw=JSONArray(playback.getString("known","[]")).getJSONObject(0)
                check(raw.getLong("pos")==61_000L && raw.getJSONObject("providerProgressBaseline").getLong("pos")==61_000L)
            }
            test("New provider progress replaces stale local position while a later confirmation wins again") {
                val history=reset();history.merge(listOf(a,b));history.confirm(a,300_000,600_000,111)
                check(history.merge(listOf(a,b)).first().position==300_000L)
                val progressed=a.copy(position=61_000)
                val updated=history.merge(listOf(progressed,b)).first()
                check(updated.position==61_000L && updated.engagedAt>playback.getLong("played:${key(a)}",0))
                history.confirm(a,90_000,600_000,222)
                val confirmed=history.merge(listOf(progressed,b)).first()
                check(confirmed.position==90_000L && confirmed.engagedAt>updated.engagedAt)
                check(playback.getLong("event:${key(a)}",0)==222L)
            }
            test("Cross-app progress orders monotonically and a batch does not invent ordering among ties") {
                val history=reset();history.merge(listOf(a,b))
                val a1=a.copy(position=61_000);val b1=b.copy(position=61_000)
                val first=history.merge(listOf(a1,b)).first().engagedAt
                val second=history.merge(listOf(a1,b1));check(second.first().title==b.title && second.first().engagedAt>first)
                val both=history.merge(listOf(b1.copy(position=62_000),a1.copy(position=62_000)))
                check(both.map { it.title }==listOf(b.title,a.title) && both[0].engagedAt==both[1].engagedAt)
            }
            test("A late new title uses a credible fresh provider stamp without promoting stale backfill") {
                val history=reset();val now=System.currentTimeMillis()
                check(playback.edit().putLong("baselineCutoff",now-60_000).commit())
                history.merge(listOf(a,b))
                val fresh=a.copy(id="fixture:new",title="New watched title",engagedAt=now-1000)
                val stale=a.copy(id="fixture:backfill",title="Old backfill",engagedAt=50)
                val unknown=a.copy(id="fixture:unknown",title="Unknown time",engagedAt=0)
                val unplayed=a.copy(id="fixture:next",title="Unplayed next title",position=0,engagedAt=now-500)
                val result=history.merge(listOf(a,b,fresh,stale,unknown,unplayed))
                check(result.first().title==fresh.title && result.first().engagedAt==fresh.engagedAt)
                check(result.single { it.title==stale.title }.engagedAt==50L && result.single { it.title==unknown.title }.engagedAt==0L)
                check(result.single { it.title==unplayed.title }.engagedAt<=now-60_000)
                check(!playback.contains("providerLastObserved"))
            }
            test("Observation stamps stay monotonic after a clock rollback and round-trip settings safely") {
                val history=reset();history.merge(listOf(a,b))
                val future=System.currentTimeMillis()+60_000
                check(playback.edit().putLong("providerLastObserved",future).commit())
                history.merge(listOf(a.copy(position=61_000),b))
                check(playback.getLong("providerObserved:${key(a)}",0)==future+1)
                val exported=LocalSettingsTransfer.export(stores)
                check(playback.edit().clear().commit());LocalSettingsTransfer.restore(exported,stores)
                check(playback.getLong("providerLastObserved",0)==future+1)
            }
        } finally { LocalSettingsTransfer.restore(backup,stores) }
    }
}
