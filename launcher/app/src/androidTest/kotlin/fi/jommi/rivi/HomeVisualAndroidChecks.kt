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
                    personal().setRowPreferences(RowPreferences(hidden=setOf("watching-shows","watching-movies","watchlist","episodes","explore","new")))
                    set("content",value); call("refreshHomeRows")
                }
                waitFor(instrumentation) { settled() && views()[tag(programs.first())]!=null }
            }
            fun publish(value:HomeContent) { main(instrumentation) { set("content",value); call("refreshProviderRows") } }
            waitFor(instrumentation) { activity.hasWindowFocus() && field("appDiscoveryComplete")==true && root().isLaidOut }
            main(instrumentation) {
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
                        check(card.detail==updated.detail && card.progress==updated.progress)
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
                    }
                }
            }
            test("Native A B C visual captures retain usable navigation and actions at normal and enlarged text") {
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
                val navKeys=listOf("nav-home","nav-shows","nav-apps","nav-inputs","nav-search","nav-settings")
                var normalTextSize=0f
                try {
                    for(fontScale in listOf(1f,1.3f))for(layout in HomeLayout.entries) {
                        main(instrumentation) {
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
                            focus("next-0")
                            val side=main(instrumentation) { activity.currentFocus }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                            waitFor(instrumentation) { activity.currentFocus?.tag.toString().startsWith("nav-") && visible(activity.currentFocus!!) }
                            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                            waitFor(instrumentation) { activity.currentFocus===side && visible(side!!) }
                        }
                        main(instrumentation) { root().scrollTo(0,0); check(first.requestFocusFromTouch()) }
                        focus("nav-home")
                        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                        waitFor(instrumentation) { settled() && activity.currentFocus===first && visible(first) }
                        val suffix=if(fontScale==1f)"100" else "130"
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
                    }
                }
            }
        } finally {
            opened?.let { activity -> main(instrumentation) { activity.finish() }; instrumentation.waitForIdleSync() }
            LocalSettingsTransfer.restore(savedSettings,stores)
            injectedArt.recycle()
        }
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
