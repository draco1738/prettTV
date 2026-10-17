package fi.jommi.rivi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Authored line icons; no icon font or bitmap dependency. */
class NavIcon(context:Context,private val gear:Boolean):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    init { isFocusable=true; isClickable=true; contentDescription=if(gear)"Settings" else "Search" }
    override fun onFocusChanged(gain:Boolean,direction:Int,old:android.graphics.Rect?) { super.onFocusChanged(gain,direction,old); invalidate() }
    override fun onDraw(c:Canvas) {
        val u=minOf(width,height)/64f; val x=width/2f; val y=height/2f
        paint.style=Paint.Style.FILL; paint.color=if(isFocused)0xFFF4F1E9.toInt() else android.graphics.Color.TRANSPARENT
        c.drawRoundRect(2f*u,2f*u,width-2f*u,height-2f*u,28f*u,28f*u,paint)
        paint.color=if(isFocused)0xFF101612.toInt() else 0xFFD5DAD2.toInt(); paint.style=Paint.Style.STROKE; paint.strokeWidth=2.2f*u
        if(!gear) { c.drawCircle(x-3*u,y-3*u,11*u,paint); c.drawLine(x+5*u,y+5*u,x+15*u,y+15*u,paint) }
        else {
            val p=Path()
            for(i in 0 until 48) {
                val r=(if(i%6 in 1..3)16f else 12.5f)*u
                val a=i*Math.PI*2/48
                val px=x+cos(a).toFloat()*r; val py=y+sin(a).toFloat()*r
                if(i==0)p.moveTo(px,py)else p.lineTo(px,py)
            }
            p.close(); c.drawPath(p,paint); c.drawCircle(x,y,5.5f*u,paint)
        }
    }
}
