package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect

/** Attributed Dashboard Icons assets, composed onto a dark native tile offline. */
object AppIconPack {
    private val icons=mapOf(
        "com.netflix.ninja" to "netflix", "com.amazon.amazonvideo.livingroom" to "prime-video-light",
        "com.google.android.youtube.tv" to "youtube", "com.apple.atve.androidtv.appletv" to "apple-tv-plus-light",
        "com.crunchyroll.crunchyroid" to "crunchyroll", "com.stremio.one" to "stremio",
        "com.cbs.ott" to "paramount-plus-light", "com.peacocktv.peacockandroid" to "peacock-light",
        "tv.pluto.android" to "pluto-tv", "com.hulu.livingroomplus" to "hulu", "com.nordvpn.android" to "nordvpn",
        "com.hbo.hbonow" to "hbo-light", "com.wbd.stream" to "hbo-light")
    fun render(context:Context,pkg:String,w:Int,h:Int):Bitmap? {
        if(pkg=="com.disney.disneyplus")return renderDisney(context,w,h)
        val name=icons[pkg] ?: return null
        return try {
            val source=context.assets.open("app-icons/$name.png").use { BitmapFactory.decodeStream(it) } ?: return null
            val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(output); canvas.drawColor(0xFF141C17.toInt())
            val ratio=minOf(w*.72f/source.width,h*.68f/source.height)
            val width=source.width*ratio; val height=source.height*ratio
            canvas.drawBitmap(source,null,RectF((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2),markPaint(pkg))
            source.recycle(); output
        } catch(_:Exception) { null }
    }
    private fun markPaint(pkg:String)=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // This attributed asset is a black monochrome silhouette, despite its "-light" name.
        // Tint only this known mark; preserve color in all other brands.
        if(pkg=="com.cbs.ott")colorFilter=PorterDuffColorFilter(0xFFF4F1E9.toInt(),PorterDuff.Mode.SRC_IN)
    }

    /** Tight transparent brand mark for the Library's shared surface and separate label. */
    fun libraryMark(context:Context,pkg:String):Bitmap? = try {
        if(pkg=="com.disney.disneyplus") {
            val mark=context.getDrawable(R.drawable.ic_disney_dark)!!
            val ratio=minOf(224f/mark.intrinsicWidth,72f/mark.intrinsicHeight)
            val output=Bitmap.createBitmap((mark.intrinsicWidth*ratio).toInt().coerceAtLeast(1),
                (mark.intrinsicHeight*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            mark.setBounds(0,0,output.width,output.height); mark.draw(Canvas(output)); output
        } else {
            val name=icons[pkg]
            if(name==null)null else context.assets.open("app-icons/$name.png").use { input ->
                val source=BitmapFactory.decodeStream(input) ?: return@use null
                try {
                    var left=source.width; var top=source.height; var right=-1; var bottom=-1
                    val pixels=IntArray(source.width*source.height)
                    source.getPixels(pixels,0,source.width,0,0,source.width,source.height)
                    for(y in 0 until source.height)for(x in 0 until source.width)if((pixels[y*source.width+x] ushr 24)>32) {
                        left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y)
                    }
                    if(right<left || bottom<top)null else {
                        val bounds=Rect(left,top,right+1,bottom+1)
                        val ratio=minOf(224f/bounds.width(),72f/bounds.height())
                        val output=Bitmap.createBitmap((bounds.width()*ratio).toInt().coerceAtLeast(1),
                            (bounds.height()*ratio).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                        Canvas(output).drawBitmap(source,bounds,RectF(0f,0f,output.width.toFloat(),output.height.toFloat()),markPaint(pkg))
                        output
                    }
                } finally { source.recycle() }
            }
        }
    } catch(_:Exception) { null }
    private fun renderDisney(context:Context,w:Int,h:Int):Bitmap? = try {
        val mark=context.getDrawable(R.drawable.ic_disney_dark)!!
        val output=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(output); canvas.drawColor(0xFF141C17.toInt())
        val ratio=minOf(w*.72f/mark.intrinsicWidth,h*.68f/mark.intrinsicHeight)
        val width=(mark.intrinsicWidth*ratio).toInt(); val height=(mark.intrinsicHeight*ratio).toInt()
        val left=(w-width)/2; val top=(h-height)/2
        mark.setBounds(left,top,left+width,top+height); mark.draw(canvas)
        output
    } catch(_:Exception) { null }
}
