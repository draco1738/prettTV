package fi.jommi.rivi

import android.animation.ValueAnimator
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Real attached focus/lifecycle checks and deterministic raster checks, only in the isolated app. */
internal object CardFocusAndroidChecks {
    private val amountField=PrettTvCard::class.java.getDeclaredField("focusAmount").apply { isAccessible=true }
    private val motionField=PrettTvCard::class.java.getDeclaredField("focusMotion").apply { isAccessible=true }
    private fun amount(card:PrettTvCard)=amountField.getFloat(card)
    private fun motion(card:PrettTvCard)=motionField.get(card) as? ValueAnimator
    private fun near(actual:Float,expected:Float)=abs(actual-expected)<.001f

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Card checks must never run in the owner's Home" }
        val activity=instrumentation.startActivitySync(
            Intent(context,CardFocusTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as CardFocusTestActivity
        var row:LinearLayout?=null
        try {
            val cards=main(instrumentation) {
                val group=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
                row=group
                activity.setContentView(group)
                List(2) { PrettTvCard(activity,1f,PrettTvCard.Mode.APP).apply {
                    isFocusableInTouchMode=true
                    group.addView(this,LinearLayout.LayoutParams(208,94))
                } }
            }
            val first=cards[0]; val second=cards[1]
            waitFor(instrumentation) { activity.hasWindowFocus() && first.isAttachedToWindow && first.isShown && first.width==208 }
            fun reset() {
                main(instrumentation) { row!!.visibility=View.VISIBLE; check(second.requestFocus()) }
                waitFor(instrumentation) { settled(first,false) && settled(second,true) }
            }

            test("Card focus reversals cancel stale motion and stay still after settling") {
                reset()
                main(instrumentation) {
                    check(first.requestFocus())
                    val entering=motion(first)
                    if(ValueAnimator.areAnimatorsEnabled()) {
                        check(entering!=null) { "Visible focus did not start its finite motion" }
                        entering.currentPlayTime=60
                        check(amount(first)>0f && amount(first)<1f)
                    }
                    val interruptedAmount=amount(first)
                    check(second.requestFocus())
                    check(entering?.isStarted!=true) { "Old focus animation survived reversal" }
                    if(ValueAnimator.areAnimatorsEnabled())check(near(amount(first),interruptedAmount)) { "Reversal jumped instead of continuing from the current artwork position" }
                    val leaving=motion(first)
                    check(first.requestFocus())
                    check(leaving?.isStarted!=true) { "Focus-out animation survived refocus" }
                }
                waitFor(instrumentation) { settled(first,true) && settled(second,false) }
                val resting=main(instrumentation) { raster(first) }
                try {
                    SystemClock.sleep(300)
                    main(instrumentation) {
                        check(settled(first,true) && settled(second,false))
                        val later=raster(first)
                        try { check(resting.sameAs(later)) { "Settled card pixels kept moving" } } finally { later.recycle() }
                    }
                } finally { resting.recycle() }
            }
            test("Hidden and detached cards cancel active focus work and recover on reattachment") {
                reset()
                var entering:ValueAnimator?=null
                main(instrumentation) {
                    check(first.requestFocus()); entering=motion(first)
                    row!!.visibility=View.INVISIBLE
                }
                waitFor(instrumentation) { motion(first)==null && entering?.isStarted!=true }
                main(instrumentation) {
                    check(near(amount(first),if(first.isFocused)1f else 0f))
                    row!!.visibility=View.VISIBLE
                }
                reset()
                main(instrumentation) {
                    check(first.requestFocus()); entering=motion(first)
                    row!!.removeView(first)
                    check(!first.isAttachedToWindow)
                    check(motion(first)==null && entering?.isStarted!=true) { "Detached card retained an animator" }
                    check(near(amount(first),if(first.isFocused)1f else 0f))
                    row!!.addView(first,0,LinearLayout.LayoutParams(208,94))
                    check(first.requestFocus())
                }
                waitFor(instrumentation) { first.isAttachedToWindow && settled(first,true) }
            }
            test("Unattached focus uses the immediate complete state without scheduling motion") {
                main(instrumentation) {
                    val group=LinearLayout(context)
                    val card=PrettTvCard(context,1f,PrettTvCard.Mode.APP).apply { isFocusableInTouchMode=true; layout(0,0,208,94) }
                    val sibling=PrettTvCard(context,1f,PrettTvCard.Mode.APP).apply { isFocusableInTouchMode=true; layout(0,0,208,94) }
                    group.addView(card); group.addView(sibling)
                    check(card.requestFocus() && !card.isAttachedToWindow)
                    check(settled(card,true))
                    check(sibling.requestFocus())
                    check(settled(card,false) && settled(sibling,true))
                }
            }
            test("All card modes move artwork while preserving overlays and covered landscape edges") {
                main(instrumentation) {
                    val art=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply {
                        eraseColor(0xFF0040C0.toInt())
                        Canvas(this).drawRect(78f,0f,82f,90f,Paint().apply { color=Color.RED })
                    }
                    try {
                        for(mode in PrettTvCard.Mode.entries) {
                            val (w,h)=dimensions(mode)
                            val card=PrettTvCard(context,1f,mode).apply {
                                isFocusableInTouchMode=true
                                title="Fixed title"; detail="Fixed details"; summary="Fixed summary"; action="Continue"; progress=.5f
                                if(mode==PrettTvCard.Mode.APP)logoBounds=Rect(60,20,100,70)
                                this.art=art; layout(0,0,w,h); check(requestFocus())
                            }
                            amountField.setFloat(card,0f)
                            val before=raster(card)
                            amountField.setFloat(card,1f)
                            val after=raster(card)
                            try {
                                val scanY=if(mode==PrettTvCard.Mode.SPORT)75 else 30
                                val shift=redCenter(after,scanY)-redCenter(before,scanY)
                                check(shift in 5f..7f) { "$mode artwork moved $shift pixels instead of six" }
                                assertFixedIvory(before,after,mode)
                                if(mode==PrettTvCard.Mode.HERO || mode==PrettTvCard.Mode.SHOW || mode==PrettTvCard.Mode.COMPACT) {
                                    val right=if(mode==PrettTvCard.Mode.COMPACT)(w*.52f).toInt()-6 else w-6
                                    for(x in listOf(6,right)) {
                                        val pixel=after.getPixel(x,h/3)
                                        check(Color.alpha(pixel)==255 && Color.blue(pixel)>Color.red(pixel)) { "$mode exposed its ground at artwork edge $x" }
                                    }
                                }
                            } finally { before.recycle(); after.recycle(); card.art=null }
                        }
                    } finally { art.recycle() }
                }
            }
            test("Contained portrait artwork keeps its visible area through the full focus drift") {
                main(instrumentation) {
                    val portrait=Bitmap.createBitmap(80,160,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
                    try {
                        for(mode in listOf(PrettTvCard.Mode.HERO,PrettTvCard.Mode.SHOW,PrettTvCard.Mode.COMPACT)) {
                            val (w,h)=dimensions(mode)
                            val card=PrettTvCard(context,1f,mode).apply {
                                isFocusableInTouchMode=true; art=portrait; layout(0,0,w,h); check(requestFocus())
                            }
                            amountField.setFloat(card,0f); val before=raster(card)
                            amountField.setFloat(card,1f); val after=raster(card)
                            try {
                                val beforeCount=redCount(before,h/3); val afterCount=redCount(after,h/3)
                                check(beforeCount>20 && abs(beforeCount-afterCount)<=1) { "$mode clipped the contained poster during focus travel" }
                                check(redCenter(after,h/3)-redCenter(before,h/3) in 5f..7f)
                            } finally { before.recycle(); after.recycle(); card.art=null }
                        }
                    } finally { portrait.recycle() }
                }
            }
        } finally {
            main(instrumentation) { row?.removeAllViews(); if(!activity.isFinishing)activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun settled(card:PrettTvCard,focused:Boolean)=card.isFocused==focused && motion(card)==null &&
        near(amount(card),if(focused)1f else 0f) && near(card.scaleX,if(focused)1.025f else 1f) && near(card.scaleY,card.scaleX)
    private fun dimensions(mode:PrettTvCard.Mode)=when(mode) {
        PrettTvCard.Mode.HERO -> 1132 to 486
        PrettTvCard.Mode.COMPACT -> 608 to 184
        PrettTvCard.Mode.APP -> 208 to 94
        else -> 420 to 218
    }
    private fun raster(card:PrettTvCard)=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888).also { card.draw(Canvas(it)) }
    private fun red(pixel:Int)=Color.red(pixel)>Color.green(pixel)*2 && Color.red(pixel)>Color.blue(pixel)*2
    private fun redCount(bitmap:Bitmap,y:Int)=(0 until bitmap.width).count { red(bitmap.getPixel(it,y)) }
    private fun redCenter(bitmap:Bitmap,y:Int):Float {
        var sum=0; var count=0
        for(x in 0 until bitmap.width)if(red(bitmap.getPixel(x,y))) { sum+=x; count++ }
        check(count>0) { "Fixture artwork marker was not visible" }
        return sum.toFloat()/count
    }
    private fun assertFixedIvory(before:Bitmap,after:Bitmap,mode:PrettTvCard.Mode) {
        val a=IntArray(before.width*before.height); val b=IntArray(a.size)
        before.getPixels(a,0,before.width,0,0,before.width,before.height)
        after.getPixels(b,0,after.width,0,0,after.width,after.height)
        var fixed=0
        for(i in a.indices)if(a[i]==0xFFF4F1E9.toInt()) {
            fixed++
            check(b[i]==a[i]) { "$mode moved or recolored its text, progress or focus outline at ${i%before.width},${i/before.width}" }
        }
        check(fixed>40) { "$mode fixture did not exercise a visible overlay" }
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
    private fun waitFor(instrumentation:Instrumentation,condition:()->Boolean) {
        val deadline=SystemClock.uptimeMillis()+10_000
        do {
            if(main(instrumentation,condition))return
            SystemClock.sleep(40)
        } while(SystemClock.uptimeMillis()<deadline)
        error("Card focus or lifecycle did not settle")
    }
}
