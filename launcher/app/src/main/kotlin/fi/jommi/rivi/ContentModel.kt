package fi.jommi.rivi

import java.util.Locale

/** A published program or a general catalog suggestion; never invented viewing history. */
data class Program(
    val id: String, val title: String, val detail: String, val art: String,
    val packageName: String, val app: String, val intentUri: String = "",
    val position: Long = 0, val duration: Long = 0,
    val catalog: Boolean = false, val sourceUrl: String = "",
    val summary: String = "",
    val watchingSource: Boolean = false, val sourceName: String = "",
    val released: Long = 0, val detailUri: String = "",
    val engagedAt: Long = 0, val mediaType: String = "unknown",val liveSports:Boolean=false,
) {
    val titleKey: String get() = title.trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    val progress: Float get() = if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat() else 0f
    val resume: Boolean get() = !catalog && position > 0 && duration > position
}

data class HomeContent(val watching: List<Program> = emptyList(), val suggestions: List<Program> = emptyList(),
    val trending: List<Program> = emptyList(), val newReleases: List<Program> = emptyList(),
    val hiddenHeroTitles:Set<String> = emptySet(),val hiddenWatchingTitles:Set<String> = emptySet(),val liveSports:List<Program> = emptyList(),
    val games:List<SportsEvent> = emptyList(),val discover:List<Program> = emptyList()) {
    val visibleWatching: List<Program> get() = watching.filter { it.titleKey !in hiddenWatchingTitles }
    val feature: Program? get() = watching.filter { it.titleKey !in hiddenHeroTitles }.maxByOrNull { it.engagedAt }
        ?: suggestions.firstOrNull { it.titleKey !in hiddenHeroTitles }
}

object ContentPolicy {
    fun discover(candidates:List<Program>,elsewhere:List<Program>,hidden:Set<String> = emptySet(),preferred:List<String> = emptyList()):List<Program> {
        val used=elsewhere.map { it.titleKey }.toSet()
        return prioritize(candidates,preferred).filter { it.title.isNotBlank() && it.packageName !in hidden && it.titleKey !in used }
            .distinctBy { it.titleKey }.take(60)
    }
    fun chronological(rows:List<Program>):List<Program> = rows.sortedByDescending { it.engagedAt.coerceAtLeast(0) }
    fun prioritize(rows:List<Program>,preferred:List<String> = emptyList()):List<Program> = rows.sortedWith(
        compareBy<Program> { it.packageName=="com.stremio.one" }
            .thenByDescending { it.resume }
            .thenBy { preferred.indexOf(it.packageName).let { n -> if(n<0)Int.MAX_VALUE else n } }
            .thenByDescending { it.engagedAt })
    fun recentReleases(rows:List<Program>,now:Long):List<Program> = rows.filter {
        it.released>0 && it.released<=now && now-it.released<=120L*86_400_000
    }.distinctBy { it.id }.sortedByDescending { it.released }.take(60)
    fun clean(rows: List<Program>, hiddenPackages: Set<String> = emptySet()): List<Program> =
        rows.filter { it.title.isNotBlank() && it.packageName !in hiddenPackages }
            .distinctBy { it.packageName + ":" + it.title.trim().lowercase(Locale.ROOT) }.take(60)

    fun combine(watching: List<Program>, previews: List<Program>, catalog: List<Program>, hidden: Set<String>): HomeContent {
        val w = clean(watching, hidden)
        val used = w.map { it.packageName + ":" + it.title.trim().lowercase(Locale.ROOT) }.toSet()
        val suggestions = clean(previews + catalog, hidden).filter { it.packageName + ":" + it.title.trim().lowercase(Locale.ROOT) !in used }
        return HomeContent(w, suggestions)
    }
}

enum class HomeLayout(val label: String) {
    CINEMA("A · Cinema"), QUIET("B · Quiet"), BALANCED("C · Balanced");
    companion object { fun parse(value: String?) = entries.firstOrNull { it.name == value } ?: BALANCED }
}
