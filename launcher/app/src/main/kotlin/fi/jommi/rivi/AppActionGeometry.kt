package fi.jommi.rivi

/** Pixel geometry shared by the contextual menu and confirmation; has no Android dependency. */
internal object AppActionGeometry {
    data class Box(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val width:Int get()=right-left
        val height:Int get()=bottom-top
    }

    fun place(screenWidth:Int,screenHeight:Int,anchor:Box?,width:Int,height:Int,inset:Int,gap:Int):Box {
        val sw=screenWidth.coerceAtLeast(1); val sh=screenHeight.coerceAtLeast(1)
        val ix=inset.coerceIn(0,(sw-1)/2); val iy=inset.coerceIn(0,(sh-1)/2)
        val w=width.coerceIn(1,sw-2*ix); val h=height.coerceIn(1,sh-2*iy)
        val minX=ix; val maxX=sw-ix-w; val minY=iy; val maxY=sh-iy-h
        if(anchor==null) return Box((sw-w)/2,(sh-h)/2,(sw+w)/2,(sh+h)/2)
        val spacing=gap.coerceAtLeast(0)
        val right=anchor.right+spacing
        val left=anchor.left-spacing-w
        val above=anchor.top-spacing-h
        val below=anchor.bottom+spacing
        // Keep the selected card unobscured whenever there is a free side.
        val x:Int; val y:Int
        when {
            right<=maxX -> { x=right.coerceAtLeast(minX); y=(anchor.bottom-h).coerceIn(minY,maxY) }
            left>=minX -> { x=left.coerceAtMost(maxX); y=(anchor.bottom-h).coerceIn(minY,maxY) }
            above>=minY -> { x=anchor.left.coerceIn(minX,maxX); y=above.coerceAtMost(maxY) }
            below<=maxY -> { x=anchor.left.coerceIn(minX,maxX); y=below.coerceAtLeast(minY) }
            else -> { x=anchor.left.coerceIn(minX,maxX); y=(anchor.bottom-h).coerceIn(minY,maxY) }
        }
        return Box(x,y,x+w,y+h)
    }
}
