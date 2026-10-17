package fi.jommi.rivi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/** Native still-art card. Remote/focus pattern adapted from Rivi by Jommi (MIT). */
class PrettTvCard(context:Context,private val unit:Float,private val mode:Mode,private val cornerRadius:Float=15f):View(context) {
    private companion object {
        val regularTypeface:Typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val mediumTypeface:Typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    enum class Mode { HERO, SHOW, COMPACT, APP, SPORT }
    var title=""
    var detail=""
    var summary=""
    var action=""
    var progress=0f
    var stremio=false
    var logoBounds:Rect?=null
    private var appShader:BitmapShader?=null
    private val appMatrix=Matrix()
    var art:Bitmap?=null
        set(value) {
            if(field===value)return
            field=value
            appGround=if(value!=null && mode==Mode.APP)value.getPixel(minOf(8,value.width-1),value.height/2) else 0xFF1C241E.toInt()
            appShader=if(value!=null && mode==Mode.APP)BitmapShader(value,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP) else null
            invalidate()
        }
    private var appGround=0xFF1C241E.toInt()
    private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotlightPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val shape=Path()
    private val compactShape=Path()
    private val actionPlay=Path()
    private val rect=RectF()
    private val imageDestination=RectF()
    private val actionBounds=RectF()
    private var shade:LinearGradient?=null
    private var leftShade:LinearGradient?=null
    private var focusAmount=0f
    private var focusMotion:ValueAnimator?=null
    private val focusInterpolator=DecelerateInterpolator()
    private val ivory=0xFFF4F1E9.toInt()
    private val muted=0xFFBCC4BA.toInt()
    private fun s(n:Float)=n*unit
    init { isFocusable=true; isClickable=true; id=generateViewId() }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        rect.set(s(3f),s(3f),w-s(3f),h-s(3f)); shape.reset(); shape.addRoundRect(rect,s(cornerRadius),s(cornerRadius),Path.Direction.CW)
        if(mode==Mode.COMPACT) {
            compactShape.reset()
            imageDestination.set(s(3f),s(6f),w*.52f,h-s(6f))
            compactShape.addRoundRect(imageDestination,s(10f),s(10f),Path.Direction.CW)
        }
        if(mode==Mode.HERO) {
            val inset=s(38f)
            actionPlay.reset(); actionPlay.moveTo(inset+s(26f),h-s(68f)); actionPlay.lineTo(inset+s(26f),h-s(46f)); actionPlay.lineTo(inset+s(44f),h-s(57f)); actionPlay.close()
        }
        shade=if(mode==Mode.SHOW)LinearGradient(0f,0f,0f,h.toFloat(),
            intArrayOf(0x000A100C,0x000A100C,0xE60A100C.toInt(),0xF20A100C.toInt()),
            floatArrayOf(0f,.32f,.58f,1f),Shader.TileMode.CLAMP)
        else LinearGradient(0f,h*.35f,0f,h.toFloat(),intArrayOf(0x000A100C,0xD60A100C.toInt()),null,Shader.TileMode.CLAMP)
        leftShade=LinearGradient(0f,0f,w*.85f,0f,intArrayOf(0xB30A100C.toInt(),0x0D0A100C),null,Shader.TileMode.CLAMP)
        // Cache an elliptical pool of light; focus changes only its opacity.
        if(w>0 && h>0)spotlightPaint.shader=RadialGradient(0f,0f,1f,
            intArrayOf(0x2EF4F1E9,0x0FF4F1E9,0x00F4F1E9),floatArrayOf(0f,.34f,.74f),Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w*.94f,h*1.4f); postTranslate(w*.23f,0f) })
        }
    }
    private fun line(c:Canvas,value:String,x:Float,y:Float,size:Float,max:Float,color:Int=ivory,bold:Boolean=false) {
        text.color=color; text.textSize=s(size)*resources.configuration.fontScale
        text.typeface=if(bold)mediumTypeface else regularTypeface
        c.drawText(TextUtils.ellipsize(value,text,max.coerceAtLeast(1f),TextUtils.TruncateAt.END).toString(),x,y,text)
    }
    private fun paragraph(c:Canvas,value:String,x:Float,y:Float,width:Float) {
        text.color=muted; text.textSize=s(22f)*resources.configuration.fontScale; text.typeface=regularTypeface
        var rest=value.trim()
        val dy=s(29f)*resources.configuration.fontScale
        for(i in 0..1) {
            if(rest.isEmpty())break
            if(i==1) { c.drawText(TextUtils.ellipsize(rest,text,width,TextUtils.TruncateAt.END).toString(),x,y+i*dy,text); break }
            val count=text.breakText(rest,true,width,null)
            var end=count
            if(count<rest.length)rest.lastIndexOf(' ',count).takeIf { it>0 }?.let { end=it }
            if(end==0)break
            c.drawText(rest.substring(0,end),x,y+i*dy,text); rest=rest.substring(end).trimStart()
        }
    }
    private fun titleLine(c:Canvas,x:Float,y:Float,size:Float,max:Float) {
        val offset=if(stremio)s(34f) else 0f
        if(stremio) {
            paint.color=0xFF8657D7.toInt(); c.drawCircle(x+s(12f),y-s(11f),s(12f),paint)
            line(c,"S",x+s(7f),y-s(5f),17f,s(20f),ivory,true)
        }
        line(c,title,x+offset,y,size,max-offset,bold=true)
    }
    override fun onDraw(c:Canvas) {
        val w=width.toFloat(); val h=height.toFloat(); val inset=s(if(mode==Mode.HERO)38f else 22f)
        val compact=mode==Mode.COMPACT; val image=art
        c.save(); c.clipPath(shape)
        if(!compact)c.drawColor(if(mode==Mode.APP)appGround else 0xFF1C241E.toInt())
        if(compact && isFocused)c.drawColor(0x0DFFFFFF)
        val artW=if(compact)w*.52f else w
        val artH=if(compact)h-s(12f) else h
        val top=if(compact)s(6f) else 0f
        val travel=s(6f); val drift=travel*focusAmount
        if(image!=null && !image.isRecycled) {
            c.save()
            if(compact)c.clipPath(compactShape)
            if(mode==Mode.APP) {
                val logo=logoBounds
                val ratio=if(logo!=null)minOf(w*.70f/logo.width(),h*.58f/logo.height()) else minOf((w-2*travel).coerceAtLeast(1f)/image.width,h/image.height)
                val cx=logo?.exactCenterX() ?: (image.width/2f); val cy=logo?.exactCenterY() ?: (image.height/2f)
                appMatrix.setScale(ratio,ratio); appMatrix.postTranslate(w/2-cx*ratio+drift,h/2-cy*ratio)
                appShader?.setLocalMatrix(appMatrix); imagePaint.shader=appShader
                c.drawRect(rect,imagePaint); imagePaint.shader=null
            } else if(mode==Mode.SPORT) {
                val ratio=minOf(w*.50f/image.width,h*.33f/image.height)
                val dw=image.width*ratio; val dh=image.height*ratio
                imageDestination.set((w-dw)/2+drift,s(51f),(w+dw)/2+drift,s(51f)+dh)
                c.drawBitmap(image,null,imageDestination,imagePaint)
            } else {
                val contain=image.width.toFloat()/image.height<1.3f
                // Reserve the full travel at rest so focus never exposes a landscape edge.
                val ratio=if(contain)minOf(artW/image.width,artH/image.height) else maxOf((artW+2*travel)/image.width,artH/image.height)
                val dw=image.width*ratio; val dh=image.height*ratio
                val imageTop=top+(artH-dh)*(if(mode==Mode.HERO && w/h>3f).12f else .5f)
                val imageDrift=if(contain)minOf(drift,((artW-dw)/2).coerceAtLeast(0f)) else drift
                imageDestination.set((artW-dw)/2+imageDrift,imageTop,(artW+dw)/2+imageDrift,imageTop+dh)
                c.drawBitmap(image,null,imageDestination,imagePaint)
            }
            c.restore()
        }
        // A very small lift separates dark app artwork from the page without whitening logos.
        if(mode==Mode.APP) { paint.color=0x06FFFFFF; c.drawRect(rect,paint) }
        if(focusAmount>0f) {
            spotlightPaint.alpha=(255*focusAmount).toInt()
            c.drawRect(rect,spotlightPaint)
        }
        if(mode==Mode.HERO) { paint.shader=leftShade; c.drawRect(rect,paint); paint.shader=null }
        if(mode==Mode.HERO || mode==Mode.SHOW) { paint.shader=shade; c.drawRect(rect,paint); paint.shader=null }
        when(mode) {
            Mode.HERO -> {
                titleLine(c,inset,h-s(183f),58f,w-2*inset)
                line(c,detail,inset,h-s(133f),25f,w-2*inset,muted)
                if(progress>0) {
                    paint.color=0xFF414840.toInt(); c.drawRoundRect(inset,h-s(115f),inset+s(370f),h-s(108f),s(3f),s(3f),paint)
                    paint.color=ivory; c.drawRoundRect(inset,h-s(115f),inset+s(370f)*progress,h-s(108f),s(3f),s(3f),paint)
                }
                text.textSize=s(26f)*resources.configuration.fontScale; text.typeface=mediumTypeface
                val buttonW=(text.measureText(action)+s(82f)).coerceAtMost(w-2*inset)
                actionBounds.set(inset,h-s(89f),inset+buttonW,h-s(25f))
                paint.color=if(isFocused)0x70101712 else 0x35101712; c.drawRoundRect(actionBounds,s(23f),s(23f),paint)
                paint.color=if(isFocused)ivory else 0xFF89958A.toInt(); paint.style=Paint.Style.STROKE; paint.strokeWidth=s(if(isFocused)2.5f else 1.3f)
                c.drawRoundRect(actionBounds,s(23f),s(23f),paint); paint.style=Paint.Style.FILL
                paint.color=ivory
                c.drawPath(actionPlay,paint)
                line(c,action,inset+s(60f),h-s(47f),26f,buttonW-s(76f),ivory,true)
            }
            Mode.COMPACT -> {
                val x=artW+s(25f); val space=w-x-s(6f)
                titleLine(c,x,s(40f),29f,space)
                line(c,detail,x,s(78f),23f,space,muted)
                paragraph(c,summary,x,s(122f),space)
            }
            Mode.SHOW -> {
                titleLine(c,inset,h-s(53f),29f,w-2*inset)
                line(c,detail,inset,h-s(21f),22f,w-2*inset,muted)
            }
            Mode.SPORT -> {
                line(c,summary,inset,s(33f),20f,w-2*inset,muted)
                if(image==null)line(c,action,inset,s(102f),36f,w-2*inset,ivory,true)
                titleLine(c,inset,h-s(53f),29f,w-2*inset)
                line(c,detail,inset,h-s(21f),22f,w-2*inset,muted)
            }
            Mode.APP -> if(image==null)line(c,title,inset,h/2+s(10f),27f,w-2*inset,bold=true)
        }
        if(progress>0 && mode==Mode.SHOW) {
            paint.color=0xFF435448.toInt(); c.drawRect(inset,h-s(9f),w-inset,h-s(5f),paint)
            paint.color=ivory; c.drawRect(inset,h-s(9f),inset+(w-inset*2)*progress,h-s(5f),paint)
        }
        c.restore()
        paint.style=Paint.Style.STROKE
        paint.color=if(isFocused && mode!=Mode.HERO)ivory else 0xFF4B584B.toInt()
        paint.strokeWidth=s(if(isFocused && mode!=Mode.HERO)2.4f else 1f)
        if(!compact || isFocused)c.drawRoundRect(rect,s(cornerRadius),s(cornerRadius),paint)
        paint.style=Paint.Style.FILL
    }
    override fun onFocusChanged(gain:Boolean,direction:Int,old:Rect?) {
        super.onFocusChanged(gain,direction,old); cancelFocusMotion()
        val target=focusScale(gain); val amount=if(gain)1f else 0f
        if(ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow && windowVisibility==VISIBLE && isShown) {
            animate().scaleX(target).scaleY(target).setDuration(140).setInterpolator(focusInterpolator).start()
            if(focusAmount!=amount) {
                val motion=ValueAnimator.ofFloat(focusAmount,amount).apply {
                    duration=180; interpolator=focusInterpolator
                    addUpdateListener { updateFocusAmount(it.animatedValue as Float) }
                    addListener(object:AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation:Animator) { if(focusMotion===animation)focusMotion=null }
                    })
                }
                focusMotion=motion; motion.start()
            }
        } else { scaleX=target; scaleY=target; updateFocusAmount(amount) }
        invalidate()
    }
    private fun focusScale(focused:Boolean)=if(focused && mode!=Mode.HERO && mode!=Mode.COMPACT)1.025f else 1f
    private fun updateFocusAmount(amount:Float) { if(focusAmount!=amount) { focusAmount=amount; invalidate() } }
    private fun cancelFocusMotion() { animate().cancel(); focusMotion?.cancel(); focusMotion=null }
    private fun settleFocus() {
        cancelFocusMotion()
        val target=focusScale(isFocused); scaleX=target; scaleY=target
        updateFocusAmount(if(isFocused)1f else 0f)
    }
    override fun onWindowVisibilityChanged(visibility:Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility!=VISIBLE)settleFocus()
    }
    override fun onVisibilityAggregated(isVisible:Boolean) {
        super.onVisibilityAggregated(isVisible)
        if(!isVisible)settleFocus()
    }
    override fun onDetachedFromWindow() { settleFocus(); super.onDetachedFromWindow() }
    private fun confirm(key:Int)=key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean { if(!confirm(key))return super.onKeyDown(key,event); if(event.repeatCount==0)event.startTracking(); return true }
    override fun onKeyLongPress(key:Int,event:KeyEvent):Boolean { if(!confirm(key))return super.onKeyLongPress(key,event); performLongClick(); return true }
    override fun onKeyUp(key:Int,event:KeyEvent):Boolean { if(!confirm(key))return super.onKeyUp(key,event); if(event.isTracking && !event.isCanceled)performClick(); return true }
}
