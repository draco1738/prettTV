package fi.jommi.rivi

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Passive header information: updates never add a remote-focus stop or rebuild Home. */
class WeatherHeaderView @JvmOverloads constructor(
    context:Context,private val unit:Float=context.resources.displayMetrics.widthPixels/1920f
):LinearLayout(context) {
    private val ivory=0xFFF4F1E9.toInt()
    private fun px(value:Int)=(value*unit+.5f).toInt()
    private fun label(size:Int,color:Int)=TextView(context).apply {
        setTextColor(color);setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size)*resources.configuration.fontScale)
        typeface=Typeface.create("sans-serif",Typeface.NORMAL);includeFontPadding=false
        maxLines=1;ellipsize=TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val city=label(23,0xFFB2C0B5.toInt()).apply { maxWidth=px(240) }
    private val glyph=WeatherGlyph(context)
    private val temperature=label(26,ivory)
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;isFocusable=false
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(city,LayoutParams(-2,-1).apply { rightMargin=px(22) })
        addView(glyph,LayoutParams(px(30),px(30)).apply { rightMargin=px(12) })
        addView(temperature,LayoutParams(-2,-1))
        addView(View(context).apply { setBackgroundColor(0xFF4B584B.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LayoutParams(px(1).coerceAtLeast(1),px(24)).apply { leftMargin=px(24);rightMargin=px(4) })
    }
    fun bind(state:WeatherState,enabled:Boolean,units:String) {
        val location=state.location
        visibility=if(enabled && location!=null)View.VISIBLE else View.GONE
        if(location==null)return
        city.text=location.city
        val reading=state.reading
        temperature.text=when {
            reading!=null->WeatherPolicy.formatTemperature(reading.temperatureCelsius,units)+(if(state.stale)" · cached" else "")
            state.loading->"Updating…"
            else->"Unavailable"
        }
        glyph.visibility=if(reading==null || reading.condition==WeatherCondition.UNKNOWN)View.GONE else View.VISIBLE
        if(reading!=null)glyph.bind(reading.condition,reading.isNight)
        contentDescription=listOf(location.city,temperature.text.toString(),reading?.description.orEmpty()).filter(String::isNotBlank).joinToString(", ")
    }
}

/** Original small condition marks; no downloaded icon or continuous animation. */
private class WeatherGlyph(context:Context):View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { style=Paint.Style.STROKE;strokeWidth=1.8f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND }
    private val path=Path()
    private var condition=WeatherCondition.UNKNOWN
    private var night=false
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun bind(value:WeatherCondition,isNight:Boolean) { if(condition!=value || night!=isNight) { condition=value;night=isNight;invalidate() } }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas);canvas.save();canvas.scale(width/32f,height/32f)
        paint.color=0xFFF4F1E9.toInt();paint.style=Paint.Style.STROKE
        fun cloud() {
            path.reset();path.moveTo(7f,23f);path.lineTo(25f,23f)
            path.cubicTo(31f,23f,31f,13f,24f,13f)
            path.cubicTo(22f,5f,11f,5f,9f,14f)
            path.cubicTo(1f,13f,0f,23f,7f,23f);canvas.drawPath(path,paint)
        }
        fun moon() {
            path.reset();path.moveTo(22f,4f);path.cubicTo(7f,2f,2f,20f,15f,26f)
            path.cubicTo(20f,28f,27f,26f,29f,21f)
            path.cubicTo(16f,23f,12f,11f,22f,4f);canvas.drawPath(path,paint)
        }
        fun sun() {
            paint.color=0xFFD8BC72.toInt();canvas.drawCircle(16f,16f,6f,paint)
            repeat(8) { canvas.save();canvas.rotate(it*45f,16f,16f);canvas.drawLine(16f,3f,16f,6f,paint);canvas.restore() }
            paint.color=0xFFF4F1E9.toInt()
        }
        when(condition) {
            WeatherCondition.CLEAR->if(night)moon() else sun()
            WeatherCondition.PARTLY_CLOUDY->{canvas.save();canvas.translate(12f,-1f);canvas.scale(.65f,.65f);if(night)moon() else sun();canvas.restore();cloud()}
            WeatherCondition.CLOUDY->cloud()
            WeatherCondition.RAIN->{cloud();canvas.drawLine(10f,26f,8f,30f,paint);canvas.drawLine(18f,26f,16f,30f,paint);canvas.drawLine(26f,26f,24f,30f,paint)}
            WeatherCondition.SNOW->{cloud();for(x in listOf(9f,17f,25f)){canvas.drawLine(x-1.5f,28f,x+1.5f,28f,paint);canvas.drawLine(x,26.5f,x,29.5f,paint)}}
            WeatherCondition.STORM->{cloud();path.reset();path.moveTo(18f,18f);path.lineTo(13f,26f);path.lineTo(18f,26f);path.lineTo(14f,31f);paint.color=0xFFD8BC72.toInt();canvas.drawPath(path,paint)}
            WeatherCondition.FOG->{cloud();canvas.drawLine(5f,27f,27f,27f,paint);canvas.drawLine(9f,31f,23f,31f,paint)}
            WeatherCondition.WIND->{canvas.drawLine(3f,12f,23f,12f,paint);canvas.drawArc(20f,5f,28f,13f,180f,270f,false,paint);canvas.drawLine(5f,18f,28f,18f,paint);canvas.drawLine(3f,24f,20f,24f,paint)}
            WeatherCondition.UNKNOWN->Unit
        }
        canvas.restore()
    }
}
