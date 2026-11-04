package fi.jommi.rivi

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator

/** Native still-art card. Remote/focus pattern adapted from Rivi by Jommi (MIT). */
class PrettTvCard(context:Context,private val unit:Float,private val mode:Mode,private val cornerRadius:Float=15f):View(context) {
    private companion object {
        val regularTypeface:Typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        val mediumTypeface:Typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
    }
    enum class Mode { HERO, SHOW, COMPACT, APP, LIBRARY, SPORT }
    /** Previewable alternative: one compositor scale, without repainting moving artwork. */
    var quietMotion=context.getSharedPreferences("pretttv",Context.MODE_PRIVATE).getBoolean("quietMotion",false)
    val isLibraryCard get()=mode==Mode.LIBRARY
    var title=""
    var detail=""
    var sourceLabel=""
    var secondaryDetail=""
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
            appGround=if(value!=null && mode==Mode.APP)value.getPixel(minOf(8,value.width-1),value.height/2) else PrettTvColors.SURFACE
            appShader=if(value!=null && mode==Mode.APP)BitmapShader(value,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP) else null
            invalidate()
        }
    private var appGround=PrettTvColors.SURFACE
    private val imagePaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val spotlightPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG)
    private class TextLineCache {
        var value=""; var size=0f; var width=0f; var bold=false; var rendered=""
    }
    private val textLines=Array(16) { TextLineCache() }
    private var textLineIndex=0
    private var paragraphValue:String?=null
    private var paragraphWidth=0f
    private var paragraphSize=0f
    private var paragraphLines:List<String> = emptyList()
    private val shape=Path()
    private val compactShape=Path()
    private val actionPlay=Path()
    private val rect=RectF()
    private val imageDestination=RectF()
    private val actionBounds=RectF()
    private val feedbackBounds=RectF()
    private val metrics=Paint.FontMetrics()
    private var shade:LinearGradient?=null
    private var leftShade:LinearGradient?=null
    private var focusAmount=0f
    private var focusMotion:ValueAnimator?=null
    private val focusInterpolator=DecelerateInterpolator()
    private val ivory=PrettTvColors.TEXT
    private val muted=PrettTvColors.SECONDARY
    private var confirmHeld=false
    private var longPressHandled=false
    private var holdStarted=0L
    private var holdAmount=0f
    private var boundaryVisible=false
    private val hideBoundary=Runnable { boundaryVisible=false; invalidate() }
    private val holdTick=object:Runnable {
        override fun run() {
            if(!confirmHeld || !isFocused || !isShown || !hasWindowFocus()) { cancelHold(); return }
            val elapsed=SystemClock.uptimeMillis()-holdStarted
            if(elapsed>=ViewConfiguration.getLongPressTimeout()) { cancelHold(); return }
            holdAmount=if(ValueAnimator.areAnimatorsEnabled())
                ((elapsed-180).toFloat()/(ViewConfiguration.getLongPressTimeout()-180).coerceAtLeast(1)).coerceIn(0f,1f) else 1f
            invalidate()
            if(ValueAnimator.areAnimatorsEnabled())postOnAnimation(this)
            else postDelayed(this,(ViewConfiguration.getLongPressTimeout()-elapsed).coerceAtLeast(1))
        }
    }
    private fun s(n:Float)=n*unit
    init { isFocusable=true; isClickable=true; id=generateViewId() }
    override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int) {
        rect.set(s(3f),s(3f),w-s(3f),h-s(3f)); shape.reset(); shape.addRoundRect(rect,s(cornerRadius),s(cornerRadius),Path.Direction.CW)
        if(mode==Mode.COMPACT) {
            compactShape.reset()
            imageDestination.set(s(3f),s(6f),w*.52f,h-s(6f))
            compactShape.addRoundRect(imageDestination,s(10f),s(10f),Path.Direction.CW)
        }
        shade=if(mode==Mode.SHOW)LinearGradient(0f,0f,0f,h.toFloat(),
            intArrayOf(0x00000000,0x00000000,0xE6000000.toInt(),0xF2000000.toInt()),
            floatArrayOf(0f,.32f,.58f,1f),Shader.TileMode.CLAMP)
        else LinearGradient(0f,h*.35f,0f,h.toFloat(),intArrayOf(0x00000000,0xD6000000.toInt()),null,Shader.TileMode.CLAMP)
        leftShade=LinearGradient(0f,0f,w*.85f,0f,intArrayOf(0xB3000000.toInt(),0x0D000000),null,Shader.TileMode.CLAMP)
        // Cache an elliptical pool of light; focus changes only its opacity.
        if(w>0 && h>0)spotlightPaint.shader=RadialGradient(0f,0f,1f,
            intArrayOf(0x2EF4F4F4,0x0FF4F4F4,0x00F4F4F4),floatArrayOf(0f,.34f,.74f),Shader.TileMode.CLAMP).apply {
            setLocalMatrix(Matrix().apply { setScale(w*.94f,h*1.4f); postTranslate(w*.23f,0f) })
        }
    }
    private fun line(c:Canvas,value:String,x:Float,y:Float,size:Float,max:Float,color:Int=ivory,bold:Boolean=false,alpha:Float=1f) {
        if(value.isEmpty() || alpha<=0f)return
        text.color=color; text.alpha=(Color.alpha(color)*alpha).toInt(); text.textSize=s(size)*resources.configuration.fontScale
        text.typeface=if(bold)mediumTypeface else regularTypeface
        val bounded=max.coerceAtLeast(1f)
        val cached=textLines[textLineIndex++ % textLines.size]
        if(cached.value!=value || cached.size!=text.textSize || cached.width!=bounded || cached.bold!=bold) {
            cached.value=value; cached.size=text.textSize; cached.width=bounded; cached.bold=bold
            cached.rendered=TextUtils.ellipsize(value,text,bounded,TextUtils.TruncateAt.END).toString()
        }
        c.drawText(cached.rendered,x,y,text)
    }
    private fun paragraph(c:Canvas,value:String,x:Float,y:Float,width:Float) {
        text.color=muted; text.textSize=s(22f)*resources.configuration.fontScale; text.typeface=regularTypeface
        if(paragraphValue!=value || paragraphWidth!=width || paragraphSize!=text.textSize) {
            paragraphValue=value; paragraphWidth=width; paragraphSize=text.textSize
            val lines=ArrayList<String>(2)
            var rest=value.trim()
            for(i in 0..1) {
                if(rest.isEmpty())break
                if(i==1) { lines.add(TextUtils.ellipsize(rest,text,width,TextUtils.TruncateAt.END).toString()); break }
                val count=text.breakText(rest,true,width,null)
                var end=count
                if(count<rest.length)rest.lastIndexOf(' ',count).takeIf { it>0 }?.let { end=it }
                if(end==0)break
                lines.add(rest.substring(0,end)); rest=rest.substring(end).trimStart()
            }
            paragraphLines=lines
        }
        val dy=s(29f)*resources.configuration.fontScale
        paragraphLines.forEachIndexed { i,line -> c.drawText(line,x,y+i*dy,text) }
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
        textLineIndex=0
        val w=width.toFloat(); val h=height.toFloat(); val inset=s(if(mode==Mode.HERO)38f else 22f)
        val compact=mode==Mode.COMPACT; val image=art
        c.save(); c.clipPath(shape)
        if(!compact)c.drawColor(when(mode) { Mode.APP->appGround; Mode.LIBRARY->PrettTvColors.SURFACE; else->PrettTvColors.SURFACE })
        val artW=if(compact)w*.52f else w
        val artH=if(compact)h-s(12f) else h
        val top=if(compact)s(6f) else 0f
        val travel=s(6f); val drift=if(quietMotion)0f else travel*focusAmount
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
            } else if(mode==Mode.LIBRARY) {
                val ratio=minOf(w*.70f/image.width,s(64f)/image.height)
                val dw=image.width*ratio; val dh=image.height*ratio
                imageDestination.set((w-dw)/2+drift,s(55f)-dh/2,(w+dw)/2+drift,s(55f)+dh/2)
                c.drawBitmap(image,null,imageDestination,imagePaint)
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
        if(!quietMotion && focusAmount>0f && mode!=Mode.APP && mode!=Mode.LIBRARY) {
            // App tiles keep a true-black face; their outline carries remote focus.
            spotlightPaint.alpha=(255*focusAmount).toInt()
            c.drawRect(rect,spotlightPaint)
        }
        if(mode==Mode.HERO) { paint.shader=leftShade; c.drawRect(rect,paint); paint.shader=null }
        if(mode==Mode.HERO || mode==Mode.SHOW) { paint.shader=shade; c.drawRect(rect,paint); paint.shader=null }
        when(mode) {
            Mode.HERO -> {
                val font=resources.configuration.fontScale
                val buttonH=s(24f+22f*font)
                val buttonTop=h-s(30f)-buttonH
                // Shrink only the action; keep the existing title/progress block anchored.
                val progressY=h-s(98f+32f*font)
                val detailY=progressY-s(39f)*font
                val titleY=detailY-s(50f)*font
                titleLine(c,inset,titleY,58f,w-2*inset)
                line(c,detail,inset,detailY,25f,w-2*inset,ivory)
                if(validProgress()) {
                    val trackW=minOf(s(220f),w*.30f)
                    paint.color=PrettTvColors.EDGE; c.drawRoundRect(inset,progressY-s(2f),inset+trackW,progressY+s(2f),s(2f),s(2f),paint)
                    paint.color=ivory; c.drawRoundRect(inset,progressY-s(2f),inset+trackW*progress,progressY+s(2f),s(2f),s(2f),paint)
                    line(c,secondaryDetail,inset+trackW+s(18f),progressY+s(7f),22f,w-2*inset-trackW-s(18f),muted)
                } else line(c,secondaryDetail,inset,progressY+s(7f),22f,w-2*inset,muted)
                text.textSize=s(22f)*resources.configuration.fontScale; text.typeface=mediumTypeface
                val buttonW=(text.measureText(action)+s(56f)).coerceAtMost((w-2*inset)*.72f)
                actionBounds.set(inset,buttonTop,inset+buttonW,h-s(30f))
                paint.color=if(isFocused)PrettTvColors.SELECTED else PrettTvColors.SURFACE
                c.drawRoundRect(actionBounds,s(12f),s(12f),paint)
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=s(if(isFocused)1.5f else 1f)
                paint.color=if(isFocused)PrettTvColors.FOCUS else PrettTvColors.STRONG_EDGE
                if(isFocused)c.drawRoundRect(actionBounds,s(12f),s(12f),paint)
                paint.style=Paint.Style.FILL
                val cy=actionBounds.centerY()
                actionPlay.reset(); actionPlay.moveTo(inset+s(17f),cy-s(6f)); actionPlay.lineTo(inset+s(17f),cy+s(6f)); actionPlay.lineTo(inset+s(26f),cy); actionPlay.close()
                paint.color=ivory
                c.drawPath(actionPlay,paint)
                text.getFontMetrics(metrics)
                val actionY=cy-(metrics.ascent+metrics.descent)/2
                line(c,action,inset+s(37f),actionY,22f,buttonW-s(51f),ivory,true)
                line(c,sourceLabel,actionBounds.right+s(20f),actionY,23f,w-inset-actionBounds.right-s(20f),muted)
            }
            Mode.COMPACT -> {
                val x=artW+s(25f); val space=w-x-s(6f)
                titleLine(c,x,s(40f),29f,space)
                line(c,sourceLabel.ifBlank { detail },x,s(78f),23f,space,muted)
                paragraph(c,summary,x,s(122f),space)
            }
            Mode.SHOW -> {
                val font=resources.configuration.fontScale
                val hasSecondary=secondaryDetail.isNotBlank()
                val secondaryY=h-s(16f+4f*(font-1f).coerceAtLeast(0f))
                val sourceY=secondaryY-if(hasSecondary)s(20f*font+12f) else 0f
                titleLine(c,inset,sourceY-s(22f*font+12f),29f,w-2*inset)
                line(c,sourceLabel.ifBlank { detail },inset,sourceY,22f,w-2*inset,muted)
                if(hasSecondary)line(c,secondaryDetail,inset,secondaryY,20f,w-2*inset,muted,alpha=focusAmount)
            }
            Mode.SPORT -> {
                line(c,summary,inset,s(33f),20f,w-2*inset,muted)
                if(image==null)line(c,action,inset,s(102f),36f,w-2*inset,ivory,true)
                titleLine(c,inset,h-s(53f),29f,w-2*inset)
                line(c,detail,inset,h-s(21f),22f,w-2*inset,muted)
            }
            Mode.APP -> if(image==null)line(c,title,inset,h/2+s(10f),27f,w-2*inset,bold=true)
            Mode.LIBRARY -> {
                if(image==null) {
                    text.textAlign=Paint.Align.CENTER
                    line(c,title.trim().take(1).uppercase(),w/2,s(74f),46f,w-s(40f),ivory,true)
                    text.textAlign=Paint.Align.LEFT
                }
                text.textAlign=Paint.Align.CENTER
                line(c,title,w/2,h-s(24f),24f,w-s(32f),ivory)
                text.textAlign=Paint.Align.LEFT
            }
        }
        if(validProgress() && mode==Mode.SHOW) {
            paint.color=PrettTvColors.EDGE; c.drawRect(inset,h-s(9f),w-inset,h-s(5f),paint)
            paint.color=ivory; c.drawRect(inset,h-s(9f),inset+(w-inset*2)*progress,h-s(5f),paint)
        }
        if(holdAmount>0f) {
            val x=(w-s(80f))/2; val y=h-s(15f)
            paint.color=PrettTvColors.STRONG_EDGE; c.drawRoundRect(x,y,x+s(80f),y+s(5f),s(2.5f),s(2.5f),paint)
            paint.color=ivory; c.drawRoundRect(x,y,x+s(80f)*holdAmount,y+s(5f),s(2.5f),s(2.5f),paint)
        }
        if(boundaryVisible) {
            val badgeW=s(60f)*resources.configuration.fontScale
            feedbackBounds.set(w-badgeW-s(12f),s(9f),w-s(12f),s(40f)*resources.configuration.fontScale)
            paint.color=0xF00A0A0A.toInt(); c.drawRoundRect(feedbackBounds,s(9f),s(9f),paint)
            line(c,"End",feedbackBounds.left+s(10f),feedbackBounds.bottom-s(8f),18f,badgeW-s(20f),ivory,true)
        }
        c.restore()
        paint.style=Paint.Style.STROKE
        paint.color=if(isFocused && mode!=Mode.HERO)ivory else if(mode==Mode.LIBRARY)PrettTvColors.STRONG_EDGE else PrettTvColors.EDGE
        paint.strokeWidth=s(if(isFocused && mode!=Mode.HERO)2.4f else 1f)
        if(!compact || isFocused)c.drawRoundRect(rect,s(cornerRadius),s(cornerRadius),paint)
        paint.style=Paint.Style.FILL
    }
    override fun onFocusChanged(gain:Boolean,direction:Int,old:Rect?) {
        super.onFocusChanged(gain,direction,old); cancelFocusMotion()
        if(!gain)clearFeedback()
        val target=focusScale(gain); val amount=if(gain)1f else 0f
        if(ValueAnimator.areAnimatorsEnabled() && isAttachedToWindow && windowVisibility==VISIBLE && isShown) {
            animate().scaleX(target).scaleY(target).setDuration(if(quietMotion)120 else 140).setInterpolator(focusInterpolator).start()
            if(quietMotion)updateFocusAmount(amount)
            else if(focusAmount!=amount) {
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
    private fun focusScale(focused:Boolean)=if(focused && mode!=Mode.HERO && mode!=Mode.COMPACT) { if(quietMotion)1.015f else 1.025f } else 1f
    private fun updateFocusAmount(amount:Float) { if(focusAmount!=amount) { focusAmount=amount; invalidate() } }
    private fun cancelFocusMotion() { animate().cancel(); focusMotion?.cancel(); focusMotion=null }
    private fun settleFocus() {
        cancelFocusMotion()
        val target=focusScale(isFocused); scaleX=target; scaleY=target
        updateFocusAmount(if(isFocused)1f else 0f)
    }
    override fun onWindowVisibilityChanged(visibility:Int) {
        super.onWindowVisibilityChanged(visibility)
        if(visibility!=VISIBLE) { clearFeedback(); settleFocus() }
    }
    override fun onVisibilityAggregated(isVisible:Boolean) {
        super.onVisibilityAggregated(isVisible)
        if(!isVisible) { clearFeedback(); settleFocus() }
    }
    override fun onWindowFocusChanged(hasWindowFocus:Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if(!hasWindowFocus)clearFeedback()
    }
    override fun onDetachedFromWindow() { clearFeedback(); settleFocus(); super.onDetachedFromWindow() }
    private fun validProgress()=progress.isFinite() && progress>0f && progress<=1f
    /** A bounded, static cue. No shake, layout movement, sound or idle animation. */
    fun showBoundaryFeedback() {
        if(!isFocused || !isAttachedToWindow || !isShown)return
        removeCallbacks(hideBoundary); boundaryVisible=true; invalidate(); postDelayed(hideBoundary,650)
    }
    private fun cancelHold() {
        confirmHeld=false; removeCallbacks(holdTick)
        if(holdAmount!=0f) { holdAmount=0f; invalidate() }
    }
    private fun clearFeedback() {
        cancelHold(); longPressHandled=true; removeCallbacks(hideBoundary)
        if(boundaryVisible) { boundaryVisible=false; invalidate() }
    }
    private fun confirm(key:Int)=key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_NUMPAD_ENTER
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean {
        if(!confirm(key)) { cancelHold(); longPressHandled=true; return super.onKeyDown(key,event) }
        if(event.repeatCount==0) {
            cancelHold(); longPressHandled=false; event.startTracking()
            if((mode==Mode.APP || mode==Mode.LIBRARY) && isLongClickable && isFocused) {
                confirmHeld=true; holdStarted=SystemClock.uptimeMillis(); postDelayed(holdTick,180)
            }
        }
        return true
    }
    override fun onKeyLongPress(key:Int,event:KeyEvent):Boolean {
        if(!confirm(key))return super.onKeyLongPress(key,event)
        longPressHandled=true; cancelHold(); if(isFocused)performLongClick(); return true
    }
    override fun onKeyUp(key:Int,event:KeyEvent):Boolean {
        if(!confirm(key))return super.onKeyUp(key,event)
        cancelHold()
        if(event.isTracking && !event.isCanceled && !longPressHandled)performClick()
        longPressHandled=false
        return true
    }
}
