package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AppRefreshWorkTest {
    @Test fun oneBrokenIconDoesNotAbortOtherApps() {
        val delivered=ArrayList<Int>(); val failed=ArrayList<Int>()
        AppRefreshWork.each(listOf(1,2,3),{true},{ if(it==2)throw IOException("broken resource");it },
            {_,value -> delivered.add(value)}, {item,_ -> failed.add(item)})
        assertEquals(listOf(1,3),delivered)
        assertEquals(listOf(2),failed)
    }
    @Test fun cancellationDuringWorkDoesNotDeliverOrStartRemainingIcons() {
        var current=true; val started=ArrayList<Int>(); val delivered=ArrayList<Int>()
        AppRefreshWork.each(listOf(1,2,3),{current},{started.add(it);current=false;it},
            {_,value -> delivered.add(value)}, {_,_ -> fail("No exception expected")})
        assertEquals(listOf(1),started)
        assertTrue(delivered.isEmpty())
    }
    @Test fun discoveryFailureIsReportedWithoutEscapingWorker() {
        var failure:Exception?=null
        val result=AppRefreshWork.attempt<List<Int>>({throw SecurityException("provider unavailable")},{failure=it})
        assertNull(result)
        assertTrue(failure is SecurityException)
        assertEquals(listOf(4),AppRefreshWork.attempt({listOf(4)},{fail("Unexpected failure")}))
    }
}
