package fi.jommi.rivi

/** Keep row selection deterministic even when scrolling and focus temporarily disagree. */
internal object RowNavigation {
    sealed interface Move {
        data class Focus(val index:Int):Move
        data object RevealStart:Move
        data object Sidebar:Move
        data object Stay:Move
    }

    fun move(index:Int,count:Int,right:Boolean,scrollX:Int,repeatCount:Int):Move {
        if(count<=0 || index !in 0 until count)return Move.Stay
        if(right)return if(index<count-1)Move.Focus(index+1) else Move.Stay
        if(index>0)return Move.Focus(index-1)
        // A framework scroll may have left focus on the offscreen first card.
        // Return to that card before treating another Left as a navigation request.
        if(scrollX>0)return Move.RevealStart
        return if(repeatCount==0)Move.Sidebar else Move.Stay
    }
}
