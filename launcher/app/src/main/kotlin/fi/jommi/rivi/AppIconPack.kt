package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

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
            canvas.drawBitmap(source,null,RectF((w-width)/2,(h-height)/2,(w+width)/2,(h+height)/2),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            source.recycle(); output
        } catch(_:Exception) { null }
    }
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
