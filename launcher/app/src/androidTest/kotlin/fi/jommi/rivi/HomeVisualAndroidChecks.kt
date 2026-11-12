package fi.jommi.rivi

import android.animation.ValueAnimator
import android.app.Dialog
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Actual Home widgets with isolated synthetic rows. Never opens or stops another app. */
internal object HomeVisualAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Visual checks require the isolated test app" }
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
        )
        val savedSettings=LocalSettingsTransfer.export(stores)
        var opened:MainActivity?=null
        val injectedArt=Bitmap.createBitmap(24,24,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(50,90,70)) }
        try {
            check(stores.getValue("home").edit().clear().putBoolean("discovery",false).putBoolean("weatherEnabled",false)
                .putBoolean("tvPermissionAsked",true).putString("layout",HomeLayout.BALANCED.name).commit())
            check(stores.getValue("apps").edit().clear().commit())
            check(stores.getValue(PersonalizationStore.PREFS_NAME).edit().clear().commit())
            val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            opened=activity
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
            fun set(name:String,value:Any)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
            fun call(name:String,vararg args:Any) {
                MainActivity::class.java.declaredMethods.single { it.name==name && it.parameterTypes.size==args.size }
                    .apply { isAccessible=true }.invoke(activity,*args)
            }
            @Suppress("UNCHECKED_CAST") fun views()=field("focusables") as Map<String,View>
            @Suppress("UNCHECKED_CAST") fun rows()=field("horizontalRows") as Map<String,HorizontalScrollView>
            @Suppress("UNCHECKED_CAST") fun bound()=field("boundPrograms") as Map<String,Program?>
            fun root()=field("root") as ScrollView
            fun content()=field("content") as HomeContent
            fun personal()=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
            fun tag(p:Program)="discover-${p.id}"
            fun rowTags(key:String):List<String> {
                val strip=rows()[key]?.getChildAt(0) as? ViewGroup ?: return emptyList()
                return (0 until strip.childCount).map { strip.getChildAt(it).tag.toString() }
            }
            fun settled()=activity.hasWindowFocus() && field("pendingViewport")==null && root().isLaidOut && !root().isLayoutRequested
            fun focus(key:String) {
                main(instrumentation) { check(views()[key]?.requestFocusFromTouch()==true) { "Missing focus target $key" } }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag==key && visible(activity.currentFocus!!) }
            }
            val programs=(0 until 12).map { i -> Program("visual:$i","Fixture title $i","Fixture provider · S1 E2","",
                "visual.fixture","Fixture provider",position=60_000,duration=600_000,mediaType="series") }
            val base=HomeContent(watching=listOf(programs.first().copy(engagedAt=100)),suggestions=programs,
                trending=programs.map { it.copy(id="trending:${it.id}",title="Trending ${it.title}") },snapshotAt=System.currentTimeMillis())
            val syntheticApps=(0 until 12).map { i -> AppEntry("${context.packageName}/visual-fixture-$i",context.packageName,
                "Fixture app $i",Intent().setComponent(ComponentName(context.packageName,"fi.jommi.rivi.NoSuchVisualFixture$i")),false,1) }
            fun reset(value:HomeContent=base) {
                main(instrumentation) {
                    set("discoverRotation",DiscoverRotation(candidateLimit=180))
                    personal().setRowPreferences(RowPreferences(hidden=setOf("watching-shows","watching-movies","watchlist","episodes","explore","new")))
                    set("content",value); call("refreshHomeRows")
                }
                waitFor(instrumentation) { settled() && views()[tag(programs.first())]!=null }
            }
            fun publish(value:HomeContent) { main(instrumentation) { set("content",value); call("refreshProviderRows") } }
            waitFor(instrumentation) { activity.hasWindowFocus() && field("appDiscoveryComplete")==true && root().isLaidOut }
            main(instrumentation) {
                set("fixtureRefreshSuspended",true)
                (field("contentRepo") as ContentRepository).stop()
                (field("weatherRepo") as WeatherRepository).stop()
                // In-flight icon callbacks belong to the prior discovery generation.
                set("generation",(field("generation") as Int)+1)
                set("apps",syntheticApps)
                (field("prefs") as Prefs).apply {
                    favourites=syntheticApps.map { it.key }; favouritesInitialised=true; hidden=emptySet()
                }
                set("content",base)
                call("onNewIntent",Intent().putExtra("layout",HomeLayout.BALANCED.name))
            }
            waitFor(instrumentation) { settled() }

            test("Balanced composition has three portrait destinations and a finite remote path") {
                reset(); focus("feature")
                main(instrumentation) {
                    fun position(key:String)=Rect().also { check(views().getValue(key).getGlobalVisibleRect(it)) }
                    val hero=position("feature")
                    val posters=(0..2).map { position("next-$it") }
                    check(posters.all { it.left>hero.right && it.top>=hero.top && it.height()>it.width() }) { "Discover cards must remain to the right of the large feature" }
                    check(posters.map { it.top }.distinct().size==1 && posters.zipWithNext().all { (a,b)->a.right<b.left }) { "Discover cards are not in one separated horizontal row" }
                    check(hero.width()>posters.sumOf { it.width() }) { "Feature lost its primary size" }
                }
                for(key in listOf("next-0","next-1","next-2","next-2")) {
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                    waitFor(instrumentation) { activity.currentFocus?.tag==key && visible(activity.currentFocus!!) }
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                waitFor(instrumentation) { activity.currentFocus?.tag=="different-picks" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(instrumentation) { activity.currentFocus?.tag=="different-picks" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(instrumentation) { activity.currentFocus?.tag=="next-0" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(instrumentation) { activity.currentFocus===field("appAnchor") && visible(activity.currentFocus!!) }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                waitFor(instrumentation) { activity.currentFocus?.tag=="feature" }
                focus("next-0")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT)
                waitFor(instrumentation) { activity.currentFocus?.tag=="feature" }
            }
            test("Compact Home keeps full portraits named apps and the first continuation row visible at Standard") {
                main(instrumentation) {
                    personal().setRowPreferences(RowPreferences())
                    set("content",base);call("refreshHomeRows")
                    call("applyInterfaceSize",HomeInterfaceSize.STANDARD)
                }
                waitFor(instrumentation) { settled() }
                focus("feature")
                main(instrumentation) {
                    call("resetHomePosition")
                    val hero=views().getValue("feature")
                    val poster=views().getValue("next-0")
                    val different=views().getValue("different-picks")
                    check("browse-shows" !in views()) { "Redundant Browse shows returned to the compact heading" }
                    val first=views().getValue("watching-shows-${programs.first().id}")
                    val bounds=Rect();check(first.getGlobalVisibleRect(bounds) && bounds.height()==first.height)
                    check(root().scrollY==0) { "First continuation row needed scrolling" }
                    val scale=field("scale") as Float
                    check(abs(poster.width-240*scale)<=1 && abs(hero.height-502*scale)<=1)
                    val actionBounds=Rect();val posterBounds=Rect()
                    check(different.getGlobalVisibleRect(actionBounds) && poster.getGlobalVisibleRect(posterBounds))
                    check(posterBounds.top-actionBounds.bottom>=12*scale) { "Different picks is flush against the posters" }
                    val caption=activity.window.decorView.findViewWithTag<TextView>("app-caption-${syntheticApps.first().key}")
                    check(caption!=null && caption.text.toString()==syntheticApps.first().label && visible(caption))
                    check(!caption.isFocusable) { "App caption introduced a duplicate focus target" }
                    val range=activity.window.decorView.findViewWithTag<TextView>("your-apps-range")
                    check(range.text=="1–8 of 12" && !range.isFocusable) { "App counter did not describe the finite row" }
                }
                reset()
            }
            test("History filters retain remote position and clear shared titles before a local-only refresh") {
                main(instrumentation) {
                    personal().setRowPreferences(RowPreferences())
                    set("content",base);call("refreshHomeRows")
                }
                waitFor(instrumentation) { settled() }
                val local="watching-shows-history-this_tv_only"
                focus(local)
                main(instrumentation) { check(views().getValue(local).performClick()) }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag==local }
                main(instrumentation) {
                    check(ViewingHistoryMode.read(stores.getValue("home"))==ViewingHistoryMode.THIS_TV_ONLY)
                    check(bound()["feature"]==null) { "Shared-account hero survived the local-only switch" }
                    check(views().getValue(local).isSelected)
                }
                val combined="watching-shows-history-combined"
                focus(combined)
                main(instrumentation) { check(views().getValue(combined).performClick()) }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag==combined }
                main(instrumentation) { check(ViewingHistoryMode.read(stores.getValue("home"))==ViewingHistoryMode.COMBINED) }
                reset()
            }
            test("Initial partial Discover fills its three cards then remains stable on later refresh") {
                reset(base.copy(suggestions=programs.take(2),trending=emptyList(),discoverySettled=false))
                fun picks()=(0..2).mapNotNull { bound()["next-$it"]?.titleKey }
                main(instrumentation) { check(picks()==listOf(programs[1].titleKey)) }
                publish(base.copy(trending=emptyList(),discoverySettled=true))
                waitFor(instrumentation) { settled() && picks().size==3 }
                val established=main(instrumentation) { picks() }
                publish(base.copy(suggestions=programs.reversed(),trending=emptyList(),discoverySettled=false))
                waitFor(instrumentation) { settled() }
                main(instrumentation) { check(picks()==established) { "A later refresh re-ranked established picks" } }
                reset()
            }
            test("Different picks is explicit keeps the hero stable and top bar Undo restores the trio") {
                reset();focus("different-picks")
                fun picks()=(0..2).mapNotNull { bound()["next-$it"]?.titleKey }
                val before=main(instrumentation) { personal().clearUndoHistory();picks() }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && picks()!=before && views()["nav-undo"]?.visibility==View.VISIBLE }
                val after=main(instrumentation) {
                    check(picks().none { it in before });check(bound()["feature"]==base.feature)
                    check(activity.currentFocus?.tag=="different-picks");picks()
                }
                publish(base.copy(suggestions=programs.reversed()))
                waitFor(instrumentation) { settled() }
                main(instrumentation) { check(picks()==after) { "Background refresh rearranged the explicit batch" } }
                focus("nav-undo");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && picks()==before }
                main(instrumentation) { check(bound()["feature"]==base.feature) }
            }
            test("Different picks reaches beyond the artwork pool and exhaustion is a no op") {
                reset(base.copy(trending=emptyList()));focus("different-picks")
                val seen=linkedSetOf<String>()
                main(instrumentation) {
                    personal().clearUndoHistory()
                    repeat(4) {
                        val batch=(0..2).mapNotNull { bound()["next-$it"]?.titleKey }
                        check(batch.none { it in seen });seen.addAll(batch)
                        if(it<3)call("differentPicks")
                    }
                    check(seen==programs.drop(1).map { it.titleKey }.toSet()) { "Rotation truncated the local catalog" }
                    val before=(0..2).map { bound()["next-$it"] }
                    val undo=personal().undoLabel()
                    call("differentPicks")
                    check((0..2).map { bound()["next-$it"] }==before && personal().undoLabel()==undo)
                    check(views().getValue("different-picks").contentDescription.toString().contains("No more unseen"))
                }
                reset()
            }
            test("Different picks Undo cannot restore a removed recommendation") {
                reset();focus("different-picks")
                val removed=main(instrumentation) { checkNotNull(bound()["next-0"]) }
                main(instrumentation) { personal().clearUndoHistory();call("differentPicks") }
                publish(base.copy(suggestions=programs.filterNot { it.id==removed.id }))
                waitFor(instrumentation) { settled() }
                focus("nav-undo");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && personal().undoLabel()==null }
                main(instrumentation) { check((0..2).mapNotNull { bound()["next-$it"] }.none { it.id==removed.id }) }
                reset()
            }
            test("Top Shows retains catalog access without a duplicate compact heading control") {
                reset(); focus("nav-shows")
                main(instrumentation) {
                    check("browse-shows" !in views())
                    check(views().getValue("nav-shows").performClick())
                }
                waitFor(instrumentation) { activity.currentFocus===field("showAnchor") && visible(activity.currentFocus!!) }
                main(instrumentation) {
                    check(activity.hasWindowFocus())
                    check(rowTags("discover").isNotEmpty()) { "Full catalog shelf was removed with the duplicate button" }
                }
            }
            test("Hero glyph keeps honest Open versus Resume semantics without a text action pill") {
                for((program,expected) in listOf(
                    programs.first() to "Open Fixture provider",
                    programs.first().copy(intentUri="fixture://episode/2") to "Resume",
                    programs.first().copy(intentUri="fixture://episode/2",position=0) to "Open Fixture provider")) {
                    reset(base.copy(watching=listOf(program.copy(engagedAt=100))))
                    focus("feature")
                    main(instrumentation) {
                        val card=views().getValue("feature") as PrettTvCard
                        check(card.action==expected && card.contentDescription.toString().contains(expected))
                        if(expected!="Resume")check(!card.contentDescription.toString().contains("Resume"))
                        check(card.isClickable && card.isFocusable)
                        assertHeroGlyph(card,field("scale") as Float)
                    }
                }
                reset()
            }
            test("Balanced discovery survives incomplete and empty feeds without dead focus targets") {
                reset(); focus("next-2")
                val one=base.copy(suggestions=listOf(programs[1]),trending=emptyList(),discover=emptyList())
                publish(one)
                waitFor(instrumentation) { settled() && bound()["next-0"]==programs[1] && views().getValue("next-2").visibility==View.INVISIBLE && activity.currentFocus?.tag=="next-0" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(instrumentation) { activity.currentFocus?.tag=="next-0" }
                publish(one.copy(suggestions=emptyList()))
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag=="discovery-empty" && views().getValue("discovery-empty").visibility==View.VISIBLE && (0..2).all { views().getValue("next-$it").visibility==View.INVISIBLE } }
                focus("feature")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(instrumentation) { activity.currentFocus?.tag=="discovery-empty" }
                publish(base)
                main(instrumentation) { call("differentPicks") }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag=="next-0" && (0..2).all { views().getValue("next-$it").visibility==View.VISIBLE } && views().getValue("discovery-empty").visibility==View.GONE }
                focus("feature")
            }
            test("Balanced posters use supplied portrait art without replacing landscape card metadata") {
                reset()
                val updated=programs[1].copy(art="content://visual.fixture/landscape",posterArt="content://visual.fixture/poster")
                publish(base.copy(suggestions=programs.map { if(it.id==updated.id)updated else it }))
                waitFor(instrumentation) { bound()["next-0"]==updated && bound()[tag(updated)]==updated }
                main(instrumentation) {
                    val requests=field("cardArt") as List<*>
                    fun artwork(key:String)=requests.first { request ->
                        request!!::class.java.getDeclaredField("card").apply { isAccessible=true }.get(request)===views().getValue(key)
                    }.let { request -> request!!::class.java.getDeclaredField("url").apply { isAccessible=true }.get(request) }
                    check(artwork("next-0")==updated.posterArt)
                    check(artwork(tag(updated))==updated.art)
                    check(bound()["feature"]==base.feature)
                }
                reset()
            }
            test("Balanced display-only art rebinds unchanged programs and retains selected title") {
                reset(); focus("next-1")
                val hero=main(instrumentation) { views().getValue("feature") }
                val selected=programs[2]
                val hints=mapOf(selected.id to PresentationArtwork(poster="content://visual.fixture/verified-poster"),
                    programs[0].id to PresentationArtwork(backdrop="content://visual.fixture/clean-backdrop"))
                publish(base.copy(presentationArtwork=hints))
                waitFor(instrumentation) { settled() && bound()["next-1"]==selected && activity.currentFocus?.tag=="next-1" }
                main(instrumentation) {
                    val requests=field("cardArt") as List<*>
                    fun artwork(key:String)=requests.firstOrNull { request ->
                        request!!::class.java.getDeclaredField("card").apply { isAccessible=true }.get(request)===views().getValue(key)
                    }?.let { request -> request::class.java.getDeclaredField("url").apply { isAccessible=true }.get(request) }
                    check(artwork("next-1")==hints.getValue(selected.id).poster)
                    check(artwork("feature")==hints.getValue(programs[0].id).backdrop)
                    check(artwork(tag(selected))==null) { "Presentation metadata leaked into the lower provider shelf" }
                    check(views()["feature"]===hero && bound()["feature"]==base.feature)
                    check(selected.art.isBlank() && selected.posterArt.isBlank())
                }
                reset()
            }
            test("Provider metadata refresh retains the focused native card and its bitmap") {
                reset(); val target=programs[3]; focus(tag(target))
                val card=main(instrumentation) { (views().getValue(tag(target)) as PrettTvCard).apply { art=injectedArt } }
                val updated=target.copy(detail="Fixture provider · S1 E3 · 7 min left",position=180_000)
                publish(base.copy(suggestions=programs.map { if(it.id==updated.id)updated else it }))
                waitFor(instrumentation) { bound()[tag(target)]==updated && settled() }
                main(instrumentation) {
                    check(views()[tag(target)]===card && activity.currentFocus===card)
                    check(card.art===injectedArt && !injectedArt.isRecycled)
                    check(card.detail==updated.detail && card.progress==updated.progress)
                }
            }
            test("An active row keeps its order until focus leaves, then adopts the latest provider order") {
                reset(); focus(tag(programs[3]))
                val oldOrder=main(instrumentation) { rowTags("discover") }
                val incoming=programs.reversed().map { it.copy(detail="Updated provider metadata") }
                publish(base.copy(suggestions=incoming))
                waitFor(instrumentation) { bound()[tag(programs[3])]?.detail=="Updated provider metadata" && settled() }
                main(instrumentation) {
                    check(rowTags("discover")==oldOrder)
                    check(activity.currentFocus?.tag==tag(programs[3]))
                }
                focus("feature")
                waitFor(instrumentation) { rowTags("discover")==incoming.map(::tag) && settled() }
                main(instrumentation) { check(activity.currentFocus?.tag=="feature") }
            }
            test("Active Continue watching promotes new playback and hero while retaining the focused title") {
                for((key,type) in listOf("watching-shows" to "series","watching-movies" to "movie")) {
                    val watching=programs.mapIndexed { i,p -> p.copy(mediaType=type,engagedAt=100L-i) }
                    val previous=base.copy(watching=watching)
                    val selected=watching[7]
                    val selectedKey="$key-${selected.id}"
                    main(instrumentation) {
                        personal().setRowPreferences(RowPreferences(hidden=
                            setOf("watching-shows","watching-movies","watchlist","episodes","explore","new")-key))
                        set("content",previous); call("refreshHomeRows")
                    }
                    waitFor(instrumentation) { settled() && rowTags(key)==watching.map { "$key-${it.id}" } }
                    focus(selectedKey)
                    val previousRoot=main(instrumentation) { root() }
                    val updated=selected.copy(detail="Fixture provider · S1 E3 · 7 min left",position=180_000,engagedAt=1_000)
                    val latest=ContentPolicy.chronological(watching.map { if(it.id==updated.id)updated else it })
                    publish(previous.copy(watching=latest))
                    waitFor(instrumentation) {
                        settled() && rowTags(key)==latest.map { "$key-${it.id}" } &&
                            bound()[selectedKey]==updated && bound()["feature"]==updated &&
                            activity.currentFocus?.tag==selectedKey && visible(activity.currentFocus!!)
                    }
                    main(instrumentation) {
                        check(root()===previousRoot) { "Playback update rebuilt the whole Home" }
                        check(rowTags(key).first()==selectedKey)
                        val card=views().getValue(selectedKey) as PrettTvCard
                        val presentation=ProgramPresentation.from(updated)
                        if(card.isContinuationCard) {
                            check(card.detail==presentation.source && card.secondaryDetail==presentation.compactSelectedDetail) { "Compact playback metadata did not refresh" }
                        } else check(card.detail==updated.detail) { "Legacy playback metadata did not refresh" }
                        check(card.progress==updated.progress)
                        @Suppress("UNCHECKED_CAST") val deferred=field("deferredRows") as Set<String>
                        check(key !in deferred) { "Continue watching chronology was deferred until focus left" }
                    }
                }
            }
            test("Same-row focus and metadata updates do not interrupt an in-progress row movement") {
                reset(); focus(tag(programs[6]))
                publish(base.copy(suggestions=programs.reversed()))
                instrumentation.waitForIdleSync()
                var moving:ValueAnimator?=null
                try {
                    main(instrumentation) {
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT))
                        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT))
                        @Suppress("UNCHECKED_CAST") val motions=field("rowMotion") as Map<HorizontalScrollView,ValueAnimator>
                        moving=motions[rows().getValue("discover")]
                        if(ValueAnimator.areAnimatorsEnabled()) {
                            check(moving!=null) { "Fixture movement did not exercise animated scrolling" }
                            // Hold the finite test animation long enough to inspect queued focus callbacks.
                            moving!!.duration=60_000
                        }
                        check(activity.currentFocus?.tag==tag(programs[7]))
                        set("content",content().copy(suggestions=programs.reversed().map { it.copy(detail="Moving refresh") }))
                        call("refreshProviderRows")
                    }
                    instrumentation.waitForIdleSync()
                    main(instrumentation) {
                        check(activity.currentFocus?.tag==tag(programs[7]))
                        check(rowTags("discover")==programs.map(::tag))
                        if(ValueAnimator.areAnimatorsEnabled()) {
                            @Suppress("UNCHECKED_CAST") val motions=field("rowMotion") as Map<HorizontalScrollView,ValueAnimator>
                            check(moving?.isStarted==true && motions[rows().getValue("discover")]===moving)
                        }
                    }
                } finally { main(instrumentation) { call("stopRowMotion") } }
            }
            test("A removed focused title is replaced immediately instead of being deferred") {
                reset(); val removed=programs[4]; focus(tag(removed))
                publish(base.copy(suggestions=programs.filterNot { it.id==removed.id }))
                waitFor(instrumentation) { views()[tag(removed)]==null && settled() }
                main(instrumentation) {
                    check(rowTags("discover")==programs.filterNot { it.id==removed.id }.map(::tag))
                    check(activity.currentFocus?.tag.toString().startsWith("discover-") && visible(activity.currentFocus!!))
                }
            }
            test("An expired focused live event disappears immediately under the existing expiry policy") {
                val now=System.currentTimeMillis()
                val event=SportsEvent("visual:event","Fixture game","NFL",now,"in","Playing","Fixture network","","",now-800_000)
                reset(base.copy(games=listOf(event))); focus("game-${event.id}")
                val eligible=SportsPolicy.upcoming(listOf(event),now+200_000)
                check(eligible.isEmpty())
                publish(base.copy(games=eligible))
                waitFor(instrumentation) { views()["game-${event.id}"]==null && settled() }
                main(instrumentation) { check(activity.currentFocus!=null && visible(activity.currentFocus!!)) }
            }
            test("Status-only updates preserve native child identities, focus and row position") {
                reset(); focus(tag(programs[5]))
                val original=main(instrumentation) { views().toMap() }
                val position=main(instrumentation) { root().scrollY to rows().mapValues { it.value.scrollX } }
                for(status in listOf(base.copy(refreshing=true),base.copy(refreshFailed=true),base.copy(offline=true),base)) {
                    publish(status)
                    waitFor(instrumentation) { settled() }
                    main(instrumentation) {
                        check(views().keys==original.keys && original.all { (key,view)->views()[key]===view })
                        check(activity.currentFocus===original[tag(programs[5])])
                        check(root().scrollY==position.first && rows().mapValues { it.value.scrollX }==position.second)
                    }
                }
            }
            test("Anchored app actions default to Open and Back preserves the exact origin without an action") {
                reset()
                val key="favorite-${syntheticApps.last().key}"; focus(key)
                val prior=main(instrumentation) { Triple(views().getValue(key),root().scrollY,rows().mapValues { it.value.scrollX }) }
                val appPreferences=LocalSettingsTransfer.export(mapOf("apps" to stores.getValue("apps")))
                main(instrumentation) { check(prior.first.performLongClick()) }
                waitFor(instrumentation) {
                    val panel=field("appActionPanel") as? Dialog
                    panel?.isShowing==true && panel.window?.decorView?.hasWindowFocus()==true &&
                        (panel.currentFocus as? TextView)?.text?.toString()=="Open"
                }
                main(instrumentation) {
                    check(views()[key]===prior.first && visible(prior.first))
                    check(root().scrollY==prior.second && rows().mapValues { it.value.scrollX }==prior.third)
                }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                waitFor(instrumentation) { field("appActionPanel")==null && settled() && activity.currentFocus===prior.first }
                main(instrumentation) {
                    check(root().scrollY==prior.second && rows().mapValues { it.value.scrollX }==prior.third)
                    check(views()[key]===prior.first)
                }
                check(LocalSettingsTransfer.export(mapOf("apps" to stores.getValue("apps")))==appPreferences)
            }
            test("Top navigation stays visible while scrolling and remote boundaries remain finite in A B and C") {
                for(layout in HomeLayout.entries) {
                    main(instrumentation) { call("onNewIntent",Intent().putExtra("layout",layout.name)) }
                    waitFor(instrumentation) { settled() }
                    val bounds=main(instrumentation) {
                        val bar=field("navigationBar") as View
                        check(!descendant(bar,root())) { "Navigation is inside the scrolling content" }
                        Rect().also { check(bar.getGlobalVisibleRect(it) && it.height()==bar.height) }
                    }
                    main(instrumentation) { root().scrollTo(0,root().getChildAt(0).height) }
                    waitFor(instrumentation) { root().scrollY>0 }
                    main(instrumentation) {
                        val bar=field("navigationBar") as View
                        val later=Rect(); check(bar.getGlobalVisibleRect(later) && later==bounds)
                        check(views().keys.none { it.startsWith("rail-") })
                        root().scrollTo(0,0)
                        check((field("firstCard") as View).requestFocusFromTouch())
                    }
                    waitFor(instrumentation) { settled() && visible(activity.currentFocus!!) }
                    val first=main(instrumentation) { activity.currentFocus }
                    repeat(3) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT) }
                    main(instrumentation) { check(activity.currentFocus===first && root().alpha==1f) }
                    instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                    waitFor(instrumentation) { activity.currentFocus?.tag=="nav-home" && visible(activity.currentFocus!!) }
                    for(hardwareHome in listOf(false,true)) {
                        focus("favorite-${syntheticApps.last().key}")
                        val before=main(instrumentation) { root() to views().toMap() }
                        main(instrumentation) {
                            check(rows().values.any { it.scrollX>0 }) { "Home reset fixture did not scroll the app row" }
                            // Select Library first, so reset must also clear the previous tab highlight.
                            check(views().getValue("nav-apps").performClick())
                            check(field("navigationSelection")=="nav-apps")
                            if(hardwareHome)call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                            else check(views().getValue("nav-home").performClick())
                        }
                        waitFor(instrumentation) {
                            settled() && activity.currentFocus===field("firstCard") && root().scrollY==0 &&
                                rows().values.all { it.scrollX==0 } && field("navigationSelection")=="nav-home"
                        }
                        main(instrumentation) {
                            check(root()===before.first && views().all { (key,view)->before.second[key]===view }) {
                                "Home reset recreated unchanged cards instead of retaining their artwork and focus state"
                            }
                        }
                    }
                }
            }
            test("Warm Home return retains the hierarchy after another activity and avoids rebuilding unchanged cards") {
                main(instrumentation) {
                    fun shelf(prefix:String)=(0 until 60).map { programs[it%programs.size].copy(id="$prefix:$it",title="$prefix title $it") }
                    personal().setRowPreferences(RowPreferences())
                    set("content",base.copy(watching=shelf("Watching"),suggestions=shelf("Suggestions"),
                        discover=shelf("Discover"),trending=shelf("Trending"),newReleases=shelf("New")))
                    call("onNewIntent",Intent().putExtra("layout",HomeLayout.BALANCED.name))
                }
                waitFor(instrumentation) { settled() }
                focus("favorite-${syntheticApps.last().key}")
                val before=main(instrumentation) { root() to views().toMap() }
                val away=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
                main(instrumentation) { away.finish() }
                instrumentation.waitForIdleSync()
                main(instrumentation) {
                    (field("contentRepo") as ContentRepository).stop()
                    (field("weatherRepo") as WeatherRepository).stop()
                    call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                }
                waitFor(instrumentation) { settled() && activity.currentFocus===field("firstCard") && root().scrollY==0 }
                main(instrumentation) {
                    check(rows().values.all { it.scrollX==0 })
                    check(root()===before.first && views().all { (key,view)->before.second[key]===view })
                }
                val rebuildNanos=ArrayList<Long>(); val resetNanos=ArrayList<Long>()
                repeat(3) {
                    rebuildNanos.add(main(instrumentation) {
                        val start=System.nanoTime()
                        MainActivity::class.java.declaredMethods.single { it.name=="render" && it.parameterTypes.size==2 }
                            .apply { isAccessible=true }.invoke(activity,true,null)
                        System.nanoTime()-start
                    })
                    waitFor(instrumentation) { settled() }
                    resetNanos.add(main(instrumentation) {
                        val start=System.nanoTime(); call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                        System.nanoTime()-start
                    })
                    waitFor(instrumentation) { settled() }
                }
                instrumentation.sendStatus(0,android.os.Bundle().apply {
                    putString("stream","HOME RESET TIMING (injected UI-thread work, not physical remote latency): rebuildMs="+
                        rebuildNanos.map { it/1_000_000.0 }+" resetMs="+resetNanos.map { it/1_000_000.0 }+"\n")
                })
                focus("favorite-${syntheticApps.last().key}")
                main(instrumentation) {
                    val saved=MainActivity::class.java.declaredMethods.single { it.name=="viewport" && it.parameterTypes.size==1 }
                        .apply { isAccessible=true }.invoke(activity,null)
                    set("appReturnViewport",checkNotNull(saved))
                    // Queue an app-return restore, then receive Home before that callback runs.
                    call("onResume")
                    call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                }
                waitFor(instrumentation) { settled() && activity.currentFocus===field("firstCard") && root().scrollY==0 && rows().values.all { it.scrollX==0 } }
                // Home arriving before a replacement hierarchy is measured still settles at its start.
                main(instrumentation) {
                    val saved=MainActivity::class.java.declaredMethods.single { it.name=="viewport" && it.parameterTypes.size==1 }
                        .apply { isAccessible=true }.invoke(activity,"favorite-${syntheticApps.last().key}")
                    set("appReturnViewport",checkNotNull(saved))
                    call("onNewIntent",Intent().putExtra("layout",HomeLayout.CINEMA.name))
                    call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                    check(field("appReturnViewport")==null) { "Home left a stale app-return restore while layout was pending" }
                }
                waitFor(instrumentation) { settled() && activity.currentFocus===field("firstCard") && root().scrollY==0 && rows().values.all { it.scrollX==0 } }
                reset()
            }
            test("Home reclaims Bluetooth feedback after returning from the translucent audio panel") {
                check(StockRecoveryStore(context).plan()==null) { "This check must not have a configured local-control connection" }
                val panel=instrumentation.startActivitySync(Intent(context,BluetoothControlActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as BluetoothControlActivity
                val owner=BluetoothControlSession::class.java.getDeclaredField("owner").apply { isAccessible=true }
                try {
                    waitFor(instrumentation) { panel.hasWindowFocus() && owner.get(BluetoothControlSession)===panel }
                } finally { main(instrumentation) { panel.finish() } }
                waitFor(instrumentation) { activity.hasWindowFocus() && owner.get(BluetoothControlSession)===activity && !BluetoothControlSession.busy }
                main(instrumentation) {
                    check(views().getValue("nav-bluetooth").contentDescription.contains("unavailable")) { "Header did not receive the refreshed unavailable state" }
                }
            }
            test("Immediate Undo appears without stealing focus and restores a hidden title through the remote") {
                reset()
                main(instrumentation) { call("onNewIntent",Intent().putExtra("layout",HomeLayout.BALANCED.name)) }
                waitFor(instrumentation) { settled() }
                focus("next-1")
                main(instrumentation) {
                    personal().clearUndoHistory()
                    call("hideTitle",programs[1],true,true)
                }
                waitFor(instrumentation) { settled() && views()["nav-undo"]?.visibility==View.VISIBLE }
                main(instrumentation) {
                    check(activity.currentFocus?.tag=="next-1") { "Undo stole content focus" }
                    check(programs[1].titleKey in content().hiddenWatchingTitles)
                    check(views().getValue("nav-undo").contentDescription.toString().contains(programs[1].title))
                }
                focus("nav-home")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(instrumentation) { activity.currentFocus?.tag=="nav-undo" }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && views()["nav-undo"]?.visibility==View.GONE }
                main(instrumentation) {
                    check(programs[1].titleKey !in content().hiddenWatchingTitles)
                    check(programs[1].titleKey !in content().hiddenHeroTitles)
                    check(activity.currentFocus?.tag=="nav-home") { "Exhausting Undo left focus on a hidden view" }
                }
            }
            test("Immediate Undo restores Watchlist removal without opening Settings") {
                reset(); focus("next-1")
                main(instrumentation) {
                    check(personal().save(programs[1]))
                    personal().clearUndoHistory()
                    check(personal().removeSaved(programs[1]))
                }
                waitFor(instrumentation) { views()["nav-undo"]?.visibility==View.VISIBLE }
                focus("nav-undo")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && personal().isSaved(programs[1]) }
                main(instrumentation) { personal().removeSaved(programs[1]); personal().clearUndoHistory() }
            }
            test("Immediate Undo reverses the actual app-menu arrangement action") {
                reset()
                val key="favorite-${syntheticApps.first().key}"
                focus(key)
                val before=main(instrumentation) { (field("prefs") as Prefs).favourites.toList() }
                main(instrumentation) {
                    personal().clearUndoHistory()
                    call("appMenu",syntheticApps.first(),key)
                    val panel=field("appActionPanel") as Dialog
                    fun find(view:View):TextView? {
                        if(view is TextView && view.text.toString()=="Move right")return view
                        if(view is ViewGroup)for(i in 0 until view.childCount)find(view.getChildAt(i))?.let { return it }
                        return null
                    }
                    check(find(checkNotNull(panel.window).decorView)?.performClick()==true)
                }
                waitFor(instrumentation) { settled() && views()["nav-undo"]?.visibility==View.VISIBLE }
                main(instrumentation) { check((field("prefs") as Prefs).favourites!=before) }
                focus("nav-undo")
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(instrumentation) { settled() && (field("prefs") as Prefs).favourites==before }
            }
            test("Continue watching binding shows episode and remaining fields once without changing provider data") {
                main(instrumentation) {
                    val supplied=programs.first().copy(detail="Fixture provider · S1 E2 · S1 E2 · 9 min left",summary="Original full synopsis")
                    val compact=PrettTvCard(activity,field("scale") as Float,PrettTvCard.Mode.CONTINUATION).apply { tag="metadata-compact-check" }
                    val legacy=PrettTvCard(activity,field("scale") as Float,PrettTvCard.Mode.SHOW).apply { tag="metadata-legacy-check" }
                    call("bindProgram",compact,supplied,586,128)
                    call("bindProgram",legacy,supplied,420,218)
                    check(compact.detail=="Fixture provider" && compact.secondaryDetail=="S1 E2 · 9 min left")
                    check(compact.summary==supplied.summary && legacy.detail==supplied.detail)
                    check(compact.contentDescription.toString().split("S1 E2").size==2)
                    check(bound()["metadata-compact-check"]==supplied)
                }
            }
            test("Home interface size is reversible and preserves the selected title and saved preferences") {
                reset()
                main(instrumentation) { call("onNewIntent",Intent().putExtra("layout",HomeLayout.BALANCED.name)) }
                waitFor(instrumentation) { settled() }
                focus("next-1")
                val baseline=main(instrumentation) { views().getValue("feature").width }
                val before=stores.getValue("home").all.toMap()
                val selected=main(instrumentation) { bound()["next-1"]?.id }
                val systemFont=activity.resources.configuration.fontScale
                main(instrumentation) { call("applyInterfaceSize",HomeInterfaceSize.LARGER) }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag=="next-1" }
                main(instrumentation) {
                    check(abs(views().getValue("feature").width-baseline*1.04f)<=1f)
                    check(bound()["next-1"]?.id==selected)
                    check(activity.resources.configuration.fontScale==systemFont)
                    check(stores.getValue("home").all.filterKeys { it!="interfaceSize" }==before.filterKeys { it!="interfaceSize" })
                }
                val exported=LocalSettingsTransfer.export(stores)
                check(stores.getValue("home").edit().putString("interfaceSize",HomeInterfaceSize.STANDARD.name).commit())
                LocalSettingsTransfer.restore(exported,stores)
                check(stores.getValue("home").getString("interfaceSize",null)==HomeInterfaceSize.LARGER.name)
                main(instrumentation) { call("applyInterfaceSize",HomeInterfaceSize.STANDARD) }
                waitFor(instrumentation) { settled() && activity.currentFocus?.tag=="next-1" }
                main(instrumentation) { check(views().getValue("feature").width==baseline) }
            }
            test("Viewing history mode clears a shared hero immediately and preserves both histories") {
                reset()
                val before=stores.getValue("playback").all.toMap()
                main(instrumentation) { call("applyViewingHistoryMode",ViewingHistoryMode.THIS_TV_ONLY) }
                waitFor(instrumentation) { settled() && content().viewingHistoryMode==ViewingHistoryMode.THIS_TV_ONLY }
                main(instrumentation) {
                    check(bound()["feature"]==null && content().watching.isEmpty())
                    check((views().getValue("feature") as PrettTvCard).title=="Your viewing on this TV")
                    check(ViewingHistoryMode.read(stores.getValue("home"))==ViewingHistoryMode.THIS_TV_ONLY)
                    check(stores.getValue("playback").all==before)
                    set("content",base.copy(watching=emptyList(),viewingHistoryMode=ViewingHistoryMode.THIS_TV_ONLY))
                    call("refreshHomeRows")
                    check(bound()["feature"]==null) { "Recommendation became a local-only hero" }
                }
                main(instrumentation) { call("applyViewingHistoryMode",ViewingHistoryMode.COMBINED) }
                waitFor(instrumentation) { settled() && ViewingHistoryMode.read(stores.getValue("home"))==ViewingHistoryMode.COMBINED }
                main(instrumentation) { check(stores.getValue("playback").all==before) }
                reset()
            }
            test("Native A B C visual captures retain usable navigation at both interface and text sizes") {
                val originalConfiguration=main(instrumentation) { Configuration(activity.resources.configuration) }
                val originalMetrics=main(instrumentation) { DisplayMetrics().apply { setTo(activity.resources.displayMetrics) } }
                val output=File(checkNotNull(context.getExternalFilesDir(null)),"visual-review").apply { check(isDirectory || mkdirs()) }
                fun capture(name:String) {
                    // Let bounded focus/dim animations reach their final drawn frame.
                    SystemClock.sleep(350)
                    instrumentation.waitForIdleSync()
                    val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Native screenshot unavailable" }
                    try {
                        val file=File(output,"$name.png")
                        file.outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG,100,it)) }
                        check(file.length()>0)
                    } finally { screenshot.recycle() }
                }
                val navKeys=listOf("nav-home","nav-shows","nav-apps","nav-inputs","nav-search","nav-settings","nav-bluetooth")
                var normalTextSize=0f
                try {
                    for(size in HomeInterfaceSize.entries)for(fontScale in listOf(1f,1.3f))for(layout in HomeLayout.entries) {
                        main(instrumentation) {
                            check(stores.getValue("home").edit().putString("interfaceSize",size.name).commit())
                            val configuration=Configuration(originalConfiguration).apply { this.fontScale=fontScale }
                            // Only this isolated test process's Resources change; no system setting is written.
                            @Suppress("DEPRECATION")
                            activity.resources.updateConfiguration(configuration,originalMetrics)
                            check(abs(activity.resources.configuration.fontScale-fontScale)<.001f)
                            set("content",base)
                            call("onNewIntent",Intent().putExtra("layout",layout.name))
                        }
                        waitFor(instrumentation) { settled() }
                        val first=main(instrumentation) {
                            val bar=field("navigationBar") as View
                            val background=bar.background as? ColorDrawable ?: error("Header has no solid background")
                            check(Color.alpha(background.color)==255 && bar.alpha==1f) { "Header must remain opaque over scrolling content" }
                            val label=views().getValue("nav-home") as TextView
                            val bluetooth=views().getValue("nav-bluetooth") as TextView
                            val expectedIcon=(36*(field("scale") as Float)+.5f).toInt()
                            check(bluetooth.compoundDrawables[0]?.intrinsicWidth==expectedIcon) { "Bluetooth icon ignored Home interface size" }
                            if(fontScale==1f)normalTextSize=label.textSize
                            else check(abs(label.textSize-normalTextSize*fontScale)<.5f) { "Navigation ignored enlarged system text" }
                            (field("firstCard") as View).also { root().scrollTo(0,0); check(it.requestFocusFromTouch()) }
                        }
                        focus("nav-home")
                        for((index,key) in navKeys.withIndex()) {
                            if(index>0)instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                            waitFor(instrumentation) { activity.currentFocus?.tag==key && visible(activity.currentFocus!!) }
                            main(instrumentation) {
                                val label=views().getValue(key) as TextView
                                check(label.layout!=null && label.layout.getEllipsisCount(0)==0) { "$key was truncated at text scale $fontScale" }
                            }
                        }
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                        waitFor(instrumentation) { activity.currentFocus===first && visible(first) }
                        if(layout==HomeLayout.BALANCED) {
                            main(instrumentation) {
                                val unit=field("scale") as Float
                                val hero=views().getValue("feature")
                                val poster=views().getValue("next-0")
                                val last=views().getValue("next-2")
                                val different=views().getValue("different-picks") as TextView
                                fun bounds(view:View)=Rect(0,0,view.width,view.height).also { root().offsetDescendantRectToMyCoords(view,it) }
                                check(abs(hero.width/unit-BalancedHomeLayout.HERO_WIDTH)<2)
                                check(abs(hero.height/unit-BalancedHomeLayout.stageHeight(fontScale))<2)
                                check(abs(poster.width/unit-BalancedHomeLayout.POSTER_WIDTH)<2)
                                check(abs(poster.width/unit-240f)<2) { "Discover did not use the enlarged portrait width" }
                                val picture=PrettTvCard::class.java.getDeclaredField("balancedArtBounds").apply { isAccessible=true }
                                    .get(poster) as android.graphics.RectF
                                check(picture.width()>0 && abs(picture.height()/picture.width()-1.5f)<.001f) { "Larger Discover artwork lost its 2:3 frame" }
                                check(poster.height-picture.bottom>=80*unit) { "Larger Discover artwork consumed its title clearance" }
                                check(bounds(hero).right<bounds(poster).left && bounds(last).right<=root().width)
                                check(bounds(hero).left>=0 && bounds(last).right<=activity.resources.displayMetrics.widthPixels)
                                check(abs(unit-activity.resources.displayMetrics.widthPixels/1920f*size.factor)<.001f)
                                check("browse-shows" !in views())
                                check(bounds(poster).top-bounds(different).bottom>=12*unit) { "Different picks lost poster clearance" }
                                check(abs(bounds(different).right-bounds(last).right)<=1) { "Different picks is not aligned to the trailing poster edge" }
                                check(abs(different.width/unit-BalancedHomeLayout.discoveryActionWidth(fontScale))<2)
                                check(abs(different.height/unit-BalancedHomeLayout.discoveryActionHeight(fontScale))<2)
                                check(different.paddingLeft>=15*unit && different.paddingRight>=15*unit)
                                check(different.layout!=null && different.layout.getEllipsisCount(0)==0) { "Different picks truncates at $fontScale" }
                                check(different.layout.height<=different.height-different.paddingTop-different.paddingBottom) { "Different picks clips vertically" }
                                for(view in listOf(hero,last,different)) {
                                    val visibleBounds=Rect()
                                    check(view.getGlobalVisibleRect(visibleBounds) && visibleBounds.width()==view.width && visibleBounds.height()==view.height) { "Approved C first-stage content is clipped" }
                                }
                                assertHeroGlyph(hero as PrettTvCard,unit)
                                check((0..2).all { bound()["next-$it"]?.id!=bound()["feature"]?.id })
                            }
                            focus("next-0")
                            val side=main(instrumentation) { activity.currentFocus }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                            waitFor(instrumentation) { activity.currentFocus?.tag=="different-picks" && visible(activity.currentFocus!!) }
                            main(instrumentation) { assertControlFocus(views().getValue("different-picks") as TextView) }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                            waitFor(instrumentation) { activity.currentFocus?.tag=="different-picks" }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                            waitFor(instrumentation) { activity.currentFocus?.tag=="nav-home" && visible(activity.currentFocus!!) }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                            waitFor(instrumentation) { activity.currentFocus?.tag=="different-picks" }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                            waitFor(instrumentation) { activity.currentFocus===side && visible(side!!) }
                        }
                        main(instrumentation) { root().scrollTo(0,0); check(first.requestFocusFromTouch()) }
                        focus("nav-home")
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                        waitFor(instrumentation) { settled() && activity.currentFocus===first && visible(first) }
                        val suffix=(if(fontScale==1f)"100" else "130")+"-size-${size.name.lowercase()}"
                        capture("home-${layout.name.lowercase()}-font-$suffix")
                        if(layout==HomeLayout.BALANCED) {
                            val key="favorite-${syntheticApps.last().key}"
                            focus(key)
                            main(instrumentation) { check(views().getValue(key).performLongClick()) }
                            waitFor(instrumentation) {
                                val panel=field("appActionPanel") as? Dialog
                                panel?.isShowing==true && panel.window?.decorView?.hasWindowFocus()==true &&
                                    (panel.currentFocus as? TextView)?.text?.toString()=="Open"
                            }
                            capture("app-actions-balanced-font-$suffix")
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
                            waitFor(instrumentation) { field("appActionPanel")==null && settled() && activity.currentFocus?.tag==key }
                        }
                    }
                } finally {
                    main(instrumentation) {
                        (field("appActionPanel") as? Dialog)?.dismiss()
                        @Suppress("DEPRECATION")
                        activity.resources.updateConfiguration(originalConfiguration,originalMetrics)
                        stores.getValue("home").edit().putString("interfaceSize",HomeInterfaceSize.STANDARD.name).apply()
                    }
                }
            }
        } finally {
            opened?.let { activity -> main(instrumentation) { activity.finish() }; instrumentation.waitForIdleSync() }
            LocalSettingsTransfer.restore(savedSettings,stores)
            injectedArt.recycle()
        }
    }

    /** Real Canvas output, not a text/glyph surrogate or source-string assertion. */
    private fun assertHeroGlyph(card:PrettTvCard,unit:Float) {
        check(card.isFocused) { "Hero glyph focus fixture did not select the hero" }
        val bitmap=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888)
        try {
            card.draw(android.graphics.Canvas(bitmap))
            fun bounds(name:String)=PrettTvCard::class.java.getDeclaredField(name).apply { isAccessible=true }
                .get(card) as android.graphics.RectF
            val action=bounds("actionBounds")
            val font=card.resources.configuration.fontScale
            check(abs(action.width()-action.height())<.01f && abs(action.width()-52*unit*font)<1) { "Hero returned to a wide text action pill" }
            check(action.left-9*unit>=0 && action.top-9*unit>=0 && action.right+9*unit<=card.width && action.bottom+9*unit<=card.height) { "Hero focus ring is clipped" }
            val title=bounds("balancedTitleBounds")
            val metadata=bounds("balancedMetadataBounds")
            val progress=bounds("balancedProgressBounds")
            check(title.top>=0 && title.bottom<=action.top)
            if(!metadata.isEmpty)check(title.bottom<=metadata.top && metadata.bottom<action.top)
            if(!progress.isEmpty)check(progress.bottom<action.top)
            val cx=action.centerX().toInt(); val cy=action.centerY().toInt()
            check(Color.red(bitmap.getPixel(cx,cy))>220) { "Native play triangle is missing" }
            check(Color.red(bitmap.getPixel((action.centerX()-12*unit*font).toInt(),cy))<30) { "Play triangle lost its black inset" }
            val ringY=(action.top-7*unit).toInt()
            check((ringY-1..ringY+1).any { Color.red(bitmap.getPixel(cx,it))>220 }) { "Hero focus outline is not visible" }
        } finally { bitmap.recycle() }
    }
    private fun assertControlFocus(control:TextView) {
        check(control.isFocused)
        val bitmap=Bitmap.createBitmap(control.width,control.height,Bitmap.Config.ARGB_8888)
        try {
            control.draw(android.graphics.Canvas(bitmap))
            check(Color.red(bitmap.getPixel(control.width/2,1))>220) { "Different picks has no visible white focus outline" }
        } finally { bitmap.recycle() }
    }
    private fun visible(view:View):Boolean {
        val bounds=Rect()
        return view.isShown && view.getGlobalVisibleRect(bounds) && bounds.width()>=view.width*.8f && bounds.height()>=view.height*.8f
    }
    private fun descendant(view:View,parent:ViewGroup):Boolean {
        var next=view.parent
        while(next!=null) { if(next===parent)return true; next=next.parent }
        return false
    }
    private fun waitFor(instrumentation:Instrumentation,predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+12_000
        do { if(main(instrumentation,predicate))return; SystemClock.sleep(40) } while(SystemClock.uptimeMillis()<end)
        error("Timed out waiting for isolated Home visual state")
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<T>()
        val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch(t:Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
        return result.get()
    }
}
