package fi.jommi.rivi

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native, finite, focus-trapped app actions. Opening this panel never rebuilds Home. */
object AppActionPanel {
    fun show(activity:Activity,anchor:View,label:String,items:List<Pair<String,()->Unit>>,onDismiss:()->Unit):Dialog {
        require(items.isNotEmpty())
        val metrics=activity.resources.displayMetrics
        val scale=metrics.widthPixels/1920f
        fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
        val dialog=Dialog(activity,R.style.Theme_PrettTvActionDialog)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(px(22),px(20),px(22),px(20))
        }
        column.addView(TextView(activity).apply {
            text=label; setTextColor(AppActionStyle.IVORY)
            AppActionStyle.textSize(this,30,scale)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(px(8),0,px(8),px(14))
        },LinearLayout.LayoutParams(-1,-2))
        var chosen:(()->Unit)?=null
        val buttons=items.mapIndexed { index,(title,action) ->
            if(title=="Force stop" && index>0) column.addView(View(activity).apply {
                setBackgroundColor(0xFF4E5C47.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(-1,px(1)).apply { topMargin=px(10);bottomMargin=px(10) })
            Button(activity).apply {
                id=View.generateViewId(); text=title
                AppActionStyle.button(this,scale)
                setCompoundDrawablesWithIntrinsicBounds(ActionGlyph(title,px(24)),null,null,null)
                compoundDrawablePadding=px(16)
                setOnClickListener { chosen=action; dialog.dismiss() }
                column.addView(this,LinearLayout.LayoutParams(-1,-2))
            }
        }
        buttons.forEachIndexed { index,button ->
            button.nextFocusLeftId=button.id; button.nextFocusRightId=button.id
            button.nextFocusUpId=buttons[(index-1).coerceAtLeast(0)].id
            button.nextFocusDownId=buttons[(index+1).coerceAtMost(buttons.lastIndex)].id
        }
        column.addView(TextView(activity).apply {
            setText(R.string.app_actions_return_hint); setTextColor(AppActionStyle.MUTED)
            AppActionStyle.textSize(this,19,scale); setPadding(px(8),px(18),px(8),0)
        },LinearLayout.LayoutParams(-1,-2))
        val scroller=ScrollView(activity).apply {
            isFillViewport=false; isVerticalScrollBarEnabled=true
            addView(column); background=AppActionStyle.surface(scale)
        }
        dialog.setContentView(scroller)
        dialog.setCanceledOnTouchOutside(true)
        // A held OK that opened the panel must not select Open on a repeat.
        dialog.setOnKeyListener { _,key,event ->
            key in AppActionStyle.CONFIRM_KEYS && event.repeatCount>0
        }
        dialog.setOnDismissListener {
            onDismiss()
            if(!activity.isFinishing && !activity.isDestroyed) chosen?.invoke()
        }
        dialog.show()
        val anchorRect=Rect()
        val box=if(anchor.getGlobalVisibleRect(anchorRect)) AppActionGeometry.Box(anchorRect.left,anchorRect.top,anchorRect.right,anchorRect.bottom) else null
        val availableWidth=(metrics.widthPixels-px(112)).coerceAtLeast(1)
        val width=px(400).coerceAtMost(availableWidth)
        column.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        AppActionStyle.position(dialog.window,AppActionGeometry.place(metrics.widthPixels,metrics.heightPixels,box,width,column.measuredHeight,px(56),px(20)))
        buttons.first().apply { isFocusableInTouchMode=true; requestFocus(); post { if(dialog.isShowing)requestFocus() } }
        return dialog
    }

    /** Small monochrome marks; no emoji fonts or new image resources. */
    private class ActionGlyph(private val label:String,private val size:Int):Drawable() {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth=1.8f; style=Paint.Style.STROKE }
        override fun getIntrinsicWidth()=size
        override fun getIntrinsicHeight()=size
        override fun isStateful()=true
        override fun onStateChange(stateSet:IntArray):Boolean { invalidateSelf(); return true }
        override fun draw(canvas:Canvas) {
            val selected=state.any { it==android.R.attr.state_focused || it==android.R.attr.state_pressed }
            paint.color=if(selected)AppActionStyle.INK else AppActionStyle.IVORY
            val save=canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat()); canvas.scale(bounds.width()/24f,bounds.height()/24f)
            when {
                label=="Open" -> { paint.style=Paint.Style.FILL; val path=android.graphics.Path().apply { moveTo(6f,3f);lineTo(20f,12f);lineTo(6f,21f);close() }; canvas.drawPath(path,paint) }
                label=="Force stop" -> { paint.style=Paint.Style.STROKE; canvas.drawRoundRect(5f,5f,19f,19f,1.5f,1.5f,paint) }
                label.contains("favorite",true) -> { paint.style=Paint.Style.STROKE; val path=android.graphics.Path(); for(i in 0..9) { val a=Math.PI*i/5-Math.PI/2;val r=if(i%2==0)10.0 else 4.6;val x=(12+kotlin.math.cos(a)*r).toFloat();val y=(12+kotlin.math.sin(a)*r).toFloat();if(i==0)path.moveTo(x,y) else path.lineTo(x,y) };path.close();canvas.drawPath(path,paint) }
                label.startsWith("Move") -> { paint.style=Paint.Style.STROKE; val right=label.endsWith("right");val a=if(right)8f else 16f;val b=24-a;canvas.drawLine(a,4f,b,12f,paint);canvas.drawLine(b,12f,a,20f,paint) }
                label.startsWith("Hide") || label.startsWith("Show") -> { paint.style=Paint.Style.STROKE; canvas.drawOval(2f,6f,22f,18f,paint);canvas.drawCircle(12f,12f,3f,paint);if(label.startsWith("Hide"))canvas.drawLine(3f,2f,21f,22f,paint) }
                else -> { paint.style=Paint.Style.STROKE; canvas.drawCircle(12f,12f,9f,paint);canvas.drawLine(12f,11f,12f,17f,paint);paint.style=Paint.Style.FILL;canvas.drawCircle(12f,7f,1.1f,paint) }
            }
            canvas.restoreToCount(save)
        }
        override fun setAlpha(alpha:Int) { paint.alpha=alpha }
        override fun setColorFilter(colorFilter:ColorFilter?) { paint.colorFilter=colorFilter }
        @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
}

/** Shared styling for actions, confirmation, progress and result; solid surfaces avoid live blur. */
internal object AppActionStyle {
    const val IVORY=0xFFF4F1E9.toInt()
    const val MUTED=0xFFC0C8B6.toInt()
    const val INK=0xFF111A13.toInt()
    private const val SURFACE=0xFF202C21.toInt()
    private const val SELECTED=0xFFCFD9C5.toInt()
    val CONFIRM_KEYS=setOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_NUMPAD_ENTER)
    fun textSize(view:TextView,size:Int,scale:Float) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,size*scale*view.resources.configuration.fontScale)
    }
    fun surface(scale:Float)=GradientDrawable().apply {
        setColor(SURFACE); cornerRadius=20*scale; setStroke((scale+.5f).toInt().coerceAtLeast(1),0xFF73816A.toInt())
    }
    fun button(button:Button,scale:Float) {
        fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
        button.apply {
            isAllCaps=false; gravity=Gravity.CENTER_VERTICAL or Gravity.START
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
            textSize(this,23,scale); minimumWidth=0; minWidth=0
            minimumHeight=px(58); minHeight=px(58)
            setPadding(px(16),px(12),px(16),px(12))
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),intArrayOf(INK,INK,IVORY)))
            background=StateListDrawable().apply {
                fun fill(color:Int)=GradientDrawable().apply { setColor(color);cornerRadius=10*scale }
                addState(intArrayOf(android.R.attr.state_focused),fill(SELECTED))
                addState(intArrayOf(android.R.attr.state_pressed),fill(SELECTED))
                addState(intArrayOf(),fill(0x00000000))
            }
            stateListAnimator=null
        }
    }
    // AppActionGeometry returns physical screen pixels from getGlobalVisibleRect.
    // START would mirror x in RTL locales and move the panel away from its anchor.
    @SuppressLint("RtlHardcoded")
    fun position(window:Window?,box:AppActionGeometry.Box) {
        window ?: return
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes=window.attributes.apply { gravity=Gravity.TOP or Gravity.LEFT; x=box.left;y=box.top;width=box.width;height=box.height;dimAmount=.30f }
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}
