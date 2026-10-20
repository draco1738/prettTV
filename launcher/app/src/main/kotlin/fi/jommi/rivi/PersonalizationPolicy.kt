package fi.jommi.rivi

import java.util.Locale

/** Exact identifiers only. A provider title is not proof that another provider has the same edition. */
object ContentIdentity {
    private val imdb = Regex("tt[0-9]{5,12}", RegexOption.IGNORE_CASE)
    private val tvmaze = Regex("tvmaze:([0-9]+)")
    fun key(program: Program): String {
        val type = program.mediaType.lowercase(Locale.ROOT).ifBlank { "unknown" }
        val explicitImdb = program.imdbId.takeIf { imdb.matches(it) }
            ?: program.id.takeIf { it.startsWith("cinemeta:") }?.substringAfterLast(':')?.takeIf { imdb.matches(it) }
        if (explicitImdb != null) return "imdb:$type:${explicitImdb.lowercase(Locale.ROOT)}"
        tvmaze.matchEntire(program.id)?.groupValues?.get(1)?.let { return "tvmaze:$type:$it" }
        // Length prefixes avoid collisions when a title contains our separators.
        return "provider:${program.packageName.length}:${program.packageName}:$type:${program.titleKey.length}:${program.titleKey}"
    }
}

enum class RowCardSize(val label: String, val visibleCards: Int) {
    SMALL("Small", 5), STANDARD("Standard", 4), LARGE("Large", 3)
}

val DEFAULT_ROW_ORDER = listOf("watching-shows", "watching-movies", "watchlist", "episodes", "sports", "discover", "explore", "trending", "new")

data class RowPreferences(
    val order: List<String> = DEFAULT_ROW_ORDER,
    val hidden: Set<String> = emptySet(),
    val sizes: Map<String, RowCardSize> = emptyMap(),
    val limits: Map<String, Int> = emptyMap(),
) {
    fun size(key: String) = sizes[key] ?: RowCardSize.STANDARD
    fun limit(key: String) = limits[key]?.takeIf { it in setOf(20, 40, 60) } ?: 60
    fun ordered(keys: Collection<String>): List<String> =
        (order + DEFAULT_ROW_ORDER + keys).distinct().filter { it in keys && it !in hidden }
    fun normalized() = copy(
        order = (order + DEFAULT_ROW_ORDER).filter { it in DEFAULT_ROW_ORDER }.distinct(),
        hidden = hidden.intersect(DEFAULT_ROW_ORDER.toSet()),
        sizes = sizes.filterKeys { it in DEFAULT_ROW_ORDER },
        limits = limits.filter { it.key in setOf("discover", "explore", "trending", "new") && it.value in setOf(20, 40, 60) },
    )
}

data class SavedTitle(val key: String, val program: Program, val savedAt: Long)
data class FollowedShow(val key: String, val program: Program, val followedAt: Long)
data class DismissedRecommendation(val key: String, val genres: Set<String>, val titleKey: String = "")

data class RecommendationFeedback(
    val dismissed: Set<String> = emptySet(),
    val genreCounts: Map<String, Int> = emptyMap(),
    /** Opt-in title exclusions for discovery experiences; Home still uses exact identity below. */
    val dismissedTitleKeys: Set<String> = emptySet(),
) {
    fun isDismissed(program: Program) = ContentIdentity.key(program) in dismissed
    fun penalty(program: Program): Int = PersonalizationPolicy.genres(program)
        .sumOf { genreCounts[it]?.coerceIn(0, 2) ?: 0 }.coerceAtMost(2)
}

object PersonalizationPolicy {
    fun genres(program: Program): Set<String> = program.genres.split(',', '|', ';', '/')
        .map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() && it.length <= 60 }.take(12).toSet()

    /** Called within one provider/priority group so feedback cannot promote free services. */
    fun applyFeedback(rows: List<Program>, feedback: RecommendationFeedback): List<Program> = rows
        .filterNot(feedback::isDismissed).withIndex()
        .sortedWith(compareBy<IndexedValue<Program>> { it.index + feedback.penalty(it.value) }.thenBy { it.index })
        .map { it.value }

    fun canConsume(saved: SavedTitle, played: Program, playedAt: Long, consumedAt: Long): Boolean =
        saved.key == ContentIdentity.key(played) && playedAt > saved.savedAt && playedAt > consumedAt

    fun feedback(dismissals: Collection<DismissedRecommendation>) = RecommendationFeedback(
        dismissals.map { it.key }.toSet(),
        dismissals.flatMap { it.genres }.groupingBy { it }.eachCount().mapValues { it.value.coerceAtMost(2) },
        dismissals.map(::dismissedTitleKey).filter { it.isNotBlank() }.toSet(),
    )

    internal fun dismissedTitleKey(item: DismissedRecommendation): String =
        normalizeTitle(item.titleKey).ifBlank { legacyProviderTitle(item.key) }

    /** Fill older canonical-ID records only from an exact identity seen before feed filtering. */
    internal fun rememberDismissedTitles(items: List<DismissedRecommendation>, programs: List<Program>): List<DismissedRecommendation> {
        val known = programs.filter { it.title.isNotBlank() }.distinctBy(ContentIdentity::key)
            .associate { ContentIdentity.key(it) to it.titleKey }
        return items.map { item ->
            val title = dismissedTitleKey(item).ifBlank { known[item.key].orEmpty() }
            if (title == item.titleKey) item else item.copy(titleKey = title)
        }
    }

    private fun normalizeTitle(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** The old provider key embeds exact UTF-16 lengths; canonical IDs never imply a title. */
    private fun legacyProviderTitle(key: String): String {
        if (!key.startsWith("provider:")) return ""
        val packageLengthEnd = key.indexOf(':', "provider:".length)
        if (packageLengthEnd < 0) return ""
        val packageLength = key.substring("provider:".length, packageLengthEnd).toIntOrNull() ?: return ""
        if (packageLength <= 0 || packageLength > key.length - packageLengthEnd - 1) return ""
        val packageEnd = packageLengthEnd + 1 + packageLength
        if (packageEnd >= key.length || key[packageEnd] != ':') return ""
        val typeEnd = key.indexOf(':', packageEnd + 1)
        if (typeEnd <= packageEnd + 1) return ""
        val titleLengthEnd = key.indexOf(':', typeEnd + 1)
        if (titleLengthEnd < 0) return ""
        val titleLength = key.substring(typeEnd + 1, titleLengthEnd).toIntOrNull() ?: return ""
        val title = key.substring(titleLengthEnd + 1)
        if (titleLength <= 0 || title.length != titleLength) return ""
        return normalizeTitle(title)
    }
}

/** A paused session keeps its event; periodic position samples are not new playback. */
internal class ConfirmedPlaybackEvents<K> {
    private val confirmed = HashMap<K, Long>()
    fun confirm(key: K, now: Long): Long = confirmed.getOrPut(key) { now }
    fun retain(present: Set<K>) { confirmed.keys.retainAll(present) }
    fun removeMatching(predicate: (K) -> Boolean) { confirmed.keys.removeAll(predicate) }
    fun clear() = confirmed.clear()
}

/** UI-owned, bounded session history. Undo actions may update state without recording another action. */
class UndoHistory(private val capacity: Int = 20) {
    private data class Entry(val label: String, val action: () -> Unit)
    private val entries = ArrayDeque<Entry>()
    private var applying = false
    fun add(label: String, action: () -> Unit) {
        if (applying || capacity <= 0) return
        entries.addLast(Entry(label, action))
        while (entries.size > capacity) entries.removeFirst()
    }
    fun canUndo() = entries.isNotEmpty()
    fun label(): String? = entries.lastOrNull()?.label
    fun clear() = entries.clear()
    fun undo(): String? {
        val entry = entries.lastOrNull() ?: return null
        applying = true
        try {
            entry.action()
            entries.removeLast()
            return entry.label
        } finally { applying = false }
    }
}
