package fi.jommi.rivi

/** Worker-owned last successful catalog results. A refresh must not publish empty rows first. */
internal data class CatalogSnapshot(
    val seeds: List<Program> = emptyList(),
    val popular: Map<String, List<Program>> = emptyMap(),
    val releases: Map<String, List<Program>> = emptyMap()
) {
    fun eligible(enabled: Boolean, available: Set<String>, hidden: Set<String>): CatalogSnapshot {
        fun keep(rows: List<Program>) = if (enabled) rows.filter { it.packageName in available && it.packageName !in hidden } else emptyList()
        return copy(seeds = keep(seeds), popular = popular.mapValues { keep(it.value) }, releases = releases.mapValues { keep(it.value) })
    }

    fun updated(type: String, top: List<Program>?, recent: List<Program>?) = copy(
        popular = if (top == null) popular else popular + (type to top),
        releases = if (recent == null) releases else releases + (type to recent)
    )

    fun browse() = (0 until 100).flatMap { i -> listOf("series", "movie").mapNotNull { popular[it]?.getOrNull(i) } }
    fun recent(now: Long) = ContentPolicy.recentReleases(releases.values.flatten(), now)
}
