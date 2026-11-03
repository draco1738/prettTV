package fi.jommi.rivi

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/** Two existing-style cards; the platform keeps ownership of inputs and Bluetooth devices. */
internal object InputsPanel {
    fun show(activity:Activity,onInputs:()->Unit,onAudio:()->Unit,onDismiss:()->Unit):Dialog {
        val scale=activity.resources.displayMetrics.widthPixels/1920f
        fun px(value:Int)=(value*scale+.5f).toInt().coerceAtLeast(1)
        val panel=Dialog(activity,R.style.Theme_PrettTvActionDialog)
        panel.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val column=LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL; setPadding(px(26),px(22),px(26),px(22))
            background=AppActionStyle.surface(scale)
        }
        fun label(value:String,size:Int,color:Int)=TextView(activity).apply {
            text=value; setTextColor(color); AppActionStyle.textSize(this,size,scale)
            typeface=Typeface.create("sans-serif",Typeface.NORMAL)
        }
        column.addView(label("Inputs",30,AppActionStyle.IVORY),LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=px(18) })
        val row=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; clipChildren=false; clipToPadding=false }
        val marks=listOf(mark(false),mark(true))
        var chosen:(()->Unit)?=null
        val cards=listOf("TV inputs" to onInputs,"Audio devices" to onAudio).mapIndexed { index,(name,action) ->
            PrettTvCard(activity,scale,PrettTvCard.Mode.LIBRARY).apply {
                title=name; art=marks[index]
                contentDescription=if(index==0)"TV inputs. Choose an HDMI or television source."
                    else "Audio devices. Turn Bluetooth on or off and manage headphones and speakers."
                setOnClickListener { chosen=action; panel.dismiss() }
                row.addView(this,LinearLayout.LayoutParams(px(320),px(154)).apply { if(index==0)rightMargin=px(18) })
            }
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=cards[(index-1).coerceAtLeast(0)].id
            card.nextFocusRightId=cards[(index+1).coerceAtMost(cards.lastIndex)].id
            card.nextFocusUpId=card.id; card.nextFocusDownId=card.id
        }
        column.addView(row)
        column.addView(label("Choose a source or control Bluetooth.  •  Back to close",20,AppActionStyle.MUTED),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=px(20) })
        panel.setContentView(column)
        panel.setCanceledOnTouchOutside(true)
        panel.setOnKeyListener { _,key,event -> key in AppActionStyle.CONFIRM_KEYS && event.repeatCount>0 }
        panel.setOnDismissListener {
            // A display list may still reference a glyph during dismissal; let Android reclaim it.
            cards.forEach { it.art=null }
            onDismiss()
            if(!activity.isFinishing && !activity.isDestroyed)chosen?.invoke()
        }
        panel.show()
        panel.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(px(710).coerceAtMost(activity.resources.displayMetrics.widthPixels-px(112)),-2)
            setGravity(Gravity.CENTER)
            attributes=attributes.apply { dimAmount=.30f }
            decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        cards.first().apply { isFocusableInTouchMode=true; requestFocus(); post { if(panel.isShowing)requestFocus() } }
        return panel
    }

    private fun mark(audio:Boolean):Bitmap=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas=Canvas(bitmap)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=AppActionStyle.IVORY; strokeWidth=5f; style=Paint.Style.STROKE; strokeJoin=Paint.Join.ROUND; strokeCap=Paint.Cap.ROUND }
        if(audio) {
            canvas.drawArc(20f,16f,76f,76f,180f,180f,false,paint)
            canvas.drawRoundRect(17f,44f,30f,77f,5f,5f,paint)
            canvas.drawRoundRect(66f,44f,79f,77f,5f,5f,paint)
        } else {
            val plug=Path().apply { moveTo(17f,27f);lineTo(79f,27f);lineTo(79f,55f);lineTo(67f,68f);lineTo(29f,68f);lineTo(17f,55f);close() }
            canvas.drawPath(plug,paint); canvas.drawLine(48f,68f,48f,83f,paint)
            for(x in listOf(34f,48f,62f))canvas.drawLine(x,38f,x,49f,paint)
        }
    }
}
