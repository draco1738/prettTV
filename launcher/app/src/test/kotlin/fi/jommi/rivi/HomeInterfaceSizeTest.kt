package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class HomeInterfaceSizeTest {
    @Test fun missingAndInvalidSettingsKeepTheApprovedSize() {
        assertEquals(HomeInterfaceSize.STANDARD,HomeInterfaceSize.parse(null))
        assertEquals(HomeInterfaceSize.STANDARD,HomeInterfaceSize.parse("NaN"))
        HomeInterfaceSize.entries.forEach { assertEquals(it,HomeInterfaceSize.parse(it.name)) }
    }
    @Test fun largerIsOneProportionalScaleForCardsAndText() {
        assertEquals(1f,HomeInterfaceSize.STANDARD.factor,0f)
        assertEquals(1.04f,HomeInterfaceSize.LARGER.factor,0.0001f)
        for(value in listOf(1000f,567f,240f,360f,208f,94f,26f,36f))
            assertEquals(1.04f,value*HomeInterfaceSize.LARGER.factor/value,0.0001f)
    }
    @Test fun allLayoutsFitWithVisibleEdgesAtBothSizes() {
        for(screen in listOf(1280,1920,3840))for(width in listOf(1792,1808))for(size in HomeInterfaceSize.entries) {
            val unit=screen/1920f*size.factor
            val content=(width*unit+.5f).toInt()
            val left=HomeInterfaceSize.inset(screen,content)
            val right=screen-content-left
            assertTrue(left>=screen/120 && right>=screen/120)
            assertTrue(kotlin.math.abs(left-right)<=1)
            assertEquals(screen,left+content+right)
        }
    }
    @Test fun standardInsetsAreUnchanged() {
        assertEquals(64,HomeInterfaceSize.inset(1920,1792))
        assertEquals(56,HomeInterfaceSize.inset(1920,1808))
    }
}
