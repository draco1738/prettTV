package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class AppRowRangeTest {
    @Test fun reportsOnlyCompleteCardsAtTheStart() { assertEquals("1–8 of 14",AppRowRange.label(14,0,1792,208,12)) }
    @Test fun rangeMovesWithTheViewportInsteadOfFocusAlone() { assertEquals("3–10 of 14",AppRowRange.label(14,440,1792,208,12)) }
    @Test fun reportsTheEndAndExcludesAPartiallyClippedFirstCard() { assertEquals("7–14 of 14",AppRowRange.label(14,14*220-12-1792,1792,208,12)) }
    @Test fun shortRowsDoNotInventHiddenApps() { assertEquals("1–3 of 3",AppRowRange.label(3,0,1792,208,12)) }
    @Test fun invalidOrUnmeasuredRowsHaveNoCounter() {
        assertEquals("",AppRowRange.label(0,0,1792,208,12))
        assertEquals("",AppRowRange.label(14,0,0,208,12))
        assertEquals("",AppRowRange.label(14,0,1792,0,12))
    }
    @Test fun undersizedOrOverscrolledViewportStillHasABoundedRange() {
        assertEquals("1–1 of 3",AppRowRange.label(3,-10,100,208,12))
        assertEquals("3–3 of 3",AppRowRange.label(3,Int.MAX_VALUE,1792,208,12))
    }
}
