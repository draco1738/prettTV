package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Exercise actual TV widgets in the isolated test app; never launch media or change stock Home. */
internal object HomeFeatureAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        val stores=linkedMapOf(
            "home" to context.getSharedPreferences("pretttv",Context.MODE_PRIVATE),
            "apps" to context.getSharedPreferences("rivi",Context.MODE_PRIVATE),
            "playback" to context.getSharedPreferences("prett-playback",Context.MODE_PRIVATE),
            PersonalizationStore.PREFS_NAME to context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE),
        )
        val originalSettings=LocalSettingsTransfer.export(stores)
        context.getSharedPreferences("pretttv",Context.MODE_PRIVATE).edit().clear().putBoolean("discovery",false).putBoolean("tvPermissionAsked",true).commit()
        context.getSharedPreferences(PersonalizationStore.PREFS_NAME,Context.MODE_PRIVATE).edit().clear().commit()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
        fun call(name:String,vararg values:Any) {
            val method=MainActivity::class.java.declaredMethods.single { it.name==name && it.parameterTypes.size==values.size }
            method.isAccessible=true; method.invoke(activity,*values)
        }
        fun waitFor(checker:()->Boolean) {
            val end=SystemClock.uptimeMillis()+15_000
            do { if(checker())return; SystemClock.sleep(80) } while(SystemClock.uptimeMillis()<end)
            error("Timed out waiting for native Home state")
        }
        fun state(predicate:()->Boolean):Boolean { var result=false; main { result=predicate() }; return result }
        @Suppress("UNCHECKED_CAST")
        fun views()=field("focusables") as Map<String,View>
        fun personal()=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
        fun watchTag(program:Program)="watchlist-"+ContentIdentity.key(program)
        fun focusedAndVisible(tag:String):Boolean {
            val view=activity.currentFocus ?: return false
            val bounds=Rect()
            return view.tag==tag && view.getGlobalVisibleRect(bounds) &&
                bounds.width()>=view.width*0.8f && bounds.height()>=view.height*0.8f
        }
        fun setContent(value:HomeContent) {
            MainActivity::class.java.getDeclaredField("content").apply { isAccessible=true }.set(activity,value)
        }
        fun click(label:String) {
            waitFor {
                val root=instrumentation.uiAutomation.rootInActiveWindow ?: return@waitFor false
                val nodes=root.findAccessibilityNodeInfosByText(label)
                val exact=nodes.firstOrNull { it.text?.toString()==label } ?: return@waitFor false
                var target:AccessibilityNodeInfo?=exact
                while(target!=null && !target.isClickable)target=target.parent
                target?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            }
            instrumentation.waitForIdleSync()
        }
        val fixture=Program("fixture:show","Test show","Drama","","fixture.app","Test provider",mediaType="series",summary="Test synopsis")
        try {
            waitFor { state { field("appDiscoveryComplete")==true } }
            main { (field("contentRepo") as ContentRepository).stop() }
            test("Native Watchlist menu saves, removes and undoes without launching media") {
                main { call("programInfo",fixture) }; click("Add to Watchlist")
                waitFor { state { PersonalizationStore(context).isSaved(fixture) } }
                main { call("programInfo",fixture) }; click("Remove from Watchlist")
                waitFor { state { !PersonalizationStore(context).isSaved(fixture) } }
                main { val personal=field("personal\$delegate") as Lazy<*>; (personal.value as PersonalizationStore).undo(); call("refreshHomeRows") }
                check(PersonalizationStore(context).isSaved(fixture))
            }
            test("Hidden rows remain reachable in row settings and can be shown again") {
                main { call("customizeRow","watchlist") }; click("Hide row")
                check("watchlist" in PersonalizationStore(context).rowPreferences().hidden)
                click("Watchlist · Hidden"); click("Show row")
                check("watchlist" !in PersonalizationStore(context).rowPreferences().hidden)
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            }
            test("Changed recommendation rows preserve focus on an unchanged Watchlist card") {
                main {
                    @Suppress("UNCHECKED_CAST") val views=field("focusables") as Map<String,View>
                    check(views[watchTag(fixture)]?.requestFocus()==true)
                }
                instrumentation.waitForIdleSync()
                main {
                    val f=MainActivity::class.java.getDeclaredField("content").apply { isAccessible=true }
                    val current=f.get(activity) as HomeContent
                    f.set(activity,current.copy(suggestions=listOf(fixture.copy(id="fixture:new",title="Another title"))))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                check(state { activity.currentFocus?.tag==watchTag(fixture) })
            }
            test("Lower-row focus remains visible after preceding rows hide or change structure") {
                val lower=fixture.copy(id="fixture:lower",title="Lower row target")
                val episode=EpisodeAlert("fixture:episode",ContentIdentity.key(fixture),fixture,1,2,"Fixture episode",1,"")
                main {
                    personal().setRowPreferences(RowPreferences())
                    setContent(HomeContent(suggestions=listOf(lower),newEpisodes=listOf(episode)))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                val tag="discover-"+lower.id
                main { check(views()[tag]?.requestFocus()==true) }
                waitFor { state { focusedAndVisible(tag) } }
                main {
                    personal().setRowPreferences(RowPreferences(hidden=setOf("watching-shows","watching-movies")))
                    call("refreshHomeRows")
                }
                waitFor { state { focusedAndVisible(tag) } }
                main {
                    personal().setRowPreferences(personal().rowPreferences().copy(sizes=mapOf("watchlist" to RowCardSize.LARGE)))
                    call("refreshHomeRows")
                }
                waitFor { state { focusedAndVisible(tag) } }
                main {
                    val current=field("content") as HomeContent
                    setContent(current.copy(newEpisodes=emptyList()))
                    call("refreshHomeRows")
                }
                waitFor { state { focusedAndVisible(tag) } }
            }
            test("Back-to-back feed publications preserve the same focused title and visibility") {
                val target=fixture.copy(id="fixture:burst-target",title="Keep this focused")
                val prefix=(0..5).map { fixture.copy(id="fixture:burst-$it",title="Earlier title $it") }
                val episode=EpisodeAlert("fixture:burst-episode",ContentIdentity.key(fixture),fixture,1,3,"Burst episode",1,"")
                val tag="discover-"+target.id
                // Cover both two partial row replacements and a full render followed by a
                // partial replacement before either gets its first pre-draw callback.
                for(structural in listOf(false,true)) {
                    main {
                        personal().setRowPreferences(RowPreferences())
                        setContent(HomeContent(suggestions=prefix+target))
                        call("refreshHomeRows")
                    }
                    instrumentation.waitForIdleSync()
                    main { check(views()[tag]?.requestFocus()==true) }
                    waitFor { state { focusedAndVisible(tag) } }
                    main {
                        val base=field("content") as HomeContent
                        val alerts=if(structural)listOf(episode) else emptyList()
                        setContent(base.copy(suggestions=prefix+target.copy(detail="First publication"),newEpisodes=alerts))
                        call("refreshHomeRows")
                        setContent(base.copy(suggestions=prefix.reversed()+target.copy(detail="Second publication"),newEpisodes=alerts))
                        call("refreshHomeRows")
                    }
                    instrumentation.waitForIdleSync()
                    waitFor { state {
                        @Suppress("UNCHECKED_CAST")
                        val bound=field("boundPrograms") as Map<String,Program?>
                        focusedAndVisible(tag) && bound[tag]?.detail=="Second publication"
                    } }
                }
                val neighbor=fixture.copy(id="fixture:burst-neighbor",title="Next surviving title")
                val neighborTag="discover-"+neighbor.id
                main {
                    setContent(HomeContent(suggestions=prefix+target+neighbor))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                main { check(views()[tag]?.requestFocus()==true) }
                waitFor { state { focusedAndVisible(tag) } }
                main {
                    val base=field("content") as HomeContent
                    setContent(base.copy(suggestions=prefix+neighbor))
                    call("refreshHomeRows")
                    val withoutTarget=field("content") as HomeContent
                    setContent(withoutTarget.copy(trending=listOf(fixture.copy(id="fixture:other-row",title="Unrelated refresh"))))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                waitFor { state { focusedAndVisible(neighborTag) && views()[tag]==null } }
            }
            test("Saved titles sharing a reused provider row ID retain distinct cards and actions") {
                val first=fixture.copy(id="preview:reused",title="Original saved edition")
                val second=fixture.copy(id="preview:reused",title="Later saved edition")
                main {
                    check(personal().save(first,100) && personal().save(second,200))
                    personal().setRowPreferences(RowPreferences(order=listOf("watchlist")))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                main {
                    val a=views()[watchTag(first)] ?: error("First saved card missing")
                    val b=views()[watchTag(second)] ?: error("Second saved card missing")
                    check(a!==b && a.id!=b.id)
                    check(b.requestFocus() && activity.currentFocus===b)
                    check(a.requestFocus() && activity.currentFocus===a)
                    check(a.performLongClick())
                }
                click("Remove from Watchlist")
                check(state { !personal().isSaved(first) && personal().isSaved(second) })
                main { check(views()[watchTag(second)]?.requestFocus()==true) }
                waitFor { state { focusedAndVisible(watchTag(second)) } }
            }
            test("Watchlist pages retain every title and focus a visible destination in both directions") {
                main {
                    repeat(70) { personal().save(fixture.copy(id="page:$it",title="Page title $it"),1_000L+it) }
                    personal().setRowPreferences(RowPreferences(order=listOf("watchlist")))
                    call("refreshHomeRows")
                }
                instrumentation.waitForIdleSync()
                var firstPageTag=""
                var secondPageTag=""
                main {
                    firstPageTag=watchTag(personal().watchlist().first())
                    secondPageTag=watchTag(personal().watchlist()[60])
                    val firstPage=views().keys.filter { it.startsWith("watchlist-") && !it.startsWith("watchlist-page-") }
                    check(firstPage.size==60)
                    check(views()["watchlist-page-previous"]==null)
                    check(views()["watchlist-page-next"]?.requestFocus()==true)
                }
                waitFor { state { focusedAndVisible("watchlist-page-next") } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { state { field("watchlistOffset")==60 && focusedAndVisible(secondPageTag) } }
                main {
                    check(views().keys.count { it.startsWith("watchlist-") && !it.startsWith("watchlist-page-") }==personal().watchlist().size-60)
                    check(views()["watchlist-page-next"]==null)
                    check(views()["watchlist-page-previous"]?.requestFocus()==true)
                }
                waitFor { state { focusedAndVisible("watchlist-page-previous") } }
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor { state { field("watchlistOffset")==0 && focusedAndVisible(firstPageTag) } }
                check(state { personal().watchlist().size>=72 })
            }
            test("Native long-row reversals keep valid focus across all layouts") {
                for(layout in HomeLayout.entries) {
                    main {
                        val personal=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
                        repeat(70) { personal.save(fixture.copy(id="fixture:$it",title="Saved title $it")) }
                        personal.setRowPreferences(RowPreferences(order=listOf("watchlist"),hidden=setOf("sports","episodes")))
                        call("onNewIntent",Intent().putExtra("layout",layout.name))
                    }
                    instrumentation.waitForIdleSync()
                    main {
                        @Suppress("UNCHECKED_CAST") val views=field("focusables") as Map<String,View>
                        check(views.entries.firstOrNull { it.key.startsWith("watchlist-") }?.value?.requestFocus()==true)
                    }
                    repeat(30) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT) }
                    repeat(15) { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT) }
                    instrumentation.waitForIdleSync()
                    check(state { activity.currentFocus?.tag.toString().startsWith("watchlist-") })
                }
            }
        } finally {
            main { activity.finish() };instrumentation.waitForIdleSync()
            LocalSettingsTransfer.restore(originalSettings,stores)
        }
    }
}
