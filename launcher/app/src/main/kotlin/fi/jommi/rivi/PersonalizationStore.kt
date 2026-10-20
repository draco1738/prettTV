package fi.jommi.rivi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Private, versioned state. Instances share one lock and always re-read current preferences. */
class PersonalizationStore(context: Context) {
    companion object {
        const val PREFS_NAME = "prett-personalization"
        private const val STATE_KEY = "state"
        private const val VERSION = 1
        private val stateLock = Any()
        private const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
        const val MAX_FOLLOWED_SHOWS = 100

        /** Strict external-input validation; local reads remain tolerant of damaged records. */
        internal fun validateStateImport(raw: String) {
            require(raw.length <= MAX_IMPORT_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES) { "Personalization exceeds 8 MB" }
            val state = JSONObject(raw)
            val version = state.opt("version") ?: VERSION
            require(version is Int && version in 0..VERSION) { "Unsupported personalization version" }
            fun array(obj: JSONObject, key: String): JSONArray? =
                if (!obj.has(key)) null else (obj.opt(key) as? JSONArray ?: error("Invalid $key list"))
            fun objectValue(obj: JSONObject, key: String): JSONObject? =
                if (!obj.has(key)) null else (obj.opt(key) as? JSONObject ?: error("Invalid $key object"))
            fun strings(items: JSONArray?) {
                if (items != null) repeat(items.length()) { require(items.opt(it) is String) { "Expected text value" } }
            }
            fun number(obj: JSONObject, key: String) {
                if (obj.has(key)) require(obj.opt(key) is Int || obj.opt(key) is Long) { "Invalid $key number" }
            }
            fun program(obj: JSONObject) {
                val textFields = listOf("id","title","detail","art","package","app","intent","sourceUrl","summary","sourceName","detailUri","mediaType","genres","imdbId")
                textFields.forEach { if (obj.has(it)) require(obj.opt(it) is String) { "Invalid program $it" } }
                require((obj.opt("title") as? String)?.isNotBlank() == true && (obj.opt("package") as? String)?.isNotBlank() == true) { "Incomplete title" }
                listOf("position","duration","released","engagedAt","providerWeight","releaseYear").forEach { number(obj,it) }
                listOf("catalog","watchingSource","liveSports").forEach { if (obj.has(it)) require(obj.opt(it) is Boolean) { "Invalid program $it" } }
            }
            listOf("watchlist" to "savedAt", "followed" to "followedAt").forEach { (key, time) ->
                array(state,key)?.let { items ->
                    if (key=="followed") require(items.length()<=MAX_FOLLOWED_SHOWS) { "Follow up to 100 shows" }
                    repeat(items.length()) {
                        val item=items.optJSONObject(it) ?: error("Invalid saved title")
                        program(item.optJSONObject("program") ?: error("Missing saved title"))
                        number(item,time)
                    }
                }
            }
            array(state,"dismissed")?.let { items -> repeat(items.length()) {
                val item=items.optJSONObject(it) ?: error("Invalid recommendation feedback")
                require(item.opt("key") is String); strings(array(item,"genres"))
                if (item.has("titleKey")) require(item.opt("titleKey") is String) { "Invalid dismissed title" }
            } }
            objectValue(state,"rows")?.let { rows ->
                strings(array(rows,"order")); strings(array(rows,"hidden"))
                objectValue(rows,"sizes")?.let { sizes -> sizes.keys().forEach { require(sizes.opt(it) is String) } }
                objectValue(rows,"limits")?.let { limits -> limits.keys().forEach { number(limits,it) } }
            }
            strings(array(state,"teams"))
            if (state.has("reminders")) require(state.opt("reminders") is Boolean)
            objectValue(state,"consumed")?.let { consumed -> consumed.keys().forEach { number(consumed,it) } }
        }
    }
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val playback = context.getSharedPreferences("prett-playback", Context.MODE_PRIVATE)
    private val history = UndoHistory()
    private var cachedRaw: String? = null
    private var cachedState: State? = null
    var lastError: String? = null
        private set
    private data class State(
        val saved: List<SavedTitle> = emptyList(),
        val followed: List<FollowedShow> = emptyList(),
        val dismissed: List<DismissedRecommendation> = emptyList(),
        val rows: RowPreferences = RowPreferences(),
        val teams: Set<String> = emptySet(),
        val reminders: Boolean = false,
        val consumed: Map<String, Long> = emptyMap(),
    )

    fun watchlist(): List<Program> = read().saved.sortedByDescending { it.savedAt }.map { it.program }
    fun isSaved(program: Program) = read().saved.any { it.key == ContentIdentity.key(program) }
    fun followedShows(): List<FollowedShow> = read().followed.sortedByDescending { it.followedAt }
    fun isFollowed(program: Program) = read().followed.any { it.key == ContentIdentity.key(program) }
    fun rowPreferences() = read().rows
    fun recommendationFeedback() = PersonalizationPolicy.feedback(read().dismissed)
    /** Migration only: supply raw feed rows before recommendation exclusions are applied. */
    fun rememberDismissedTitles(programs: List<Program>) {
        if (programs.isEmpty() || read().dismissed.all { it.titleKey.isNotBlank() }) return
        update { state -> state.copy(dismissed = PersonalizationPolicy.rememberDismissedTitles(state.dismissed, programs)) }
    }
    fun favoriteTeams() = read().teams
    fun kickoffRemindersEnabled() = read().reminders
    fun canUndo() = history.canUndo()
    fun undoLabel() = history.label()
    fun undo(): String? = try { history.undo() } catch (_: IllegalStateException) { null }
    fun clearUndoHistory() = history.clear()
    fun recordUndo(label: String, action: () -> Unit) = history.add(label, action)

    fun save(program: Program, now: Long = System.currentTimeMillis()): Boolean {
        lastError = null
        if (!valid(program)) { lastError = "This title has no usable app destination."; return false }
        if (programTextLength(program) > MAX_IMPORT_BYTES) {
            lastError = "This title's metadata exceeds the Watchlist storage limit."
            return false
        }
        val key = ContentIdentity.key(program)
        update { state ->
            if (state.saved.any { it.key == key }) state
            else state.copy(saved = state.saved + SavedTitle(key, program, now.coerceAtLeast(0)))
        } ?: return false
        history.add("Add to Watchlist") { update { it.copy(saved = it.saved.filterNot { item -> item.key == key }) } }
        return true
    }

    fun removeSaved(program: Program): Boolean {
        val key = ContentIdentity.key(program)
        val change = update { it.copy(saved = it.saved.filterNot { item -> item.key == key }) } ?: return false
        val removed = change.first.saved.filter { it.key == key }
        history.add("Remove from Watchlist") { restoreSaved(removed) }
        return true
    }

    /** Only our confirmed played: records count; provider timestamps are never evidence. */
    fun consumePlaybackHistory(rows: List<Program>): Int {
        val saved = read().saved
        val candidates = (rows + saved.map { it.program }).distinctBy { it.packageName + "|" + it.titleKey }
        val evidence = candidates.mapNotNull { program ->
            val stamp = try {
                val key = program.packageName + "|" + program.titleKey
                playback.getLong("event:$key", playback.getLong("played:$key", 0))
            }
                catch (_: ClassCastException) { 0 }
            if (stamp > 0) program to stamp else null
        }
        return consume(evidence)
    }

    fun consumeConfirmedPlayback(program: Program, eventAt: Long): Int = consume(listOf(program to eventAt))

    private fun consume(evidence: List<Pair<Program, Long>>): Int {
        val change = update { state ->
            val removed = state.saved.mapNotNull { saved ->
                evidence.filter { (program, stamp) ->
                    PersonalizationPolicy.canConsume(saved, program, stamp, state.consumed[saved.key] ?: 0)
                }.maxOfOrNull { it.second }?.let { saved.key to it }
            }.toMap()
            if (removed.isEmpty()) state else state.copy(
                saved = state.saved.filterNot { it.key in removed }, consumed = state.consumed + removed,
            )
        } ?: return 0
        val remaining = change.second.saved.map { it.key }.toSet()
        val removed = change.first.saved.filter { it.key !in remaining }
        history.add("Watched title removed from Watchlist") { restoreSaved(removed) }
        return removed.size
    }

    private fun restoreSaved(items: List<SavedTitle>) {
        val changed = update { state -> state.copy(saved = (state.saved + items).distinctBy { it.key }) }
        check(changed != null || items.all { item -> read().saved.any { it.key == item.key } }) { "Unable to restore Watchlist" }
        // Retaining consumed timestamps prevents the same event from removing an undone item.
    }

    fun follow(program: Program, now: Long = System.currentTimeMillis()): Boolean {
        lastError = null
        if (!valid(program) || program.mediaType != "series") { lastError = "Only identified shows can be followed."; return false }
        val key = ContentIdentity.key(program)
        update { state ->
            if (state.followed.any { it.key == key }) state
            else if (state.followed.size >= MAX_FOLLOWED_SHOWS) {
                lastError = "You can follow up to 100 shows. Unfollow a show before adding another."
                state
            }
            else state.copy(followed = state.followed + FollowedShow(key, program, now.coerceAtLeast(0)))
        } ?: return false
        history.add("Follow show") { update { it.copy(followed = it.followed.filterNot { item -> item.key == key }) } }
        return true
    }

    fun unfollow(program: Program) = unfollow(ContentIdentity.key(program))
    fun unfollow(key: String): Boolean {
        val change = update { it.copy(followed = it.followed.filterNot { item -> item.key == key }) } ?: return false
        val removed = change.first.followed.filter { it.key == key }
        history.add("Unfollow show") { update { it.copy(followed = (it.followed + removed).distinctBy { item -> item.key }) } }
        return true
    }

    fun dismissRecommendation(program: Program) {
        val key = ContentIdentity.key(program)
        update { state ->
            if (state.dismissed.any { it.key == key }) state
            else state.copy(dismissed = state.dismissed + DismissedRecommendation(key, PersonalizationPolicy.genres(program), program.titleKey))
        } ?: return
        history.add("Not interested") { update { it.copy(dismissed = it.dismissed.filterNot { item -> item.key == key }) } }
    }

    fun resetRecommendationFeedback() {
        val change = update { it.copy(dismissed = emptyList()) } ?: return
        history.add("Reset recommendation feedback") { update { it.copy(dismissed = change.first.dismissed) } }
    }

    fun setRowPreferences(value: RowPreferences) {
        val change = update { it.copy(rows = value.normalized()) } ?: return
        history.add("Change rows") { update { it.copy(rows = change.first.rows) } }
    }

    fun setFavoriteTeams(value: Set<String>) {
        val clean = value.filter { it.isNotBlank() && it.length <= 160 }.toSet()
        val change = update { it.copy(teams = clean) } ?: return
        history.add("Change favorite teams") { update { it.copy(teams = change.first.teams) } }
    }

    fun setKickoffRemindersEnabled(value: Boolean) {
        val change = update { it.copy(reminders = value) } ?: return
        history.add("Change kickoff reminders") { update { it.copy(reminders = change.first.reminders) } }
    }

    /** Export contains preferences, never recovery keys or streaming authentication. */
    fun exportJson(): String = synchronized(stateLock) { encode(readLocked()).toString() }
    fun importJson(raw: String): Boolean = synchronized(stateLock) {
        lastError = null
        if (!withinLimit(raw)) { lastError = "Settings file exceeds the 8 MB limit."; return@synchronized false }
        val parsed = try { validateStateImport(raw); decode(JSONObject(raw)) } catch (_: Exception) { null } ?: return@synchronized false
        val encoded = encode(parsed).toString()
        if (!withinLimit(encoded)) { lastError = "Settings file exceeds the 8 MB limit."; return@synchronized false }
        write(encoded, parsed)
        history.clear()
        true
    }

    private fun valid(program: Program) = program.title.isNotBlank() && program.packageName.isNotBlank()
    private fun programTextLength(p: Program) = listOf(p.id,p.title,p.detail,p.art,p.packageName,p.app,p.intentUri,
        p.sourceUrl,p.summary,p.sourceName,p.detailUri,p.mediaType,p.genres,p.imdbId).sumOf { it.length.toLong() }
    private fun read() = synchronized(stateLock) { readLocked() }
    private fun readLocked(): State = try {
        val raw = prefs.getString(STATE_KEY, "{}") ?: "{}"
        if (raw == cachedRaw && cachedState != null) cachedState!!
        else (decode(JSONObject(raw)) ?: State()).also { cachedRaw = raw; cachedState = it }
    } catch (_: Exception) { State() }
    private fun update(change: (State) -> State): Pair<State, State>? = synchronized(stateLock) {
        // A future version must not be silently overwritten by an older installed build.
        val raw = try { JSONObject(prefs.getString(STATE_KEY, "{}") ?: "{}") } catch (_: Exception) { JSONObject() }
        lastError = null
        if (raw.optInt("version", VERSION) > VERSION) {
            lastError = "These preferences were saved by a newer prettTV version."
            return@synchronized null
        }
        val previous = readLocked()
        val next = change(previous)
        if (next == previous) return@synchronized null
        val encoded = encode(next).toString()
        if (!withinLimit(encoded)) {
            lastError = "Watchlist storage is full. Remove a saved title before adding another."
            return@synchronized null
        }
        write(encoded, next)
        previous to next
    }
    private fun withinLimit(raw: String) = raw.length <= MAX_IMPORT_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_IMPORT_BYTES
    private fun write(raw: String, state: State) {
        prefs.edit().putString(STATE_KEY, raw).apply()
        cachedRaw = raw
        cachedState = state
    }

    private fun encode(state: State) = JSONObject().put("version", VERSION)
        .put("watchlist", JSONArray().apply { state.saved.forEach { put(JSONObject().put("program", programJson(it.program)).put("savedAt", it.savedAt)) } })
        .put("followed", JSONArray().apply { state.followed.forEach { put(JSONObject().put("program", programJson(it.program)).put("followedAt", it.followedAt)) } })
        .put("dismissed", JSONArray().apply { state.dismissed.forEach {
            put(JSONObject().put("key", it.key).put("genres", JSONArray(it.genres.toList())).put("titleKey", PersonalizationPolicy.dismissedTitleKey(it)))
        } })
        .put("rows", JSONObject().put("order", JSONArray(state.rows.order)).put("hidden", JSONArray(state.rows.hidden.toList()))
            .put("sizes", JSONObject().apply { state.rows.sizes.forEach { (key, size) -> put(key, size.name) } })
            .put("limits", JSONObject().apply { state.rows.limits.forEach { (key, count) -> put(key, count) } }))
        .put("teams", JSONArray(state.teams.toList())).put("reminders", state.reminders)
        .put("consumed", JSONObject().apply { state.consumed.forEach { (key, stamp) -> put(key, stamp) } })

    private fun decode(json: JSONObject): State? {
        if (json.optInt("version", VERSION) !in 0..VERSION) return null
        val rows = json.optJSONObject("rows") ?: JSONObject()
        val sizes = rows.optJSONObject("sizes") ?: JSONObject()
        val limits = rows.optJSONObject("limits") ?: JSONObject()
        val consumed = json.optJSONObject("consumed") ?: JSONObject()
        return State(
            saved = objects(json.optJSONArray("watchlist")).mapNotNull { item ->
                parseProgram(item.optJSONObject("program"))?.let { SavedTitle(ContentIdentity.key(it), it, item.optLong("savedAt").coerceAtLeast(0)) }
            }.distinctBy { it.key },
            followed = objects(json.optJSONArray("followed")).mapNotNull { item ->
                parseProgram(item.optJSONObject("program"))?.takeIf { it.mediaType == "series" }?.let {
                    FollowedShow(ContentIdentity.key(it), it, item.optLong("followedAt").coerceAtLeast(0))
                }
            }.distinctBy { it.key },
            dismissed = objects(json.optJSONArray("dismissed")).mapNotNull { item ->
                item.optString("key").takeIf { it.isNotBlank() }?.let {
                    DismissedRecommendation(it, strings(item.optJSONArray("genres")).toSet(), item.opt("titleKey") as? String ?: "")
                }?.let { it.copy(titleKey = PersonalizationPolicy.dismissedTitleKey(it)) }
            }.distinctBy { it.key },
            rows = RowPreferences(strings(rows.optJSONArray("order")), strings(rows.optJSONArray("hidden")).toSet(),
                sizes.keys().asSequence().mapNotNull { key -> RowCardSize.entries.firstOrNull { it.name == sizes.optString(key) }?.let { key to it } }.toMap(),
                limits.keys().asSequence().associateWith { limits.optInt(it) }).normalized(),
            teams = strings(json.optJSONArray("teams")).filter { it.isNotBlank() && it.length <= 160 }.toSet(),
            reminders = json.optBoolean("reminders", false),
            consumed = consumed.keys().asSequence().associateWith { consumed.optLong(it).coerceAtLeast(0) },
        )
    }

    private fun strings(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.opt(it) as? String }
    private fun objects(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
    private fun programJson(p: Program) = JSONObject().put("id", p.id).put("title", p.title).put("detail", p.detail).put("art", p.art)
        .put("package", p.packageName).put("app", p.app).put("intent", p.intentUri).put("position", p.position).put("duration", p.duration)
        .put("catalog", p.catalog).put("sourceUrl", p.sourceUrl).put("summary", p.summary).put("watchingSource", p.watchingSource)
        .put("sourceName", p.sourceName).put("released", p.released).put("detailUri", p.detailUri).put("engagedAt", p.engagedAt)
        .put("mediaType", p.mediaType).put("liveSports", p.liveSports).put("genres", p.genres).put("providerWeight", p.providerWeight)
        .put("releaseYear", p.releaseYear).put("imdbId", p.imdbId)
    private fun parseProgram(j: JSONObject?): Program? {
        if (j == null) return null
        fun string(name: String) = if (j.isNull(name)) "" else j.optString(name)
        return Program(string("id"), string("title"), string("detail"), string("art"), string("package"), string("app"),
            intentUri = string("intent"), position = j.optLong("position").coerceAtLeast(0), duration = j.optLong("duration").coerceAtLeast(0),
            catalog = j.optBoolean("catalog"), sourceUrl = string("sourceUrl"), summary = string("summary"), watchingSource = j.optBoolean("watchingSource"),
            sourceName = string("sourceName"), released = j.optLong("released"), detailUri = string("detailUri"), engagedAt = j.optLong("engagedAt"),
            mediaType = string("mediaType").ifBlank { "unknown" }, liveSports = j.optBoolean("liveSports"), genres = string("genres"), providerWeight = j.optLong("providerWeight"),
            releaseYear = j.optInt("releaseYear"), imdbId = string("imdbId")).takeIf(::valid)
    }
}
