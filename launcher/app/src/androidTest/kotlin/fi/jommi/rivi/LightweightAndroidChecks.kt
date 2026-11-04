package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Bounded real-device regressions, using only the isolated app and synthetic titles. */
internal object LightweightAndroidChecks {
    fun run(i:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=i.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        test("Duplicate artwork subscribers share a decode, and cancelling one preserves the others") {
            val loader=ArtworkLoader(context)
            try {
                val done=CountDownLatch(11);val images=ArrayList<Bitmap>();var cancelledDelivered=false
                main(i) {
                    val ticket=loader.load("content://${context.packageName}.art/slow/shared",320,180) { cancelledDelivered=true }
                    repeat(11) { loader.load("content://${context.packageName}.art/slow/shared",320,180) {
                        checkNotNull(it);images.add(it);done.countDown()
                    } }
                    check(loader.pendingCount()==1);ticket.cancel()
                }
                check(done.await(5,TimeUnit.SECONDS))
                main(i) { check(!cancelledDelivered && images.size==11 && images.all { it===images.first() });check(loader.cachedBytes()>0);loader.trimTo(0);check(loader.cachedBytes()==0) }
            } finally { main(i) { loader.close() } }
        }
        test("Visible artwork overtakes queued prefetch without dropping callbacks") {
            val loader=ArtworkLoader(context);val order=ArrayList<Int>();val done=CountDownLatch(9);val visibleDone=CountDownLatch(1)
            try {
                FaultArtworkProvider.blockedStarted=CountDownLatch(2);FaultArtworkProvider.unblock=java.util.concurrent.Semaphore(0)
                main(i) {
                    repeat(2) { n->loader.load("content://${context.packageName}.art/blocked/priority-$n",320,180) {
                        checkNotNull(it);order.add(n);done.countDown()
                    } }
                }
                check(FaultArtworkProvider.blockedStarted!!.await(5,TimeUnit.SECONDS))
                main(i) { for(n in 2..8)loader.load("content://${context.packageName}.art/slow/priority-$n",320,180,prefetch=n!=8) {
                    checkNotNull(it);order.add(n);done.countDown();if(n==8)visibleDone.countDown()
                } }
                // Free only one worker: two simultaneous decodes can legitimately finish out of order.
                FaultArtworkProvider.unblock!!.release()
                check(visibleDone.await(5,TimeUnit.SECONDS))
                main(i) { check(order.indexOf(8)==1) { "Visible artwork waited behind prefetch: $order" } }
                FaultArtworkProvider.unblock!!.release()
                check(done.await(8,TimeUnit.SECONDS))
                main(i) { check(order.toSet().size==9) }
            } finally { FaultArtworkProvider.unblock?.release(2);FaultArtworkProvider.blockedStarted=null;FaultArtworkProvider.unblock=null;main(i) { loader.close() } }
        }
        test("Quiet focus keeps icon pixels still while Classic retains its existing slide") {
            main(i) {
                val art=Bitmap.createBitmap(80,45,Bitmap.Config.ARGB_8888)
                Canvas(art).apply { drawColor(Color.BLACK);drawRect(20f,4f,60f,41f,Paint().apply { color=Color.RED }) }
                val amount=PrettTvCard::class.java.getDeclaredField("focusAmount").apply { isAccessible=true }
                for(quiet in listOf(false,true)) {
                    val card=PrettTvCard(context,1f,PrettTvCard.Mode.APP,36f).apply {
                        this.art=art;quietMotion=quiet
                        measure(View.MeasureSpec.makeMeasureSpec(208,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY));layout(0,0,208,96)
                    }
                    val before=Bitmap.createBitmap(208,96,Bitmap.Config.ARGB_8888)
                    val after=Bitmap.createBitmap(208,96,Bitmap.Config.ARGB_8888)
                    try {
                        amount.setFloat(card,0f);card.draw(Canvas(before))
                        amount.setFloat(card,1f);card.draw(Canvas(after))
                        check(before.sameAs(after)==quiet)
                    } finally { before.recycle();after.recycle();card.art=null }
                }
                art.recycle()
            }
        }
        val settings=context.getSharedPreferences("pretttv",Context.MODE_PRIVATE)
        settings.edit().putBoolean("discovery",false).putBoolean("weatherEnabled",false).putBoolean("tvPermissionAsked",true).commit()
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
        fun set(name:String,value:Any)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(activity,value)
        fun call(name:String,vararg args:Any?)=MainActivity::class.java.declaredMethods.single { it.name==name && it.parameterTypes.size==args.size }.apply { isAccessible=true }.invoke(activity,*args)
        @Suppress("UNCHECKED_CAST") fun rows()=field("horizontalRows") as Map<String,HorizontalScrollView>
        @Suppress("UNCHECKED_CAST") fun views()=field("focusables") as Map<String,View>
        fun settled()=field("pendingViewport")==null && !(field("root") as ScrollView).isLayoutRequested && activity.hasWindowFocus()
        val titles=(0 until 60).map { Program("light:$it","Fixture title $it","Fixture service","","fixture","Fixture service",mediaType="series") }
        val base=HomeContent(suggestions=titles,discover=titles.map { it.copy(id="explore:${it.id}") },trending=titles.map { it.copy(id="trending:${it.id}") },newReleases=titles.map { it.copy(id="new:${it.id}") })
        try {
            waitFor(i) { field("appDiscoveryComplete")==true && settled() }
            main(i) {
                (field("contentRepo") as ContentRepository).stop();(field("weatherRepo") as WeatherRepository).stop()
                set("generation",(field("generation") as Int)+1)
                val personal=(field("personal\$delegate") as Lazy<*>).value as PersonalizationStore
                personal.setRowPreferences(RowPreferences())
                set("content",base);call("render",true,null)
            }
            waitFor(i,::settled)
            test("Four 60-title shelves retain the complete catalogue with fewer than 40 live cards") {
                main(i) {
                    val shelves=listOf("discover","explore","trending","new").map { rows().getValue(it).getChildAt(0) as WindowedRow }
                    check(shelves.all { it.keys.size==60 });check(shelves.sumOf { it.childCount }<40)
                    i.sendStatus(0,Bundle().apply { putString("stream","WINDOWED CARDS ${shelves.sumOf { it.childCount }} / 240; full title count 240\n") })
                }
            }
            test("All 60 identities remain reachable through held-key boundaries and reversals in A B C") {
                for(layout in HomeLayout.entries) {
                    main(i) { call("onNewIntent",Intent().putExtra("layout",layout.name)) }
                    waitFor(i,::settled)
                    main(i) { check(views().getValue("discover-light:0").requestFocusFromTouch()) }
                    for(direction in listOf(1,-1,1,-1,1,-1,1,-1))repeat(65) { step->
                        main(i) {
                            val code=if(direction>0)KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
                            val time=SystemClock.uptimeMillis()
                            val before=(activity.currentFocus?.tag as String).substringAfterLast(':').toInt()
                            activity.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,code,step))
                            val expected=(before+direction).coerceIn(0,59)
                            check(activity.currentFocus?.tag=="discover-light:$expected") { "Lost title $expected in $layout at step $step" }
                            check((rows().getValue("discover").getChildAt(0) as WindowedRow).childCount<=12)
                        }
                        SystemClock.sleep(18)
                    }
                    main(i) { call("stopRowMotion") }
                    waitFor(i,::settled)
                }
            }
            test("Metadata, app Back and Home preserve stable navigation") {
                main(i) { call("focusTarget","discover-light:42").let { check((it as View).requestFocusFromTouch()) };call("revealFocus",activity.currentFocus) }
                waitFor(i,::settled)
                val saved=main(i) { call("viewport",null) }
                main(i) {
                    set("content",base.copy(suggestions=titles.map { it.copy(detail="Updated episode") }))
                    call("refreshProviderRows")
                    check(activity.currentFocus?.tag=="discover-light:42")
                    check((activity.currentFocus as PrettTvCard).detail=="Updated episode")
                    set("appReturnViewport",saved!!)
                }
                val away=i.startActivitySync(Intent(context,CardFocusTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
                main(i) { away.finish() }
                waitFor(i) { settled() && activity.currentFocus?.tag=="discover-light:42" }
                main(i) {
                    (field("contentRepo") as ContentRepository).stop()
                    check(call("viewport",null)==saved)
                    call("onNewIntent",Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
                }
                waitFor(i) { settled() && (field("root") as ScrollView).scrollY==0 && rows().values.all { it.scrollX==0 } }
            }
            test("Background pressure drops bitmap references while preserving Home and its selected identity") {
                val bitmap=Bitmap.createBitmap(80,45,Bitmap.Config.RGB_565)
                main(i) {
                    val card=views().getValue("discover-light:0") as PrettTvCard
                    card.art=bitmap;card.requestFocusFromTouch()
                    // Register representative content art so the actual retention path owns it.
                    val updated=base.copy(suggestions=titles.map { it.copy(art="content://${context.packageName}.art/good/memory") })
                    set("content",updated);call("refreshProviderRows")
                }
                waitFor(i,::settled)
                val root=main(i) { field("root") }
                main(i) { call("releaseArtwork",true);check(field("root")===root);check(activity.currentFocus?.tag=="discover-light:0")
                    check((field("artwork") as ArtworkLoader).cachedBytes()==0)
                    check((views().getValue("discover-light:0") as PrettTvCard).art==null)
                    call("loadVisibleArt")
                }
                waitFor(i) { (views()["discover-light:0"] as? PrettTvCard)?.art!=null }
            }
            test("Removing a late selected title focuses its chronological neighbour, not the start of the shelf") {
                main(i) {
                    set("content",base);call("refreshHomeRows")
                }
                waitFor(i,::settled)
                main(i) {
                    (call("focusTarget","discover-light:42") as View).requestFocusFromTouch()
                    call("revealFocus",activity.currentFocus)
                    set("content",base.copy(suggestions=titles.filterNot { it.id=="light:42" }))
                    call("refreshProviderRows")
                }
                waitFor(i) { settled() && activity.currentFocus?.tag=="discover-light:43" }
                main(i) { check("discover-light:42" !in views());check(rows().getValue("discover").scrollX>0) }
            }
        } finally { main(i) { activity.finish() };i.waitForIdleSync() }
    }

    /** Native card pixels; timing is rendered deterministically at half speed for comparison. */
    fun preview(i:Instrumentation) {
        val context=i.targetContext;check(context.packageName=="dev.zane.pretttv.stability")
        val repo=AppRepository(context)
        val packages=listOf("com.netflix.ninja","com.amazon.amazonvideo.livingroom","com.google.android.youtube.tv")
        val apps=repo.discover().filter { it.packageName in packages }.sortedBy { packages.indexOf(it.packageName) }
        check(apps.size==3)
        apps.forEach { it.bitmap=repo.loadTile(it,320,180,0f) }
        val output=File(context.getExternalFilesDir(null),"motion-preview").apply { mkdirs() }
        main(i) {
            val rows=listOf(false,true).map { quiet->apps.map { entry->PrettTvCard(context,1f,PrettTvCard.Mode.APP,36f).apply {
                title=entry.label;art=entry.bitmap;logoBounds=entry.logoBounds;quietMotion=quiet
                measure(View.MeasureSpec.makeMeasureSpec(208,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(96,View.MeasureSpec.EXACTLY));layout(0,0,208,96)
            } } }
            val amount=PrettTvCard::class.java.getDeclaredField("focusAmount").apply { isAccessible=true }
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=23f }
            repeat(120) { frame->
                val bitmap=Bitmap.createBitmap(780,430,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);canvas.drawColor(Color.BLACK)
                canvas.drawText("Native focus comparison · half speed",32f,38f,paint)
                val selected=listOf(0,1,2,1)[frame/30]
                val previous=listOf(0,0,1,2)[frame/30]
                val elapsed=(frame%30)*16.667f
                rows.forEachIndexed { n,cards->
                    paint.textSize=20f;paint.color=Color.WHITE
                    canvas.drawText(if(n==0)"A · Current — lift + artwork slide" else "B · Quiet — smaller lift, still artwork",32f,90f+n*154,paint)
                    cards.forEachIndexed { index,card->
                        val duration=if(n==0)180f else 120f
                        val fraction=(elapsed/duration).coerceIn(0f,1f);val ease=1-(1-fraction)*(1-fraction)
                        val focus=if(index==selected) { if(selected==previous)1f else ease } else if(index==previous)1-ease else 0f
                        amount.setFloat(card,focus)
                        val scale=1+focus*if(n==0)0.025f else 0.015f
                        canvas.save();canvas.translate(38f+index*232,106f+n*154);canvas.scale(scale,scale,104f,48f);card.draw(canvas)
                        if(index==selected) { paint.color=Color.WHITE;paint.style=Paint.Style.STROKE;paint.strokeWidth=2.4f;canvas.drawRoundRect(3f,3f,205f,93f,36f,36f,paint);paint.style=Paint.Style.FILL }
                        canvas.restore()
                    }
                }
                paint.textSize=16f;paint.color=Color.LTGRAY;canvas.drawText("Same cards, spacing, black surfaces and immediate focus outline.",32f,409f,paint)
                File(output,"frame-%03d.png".format(frame)).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            }
        }
    }
    private fun waitFor(i:Instrumentation,predicate:()->Boolean) {
        val end=SystemClock.uptimeMillis()+12_000
        do { if(main(i,predicate))return;SystemClock.sleep(40) } while(SystemClock.uptimeMillis()<end)
        error("Timed out waiting for lightweight fixture")
    }
    private fun <T> main(i:Instrumentation,block:()->T):T {
        val value=AtomicReference<Result<T>>();i.runOnMainSync { value.set(runCatching(block)) };return value.get().getOrThrow()
    }
}
