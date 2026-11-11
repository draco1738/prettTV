package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class AppReadabilitySizingTest {
    @Test fun standardKeepsReferenceGeometryAndNativeDimensions() {
        val dimensions = ReadabilityDimensions()
        for (width in listOf(1280, 1920, 3840))
            assertEquals(width / 1920f, dimensions.referenceScale(width), 0f)
        for (value in listOf(0, 1, 8, 22, 64, 400))
            assertEquals(value, dimensions.pixels(value))
        assertEquals(17f, dimensions.sp(17f), 0f)
        assertEquals(63, dimensions.dp(42, 1.5f))
    }

    @Test fun largerUsesOneFactorForReferenceGeometryDpAndSp() {
        val dimensions = ReadabilityDimensions(HomeInterfaceSize.LARGER)
        assertEquals(1.04f, dimensions.referenceScale(1920), .00001f)
        assertEquals(416, dimensions.pixels(400))
        assertEquals(104, dimensions.dp(100, 1f))
        assertEquals(26f, dimensions.sp(25f), .00001f)
    }

    @Test fun systemFontScaleIsIndependentOfAppSize() {
        for (size in HomeInterfaceSize.entries) {
            val dimensions = ReadabilityDimensions(size)
            val geometry = dimensions.referenceScale(1920)
            for (fontScale in listOf(1f, 1.3f, 1.5f)) {
                assertEquals(geometry, dimensions.referenceScale(1920), 0f)
                assertEquals(25f * size.factor * fontScale, dimensions.sp(25f) * fontScale, .00001f)
            }
        }
    }

    @Test fun layoutSentinelsAndZeroAreNotTurnedIntoPixels() {
        val dimensions = ReadabilityDimensions(HomeInterfaceSize.LARGER)
        for (sentinel in listOf(-2, -1, 0)) assertEquals(sentinel, dimensions.pixels(sentinel))
    }

    @Test fun exactPreferenceValuesKeepExistingEnumAndDefault() {
        for (value in listOf(null, "", "104", "larger", "NaN"))
            assertEquals(1f, ReadabilityDimensions(HomeInterfaceSize.parse(value)).factor, 0f)
        assertEquals(1.04f, ReadabilityDimensions(HomeInterfaceSize.parse("LARGER")).factor, .00001f)
    }
}
