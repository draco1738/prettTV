package fi.jommi.rivi

/** Counts complete cards in a finite row; the clipped next card is not counted twice. */
internal object AppRowRange {
    fun label(count:Int,offset:Int,viewport:Int,card:Int,gap:Int):String {
        if(count<=0 || viewport<=0 || card<=0 || gap<0)return ""
        val step=card.toLong()+gap
        val left=offset.coerceAtLeast(0).toLong()
        val first=((left+step-1)/step).coerceIn(0,(count-1).toLong())
        val end=((left+viewport+gap)/step).coerceIn(first+1,count.toLong())
        return "${first+1}–$end of $count"
    }
}
