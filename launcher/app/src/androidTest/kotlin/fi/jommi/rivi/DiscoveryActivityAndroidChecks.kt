package fi.jommi.rivi

import android.app.Instrumentation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Activity/inventory callbacks with synthetic titles in the isolated package only. */
internal object DiscoveryActivityAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Discovery checks require the isolated test app" }
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
        )
        val saved=LocalSettingsTransfer.export(stores)
        val metadataFile=File(context.cacheDir,"discovery-meta/metadata-v1.bin")
        val savedMetadata=metadataFile.takeIf { it.isFile }?.readBytes()
        var opened:DiscoveryActivity?=null
        var covering:DiscoveryActivity?=null
        var activityWorker:ExecutorService?=null
        try {
            check(stores.getValue("home").edit().clear().putBoolean("discovery",false).commit())
            check(stores.getValue(PersonalizationStore.PREFS_NAME).edit().clear().commit())
            fun launch()=instrumentation.startActivitySync(Intent(context,DiscoveryActivity::class.java)
                .putExtra("originalApp",OriginalApp.RABBIT_HOLE.route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as DiscoveryActivity
            val activity=launch().also { opened=it }
            fun cover():DiscoveryActivity {
                val monitor=instrumentation.addMonitor(DiscoveryActivity::class.java.name,null,false)
                try {
                    main(instrumentation) { activity.startActivity(Intent(activity,DiscoveryActivity::class.java)
                        .putExtra("originalApp",OriginalApp.RABBIT_HOLE.route)) }
                    return monitor.waitForActivityWithTimeout(12_000) as? DiscoveryActivity
                        ?: error("Isolated covering Activity did not launch")
                } finally { instrumentation.removeMonitor(monitor) }
            }
            fun field(name:String)=DiscoveryActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
            fun set(name:String,value:Any?)=DiscoveryActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
            activityWorker=field("worker") as ExecutorService
            fun receive(value:HomeContent)=DiscoveryActivity::class.java.getDeclaredMethod("receive",HomeContent::class.java)
                .apply { isAccessible=true }.invoke(activity,value)
            @Suppress("UNCHECKED_CAST") fun installed()=field("installed") as List<AppEntry>
            @Suppress("UNCHECKED_CAST") fun trail()=field("trail") as MutableList<Program>
            @Suppress("UNCHECKED_CAST") fun picks()=field("picks") as List<BlindPickCard>
            fun repository()=field("repository") as ContentRepository
            val absentPackage="dev.zane.pretttv.removed.discovery.fixture"
            val removed=Program("tvmaze:991001","Removed fixture","","",absentPackage,"Removed app",mediaType="series")
            fun entry(packageName:String)=AppEntry("$packageName/Fixture",packageName,"Fixture",Intent(),false,1)
            fun staleRabbit() {
                repository().stop()
                set("app",OriginalApp.RABBIT_HOLE)
                set("installed",listOf(entry(absentPackage)))
                set("cacheHydrated",true);set("lookupAttempted",true)
                set("rabbitSeed",removed)
                trail().apply { clear();add(removed.copy(id="tvmaze:991002",title="Removed history")) }
                receive(HomeContent(suggestions=listOf(removed)))
                check(field("rabbitSeed")==removed && trail().size==1)
            }
            waitFor(instrumentation) { field("loaded")==true && activity.hasWindowFocus() }

            test("Discovery package receiver refresh drops unavailable Rabbit Hole seed and Back history") {
                main(instrumentation) {
                    check(installed().none { it.packageName==absentPackage })
                    staleRabbit()
                    (field("packageReceiver") as BroadcastReceiver).onReceive(activity,
                        Intent(Intent.ACTION_PACKAGE_REMOVED,Uri.parse("package:$absentPackage")))
                }
                waitFor(instrumentation) { installed().none { it.packageName==absentPackage } }
                main(instrumentation) {
                    check((field("rabbitSeed") as? Program)?.packageName!=absentPackage)
                    check(trail().none { it.packageName==absentPackage })
                    repository().stop()
                }
            }
            test("Blind Pick replaces a removed provider even when its canonical title remains available") {
                main(instrumentation) {
                    repository().stop()
                    val keptPackage="dev.zane.pretttv.kept.discovery.fixture"
                    val replacement=removed.copy(packageName=keptPackage,app="Available fixture")
                    val second=replacement.copy(id="tvmaze:991003",title="Second fixture")
                    val third=replacement.copy(id="tvmaze:991004",title="Third fixture")
                    set("app",OriginalApp.BLIND_PICK)
                    set("installed",listOf(entry(keptPackage)))
                    set("picks",listOf(removed,second,third).map { BlindPickCard(it,"Fixture clue","Fixture clue") })
                    receive(HomeContent(suggestions=listOf(replacement,second,third)))
                    check(picks().size==3 && picks().all { it.program.packageName==keptPackage })
                    check(picks().any { it.program==replacement })
                }
            }
            fun cachedFacts(rows:List<Program>,genre:String,distinct:Boolean=false) {
                metadataFile.parentFile!!.mkdirs()
                DataOutputStream(metadataFile.outputStream()).use { output ->
                    output.writeInt(0x50544d44);output.writeInt(1);output.writeInt(rows.size)
                    for(row in rows) {
                        output.writeUTF(checkNotNull(DiscoveryMetadataPolicy.cacheKey(row)))
                        output.writeLong(System.currentTimeMillis())
                        output.writeInt(1);output.writeUTF(if(distinct)row.title else genre)
                        output.writeInt(0);output.writeInt(0)
                        output.writeInt(20);output.writeInt(if(distinct)0 else 2025)
                        output.writeUTF("TVmaze");output.writeUTF("https://www.tvmaze.com/shows/${row.id.substringAfter(':')}")
                    }
                }
            }
            fun prepareEnrichment(rows:List<Program>,filters:BlindPickFilters,genre:String) {
                waitFor(instrumentation) { field("cacheBusy")==false && field("lookupBusy")==false }
                main(instrumentation) { repository().stop() }
                // Every lookup candidate is cached: this fixture performs no provider requests.
                cachedFacts(rows,genre)
                main(instrumentation) {
                    set("app",OriginalApp.BLIND_PICK)
                    set("installed",rows.map { it.packageName }.distinct().map(::entry))
                    set("filters",filters);set("picks",emptyList<BlindPickCard>())
                    set("metadata",emptyMap<String,TitleMetadata>())
                    set("cacheHydrated",true);set("lookupAttempted",true)
                    receive(HomeContent(suggestions=rows))
                }
            }
            fun enrich() {
                check(stores.getValue("home").edit().putBoolean("discovery",true).commit())
                main(instrumentation) {
                    set("lookupAttempted",false)
                    DiscoveryActivity::class.java.getDeclaredMethod("enrich").apply { isAccessible=true }.invoke(activity)
                    check(field("lookupBusy")==true) { "No metadata lookup began for the filtered candidates" }
                }
            }
            test("Blind Pick resolves missing mood and runtime facts through its real asynchronous enrichment") {
                val rows=(1..3).map { i -> removed.copy(id="tvmaze:${992000+i}",title="Unknown facts $i",genres="") }
                prepareEnrichment(rows,BlindPickFilters(DiscoveryMediaType.SHOW,BlindPickMood.FEEL_GOOD,30),"Comedy")
                try {
                    main(instrumentation) { check(picks().isEmpty()) }
                    enrich()
                    waitFor(instrumentation) { field("lookupBusy")==false && picks().size==3 }
                    main(instrumentation) { check(picks().all { "Comedy" in it.clue && "20 min" in it.clue }) }
                } finally { check(stores.getValue("home").edit().putBoolean("discovery",false).commit()) }
            }
            test("Blind Pick reapplies an active mood when enrichment contradicts three displayed genres") {
                val rows=(1..3).map { i -> removed.copy(id="tvmaze:${993000+i}",title="Changed facts $i",genres="Comedy") }
                prepareEnrichment(rows,BlindPickFilters(DiscoveryMediaType.SHOW,BlindPickMood.FEEL_GOOD),"Documentary")
                try {
                    main(instrumentation) { check(picks().size==3) }
                    enrich()
                    waitFor(instrumentation) { field("lookupBusy")==false }
                    main(instrumentation) { check(picks().isEmpty()) { "Documentary cards remained under Something lighter" } }
                } finally { check(stores.getValue("home").edit().putBoolean("discovery",false).commit()) }
            }
            test("Rabbit Hole performs at most one useful continuation and stops immediately when connections exist") {
                val rows=(1..2).map { i -> removed.copy(id="tvmaze:${994000+i}",title="Continuation fixture $i",genres="") }
                for(connected in listOf(false,true)) {
                    waitFor(instrumentation) { field("cacheBusy")==false && field("lookupBusy")==false }
                    main(instrumentation) { repository().stop() }
                    cachedFacts(rows,"Drama",distinct=!connected)
                    main(instrumentation) {
                        set("app",OriginalApp.RABBIT_HOLE);set("installed",listOf(entry(rows.first().packageName)))
                        set("rabbitSeed",rows.first());set("rabbitBatches",0)
                        set("metadata",emptyMap<String,TitleMetadata>())
                        set("cacheHydrated",true);set("lookupAttempted",true)
                        receive(HomeContent(suggestions=rows))
                    }
                    try {
                        enrich()
                        waitFor(instrumentation) { field("lookupBusy")==false }
                        main(instrumentation) {
                            check(field("rabbitBatches")==if(connected)1 else 2) { "Rabbit Hole repeated unchanged work or missed its bounded continuation" }
                            @Suppress("UNCHECKED_CAST") val facts=field("metadata") as Map<String,TitleMetadata>
                            check(RabbitHolePolicy.related(rows.first(),rows,metadata=facts).isNotEmpty()==connected)
                        }
                    } finally { check(stores.getValue("home").edit().putBoolean("discovery",false).commit()) }
                }
            }
            val recommendation=removed.copy(id="tvmaze:995001",title="Recommendation fixture",app="Fixture app")
            fun details(program:Program)=DiscoveryActivity::class.java.getDeclaredMethod("details",Program::class.java)
                .apply { isAccessible=true }.invoke(activity,program)
            fun chooseAction(label:String) {
                val menu=field("dialog") as android.app.AlertDialog
                val list=menu.listView
                val index=(0 until list.adapter.count).single { list.adapter.getItem(it).toString()==label }
                check(list.performItemClick(list.getChildAt(index),index,list.adapter.getItemId(index)))
            }
            test("Discovery title menu explains source and explicit preference without claiming entitlement") {
                prepareEnrichment(listOf(recommendation),BlindPickFilters(),"Drama")
                main(instrumentation) {
                    check(stores.getValue("home").edit().putStringSet("preferredServices",setOf(recommendation.packageName)).commit())
                    details(recommendation)
                    chooseAction("Why this recommendation?")
                    val explanation=(field("dialog") as android.app.AlertDialog).findViewById<android.widget.TextView>(android.R.id.message).text.toString()
                    check(explanation.contains("Fixture app shared this title with Android TV"))
                    check(explanation.contains("This service is selected"))
                    check(explanation.contains("cannot verify your sign-in, subscription"))
                    (field("dialog") as android.app.AlertDialog).dismiss()
                }
            }
            test("Discovery Not interested and direct Undo change eligibility without removing Watchlist") {
                main(instrumentation) {
                    val personal=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
                    check(personal.save(recommendation))
                    personal.clearUndoHistory()
                    details(recommendation)
                    chooseAction("Not interested")
                }
                waitFor(instrumentation) {
                    @Suppress("UNCHECKED_CAST") val controls=field("controls") as Map<String,android.view.View>
                    controls["discovery-undo"]?.visibility==android.view.View.VISIBLE
                }
                main(instrumentation) {
                    val personal=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
                    check(personal.recommendationFeedback().isDismissed(recommendation))
                    check(personal.isSaved(recommendation))
                    @Suppress("UNCHECKED_CAST") val candidates=field("candidates") as List<Program>
                    check(candidates.none { ContentIdentity.key(it)==ContentIdentity.key(recommendation) })
                    @Suppress("UNCHECKED_CAST") val controls=field("controls") as Map<String,android.view.View>
                    check(controls.getValue("discovery-undo").requestFocusFromTouch())
                }
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { !((field("personal\$delegate") as Lazy<*>).value as PersonalizationStore).recommendationFeedback().isDismissed(recommendation) }
                main(instrumentation) {
                    val personal=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
                    check(personal.isSaved(recommendation) && !personal.canUndo())
                    @Suppress("UNCHECKED_CAST") val candidates=field("candidates") as List<Program>
                    check(candidates.any { ContentIdentity.key(it)==ContentIdentity.key(recommendation) })
                }
            }
            test("Discovery re-registers and refreshes package inventory across actual stop and start") {
                repeat(2) {
                    main(instrumentation) { staleRabbit() }
                    covering=cover()
                    check(covering!==activity)
                    waitFor(instrumentation) { field("active")==false }
                    val stoppedGeneration=main(instrumentation) {
                        val token=field("appInventoryGeneration") as Int
                        (field("packageReceiver") as BroadcastReceiver).onReceive(activity,
                            Intent(Intent.ACTION_PACKAGE_REMOVED,Uri.parse("package:$absentPackage")))
                        check(field("appInventoryGeneration")==token) { "Stopped Discovery accepted a package callback" }
                        token
                    }
                    main(instrumentation) { covering!!.finish() }
                    covering=null
                    waitFor(instrumentation) {
                        field("active")==true && (field("appInventoryGeneration") as Int)>stoppedGeneration &&
                            installed().none { it.packageName==absentPackage }
                    }
                    main(instrumentation) {
                        check((field("rabbitSeed") as? Program)?.packageName!=absentPackage)
                        check(trail().none { it.packageName==absentPackage })
                        repository().stop()
                    }
                }
            }
        } finally {
            try {
                covering?.let { activity -> main(instrumentation) { activity.finish() };instrumentation.waitForIdleSync() }
                opened?.let { activity -> main(instrumentation) { activity.finish() };instrumentation.waitForIdleSync() }
                activityWorker?.let { check(it.awaitTermination(3,TimeUnit.SECONDS)) { "Discovery worker did not finish before fixture restoration" } }
            } finally {
                if(savedMetadata==null)metadataFile.delete() else metadataFile.writeBytes(savedMetadata)
                LocalSettingsTransfer.restore(saved,stores)
            }
        }
    }

    private fun waitFor(instrumentation:Instrumentation,predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+12_000
        do { if(main(instrumentation,predicate))return;SystemClock.sleep(40) } while(SystemClock.uptimeMillis()<end)
        error("Timed out waiting for isolated Discovery state")
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<T>()
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch(t:Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
        return result.get()
    }
}
