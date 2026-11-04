package fi.jommi.rivi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

/** Original utility pictograms and plain app names, with transparent backgrounds.
 * Brand logos in AppIconPack keep their attribution; these are not third-party logos.
 */
internal object DarkAppGlyphs {
    private data class Mark(val label:String,val shape:String,val accent:Int=0xFF69D8E0.toInt())
    private val marks=mapOf(
        "com.phlox.tvwebbrowser" to Mark("TV Bro","browser"),
        "com.rblive.app" to Mark("RBTV+","play",0xFFFFC34A.toInt()),
        "com.android.tv.settings" to Mark("Settings","sliders"),
        "com.tcl.ocean.instructions" to Mark("Guide","book"),
        "com.tcl.tv" to Mark("TV","screen"),
        "com.tcl.exhibit" to Mark("Exhibition","screen"),
        "com.tcl.ui_mediaCenter" to Mark("Media","play"),
        "com.tcl.suspension" to Mark("Controls","sliders"),
        "com.tcl.dashboard" to Mark("Controls","sliders"),
        "com.tcl.t_solo" to Mark("Audio","headphones"),
        "com.android.vending" to Mark("Play Store","play",0xFF70E2A0.toInt()),
        "com.google.android.play.games" to Mark("Games","game",0xFF70E2A0.toInt()),
        "com.google.android.videos" to Mark("Google TV","screen"),
        "com.google.android.youtube.tvmusic" to Mark("Music","music",0xFFFF5858.toInt()),
        "com.google.android.youtube.tvunplugged" to Mark("YouTube TV","screen",0xFFFF5858.toInt()),
        "com.instagram.airwave" to Mark("Instagram","screen"),
        "com.corepower" to Mark("CorePower","play"),
        "com.roku.web.trc" to Mark("Roku","play",0xFFB690F5.toInt()),
        "com.nousguide.android.rbtv" to Mark("Red Bull","play",0xFFFF5858.toInt()),
        "com.tubitv" to Mark("Tubi","play",0xFFFFDE55.toInt()),
        "com.limelight" to Mark("Moonlight","game"),
        "dev.zane.pretttv.sevenmovies" to Mark("7Movies","play")
    )

    fun render(pkg:String):Bitmap? {
        val mark=marks[pkg] ?: return null
        val output=Bitmap.createBitmap(224,72,Bitmap.Config.ARGB_8888)
        val c=Canvas(output)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=PrettTvColors.TEXT;style=Paint.Style.STROKE
            strokeWidth=2.2f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
        }
        c.save();c.translate(4f,12f);c.scale(1.5f,1.5f)
        fun line(x:Float,y:Float,a:Float,b:Float)=c.drawLine(x,y,a,b,p)
        when(mark.shape) {
            "browser" -> {
                c.drawCircle(16f,16f,13f,p)
                c.drawOval(9f,3f,23f,29f,p)
                line(3f,16f,29f,16f)
                p.color=mark.accent;c.drawArc(3f,3f,29f,29f,210f,90f,false,p)
            }
            "sliders" -> {
                for(x in listOf(7f,16f,25f))line(x,4f,x,28f)
                p.color=mark.accent
                for((x,y) in listOf(7f to 11f,16f to 22f,25f to 9f)) {
                    p.style=Paint.Style.FILL;p.color=PrettTvColors.BACKGROUND;c.drawCircle(x,y,3.5f,p)
                    p.style=Paint.Style.STROKE;p.color=mark.accent;c.drawCircle(x,y,3.5f,p)
                }
            }
            "book" -> {
                c.drawRoundRect(5f,3f,27f,29f,3f,3f,p);line(10f,3f,10f,29f)
                p.color=mark.accent;line(14f,11f,23f,11f);line(14f,16f,21f,16f)
            }
            "headphones" -> {
                c.drawArc(4f,4f,28f,28f,180f,180f,false,p)
                p.color=mark.accent;c.drawRoundRect(3f,16f,9f,28f,2f,2f,p)
                c.drawRoundRect(23f,16f,29f,28f,2f,2f,p)
            }
            "game" -> {
                val path=Path().apply {
                    moveTo(10f,8f);lineTo(22f,8f);cubicTo(28f,8f,30f,18f,29f,25f)
                    cubicTo(28f,30f,22f,23f,21f,22f);lineTo(11f,22f)
                    cubicTo(10f,23f,4f,30f,3f,25f);cubicTo(2f,18f,4f,8f,10f,8f)
                };c.drawPath(path,p)
                line(7f,15f,13f,15f);line(10f,12f,10f,18f)
                p.color=mark.accent;c.drawCircle(23f,14f,1f,p);c.drawCircle(20f,18f,1f,p)
            }
            "music" -> {
                line(12f,23f,12f,7f);line(12f,7f,26f,4f);line(26f,4f,26f,20f)
                p.color=mark.accent;c.drawOval(4f,21f,12f,27f,p);c.drawOval(18f,18f,26f,24f,p)
            }
            "screen" -> {
                c.drawRoundRect(2f,5f,30f,25f,3f,3f,p)
                line(11f,29f,21f,29f);line(16f,25f,16f,29f)
                p.color=mark.accent;line(8f,11f,14f,11f)
            }
            else -> {
                c.drawRoundRect(2f,5f,30f,27f,4f,4f,p)
                p.color=mark.accent
                c.drawPath(Path().apply { moveTo(13f,11f);lineTo(22f,16f);lineTo(13f,21f);close() },p)
            }
        }
        c.restore()
        p.style=Paint.Style.FILL;p.color=PrettTvColors.TEXT
        p.typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL);p.textSize=29f
        p.textSize*=minOf(1f,154f/p.measureText(mark.label))
        c.drawText(mark.label,66f,36f-(p.ascent()+p.descent())/2,p)
        return output
    }
}
