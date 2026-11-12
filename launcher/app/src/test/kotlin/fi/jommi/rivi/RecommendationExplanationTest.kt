package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class RecommendationExplanationTest {
    private fun program(pkg:String="service")=Program("id","Title","","",pkg,"Service")
    @Test fun explicitPreferenceIsReportedWithoutInventingViewingOrEntitlement() {
        val text=RecommendationExplanation.describe(program(),setOf("service"),emptySet())
        assertTrue(text.contains("selected in your recommendation preferences"))
        assertFalse(text.contains("because you watched"))
        assertFalse(text.contains("automatic service priorities"))
        assertTrue(text.contains("cannot verify your sign-in, subscription"))
    }
    @Test fun emptyExplicitSelectionDoesNotFallBackToInferredHistory() {
        val text=RecommendationExplanation.describe(program(),emptySet(),setOf("service"))
        assertTrue(text.contains("not selected as a preferred service"))
        assertFalse(text.contains("has published playback progress"))
    }
    @Test fun automaticPriorityDescribesPublishedEvidenceAndSharedAccountLimits() {
        val text=RecommendationExplanation.describe(program(),null,setOf("service"))
        assertTrue(text.contains("has published playback progress on this TV"))
        assertTrue(text.contains("does not identify who watched"))
    }
    @Test fun catalogDoesNotPretendToBeAnAppFeedOrSubscription() {
        val text=RecommendationExplanation.describe(program().copy(catalog=true,sourceName="Catalog"),setOf("service"),setOf("service"))
        assertTrue(text.contains("comes from Catalog"))
        assertTrue(text.contains("does not confirm streaming availability"))
        assertFalse(text.contains("shared this title with Android TV"))
        assertFalse(text.contains("selected in your recommendation preferences"))
    }
    @Test fun unknownSourceGetsNoPersonalizedClaim() {
        val text=RecommendationExplanation.describe(program(),null,emptySet())
        assertTrue(text.contains("not evidence that you watched a similar title"))
        assertFalse(text.contains("has published playback progress"))
    }
    @Test fun plutoSelectionDoesNotPromisePromotionOverPreferredServices() {
        val text=RecommendationExplanation.describe(program("tv.pluto.android"),setOf("tv.pluto.android"),emptySet())
        assertTrue(text.contains("remains a fallback in Suggestions and Discover even when selected"))
    }
}
