package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test

class DiscoveryExperiencesPolicyTest {
    private fun program(title: String, pkg: String = "paid.one", type: String = "movie", genres: String = "Drama") =
        Program("preview:$pkg:$title", title, "", "", pkg, pkg, mediaType = type, genres = genres)
    private fun metadata(vararg rows: Pair<Program, TitleMetadata>) = rows.associate { ContentIdentity.key(it.first) to it.second }

    @Test fun blindPickIsDeterministicUniqueAndBounded() {
        val rows = (1..20).map { program("Title $it") }
        val first = BlindPickPolicy.pick(rows, seed = 12)
        assertEquals(first, BlindPickPolicy.pick(rows, seed = 12))
        assertNotEquals(first, BlindPickPolicy.pick(rows, seed = 13))
        assertEquals(3, first.size)
        assertEquals(3, first.map { it.program.titleKey }.toSet().size)
        assertEquals(3, BlindPickPolicy.pick(rows, limit = 999).size)
        assertTrue(BlindPickPolicy.pick(rows, limit = -1).isEmpty())
    }

    @Test fun preferredProvidersHaveVarietyBeforeFreePlutoAndStremio() {
        val rows = (1..50).map { program("Large feed $it") } +
            program("Second provider", "paid.two") + program("Free", "com.tubitv") +
            program("Pluto", "tv.pluto.android") + program("Stremio", RecommendationPolicy.STREMIO)
        val preferred = setOf("paid.one", "paid.two")
        val picks = BlindPickPolicy.pick(rows, preferredServices = preferred, seed = 25)
        assertTrue(picks.all { it.program.packageName in preferred })
        assertEquals(preferred, picks.take(2).map { it.program.packageName }.toSet())
        val tail = BlindPickPolicy.pick(rows.takeLast(3), preferredServices = setOf(RecommendationPolicy.STREMIO))
        assertEquals(listOf("Free", "Pluto", "Stremio"), tail.map { it.program.title })
    }

    @Test fun duplicateTitleChoosesPreferredProviderAndCanonicalIdsDeduplicateTranslatedTitles() {
        val paid = program("The title")
        val stremio = paid.copy(packageName = RecommendationPolicy.STREMIO)
        val canonical = program("Original").copy(imdbId = "tt1234567")
        val translated = program("Translated", "other").copy(imdbId = "tt1234567")
        val picks = BlindPickPolicy.pick(listOf(stremio, translated, paid, canonical), preferredServices = setOf("paid.one"))
        assertEquals(2, picks.size)
        assertEquals(setOf("paid.one"), picks.map { it.program.packageName }.toSet())
    }

    @Test fun hiddenDismissedAndWatchingTitlesCannotReappearThroughOtherProviders() {
        val watching = program("Watching")
        val hidden = program("Hidden")
        val dismissed = program("Dismissed")
        val rows = listOf(watching, hidden, dismissed, dismissed.copy(packageName = "other"),
            program("Hidden package", "hidden.app"), program("Game").copy(liveSports = true), program("Allowed"))
        val picks = BlindPickPolicy.pick(rows, blockedKeys = setOf(ContentIdentity.key(dismissed)),
            blockedTitleKeys = setOf(" Watching ", " HIDDEN "), hiddenPackages = setOf("hidden.app"))
        assertEquals(listOf("Allowed"), picks.map { it.program.title })
    }

    @Test fun filtersNeverInventGenreOrRuntimeAndDoNotSilentlyRelax() {
        val unknown = program("Unknown", genres = "").copy(detail = "Funny comedy", summary = "An action-packed mystery")
        val long = program("Long comedy", genres = "Comedy").copy(duration = 100 * 60_000L)
        val short = program("Short comedy", genres = "Comedy").copy(duration = 20 * 60_000L)
        val show = program("Comedy show", type = "series", genres = "Comedy").copy(duration = 20 * 60_000L)
        val filters = BlindPickFilters(DiscoveryMediaType.MOVIE, BlindPickMood.FEEL_GOOD, 30)
        assertEquals(listOf(short), BlindPickPolicy.pick(listOf(unknown, long, short, show), filters).map { it.program })
        assertTrue(BlindPickPolicy.pick(listOf(unknown, long), filters).isEmpty())
        assertEquals(2, BlindPickPolicy.pick(listOf(unknown, long), BlindPickFilters()).size)
        assertTrue(BlindPickPolicy.pick(listOf(short), BlindPickFilters(maxMinutes = 0)).isEmpty())
        assertTrue(BlindPickPolicy.clue(unknown).contains("Runtime unavailable"))
    }

    @Test fun runtimeFilterRoundsUpAndVerifiedMetadataCanSupplyMissingRuntime() {
        val over = program("Just too long").copy(duration = 30 * 60_000L + 1)
        val known = program("Metadata duration")
        val rows = listOf(over, known)
        val picks = BlindPickPolicy.pick(rows, BlindPickFilters(maxMinutes = 30),
            metadata = metadata(known to TitleMetadata(runtimeMinutes = 30)))
        assertEquals(listOf(known), picks.map { it.program })
        assertTrue(picks.single().clue.contains("30 min"))
    }

    @Test fun movieAndShowFiltersExcludeUnknownTypesInsteadOfGuessingFromDescription() {
        val movie = program("Movie")
        val show = program("Show", type = "tv_series")
        val unknown = program("Unknown", type = "unknown").copy(detail = "A TV series")
        val channel = program("Channel", type = "tv")
        assertEquals(listOf(movie), BlindPickPolicy.pick(listOf(movie, show, unknown, channel),
            BlindPickFilters(mediaType = DiscoveryMediaType.MOVIE)).map { it.program })
        assertEquals(listOf(show), BlindPickPolicy.pick(listOf(movie, show, unknown, channel),
            BlindPickFilters(mediaType = DiscoveryMediaType.SHOW)).map { it.program })
    }

    @Test fun unrevealedCluesContainOnlyStructuredFacts() {
        val secret = program("Hidden Harbor", genres = "Mystery").copy(
            summary = "<b>HIDDEN HARBOR</b> follows an investigator. Hidden   Harbor has a secret.",
            duration = 90 * 60_000L,
            releaseYear = 2024,
        )
        val pick = BlindPickPolicy.pick(listOf(secret)).single()
        assertEquals("Movie · Mystery · 90 min · 2024", pick.clue)
        assertEquals("Mystery pick 1. ${pick.clue}", pick.accessibilityLabel)
        assertEquals(secret, pick.program) // The actual reveal and launch still retain exact metadata.
    }

    @Test fun synopsisAliasesAndCharacterNamesCannotLeakBeforeOrAfterEnrichment() {
        val secret = program("Attack on Titan", type = "series", genres = "Action").copy(
            summary = "Attack on Titan (Shingeki no Kyojin) follows Eren Jaeger, Mikasa Ackerman, " +
                "and Armin Arlert as they fight Titans.",
        )
        val enriched = TitleMetadata(genres = listOf("Action", "Anime"), runtimeMinutes = 24, year = 2013,
            cast = listOf("Yuki Kaji"), sourceName = "Attack on Titan (Shingeki no Kyojin)")
        val initial = BlindPickPolicy.pick(listOf(secret)).single()
        val updated = BlindPickPolicy.pick(listOf(secret), metadata = metadata(secret to enriched)).single()
        assertEquals("TV show · Action · Runtime unavailable", initial.clue)
        assertEquals("TV show · Action / Anime · 24 min · 2013", updated.clue)
        assertEquals(updated.clue, BlindPickPolicy.clue(secret, enriched))
        for (pick in listOf(initial, updated)) {
            assertEquals("Mystery pick 1. ${pick.clue}", pick.accessibilityLabel)
            for (identity in listOf("Attack on Titan", "Shingeki no Kyojin", "Eren Jaeger",
                    "Mikasa Ackerman", "Armin Arlert", "Titans", "Yuki Kaji")) {
                assertFalse(pick.clue.contains(identity, ignoreCase = true))
                assertFalse(pick.accessibilityLabel.contains(identity, ignoreCase = true))
            }
            assertEquals(secret, pick.program)
        }
    }

    @Test fun partialMetadataStillProducesUsefulCluesWithoutSynopsisFallback() {
        val p = program("It", genres = "Horror").copy(summary = "It follows a town where it appears.")
        assertEquals("Movie · Horror · Runtime unavailable", BlindPickPolicy.clue(p))
        val unknown = program("Unknown identity", type = "", genres = "").copy(summary = "An identifying plot.")
        assertEquals("Unclassified title · Runtime unavailable", BlindPickPolicy.clue(unknown))
    }

    @Test fun synopsisPunctuationAndMarkupNeverEnterConcealedClues() {
        val spider = program("Spider-Man").copy(
            summary = "Spider Man protects a city. SPIDER–MAN returns. SpiderMan faces a choice. " +
                "Ｓｐｉｄｅｒ－Ｍａｎ helps a friend. &lt;b&gt;Spider&#x2011;Man&lt;/b&gt; " +
                "Spider&#45;Man and Spider&hyphen;Man return. Spider&nbsp;Man meets a friend. " +
                "Spider&amp;ndash;Man faces a choice.",
        )
        val queen = program("The Queen’s Gambit").copy(
            summary = "The Queen's Gambit follows a player. The Queens Gambit explores competition. " +
                "The Queenʼs Gambit continues. The Queen&rsquo;s Gambit follows a chess player.",
        )
        for (program in listOf(spider, queen)) {
            val pick = BlindPickPolicy.pick(listOf(program)).single()
            assertEquals("Movie · Drama · Runtime unavailable", pick.clue)
            assertEquals("Mystery pick 1. ${pick.clue}", pick.accessibilityLabel)
        }
    }

    @Test fun rabbitHoleUsesPublishedConnectionsAndRejectsProviderOnlyFiller() {
        val seed = program("Seed", genres = "Drama, Mystery").copy(releaseYear = 2024)
        val genre = program("Genre match", "other", genres = "Mystery")
        val period = program("Period match", "other", genres = "Comedy").copy(releaseYear = 2021)
        val providerOnly = program("Same app", genres = "Nature")
        val results = RabbitHolePolicy.related(seed, listOf(genre, period, providerOnly))
        assertEquals(setOf("Genre match", "Period match"), results.map { it.program.title }.toSet())
        assertTrue(results.first { it.program == genre }.reasons.any {
            it.kind == DiscoveryConnectionKind.GENRE && it.label.contains("mystery")
        })
        assertTrue(results.first { it.program == period }.reason.contains("2020s"))
        assertTrue(results.none { it.reason.contains("inspired", ignoreCase = true) })
    }

    @Test fun rabbitHoleUsesExactCastAndDirectorCreditsInsteadOfSubstringNames() {
        val seed = program("Seed", genres = "")
        val match = program("Match", genres = "")
        val mismatch = program("Mismatch", genres = "")
        val info = metadata(
            seed to TitleMetadata(cast = listOf("Alex Example"), directors = listOf("Jordan Director")),
            match to TitleMetadata(cast = listOf(" alex   example "), directors = listOf("JORDAN DIRECTOR")),
            mismatch to TitleMetadata(cast = listOf("Alexander Example"), directors = listOf("Jordan Director Jr.")),
        )
        val result = RabbitHolePolicy.related(seed, listOf(match, mismatch), metadata = info).single()
        assertEquals(match, result.program)
        assertTrue(result.reasons.any { it.kind == DiscoveryConnectionKind.CAST })
        assertTrue(result.reasons.any { it.kind == DiscoveryConnectionKind.DIRECTOR })
        assertTrue(result.reasons.any { it.kind == DiscoveryConnectionKind.PROVIDER })
    }

    @Test fun catalogAssignedAppIsNotEvidenceOfProviderAvailability() {
        val seed = program("Seed")
        val catalogTitle = program("Catalog title").copy(catalog = true)
        val candidate = program("App title")
        assertTrue(RabbitHolePolicy.related(seed, listOf(catalogTitle)).single().reasons
            .none { it.kind == DiscoveryConnectionKind.PROVIDER })
        assertTrue(RabbitHolePolicy.related(seed.copy(catalog = true), listOf(candidate)).single().reasons
            .none { it.kind == DiscoveryConnectionKind.PROVIDER })
    }

    @Test fun rabbitHoleExcludesSeedHiddenAndDismissedCopiesAndLimitsAfterFiltering() {
        val seed = program("Seed")
        val dismissed = program("Dismissed")
        val rows = listOf(seed, seed.copy(packageName = "other"), dismissed,
            dismissed.copy(packageName = "other"), program("Hidden"), program("Hidden provider", "hidden.app")) +
            (1..100).map { program("Eligible $it") }
        val results = RabbitHolePolicy.related(seed, rows, blockedKeys = setOf(ContentIdentity.key(dismissed)),
            blockedTitleKeys = setOf("hidden"), hiddenPackages = setOf("hidden.app"), limit = 24)
        assertEquals(24, results.size)
        assertTrue(results.all { it.program.title.startsWith("Eligible") })
        assertEquals(60, RabbitHolePolicy.related(seed, rows, limit = 999).size)
        assertTrue(RabbitHolePolicy.related(seed, rows, limit = 0).isEmpty())
    }

    @Test fun rabbitHolePrefersExistingServicesAndNeverDuplicatesCrossProviderTitles() {
        val seed = program("Seed")
        val paid = program("Same title")
        val stremio = paid.copy(packageName = RecommendationPolicy.STREMIO)
        val free = program("Free title", "com.tubitv")
        val results = RabbitHolePolicy.related(seed, listOf(stremio, free, paid), preferredServices = setOf("paid.one"))
        assertEquals(listOf(paid, free), results.map { it.program })
    }

    @Test fun connectionFilterRunsBeforeProviderPriorityAndResultLimit() {
        val seed = program("Seed")
        val preferredGenres = (1..24).map { program("Preferred genre $it") }
        val cast = program("Verified cast", "other", genres = "Comedy")
        val director = program("Verified director", "other", genres = "Nature")
        val rows = preferredGenres + cast + director
        val info = metadata(
            seed to TitleMetadata(cast = listOf("Alex Actor"), directors = listOf("Sam Director")),
            cast to TitleMetadata(cast = listOf("Alex Actor")),
            director to TitleMetadata(directors = listOf("Sam Director")),
        )
        assertEquals(24, RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info).size)
        assertEquals(listOf(cast), RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info, connectionKind = DiscoveryConnectionKind.CAST).map { it.program })
        assertEquals(listOf(director), RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info, connectionKind = DiscoveryConnectionKind.DIRECTOR).map { it.program })
    }

    @Test fun verifiedConnectionSurvivesPreferredProviderCopyWithoutCredits() {
        val seed = program("Seed")
        val preferred = program("Same title")
        val verified = program("Same title", "other")
        val info = metadata(
            seed to TitleMetadata(cast = listOf("Alex Actor"), directors = listOf("Sam Director")),
            verified to TitleMetadata(cast = listOf("Alex Actor"), directors = listOf("Sam Director")),
        )
        val rows = listOf(preferred, verified)
        assertEquals(listOf(preferred), RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info).map { it.program })
        assertEquals(listOf(verified), RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info, connectionKind = DiscoveryConnectionKind.CAST).map { it.program })
        assertEquals(listOf(verified), RabbitHolePolicy.related(seed, rows, preferredServices = setOf("paid.one"),
            metadata = info, connectionKind = DiscoveryConnectionKind.DIRECTOR).map { it.program })
    }
}
