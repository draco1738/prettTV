package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import java.util.concurrent.atomic.AtomicReference

/** Real view clipping and text pixels; runs only in the isolated test application. */
internal object HomePerformanceAndroidChecks {
    private class CountedCard(context:Context):View(context) {
        var visibilityQueries=0
        override fun getGlobalVisibleRect(bounds:Rect,offset:Point?):Boolean {
            visibilityQueries++
            return super.getGlobalVisibleRect(bounds,offset)
        }
    }
    private class CountedRow(context:Context):HorizontalScrollView(context) {
        var visibilityQueries=0
        override fun getGlobalVisibleRect(bounds:Rect,offset:Point?):Boolean {
            visibilityQueries++
            return super.getGlobalVisibleRect(bounds,offset)
        }
    }
    private data class Visibility(val visible:Boolean,val bounds:Rect?)

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Performance checks must never run in the owner's Home" }
        val activity=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
        try {
            val rows=ArrayList<CountedRow>()
            val cards=ArrayList<CountedCard>()
            main(instrumentation) {
                val stage=FrameLayout(activity)
                activity.setContentView(stage)
                repeat(4) { rowIndex ->
                    val strip=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL }
                    repeat(60) {
                        val card=CountedCard(activity)
                        cards.add(card); strip.addView(card,LinearLayout.LayoutParams(100,100))
                    }
                    val row=CountedRow(activity).apply {
                        clipChildren=true; clipToPadding=true
                        addView(strip,android.view.ViewGroup.LayoutParams(-2,100))
                    }
                    rows.add(row)
                    stage.addView(row,FrameLayout.LayoutParams(500,100).apply { topMargin=if(rowIndex==0)20 else 2000+rowIndex*120 })
                }
            }
            val deadline=SystemClock.uptimeMillis()+10_000
            while(!main(instrumentation) { activity.hasWindowFocus() && cards.all { it.isLaidOut && it.width==100 } }) {
                check(SystemClock.uptimeMillis()<deadline) { "Artwork visibility fixture did not lay out" }
                SystemClock.sleep(40)
            }
            test("Artwork scan skips per-card queries in clipped rows with identical visible bounds") {
                main(instrumentation) {
                    val expected=cards.map { visibility(it) }
                    check(expected.count { it.visible }==5) { "Expected exactly five visible fixture cards" }
                    cards.forEach { it.visibilityQueries=0 }; rows.forEach { it.visibilityQueries=0 }
                    val scan=ArtworkVisibilityScan()
                    val actual=cards.map { visibility(it,scan) }
                    check(actual==expected) { "Grouped scan changed visible artwork or clipping" }
                    check(cards.sumOf { it.visibilityQueries }==60) { "Offscreen rows still traversed every card" }
                    check(rows.sumOf { it.visibilityQueries }==4) { "Rows were queried more than once per scan" }
                    // 64 real global-visibility calls versus 240: no benchmark timing assumption.
                    check(cards.sumOf { it.visibilityQueries }+rows.sumOf { it.visibilityQueries }==64)
                }
            }
            test("Artwork scan follows horizontal clipping and refreshes row visibility on the next scan") {
                main(instrumentation) {
                    rows[0].scrollTo(50,0)
                    rows[1].offsetTopAndBottom(100-rows[1].top)
                    val expected=cards.map { visibility(it) }
                    val scan=ArtworkVisibilityScan()
                    check(cards.map { visibility(it,scan) }==expected)
                    check(expected.first().bounds?.width()==50) { "Partial card clipping was not exercised" }
                    check(expected.count { it.visible }==11) { "Moved row did not become visible" }
                    rows[1].offsetTopAndBottom(2300-rows[1].top)
                    rows[2].clipChildren=false
                    cards.forEach { it.visibilityQueries=0 }
                    val next=ArtworkVisibilityScan()
                    val actual=cards.map { visibility(it,next) }
                    check(cards.drop(120).take(60).all { it.visibilityQueries==1 }) { "Unclipped rows must use the original per-card query" }
                    check(actual==cards.map { visibility(it) }) { "A previous scan's row visibility survived movement" }
                }
            }
            test("Compact summary cache preserves wrapping pixels and reuses settled text layouts") {
                main(instrumentation) {
                    val paragraph=PrettTvCard::class.java.getDeclaredMethod("paragraph",Canvas::class.java,String::class.java,
                        Float::class.javaPrimitiveType,Float::class.javaPrimitiveType,Float::class.javaPrimitiveType).apply { isAccessible=true }
                    val cachedLines=PrettTvCard::class.java.getDeclaredField("paragraphLines").apply { isAccessible=true }
                    for(font in listOf(1f,1.3f,2f)) {
                        val scaled=context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=font })
                        val card=PrettTvCard(scaled,1f,PrettTvCard.Mode.COMPACT)
                        for(width in listOf(24f,160f,300f))for(value in listOf(
                            "  A long description that wraps over two lines and needs an ellipsis at the end.  ",
                            "A changed description", "Supercalifragilisticexpialidocious", "")) {
                            val expected=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                            val actual=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                            val repeated=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                            try {
                                legacyParagraph(Canvas(expected),value,8f,52f,width,font)
                                paragraph.invoke(card,Canvas(actual),value,8f,52f,width)
                                check(actual.sameAs(expected)) { "Cached summary changed pixels at width $width, font $font" }
                                val layout=cachedLines.get(card)
                                paragraph.invoke(card,Canvas(repeated),value,8f,52f,width)
                                check(cachedLines.get(card)===layout) { "An unchanged draw rebuilt the paragraph" }
                                check(repeated.sameAs(expected)) { "Repeated summary draw changed pixels" }
                            } finally { expected.recycle(); actual.recycle(); repeated.recycle() }
                        }
                    }
                    val scaled=context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=1f })
                    val original=Configuration(scaled.resources.configuration)
                    val card=PrettTvCard(scaled,1f,PrettTvCard.Mode.COMPACT)
                    val value="A description whose wrapping changes when the system font gets larger"
                    val before=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                    val after=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                    val expected=Bitmap.createBitmap(340,220,Bitmap.Config.ARGB_8888)
                    try {
                        paragraph.invoke(card,Canvas(before),value,8f,52f,160f)
                        val layout=cachedLines.get(card)
                        @Suppress("DEPRECATION")
                        scaled.resources.updateConfiguration(Configuration(original).apply { fontScale=2f },scaled.resources.displayMetrics)
                        paragraph.invoke(card,Canvas(after),value,8f,52f,160f)
                        legacyParagraph(Canvas(expected),value,8f,52f,160f,2f)
                        check(cachedLines.get(card)!==layout && after.sameAs(expected)) { "Existing card retained stale wrapping after a font-size change" }
                    } finally {
                        @Suppress("DEPRECATION")
                        scaled.resources.updateConfiguration(original,scaled.resources.displayMetrics)
                        before.recycle(); after.recycle(); expected.recycle()
                    }
                }
            }
        } finally {
            main(instrumentation) { if(!activity.isFinishing)activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }

    private fun visibility(card:View,scan:ArtworkVisibilityScan?=null):Visibility {
        val bounds=Rect()
        val visible=scan?.isVisible(card,bounds) ?: card.getGlobalVisibleRect(bounds)
        return Visibility(visible,if(visible)bounds else null)
    }
    /** The previous drawing algorithm is the pixel oracle, including its word-break behavior. */
    private fun legacyParagraph(canvas:Canvas,value:String,x:Float,y:Float,width:Float,font:Float) {
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color=PrettTvColors.SECONDARY; textSize=22f*font; typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        }
        var rest=value.trim()
        for(i in 0..1) {
            if(rest.isEmpty())break
            if(i==1) { canvas.drawText(TextUtils.ellipsize(rest,paint,width,TextUtils.TruncateAt.END).toString(),x,y+i*29f*font,paint); break }
            val count=paint.breakText(rest,true,width,null)
            var end=count
            if(count<rest.length)rest.lastIndexOf(' ',count).takeIf { it>0 }?.let { end=it }
            if(end==0)break
            canvas.drawText(rest.substring(0,end),x,y+i*29f*font,paint)
            rest=rest.substring(end).trimStart()
        }
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }
}
