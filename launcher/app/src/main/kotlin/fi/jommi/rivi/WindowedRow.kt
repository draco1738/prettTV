package fi.jommi.rivi

import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil

/** Full scroll extent and stable item identities, with only the viewport and two neighbours alive. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic row, never inflated from XML.
internal class WindowedRow(
    context:Context, val keys:List<String>, private val cardWidth:Int, private val cardHeight:Int,
    private val spacing:Int, private val create:(Int)->View, private val release:(View)->Unit,
    private val changed:(WindowedRow)->Unit,
):ViewGroup(context) {
    private val mounted=sortedMapOf<Int,View>()
    private val step=cardWidth+spacing
    private var viewportWidth=cardWidth*5
    init { clipChildren=false; clipToPadding=false; isFocusable=false }

    fun indexOf(view:View?)=mounted.entries.firstOrNull { it.value===view }?.key ?: -1
    fun ensure(index:Int):View? {
        if(index !in keys.indices)return null
        mounted[index]?.let { return it }
        updateWindow(index*step,viewportWidth)
        return mounted[index]
    }
    fun ensure(key:String):View?=ensure(keys.indexOf(key))
    fun updateWindow(scrollX:Int,width:Int=viewportWidth) {
        viewportWidth=width.coerceAtLeast(cardWidth)
        val first=(scrollX.coerceAtLeast(0)/step-2).coerceAtLeast(0)
        val end=(ceil((scrollX+viewportWidth).toDouble()/step).toInt()+2).coerceAtMost(keys.size)
        val wanted=(first until end).toMutableSet()
        // Never detach focus before Android has transferred it to the next stable identity.
        mounted.entries.firstOrNull { it.value.hasFocus() }?.let { wanted.add(it.key) }
        val removed=mounted.keys.filter { it !in wanted }
        removed.forEach { index->mounted.remove(index)?.let { release(it);removeView(it) } }
        var added=false
        wanted.sorted().forEach { index->if(index !in mounted) {
            val view=create(index); mounted[index]=view
            addView(view, mounted.keys.indexOf(index),LayoutParams(cardWidth,cardHeight))
            measureCard(view); view.layout(index*step,0,index*step+cardWidth,cardHeight)
            added=true
        } }
        if(added || removed.isNotEmpty())changed(this)
    }
    private fun measureCard(view:View)=view.measure(MeasureSpec.makeMeasureSpec(cardWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cardHeight,MeasureSpec.EXACTLY))
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) {
        mounted.values.forEach(::measureCard)
        setMeasuredDimension((keys.size*step-spacing).coerceAtLeast(0),cardHeight)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int) {
        mounted.forEach { (index,view)->view.layout(index*step,0,index*step+cardWidth,cardHeight) }
    }
}
