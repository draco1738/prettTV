package fi.jommi.rivi

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/** Small, static native glyph: radio state, never a claim that headphones are connected. */
internal class BluetoothStatusDrawable(private val size:Int):Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth=2.2f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
    }
    private val rune=Path().apply {
        moveTo(9f,8f);lineTo(23f,21f);lineTo(16f,28f);lineTo(16f,2f)
        lineTo(23f,9f);lineTo(9f,22f)
    }
    private val mark=Path()
    private var enabled:Boolean?=null
    private var working=false
    private var focused=false
    private var opacity=255

    fun bind(value:BluetoothControlSession.State) {
        if(enabled==value.enabled && working==value.working)return
        enabled=value.enabled;working=value.working;invalidateSelf()
    }
    override fun isStateful()=true
    override fun onStateChange(state:IntArray):Boolean {
        val next=android.R.attr.state_focused in state
        if(next==focused)return false
        focused=next;invalidateSelf();return true
    }
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
    override fun setAlpha(alpha:Int) { opacity=alpha.coerceIn(0,255);invalidateSelf() }
    override fun setColorFilter(filter:ColorFilter?) { paint.colorFilter=filter;invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity()=PixelFormat.TRANSLUCENT

    override fun draw(canvas:Canvas) {
        if(bounds.isEmpty)return
        val save=canvas.save()
        canvas.translate(bounds.left.toFloat(),bounds.top.toFloat())
        canvas.scale(bounds.width()/36f,bounds.height()/36f)
        val foreground=if(focused)PrettTvColors.TEXT else if(enabled==true && !working)0xFF93C5FD.toInt() else PrettTvColors.MUTED
        paint.color=foreground;paint.alpha=opacity;paint.style=Paint.Style.STROKE
        canvas.drawPath(rune,paint)
        when {
            working->{
                // No animation/timer: distinct dots communicate pending work without idle redraws.
                paint.style=Paint.Style.FILL
                for(x in listOf(24f,28f,32f))canvas.drawCircle(x,31f,1.2f,paint)
            }
            enabled==false->canvas.drawLine(5f,4f,29f,30f,paint)
            enabled==true->{
                paint.style=Paint.Style.FILL;canvas.drawCircle(28f,28f,6f,paint)
                paint.style=Paint.Style.STROKE;paint.strokeWidth=1.8f
                paint.color=PrettTvColors.BACKGROUND;paint.alpha=opacity
                mark.reset();mark.moveTo(25f,28f);mark.lineTo(27f,30f);mark.lineTo(31f,26f)
                canvas.drawPath(mark,paint);paint.strokeWidth=2.2f
            }
            else->{
                // A drawn question mark keeps unavailable distinct from verified off.
                paint.strokeWidth=1.8f
                mark.reset();mark.moveTo(25f,24.5f);mark.cubicTo(25f,21f,31f,21f,31f,24.5f)
                mark.cubicTo(31f,27f,28f,26f,28f,29f)
                canvas.drawPath(mark,paint);paint.style=Paint.Style.FILL;canvas.drawCircle(28f,32f,1f,paint)
                paint.strokeWidth=2.2f
            }
        }
        canvas.restoreToCount(save)
    }
}
