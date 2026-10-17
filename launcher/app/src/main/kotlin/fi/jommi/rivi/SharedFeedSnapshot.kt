package fi.jommi.rivi

/** Null is a failed read, not a provider's deliberate empty result. */
internal data class SharedFeedSnapshot(
    val published: List<Program> = emptyList(),
    val watching: List<Program> = emptyList(),
    val channels: Map<Long,String> = emptyMap()
) {
    fun updated(published: List<Program>?, watching: List<Program>?, channels: Map<Long,String>?) = copy(
        published=published ?: this.published, watching=watching ?: this.watching, channels=channels ?: this.channels)
    fun eligible(available:Set<String>) = copy(
        published=published.filter { it.packageName in available },watching=watching.filter { it.packageName in available })
}
