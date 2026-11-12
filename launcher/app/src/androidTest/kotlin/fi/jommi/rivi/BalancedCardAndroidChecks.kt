package fi.jommi.rivi

import android.app.Instrumentation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.StaticLayout
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil

/** Synthetic raster fixtures, not provider artwork or a claim of production screenshot parity. */
internal object BalancedCardAndroidChecks {
    private val modes=listOf(PrettTvCard.Mode.SPLIT_HERO,PrettTvCard.Mode.POSTER,PrettTvCard.Mode.CONTINUATION)
    private fun field(name:String)=PrettTvCard::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun bounds(card:PrettTvCard,name:String)=RectF(field(name).get(card) as RectF)
    private fun titleLayout(card:PrettTvCard)=field("balancedTitleLayout").get(card) as StaticLayout
    private fun near(a:Float,b:Float)=abs(a-b)<.01f
    private fun scaled(context:Context,font:Float)=context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=font })
    private fun raster(card:PrettTvCard)=Bitmap.createBitmap(card.width,card.height,Bitmap.Config.ARGB_8888).also { card.draw(Canvas(it)) }
    private fun dimensions(mode:PrettTvCard.Mode,font:Float)=when(mode) {
        PrettTvCard.Mode.SPLIT_HERO -> 1112 to 510
        PrettTvCard.Mode.POSTER -> 202 to (384+ceil(70.0*(font-1f)).toInt())
        PrettTvCard.Mode.CONTINUATION -> 586 to (128+ceil(100.0*(font-1f)).toInt())
        else -> error("Not a Balanced fixture: $mode")
    }
    private fun fixture(context:Context,mode:PrettTvCard.Mode,font:Float=1f)=PrettTvCard(scaled(context,font),1f,mode).apply {
        val (w,h)=dimensions(mode,font)
        isFocusableInTouchMode=true; quietMotion=false
        title="A Very Long Journey Home Across the Mountains and Beyond the Distant Horizon"
        detail="S2 E4"; secondaryDetail="31 min left"; action="Resume"; sourceLabel="Fixture provider"; progress=.25f
        contentDescription="$title. $detail. $secondaryDetail. $action. $sourceLabel"
        layout(0,0,w,h)
    }

    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit,activity:CardFocusTestActivity) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability") { "Render checks must never run in the owner's Home" }
        test("Balanced hero wraps two lines and reserves metadata progress action at both font scales") {
            main(instrumentation) {
                for(font in listOf(1f,1.3f))for(progress in listOf(0f,.25f,1f)) {
                    val card=fixture(context,PrettTvCard.Mode.SPLIT_HERO,font).apply { this.progress=progress }
                    val image=raster(card)
                    try {
                        val title=bounds(card,"balancedTitleBounds"); val metadata=bounds(card,"balancedMetadataBounds")
                        val track=bounds(card,"balancedProgressBounds"); val action=bounds(card,"actionBounds")
                        val layout=titleLayout(card)
                        check(layout.lineCount==2 && layout.getEllipsisCount(1)>0) { "Long hero title did not wrap and ellipsize at $font" }
                        val split=layout.getLineStart(1)
                        check(card.title[split-1].isWhitespace() || card.title[split].isWhitespace()) { "Hero split a word despite an available word boundary" }
                        check(near(title.left,44f) && near(title.width(),520f) && near(metadata.width(),650f))
                        check(title.top>=44f && title.bottom<=metadata.top-22f) { "Hero title overlaps metadata at $font" }
                        check(layout.paint.textSize>=56f && layout.paint.textSize<=56f*font+.01f)
                        check(near(action.height(),52f*font) && near(action.bottom,472f))
                        check(action.width()<470f && metadata.bottom<action.top)
                        check(image.getPixel((action.right-10f).toInt(),action.centerY().toInt())==Color.BLACK) { "Hero action did not keep a black face" }
                        if(progress>0f) {
                            check(near(track.width(),336f) && near(track.height(),4f))
                            check(metadata.bottom<track.top && track.bottom<action.top)
                            check(image.getPixel((track.left+2f).toInt(),track.centerY().toInt())==PrettTvColors.TEXT)
                            check(image.getPixel((track.right-2f).toInt(),track.centerY().toInt())==if(progress==1f)PrettTvColors.TEXT else PrettTvColors.EDGE)
                        } else check(track.isEmpty) { "Missing progress manufactured a track" }
                        check(Color.alpha(image.getPixel(0,0))==0)
                        val cached=titleLayout(card)
                        val again=raster(card)
                        try { check(titleLayout(card)===cached && image.sameAs(again)) { "Settled hero rebuilt text or changed pixels" } } finally { again.recycle() }
                        card.title="Game of Thrones" // Approved hierarchy, exercised as ordinary supplied title data.
                        val approved=raster(card)
                        try {
                            val wrapped=titleLayout(card)
                            check(wrapped.lineCount==2 && wrapped.getEllipsisCount(1)==0)
                            check(card.title.substring(0,wrapped.getLineEnd(0)).trim()=="Game of") { "Feature title lost its two-line left-column hierarchy" }
                            check(near(wrapped.paint.textSize,56f*font))
                        } finally { approved.recycle() }
                        card.title="Short title"
                        val rebound=raster(card)
                        try { check(titleLayout(card)!==cached && titleLayout(card).lineCount==1) { "Rebound title kept stale wrapping" } } finally { rebound.recycle() }
                    } finally { image.recycle() }
                }
            }
        }
        test("Balanced hero keeps bright scene art behind strong left and bottom black scrims") {
            main(instrumentation) {
                val white=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                val card=fixture(context,PrettTvCard.Mode.SPLIT_HERO).apply {
                    title=""; detail=""; secondaryDetail=""; action=""; sourceLabel=""; progress=0f; art=white
                }
                val image=raster(card)
                try {
                    check(Color.red(image.getPixel(1000,50))>230) { "Scene artwork was obscured on its open side" }
                    check(Color.red(image.getPixel(50,50))<30) { "Left scrim was too weak for white display type" }
                    check(Color.red(image.getPixel(1000,480))<50) { "Bottom scrim was missing" }
                    check(bounds(card,"balancedMetadataBounds").isEmpty && bounds(card,"balancedProgressBounds").isEmpty && bounds(card,"actionBounds").isEmpty)
                    card.art=null
                    val missing=raster(card)
                    try { check(missing.getPixel(1000,50)==Color.BLACK && missing.getPixel(500,250)==Color.BLACK) } finally { missing.recycle() }
                } finally { card.art=null; image.recycle(); white.recycle() }
            }
        }
        test("Balanced poster uses a 2 to 3 picture and two full size title lines at both font scales") {
            main(instrumentation) {
                val portrait=Bitmap.createBitmap(200,300,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
                try {
                    for(font in listOf(1f,1.3f)) {
                        val card=fixture(context,PrettTvCard.Mode.POSTER,font).apply { art=portrait }
                        val accessible=card.contentDescription
                        val image=raster(card)
                        try {
                            val art=bounds(card,"balancedArtBounds"); val title=bounds(card,"balancedTitleBounds"); val layout=titleLayout(card)
                            check(near(art.width(),202f) && near(art.height(),303f))
                            check(near(title.top,319f) && title.bottom<=card.height-4f) { "Poster title escaped ${card.height}px at $font" }
                            check(layout.lineCount==2 && layout.getEllipsisCount(1)>0)
                            check(near(layout.paint.textSize,25f*font)) { "Poster did not preserve scaled 25px type" }
                            check(image.getPixel(101,20)==Color.GREEN && image.getPixel(101,292)==Color.GREEN)
                            check(image.getPixel(101,310)==Color.BLACK) { "Poster lost the black gap beneath its picture" }
                            check(bounds(card,"balancedProgressBounds").isEmpty) { "Poster acquired an unrelated progress overlay" }
                            check(card.contentDescription==accessible && accessible.toString().contains(card.title)) { "Visual truncation damaged the accessible name" }
                        } finally { card.art=null; image.recycle() }
                    }
                } finally { portrait.recycle() }
            }
        }
        test("Balanced poster fills its frame without flattening wide fallback artwork") {
            main(instrumentation) {
                val landscape=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.BLUE)
                    Canvas(this).apply {
                        val p=Paint().apply { color=Color.RED }
                        drawRect(0f,0f,16f,90f,p); drawRect(144f,0f,160f,90f,p)
                    }
                }
                val card=fixture(context,PrettTvCard.Mode.POSTER).apply { art=landscape }
                val image=raster(card)
                try {
                    val destination=bounds(card,"imageDestination")
                    check(near(destination.height(),303f) && abs(destination.width()/destination.height()-160f/90f)<.0001f)
                    check(destination.left<0f && destination.right>202f) { "Wide fallback did not use proportional center cropping" }
                    check(image.getPixel(10,151)==Color.BLUE && image.getPixel(191,151)==Color.BLUE) { "Wide fallback squeezed its outside edge markers into view" }
                    check(image.getPixel(101,30)==Color.BLUE && image.getPixel(101,270)==Color.BLUE) { "Poster still has black letterbox strips" }
                    card.art=null
                    val missing=raster(card)
                    try { check(missing.getPixel(101,151)==Color.BLACK && titleLayout(card).lineCount==2) } finally { missing.recycle() }
                } finally { card.art=null; image.recycle(); landscape.recycle() }
            }
        }
        test("Balanced nonstandard portraits fill all edges and keep circles round") {
            main(instrumentation) {
                for((w,h) in listOf(210 to 295,200 to 400)) {
                    val poster=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.GREEN)
                        Canvas(this).drawCircle(w/2f,h/2f,35f,Paint().apply { color=Color.WHITE })
                    }
                    val card=fixture(context,PrettTvCard.Mode.POSTER).apply { art=poster }
                    val image=raster(card)
                    try {
                        val destination=bounds(card,"imageDestination")
                        check(abs(destination.width()/destination.height()-w.toFloat()/h)<.0001f) { "Poster changed source proportions" }
                        for((x,y) in listOf(15 to 15,187 to 15,15 to 288,187 to 288))
                            check(image.getPixel(x,y)==Color.GREEN) { "Non-2:3 portrait left a black gutter" }
                        val circleWidth=(0 until 202).count { image.getPixel(it,151)==Color.WHITE }
                        val circleHeight=(0 until 303).count { image.getPixel(101,it)==Color.WHITE }
                        check(circleWidth>50 && abs(circleWidth-circleHeight)<=2) { "Round poster marker became an oval" }
                    } finally { card.art=null;image.recycle();poster.recycle() }
                }
            }
        }
        test("Balanced continuation keeps text on black beside its 190px image and real progress") {
            main(instrumentation) {
                val imageArt=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                try {
                    for(font in listOf(1f,1.3f))for(progress in listOf(0f,.25f)) {
                        val card=fixture(context,PrettTvCard.Mode.CONTINUATION,font).apply { art=imageArt; this.progress=progress }
                        val image=raster(card)
                        try {
                            val art=bounds(card,"balancedArtBounds"); val title=bounds(card,"balancedTitleBounds")
                            val metadata=bounds(card,"balancedMetadataBounds"); val track=bounds(card,"balancedProgressBounds")
                            check(near(art.width(),190f) && near(art.height(),card.height.toFloat()))
                            check(near(title.left,214f) && title.right<=card.width-22f && title.top>3f)
                            check(title.bottom<metadata.top && metadata.bottom<card.height-3f)
                            check(image.getPixel(100,card.height/2)==Color.BLUE && image.getPixel(202,card.height/2)==Color.BLACK)
                            if(progress>0f) {
                                check(metadata.bottom<track.top && track.bottom<card.height-3f)
                                check(near(track.height(),4f) && track.right<=card.width-22f)
                                check(image.getPixel((track.left+2).toInt(),track.centerY().toInt())==PrettTvColors.TEXT)
                            } else check(track.isEmpty)
                            card.detail=""; card.secondaryDetail=""
                            val fallback=raster(card)
                            try { check(!bounds(card,"balancedMetadataBounds").isEmpty) { "Missing detail discarded the real source label" } } finally { fallback.recycle() }
                        } finally { card.art=null; image.recycle() }
                    }
                } finally { imageArt.recycle() }
            }
        }
        test("Balanced focus is a static white outline and preserves OK and long OK semantics") {
            main(instrumentation) {
                for(mode in modes)for(quiet in listOf(false,true)) {
                    val card=fixture(context,mode).apply { quietMotion=quiet }
                    val before=raster(card)
                    var clicks=0; var longClicks=0
                    card.setOnClickListener { clicks++ }; card.setOnLongClickListener { longClicks++; true }
                    check(card.requestFocus())
                    val after=raster(card)
                    try {
                        check(!before.sameAs(after)) { "$mode did not reveal a focus outline" }
                        check(near(card.scaleX,1f) && near(card.scaleY,1f) && field("focusMotion").get(card)==null)
                        if(mode==PrettTvCard.Mode.SPLIT_HERO) {
                            val action=bounds(card,"actionBounds")
                            check(after.getPixel((action.left-7f).toInt(),action.centerY().toInt())==Color.WHITE)
                            check(before.getPixel(1000,50)==after.getPixel(1000,50))
                        } else check(after.getPixel(3,card.height/2)==Color.WHITE)
                        field("focusAmount").setFloat(card,0f)
                        val amountZero=raster(card)
                        try { check(after.sameAs(amountZero)) { "$mode still responded to artwork drift or spotlight opacity" } } finally { amountZero.recycle() }
                        fun key(action:Int,flags:Int=0)=KeyEvent(SystemClock.uptimeMillis(),SystemClock.uptimeMillis(),action,KeyEvent.KEYCODE_DPAD_CENTER,0,0,-1,0,flags)
                        card.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER,key(KeyEvent.ACTION_DOWN))
                        card.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER,key(KeyEvent.ACTION_UP,KeyEvent.FLAG_TRACKING))
                        check(clicks==1 && longClicks==0)
                        card.onKeyDown(KeyEvent.KEYCODE_DPAD_CENTER,key(KeyEvent.ACTION_DOWN))
                        card.onKeyLongPress(KeyEvent.KEYCODE_DPAD_CENTER,key(KeyEvent.ACTION_DOWN))
                        card.onKeyUp(KeyEvent.KEYCODE_DPAD_CENTER,key(KeyEvent.ACTION_UP,KeyEvent.FLAG_TRACKING))
                        check(clicks==1 && longClicks==1) { "$mode long OK also launched on release" }
                    } finally { before.recycle(); after.recycle() }
                }
            }
        }
        test("Every Balanced mode preserves the Stremio source badge at both text scales") {
            main(instrumentation) {
                for(mode in modes)for(font in listOf(1f,1.3f)) {
                    val card=fixture(context,mode,font).apply { stremio=true }
                    val image=raster(card)
                    try {
                        val y=ceil(14f+14f*font).toInt()
                        check(image.getPixel(18,y)==0xFF8657D7.toInt()) { "$mode lost its source badge at $font" }
                        card.stremio=false
                        val rebound=raster(card)
                        try { check(rebound.getPixel(18,y)!=0xFF8657D7.toInt()) { "Source badge survived rebinding" } }
                        finally { rebound.recycle() }
                    } finally { image.recycle() }
                }
            }
        }
        test("Attached Balanced cards settle immediately and clear feedback on hide and detach") {
            val group=main(instrumentation) {
                LinearLayout(activity).apply {
                    orientation=LinearLayout.VERTICAL
                    activity.setContentView(this)
                }
            }
            for(mode in modes) {
                val card=main(instrumentation) {
                    fixture(activity,mode).also { group.addView(it,LinearLayout.LayoutParams(it.width,it.height)) }
                }
                try {
                    waitFor(instrumentation) { activity.hasWindowFocus() && card.isShown && card.isAttachedToWindow }
                    main(instrumentation) {
                        check(card.requestFocus())
                        check(field("focusMotion").get(card)==null && near(card.scaleX,1f) && near(card.scaleY,1f))
                        card.showBoundaryFeedback(); check(field("boundaryVisible").getBoolean(card))
                        group.visibility=View.INVISIBLE
                        check(!field("boundaryVisible").getBoolean(card) && field("focusMotion").get(card)==null)
                        group.visibility=View.VISIBLE
                        check(card.requestFocus()); card.showBoundaryFeedback()
                        group.removeView(card)
                        check(!field("boundaryVisible").getBoolean(card) && !field("confirmHeld").getBoolean(card))
                        check(field("focusMotion").get(card)==null && near(card.scaleX,1f))
                    }
                } finally { main(instrumentation) { group.removeView(card) } }
            }
        }
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
        error("Balanced card fixture did not attach")
    }
}
