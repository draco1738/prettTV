package fi.jommi.rivi

/**
 * Pure home-screen logic (no Android types) so it can be unit-tested on the JVM.
 * Keys are "package/activity" component strings.
 */
object HomeModel {
    class Item(val key: String, val packageName: String, val label: String)
    class Arranged(val favourites: List<String>, val rest: List<String>)

    /** First-run favourites: the preferred streaming apps that are actually installed, in preferred order. */
    fun initialFavourites(preferredPackages: List<String>, installed: List<Item>): List<String> =
        preferredPackages.mapNotNull { p -> installed.firstOrNull { it.packageName == p }?.key }

    /**
     * Favourites keep the user's order; entries whose app is missing or hidden are skipped (but stay
     * in the saved list, so they return if the app is reinstalled). Everything else goes to the grid,
     * most recently launched first, then alphabetical. [trailing] keys (e.g. "inputs") go last.
     */
    fun arrange(installed: List<Item>, favourites: List<String>, hidden: Set<String>,
                recent: Map<String, Long>, trailing: List<String> = emptyList()): Arranged {
        val known = installed.map { it.key }.toHashSet().apply { addAll(trailing) }
        val favs = favourites.distinct().filter { it in known && it !in hidden }
        val favSet = favs.toHashSet()
        val rest = installed.filter { it.key !in favSet && it.key !in hidden }
            .sortedWith(compareByDescending<Item> { recent[it.key] ?: 0L }.thenBy { it.label.lowercase() }.thenBy { it.key })
            .map { it.key }
        return Arranged(favs, rest + trailing.filter { it !in hidden && it !in favSet })
    }

    /** Move [key] by [delta] positions inside [list]; out-of-range moves clamp. Returns a new list. */
    fun move(list: List<String>, key: String, delta: Int): List<String> {
        val i = list.indexOf(key); if (i < 0) return list
        val j = (i + delta).coerceIn(0, list.size - 1); if (i == j) return list
        return list.toMutableList().apply { removeAt(i); add(j, key) }
    }

    fun parseRecent(s: String?): Map<String, Long> =
        s?.split('\n')?.mapNotNull {
            val i = it.lastIndexOf('='); if (i <= 0) null else (it.substring(0, i) to (it.substring(i + 1).toLongOrNull() ?: return@mapNotNull null))
        }?.toMap() ?: emptyMap()

    /** Record a launch and keep only the [keep] most recent entries. */
    fun touchRecent(current: Map<String, Long>, key: String, now: Long, keep: Int = 24): String =
        (current + (key to now)).entries.sortedByDescending { it.value }.take(keep).joinToString("\n") { "${it.key}=${it.value}" }

    /** Which tile should get focus: the wanted key if still present, else first favourite, else first grid tile. */
    fun focusTarget(wanted: String?, a: Arranged): String? =
        wanted?.takeIf { it in a.favourites || it in a.rest } ?: a.favourites.firstOrNull() ?: a.rest.firstOrNull()

    /** Keep the same item after a reorder; after removal prefer the next surviving neighbor. */
    fun focusAfterChange(wanted:String,before:List<String>,after:List<String>):String? {
        if(wanted in after)return wanted
        val index=before.indexOf(wanted)
        if(index<0)return after.firstOrNull()
        val remaining=after.toHashSet()
        return before.drop(index+1).firstOrNull { it in remaining }
            ?: before.take(index).lastOrNull { it in remaining }
            ?: after.firstOrNull()
    }

    /** Disk-cache files that no longer correspond to an installed app version can be deleted. */
    fun staleCacheFiles(existing: List<String>, wanted: Set<String>): List<String> = existing.filter { it !in wanted }
}
