package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class ImmediateUndoTest {
    @Test fun availabilityChangesAfterAddUndoAndClearWithoutRecordingAnotherAction() {
        var calls=0
        val history=UndoHistory(onChanged={ calls++ })
        assertFalse(history.canUndo())
        history.add("Hide title") { }
        assertEquals(1,calls)
        assertEquals("Hide title",history.label())
        assertEquals("Hide title",history.undo())
        assertEquals(2,calls)
        assertFalse(history.canUndo())
        history.add("Arrange app") { }
        history.clear()
        assertEquals(4,calls)
        assertFalse(history.canUndo())
    }
    @Test fun failedUndoKeepsTheActionAndNotifiesTheUi() {
        var calls=0
        val history=UndoHistory(onChanged={ calls++ })
        history.add("Retry hide") { error("write unavailable") }
        try { history.undo(); fail("Expected failed undo") } catch(_:IllegalStateException) { }
        assertEquals("Retry hide",history.label())
        assertEquals(2,calls)
    }
    @Test fun observerCannotTurnSuccessfulPersistenceIntoAReportedFailure() {
        val history=UndoHistory(onChanged={ error("detached UI") })
        var restored=false
        history.add("Restore title") { restored=true }
        assertEquals("Restore title",history.undo())
        assertTrue(restored)
        assertFalse(history.canUndo())
    }
}
