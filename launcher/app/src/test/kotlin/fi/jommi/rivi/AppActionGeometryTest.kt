package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class AppActionGeometryTest {
    @Test fun appNearLeftGetsPanelOnRightWithoutCoveringCard() {
        val anchor=AppActionGeometry.Box(56,660,264,754)
        val panel=AppActionGeometry.place(1920,1080,anchor,400,460,56,20)
        assertEquals(284,panel.left); assertEquals(754,panel.bottom)
    }
    @Test fun lastAppGetsPanelOnLeftAndInsideSafeInsets() {
        val anchor=AppActionGeometry.Box(1656,660,1864,754)
        val panel=AppActionGeometry.place(1920,1080,anchor,400,460,56,20)
        assertEquals(1636,panel.right); assertEquals(754,panel.bottom)
    }
    @Test fun middleCardOnNarrowScreenUsesSpaceAbove() {
        val anchor=AppActionGeometry.Box(330,500,530,600)
        val panel=AppActionGeometry.place(860,800,anchor,400,300,56,20)
        assertEquals(480,panel.bottom)
        assertTrue(panel.left>=56); assertTrue(panel.right<=804)
    }
    @Test fun enlargedFontMenuIsClampedAndCanBeScrolledWithinSafeHeight() {
        val panel=AppActionGeometry.place(1920,1080,AppActionGeometry.Box(1656,660,1864,754),400,2200,56,20)
        assertEquals(56,panel.top);assertEquals(1024,panel.bottom)
        assertEquals(400,panel.width)
    }
    @Test fun missingAnchorCentersPanel() {
        val panel=AppActionGeometry.place(1920,1080,null,400,400,56,20)
        assertEquals(AppActionGeometry.Box(760,340,1160,740),panel)
    }
    @Test fun unusualDisplaySizesNeverProduceNegativeOrOffscreenBounds() {
        for(w in listOf(1,120,320,1280,1920,3840))for(h in listOf(1,80,240,720,1080,2160)) {
            val panel=AppActionGeometry.place(w,h,AppActionGeometry.Box(-100,500,w+100,h+100),400,1600,56,20)
            assertTrue(panel.left>=0);assertTrue(panel.top>=0);assertTrue(panel.right<=w);assertTrue(panel.bottom<=h)
            assertTrue(panel.width>0);assertTrue(panel.height>0)
        }
    }
}
