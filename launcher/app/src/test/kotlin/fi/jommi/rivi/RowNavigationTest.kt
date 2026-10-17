package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class RowNavigationTest {
    @Test fun leftFromScrolledFirstCardRevealsStartBeforeOpeningSidebar() {
        assertEquals(RowNavigation.Move.RevealStart,RowNavigation.move(0,30,false,780,0))
        assertEquals(RowNavigation.Move.RevealStart,RowNavigation.move(0,30,false,1,4))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,30,false,0,5))
        assertEquals(RowNavigation.Move.Sidebar,RowNavigation.move(0,30,false,0,0))
    }

    @Test fun leftAlwaysSelectsThePreviousCardBeforeConsideringTheSidebar() {
        for(index in 1 until 30)for(scrollX in listOf(0,1,780,6000)) {
            assertEquals(RowNavigation.Move.Focus(index-1),RowNavigation.move(index,30,false,scrollX,0))
            assertEquals(RowNavigation.Move.Focus(index-1),RowNavigation.move(index,30,false,scrollX,7))
        }
    }

    @Test fun rightMovesOneCardAtATimeAndStopsAtTheEnd() {
        for(index in 0 until 29) {
            assertEquals(RowNavigation.Move.Focus(index+1),RowNavigation.move(index,30,true,0,0))
            assertEquals(RowNavigation.Move.Focus(index+1),RowNavigation.move(index,30,true,6000,8))
        }
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(29,30,true,6000,0))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(29,30,true,6000,12))
    }

    @Test fun heldLeftReturnsToTheFirstCardWithoutSpillingIntoTheSidebar() {
        var index=5
        val visited=ArrayList<Int>()
        for(repeatCount in 0..8) {
            when(val move=RowNavigation.move(index,30,false,0,repeatCount)) {
                is RowNavigation.Move.Focus -> { index=move.index;visited.add(index) }
                RowNavigation.Move.Stay -> assertEquals(0,index)
                else -> fail("Holding Left must stay in the row: $move")
            }
        }
        assertEquals(listOf(4,3,2,1,0),visited)
        assertEquals(RowNavigation.Move.Sidebar,RowNavigation.move(index,30,false,0,0))
    }

    @Test fun sidebarRequiresAFreshLeftPressAtTheTrueStart() {
        assertEquals(RowNavigation.Move.Sidebar,RowNavigation.move(0,6,false,0,0))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,6,false,0,1))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,6,false,0,100))
    }

    @Test fun invalidOrEmptyRowsNeverMoveOrRevealTheSidebar() {
        val invalid=listOf(-1 to 3,3 to 3,5 to 3,0 to 0,0 to -1,-1 to 0)
        for((index,count) in invalid)for(right in listOf(false,true)) {
            assertEquals(RowNavigation.Move.Stay,RowNavigation.move(index,count,right,0,0))
            assertEquals(RowNavigation.Move.Stay,RowNavigation.move(index,count,right,780,3))
        }
    }

    @Test fun singleCardRowHasABoundedRightEdgeAndAnIntentionalSidebarEntry() {
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,1,true,0,0))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,1,true,0,10))
        assertEquals(RowNavigation.Move.RevealStart,RowNavigation.move(0,1,false,10,0))
        assertEquals(RowNavigation.Move.Stay,RowNavigation.move(0,1,false,0,10))
        assertEquals(RowNavigation.Move.Sidebar,RowNavigation.move(0,1,false,0,0))
    }
}
