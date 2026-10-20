package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class PersonalizationPolicyTest {
    private fun program(title: String = "The Show", pkg: String = "provider.one", type: String = "series") =
        Program("preview:1", title, "", "", pkg, "Provider", mediaType = type)

    @Test fun exactProviderFallbackDoesNotMergeDifferentAppsTypesOrPunctuation() {
        val first = program()
        assertEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(id = "watch:23", title = " THE   SHOW ")))
        assertNotEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(packageName = "provider.two")))
        assertNotEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(mediaType = "movie")))
        assertNotEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(title = "The Show!")))
    }

    @Test fun canonicalIdsMatchAcrossAppsWithoutFuzzyTitleGuessing() {
        val first = program().copy(imdbId = "tt1234567")
        val second = program("A translated title", "provider.two").copy(id = "cinemeta:series:tt1234567")
        assertEquals(ContentIdentity.key(first), ContentIdentity.key(second))
        assertNotEquals(ContentIdentity.key(first), ContentIdentity.key(second.copy(id = "cinemeta:series:tt1234568")))
        assertNotEquals(ContentIdentity.key(program().copy(id = "tvmaze:11")), ContentIdentity.key(program().copy(id = "tvmaze:12")))
    }

    @Test fun malformedCanonicalIdsFallBackToProviderIdentity() {
        val first = program()
        assertEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(imdbId = "https://example/tt1234567", id = "preview:tt1234567")))
        assertEquals(ContentIdentity.key(first), ContentIdentity.key(first.copy(id = "tvmaze:11:other")))
    }

    @Test fun playbackMustMatchBeNewerThanSaveAndNotAlreadyConsumed() {
        val p = program()
        val saved = SavedTitle(ContentIdentity.key(p), p, 100)
        assertFalse(PersonalizationPolicy.canConsume(saved, p, 100, 0))
        assertFalse(PersonalizationPolicy.canConsume(saved, p, 99, 0))
        assertFalse(PersonalizationPolicy.canConsume(saved, p.copy(packageName = "other"), 200, 0))
        assertTrue(PersonalizationPolicy.canConsume(saved, p, 200, 0))
        assertFalse(PersonalizationPolicy.canConsume(saved, p, 200, 200))
        assertTrue(PersonalizationPolicy.canConsume(saved, p, 201, 200))
    }

    @Test fun playbackUsesExactCanonicalMatchWhenProviderSnapshotHasChanged() {
        val savedProgram = program().copy(imdbId = "tt1234567")
        val saved = SavedTitle(ContentIdentity.key(savedProgram), savedProgram, 100)
        assertTrue(PersonalizationPolicy.canConsume(saved, program("Translated", "second").copy(imdbId = "tt1234567"), 200, 0))
        assertFalse(PersonalizationPolicy.canConsume(saved, program("The Show season two"), 200, 0))
    }

    @Test fun periodicPositionUpdatesAndPauseDoNotBecomeNewPlaybackEvents() {
        val events = ConfirmedPlaybackEvents<String>()
        assertEquals(100L, events.confirm("session|title", 100))
        assertEquals(100L, events.confirm("session|title", 200))
        events.retain(setOf("session|title")) // The paused controller is still present.
        assertEquals(100L, events.confirm("session|title", 300))
        events.retain(emptySet()) // Session ended or changed title.
        assertEquals(400L, events.confirm("session|title", 400))
        assertEquals(500L, events.confirm("new-session|title", 500))
        events.removeMatching { it.startsWith("new-session") }
        assertEquals(600L, events.confirm("new-session|title", 600))
    }

    @Test fun defaultsPlaceSavedAndEpisodeRowsBetweenWatchingAndSports() {
        val rows = RowPreferences()
        assertEquals(listOf("watching-shows", "watching-movies", "watchlist", "episodes", "sports", "discover", "explore", "trending", "new"), rows.ordered(DEFAULT_ROW_ORDER))
        assertEquals(RowCardSize.STANDARD, rows.size("sports"))
        assertEquals(60, rows.limit("discover"))
        assertEquals(listOf(5, 4, 3), RowCardSize.entries.map { it.visibleCards })
    }

    @Test fun rowMigrationKeepsChosenOrderAddsNewKeysAndRejectsInvalidPreferences() {
        val rows = RowPreferences(
            order = listOf("sports", "watchlist", "sports", "invalid"),
            hidden = setOf("episodes", "invalid"),
            sizes = mapOf("sports" to RowCardSize.SMALL, "invalid" to RowCardSize.LARGE),
            limits = mapOf("explore" to 40, "watchlist" to 20, "trending" to 999),
        ).normalized()
        assertEquals(listOf("sports", "watchlist"), rows.order.take(2))
        assertEquals(DEFAULT_ROW_ORDER.size, rows.order.size)
        assertEquals(setOf("episodes"), rows.hidden)
        assertEquals(mapOf("sports" to RowCardSize.SMALL), rows.sizes)
        assertEquals(mapOf("explore" to 40), rows.limits)
        assertFalse(rows.ordered(DEFAULT_ROW_ORDER).contains("episodes"))
        assertEquals(60, rows.limit("trending"))
    }

    @Test fun feedbackOnlyExcludesIdentityAndDoesNotInventGenreFromDescription() {
        val dismissed = program().copy(genres = "Drama, Science-fiction | Drama", detail = "Comedy")
        val feedback = PersonalizationPolicy.feedback(listOf(DismissedRecommendation(ContentIdentity.key(dismissed), PersonalizationPolicy.genres(dismissed))))
        assertTrue(feedback.isDismissed(dismissed))
        assertFalse(feedback.isDismissed(program("Another title")))
        assertEquals(setOf("drama", "science-fiction"), feedback.genreCounts.keys)
        assertEquals(0, feedback.penalty(program("No metadata").copy(detail = "Drama")))
    }

    @Test fun genrePenaltyIsBoundedAndKeepsMostOriginalOrdering() {
        val feedback = RecommendationFeedback(genreCounts = mapOf("drama" to 100, "action" to 99))
        val cards = (0..9).map { program("Title $it").copy(genres = if (it == 0) "Drama, Action" else "Comedy") }
        val result = PersonalizationPolicy.applyFeedback(cards, feedback)
        assertEquals(2, feedback.penalty(cards.first()))
        assertTrue(result.indexOf(cards.first()) <= 2)
        assertEquals(cards.drop(3), result.drop(3))
    }

    @Test fun dismissedTitleKeysPersistIndependentlyOfHomeIdentityFiltering() {
        val first = program().copy(imdbId = "tt1234567", genres = "Drama")
        val otherProvider = program(pkg = "other")
        val feedback = PersonalizationPolicy.feedback(listOf(DismissedRecommendation(
            ContentIdentity.key(first), PersonalizationPolicy.genres(first), " THE   SHOW ",
        )))
        assertEquals(setOf(first.titleKey), feedback.dismissedTitleKeys)
        assertTrue(feedback.isDismissed(first))
        assertFalse(feedback.isDismissed(otherProvider)) // Existing Home behavior stays exact.
        assertTrue(otherProvider.titleKey in feedback.dismissedTitleKeys)
        assertEquals(mapOf("drama" to 1), feedback.genreCounts)
    }

    @Test fun legacyProviderDismissalRecoversLengthPrefixedTitleWithoutNeedingFeedRows() {
        val p = program(title = "The:  Show 😀", pkg = "provider:with:colons")
        val feedback = PersonalizationPolicy.feedback(listOf(DismissedRecommendation(ContentIdentity.key(p), emptySet())))
        assertEquals(setOf(p.titleKey), feedback.dismissedTitleKeys)
        val malformed = listOf("provider:999999999999:x:series:4:show", "provider:1:x:series:99:show",
            "provider:1:x:series:4:show:extra", "provider:-1:x:series:4:show", "provider:1:x::4:show",
            "imdb:series:tt1234567", "tvmaze:series:123")
        assertTrue(PersonalizationPolicy.feedback(malformed.map { DismissedRecommendation(it, emptySet()) })
            .dismissedTitleKeys.isEmpty())
    }

    @Test fun legacyCanonicalDismissalBackfillsOnlyFromAnExactIdentityAndKeepsExplicitTitle() {
        val p = program().copy(imdbId = "tt1234567")
        val original = DismissedRecommendation(ContentIdentity.key(p), setOf("drama"))
        assertTrue(PersonalizationPolicy.feedback(listOf(original)).dismissedTitleKeys.isEmpty())
        val mismatched = PersonalizationPolicy.rememberDismissedTitles(listOf(original),
            listOf(p.copy(imdbId = "tt1234568")))
        assertEquals(listOf(original), mismatched)
        val backfilled = PersonalizationPolicy.rememberDismissedTitles(listOf(original), listOf(p))
        assertEquals(setOf(p.titleKey), PersonalizationPolicy.feedback(backfilled).dismissedTitleKeys)
        assertEquals(backfilled, PersonalizationPolicy.rememberDismissedTitles(backfilled,
            listOf(p.copy(title = "A changed localized title"))))
        assertEquals(original.key, backfilled.single().key)
        assertEquals(original.genres, backfilled.single().genres)
    }

    @Test fun feedbackCannotPromoteFreePlutoOrStremioAheadOfPaidService() {
        val paid = program("Paid").copy(genres = "Drama")
        val free = program("Free", "com.tubitv")
        val pluto = program("Pluto", "tv.pluto.android")
        val stremio = program("Stremio", RecommendationPolicy.STREMIO)
        val feedback = RecommendationFeedback(genreCounts = mapOf("drama" to 2))
        val result = RecommendationPolicy.balanced(listOf(pluto, stremio, free, paid), setOf(paid.packageName),
            lowerPriority = setOf(pluto.packageName), feedback = feedback)
        assertEquals(listOf(paid, free, pluto, stremio), result)
    }

    @Test fun recommendationDismissalIsAppliedBeforeLimitsAndAcrossDiscoveryRows() {
        val cards = (0..199).map { program("Title $it") }
        val feedback = RecommendationFeedback(cards.take(30).map(ContentIdentity::key).toSet())
        val rows = RecommendationPolicy.build(cards, emptyList(), emptyList(), emptyList(), emptyList(),
            setOf("provider.one"), feedback = feedback)
        assertEquals(60, rows.suggestions.size)
        assertEquals(60, rows.discover.size)
        assertTrue((rows.suggestions + rows.discover).none(feedback::isDismissed))
    }

    @Test fun undoIsBoundedAndDoesNotRecordUndoWithinUndo() {
        val history = UndoHistory(2)
        var value = 3
        history.add("first") { value = 0 }
        history.add("second") { value = 1 }
        history.add("third") { value = 2; history.add("recursive") { value = 99 } }
        assertEquals("third", history.undo())
        assertEquals(2, value)
        assertEquals("second", history.undo())
        assertEquals(1, value)
        assertFalse(history.canUndo())
        assertNull(history.undo())
    }

    @Test fun failedUndoRemainsAvailableRatherThanLosingUserAction() {
        val history = UndoHistory()
        var shouldFail = true
        history.add("retry") { if (shouldFail) error("transient") }
        try { history.undo(); fail("Expected undo failure") } catch (_: IllegalStateException) { }
        assertEquals("retry", history.label())
        shouldFail = false
        assertEquals("retry", history.undo())
        assertFalse(history.canUndo())
    }
}
