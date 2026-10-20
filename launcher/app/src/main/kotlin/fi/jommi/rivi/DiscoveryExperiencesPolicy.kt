package fi.jommi.rivi

import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

internal enum class DiscoveryMediaType(val label: String) {
    BOTH("Shows & movies"), MOVIE("Movies"), SHOW("TV shows")
}

/** Mood filters are explicit genre groupings, not inferred plot or tone classifications. */
internal enum class BlindPickMood(val label: String, internal val genres: Set<String>) {
    ANY("Any mood", emptySet()),
    FEEL_GOOD("Something lighter", setOf("comedy", "family")),
    TENSE("Something tense", setOf("thriller", "mystery", "crime", "horror", "suspense")),
    ADVENTUROUS("An adventure", setOf("action", "adventure", "fantasy", "science-fiction", "science fiction", "sci-fi")),
    CURIOUS("Something factual", setOf("documentary", "history", "science", "nature")),
}

internal data class BlindPickFilters(
    val mediaType: DiscoveryMediaType = DiscoveryMediaType.BOTH,
    val mood: BlindPickMood = BlindPickMood.ANY,
    val maxMinutes: Int? = null,
)

/**
 * Before reveal, bind only clue/accessibilityLabel. The program's artwork, detail, title,
 * and launch destination belong to the revealed state, not hidden views or content descriptions.
 */
internal data class BlindPickCard(
    val program: Program,
    val clue: String,
    val accessibilityLabel: String,
)

internal object BlindPickPolicy {
    /** A strict filter returns fewer than three when needed; the caller can offer "Any mood". */
    fun pick(
        candidates: List<Program>,
        filters: BlindPickFilters = BlindPickFilters(),
        preferredServices: Set<String> = emptySet(),
        blockedKeys: Set<String> = emptySet(),
        blockedTitleKeys: Set<String> = emptySet(),
        hiddenPackages: Set<String> = emptySet(),
        seed: Long = 0,
        limit: Int = 3,
        metadata: Map<String, TitleMetadata> = emptyMap(),
    ): List<BlindPickCard> {
        if (limit <= 0 || (filters.maxMinutes != null && filters.maxMinutes <= 0)) return emptyList()
        val eligible = DiscoveryCandidates.eligible(candidates, blockedKeys, blockedTitleKeys, hiddenPackages)
            .filter { p ->
                val info = metadata[ContentIdentity.key(p)]
                val minutes = DiscoveryCandidates.runtime(p, info)
                DiscoveryCandidates.matchesType(p, filters.mediaType) &&
                    (filters.mood == BlindPickMood.ANY ||
                        DiscoveryCandidates.genres(p, info).any { it in filters.mood.genres }) &&
                    (filters.maxMinutes == null || (minutes != null && minutes <= filters.maxMinutes))
            }
        val unique = DiscoveryCandidates.unique(eligible, preferredServices)
        val random = Random(seed)
        val ordered = buildList {
            // Shuffle titles and round-robin services inside each priority tier. A large feed
            // must not occupy every pick while equally preferred providers are available.
            for ((_, tier) in unique.groupBy { DiscoveryCandidates.tier(it, preferredServices) }.toSortedMap()) {
                val providers = tier.groupBy { it.packageName }.values
                    .map { it.shuffled(random) }.shuffled(random)
                for (index in 0 until (providers.maxOfOrNull { it.size } ?: 0)) {
                    providers.forEach { rows -> rows.getOrNull(index)?.let(::add) }
                }
            }
        }
        return ordered.take(limit.coerceAtMost(3)).mapIndexed { index, program ->
            val clue = clue(program, metadata[ContentIdentity.key(program)])
            BlindPickCard(program, clue, "Mystery pick ${index + 1}. $clue")
        }
    }

    /** Structured facts only: summaries can identify a title through aliases, characters or plot. */
    fun clue(program: Program, metadata: TitleMetadata? = null): String {
        val type = when (DiscoveryCandidates.type(program)) {
            DiscoveryMediaType.MOVIE -> "Movie"
            DiscoveryMediaType.SHOW -> "TV show"
            else -> "Unclassified title"
        }
        val genres = DiscoveryCandidates.genres(program, metadata).take(3)
            .joinToString(" / ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        val runtime = DiscoveryCandidates.runtime(program, metadata)?.let { "$it min" } ?: "Runtime unavailable"
        val year = DiscoveryCandidates.year(program, metadata)?.toString().orEmpty()
        return listOf(type, genres, runtime, year).filter { it.isNotBlank() }.joinToString(" · ")
    }
}

internal enum class DiscoveryConnectionKind { DIRECTOR, CAST, GENRE, RELEASE_PERIOD, PROVIDER }

internal data class DiscoveryConnection(val kind: DiscoveryConnectionKind, val label: String)
internal data class RelatedTitle(val program: Program, val reasons: List<DiscoveryConnection>) {
    val reason: String get() = reasons.joinToString(" · ") { it.label }
}

internal object RabbitHolePolicy {
    /**
     * Connections are exact published credits/genres/years. A shared provider is a viewing
     * option, never proof of a creative connection, and cannot qualify a title by itself.
     */
    fun related(
        seed: Program,
        candidates: List<Program>,
        preferredServices: Set<String> = emptySet(),
        blockedKeys: Set<String> = emptySet(),
        blockedTitleKeys: Set<String> = emptySet(),
        hiddenPackages: Set<String> = emptySet(),
        limit: Int = 24,
        metadata: Map<String, TitleMetadata> = emptyMap(),
        connectionKind: DiscoveryConnectionKind? = null,
    ): List<RelatedTitle> {
        if (limit <= 0) return emptyList()
        val seedInfo = metadata[ContentIdentity.key(seed)]
        val seedGenres = DiscoveryCandidates.genres(seed, seedInfo)
        val seedYear = DiscoveryCandidates.year(seed, seedInfo)
        val eligible = DiscoveryCandidates.eligible(candidates, blockedKeys + ContentIdentity.key(seed),
            blockedTitleKeys + seed.titleKey, hiddenPackages)
        return eligible.mapNotNull { p ->
            val info = metadata[ContentIdentity.key(p)]
            val connections = buildList {
                shared(seedInfo?.directors.orEmpty(), info?.directors.orEmpty()).take(2).forEach {
                    add(DiscoveryConnection(DiscoveryConnectionKind.DIRECTOR, "Director: $it"))
                }
                shared(seedInfo?.cast.orEmpty(), info?.cast.orEmpty()).take(2).forEach {
                    add(DiscoveryConnection(DiscoveryConnectionKind.CAST, "Cast: $it"))
                }
                val commonGenres = seedGenres.intersect(DiscoveryCandidates.genres(p, info)).sorted().take(3)
                if (commonGenres.isNotEmpty()) add(DiscoveryConnection(DiscoveryConnectionKind.GENRE,
                    "Shared genre: ${commonGenres.joinToString(" / ")}"))
                val year = DiscoveryCandidates.year(p, info)
                if (seedYear != null && year != null) {
                    if (seedYear == year) add(DiscoveryConnection(DiscoveryConnectionKind.RELEASE_PERIOD, "Both released in $year"))
                    else if (seedYear / 10 == year / 10) add(DiscoveryConnection(DiscoveryConnectionKind.RELEASE_PERIOD,
                        "Both from the ${year / 10 * 10}s"))
                }
            }
            if (connections.isEmpty()) return@mapNotNull null
            val provider = if (!p.catalog && !seed.catalog && p.packageName == seed.packageName && p.app.isNotBlank())
                listOf(DiscoveryConnection(DiscoveryConnectionKind.PROVIDER, "Shared app: ${p.app.take(80)}")) else emptyList()
            RelatedTitle(p, (connections + provider).take(6))
        }.filter { connectionKind == null || it.reasons.any { reason -> reason.kind == connectionKind } }
            .sortedWith(compareBy<RelatedTitle> { DiscoveryCandidates.tier(it.program, preferredServices) }
            .thenByDescending { row -> row.reasons.sumOf { connectionWeight(it.kind) } }
            .thenBy { it.program.titleKey })
            // Choose among providers only after each card proves the requested connection.
            // A preferred card without credits must not erase a verified cast/director match.
            .distinctBy { it.program.titleKey }
            .distinctBy { ContentIdentity.key(it.program) }
            .take(limit.coerceAtMost(60))
    }

    private fun shared(left: List<String>, right: List<String>): List<String> {
        val rightNames = right.map(DiscoveryCandidates::normalize).filter { it.isNotBlank() }.toSet()
        return left.map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.length in 2..120 && DiscoveryCandidates.normalize(it) in rightNames }
            .distinctBy(DiscoveryCandidates::normalize)
    }

    private fun connectionWeight(kind: DiscoveryConnectionKind): Int = when (kind) {
        DiscoveryConnectionKind.DIRECTOR -> 50
        DiscoveryConnectionKind.CAST -> 30
        DiscoveryConnectionKind.GENRE -> 10
        DiscoveryConnectionKind.RELEASE_PERIOD -> 2
        DiscoveryConnectionKind.PROVIDER -> 0
    }
}

private object DiscoveryCandidates {
    private val freeServices = setOf("tv.pluto.android", "com.tubitv", "com.google.android.youtube.tv",
        "com.plexapp.android", "com.roku.web.trc", "com.amazon.imdb.tv", "com.xumo.xumo")
    fun normalize(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    fun eligible(candidates: List<Program>, blockedKeys: Set<String>, blockedTitleKeys: Set<String>,
                 hiddenPackages: Set<String>): List<Program> {
        // Expand a dismissed identity to its known title copies before choosing a provider.
        val titles = blockedTitleKeys.map(::normalize).toSet() +
            candidates.filter { ContentIdentity.key(it) in blockedKeys }.map { it.titleKey }
        return candidates.filter {
            it.title.isNotBlank() && it.packageName.isNotBlank() && !it.liveSports &&
                it.packageName !in hiddenPackages && it.titleKey !in titles &&
                ContentIdentity.key(it) !in blockedKeys
        }
    }

    fun unique(candidates: List<Program>, services: Set<String>): List<Program> = candidates
        .sortedBy { tier(it, services) }
        .distinctBy { it.titleKey }
        .distinctBy(ContentIdentity::key)

    fun tier(p: Program, services: Set<String>): Int = when {
        p.packageName == RecommendationPolicy.STREMIO -> 5
        p.packageName == "tv.pluto.android" -> 4
        p.catalog -> 3
        p.packageName in services -> 0
        p.packageName in freeServices -> 2
        else -> 1
    }

    fun type(p: Program): DiscoveryMediaType = when (p.mediaType.lowercase(Locale.ROOT)) {
        "movie", "film" -> DiscoveryMediaType.MOVIE
        "series", "show", "episode", "tv_series", "tv_episode" -> DiscoveryMediaType.SHOW
        else -> DiscoveryMediaType.BOTH
    }

    fun matchesType(p: Program, filter: DiscoveryMediaType) = filter == DiscoveryMediaType.BOTH || type(p) == filter
    fun genres(p: Program, info: TitleMetadata?): Set<String> =
        if (info?.genres?.any { it.isNotBlank() } == true) info.genres.map(::normalize)
            .filter { it.isNotBlank() && it.length <= 60 }.take(12).toSet()
        else PersonalizationPolicy.genres(p)

    fun runtime(p: Program, info: TitleMetadata?): Int? = when {
        info != null && info.runtimeMinutes in 1..1440 -> info.runtimeMinutes
        p.duration in 1..86_400_000L -> ceil(p.duration / 60_000.0).toInt().coerceAtLeast(1)
        else -> null
    }

    fun year(p: Program, info: TitleMetadata?): Int? =
        info?.year?.takeIf { it in 1900..2200 } ?: p.releaseYear.takeIf { it in 1900..2200 }
}
