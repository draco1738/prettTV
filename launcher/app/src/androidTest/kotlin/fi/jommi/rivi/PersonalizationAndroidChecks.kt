package fi.jommi.rivi

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Store tests require Android's real JSON and SharedPreferences implementation. */
internal object PersonalizationAndroidChecks {
    fun run(context: Context, test: (String, () -> Unit) -> Unit) {
        check(context.packageName == "dev.zane.pretttv.stability")
        val prefs = context.getSharedPreferences(PersonalizationStore.PREFS_NAME, Context.MODE_PRIVATE)
        val playback = context.getSharedPreferences("prett-playback", Context.MODE_PRIVATE)
        val original = prefs.getString("state", null)
        val previousPlayback = playback.all
        fun card(title: String = "Fixture", pkg: String = "fixture.provider") = Program(
            "preview:fixture", title, "Drama", "https://example.invalid/art", pkg, "Fixture app",
            intentUri = "fixture://title", mediaType = "series", genres = "Drama", summary = "A summary", releaseYear = 2026,
        )
        fun fresh(): PersonalizationStore {
            check(prefs.edit().clear().commit())
            check(playback.edit().clear().commit())
            return PersonalizationStore(context)
        }
        try {
            test("Watchlist preserves full source snapshot and newest-first ordering across instances") {
                val store = fresh()
                val first = card()
                val second = card("Second").copy(mediaType = "movie", imdbId = "tt7654321", position = 23, duration = 400, detailUri = "fixture://details")
                check(store.save(first, 100) && store.save(second, 200))
                check(!store.save(first, 300))
                check(PersonalizationStore(context).watchlist() == listOf(second, first))
                check(store.follow(first, 150))
                check(!store.follow(second))
                check(PersonalizationStore(context).followedShows().single().program == first)
            }
            test("Only new confirmed playback removes Watchlist and undo survives refresh and restart") {
                val store = fresh()
                val program = card()
                store.save(program, 100)
                check(store.consumePlaybackHistory(listOf(program.copy(engagedAt = 9999))) == 0)
                check(playback.edit().putLong("played:fixture.provider|fixture", 99).commit())
                check(store.consumePlaybackHistory(listOf(program)) == 0)
                check(playback.edit().putLong("played:fixture.provider|fixture", 101).putLong("event:fixture.provider|fixture", 101).commit())
                check(store.consumePlaybackHistory(listOf(program)) == 1)
                check(store.watchlist().isEmpty())
                check(store.undo() != null && store.isSaved(program))
                check(playback.edit().putLong("played:fixture.provider|fixture", 500).commit())
                check(store.consumePlaybackHistory(listOf(program)) == 0)
                check(PersonalizationStore(context).consumePlaybackHistory(listOf(program)) == 0)
                check(playback.edit().putLong("played:fixture.provider|fixture", 501).putLong("event:fixture.provider|fixture", 501).commit())
                check(store.consumePlaybackHistory(listOf(program)) == 1)
            }
            test("State round-trip preserves rows favorites follows feedback and consumed evidence") {
                val store = fresh()
                val program = card()
                store.save(program, 100)
                store.follow(program, 100)
                store.dismissRecommendation(program)
                store.setRowPreferences(RowPreferences(order = listOf("watchlist"), hidden = setOf("sports"),
                    sizes = mapOf("watchlist" to RowCardSize.LARGE), limits = mapOf("discover" to 40)))
                store.setFavoriteTeams(setOf("football/nfl:33"))
                store.setKickoffRemindersEnabled(true)
                val exported = store.exportJson()
                val recreated = fresh()
                check(recreated.importJson(exported))
                check(recreated.watchlist() == listOf(program))
                check(recreated.isFollowed(program))
                check(recreated.recommendationFeedback().isDismissed(program))
                check(recreated.recommendationFeedback().dismissedTitleKeys == setOf(program.titleKey))
                check(recreated.rowPreferences().order.first() == "watchlist")
                check(recreated.rowPreferences().size("watchlist") == RowCardSize.LARGE)
                check(recreated.favoriteTeams() == setOf("football/nfl:33") && recreated.kickoffRemindersEnabled())
                check(!recreated.canUndo())
                val before = recreated.exportJson()
                check(!recreated.importJson("{broken") && !recreated.importJson("""{"version":999}"""))
                check(recreated.exportJson() == before)
            }
            test("Dismissed title exclusions survive restart reset undo and malformed imports") {
                val store = fresh()
                val p = card("Hidden across providers").copy(imdbId = "tt1234567")
                store.dismissRecommendation(p)
                check(PersonalizationStore(context).recommendationFeedback().dismissedTitleKeys == setOf(p.titleKey))
                store.resetRecommendationFeedback()
                check(store.recommendationFeedback().dismissedTitleKeys.isEmpty())
                check(store.undo() != null)
                check(store.recommendationFeedback().dismissedTitleKeys == setOf(p.titleKey))
                val before = store.exportJson()
                val invalid = JSONObject(before).apply { getJSONArray("dismissed").getJSONObject(0).put("titleKey", 42) }
                check(!store.importJson(invalid.toString()) && store.exportJson() == before)
                check(store.undo() != null)
                check(store.recommendationFeedback().dismissed.isEmpty())
                check(store.recommendationFeedback().dismissedTitleKeys.isEmpty())
            }
            test("Legacy dismissals recover provider titles and migrate exact canonical feed matches without undo entries") {
                val store = fresh()
                val provider = card("Legacy provider")
                val canonical = card("Legacy canonical").copy(imdbId = "tt1234567")
                val legacy = JSONObject().put("version", 1).put("dismissed", JSONArray().apply {
                    listOf(provider, canonical).forEach { put(JSONObject().put("key", ContentIdentity.key(it)).put("genres", JSONArray())) }
                })
                check(store.importJson(legacy.toString()))
                check(store.recommendationFeedback().dismissedTitleKeys == setOf(provider.titleKey))
                store.rememberDismissedTitles(listOf(canonical.copy(imdbId = "tt1234568")))
                check(store.recommendationFeedback().dismissedTitleKeys == setOf(provider.titleKey))
                store.rememberDismissedTitles(listOf(canonical))
                check(PersonalizationStore(context).recommendationFeedback().dismissedTitleKeys == setOf(provider.titleKey, canonical.titleKey))
                check(!store.canUndo())
                val exported = store.exportJson()
                val recreated = fresh()
                check(recreated.importJson(exported))
                check(recreated.recommendationFeedback().dismissedTitleKeys == setOf(provider.titleKey, canonical.titleKey))
            }
            test("Malformed stored state recovers and future state is never overwritten") {
                val store = fresh()
                check(prefs.edit().putString("state", "{broken").commit())
                check(store.watchlist().isEmpty())
                check(store.save(card(), 100))
                val future = JSONObject(store.exportJson()).put("version", 999).toString()
                check(prefs.edit().putString("state", future).commit())
                check(!store.save(card("Another"), 200))
                check(prefs.getString("state", "") == future)
            }
            test("Oversize writes reject the new title without discarding the existing Watchlist") {
                val store = fresh()
                val program = card()
                check(store.save(program, 100))
                check(!store.save(card("Too large").copy(summary = "x".repeat(8 * 1024 * 1024)), 200))
                check(store.lastError != null)
                check(PersonalizationStore(context).watchlist() == listOf(program))
            }
            test("Following rejects the 101st show with an explanation and keeps all saved follows") {
                val store = fresh()
                repeat(100) { check(store.follow(card("Follow $it"), it.toLong())) }
                check(!store.follow(card("Follow 101"), 101))
                check(store.lastError?.contains("100") == true)
                check(PersonalizationStore(context).followedShows().size == 100)
                check(store.unfollow(card("Follow 0")) && store.follow(card("Replacement"), 102))
                check(store.followedShows().size == 100)
            }
            val transferStores = linkedMapOf(
                "home" to context.getSharedPreferences("transfer-test-home", Context.MODE_PRIVATE),
                "apps" to context.getSharedPreferences("transfer-test-apps", Context.MODE_PRIVATE),
                "playback" to context.getSharedPreferences("transfer-test-playback", Context.MODE_PRIVATE),
                PersonalizationStore.PREFS_NAME to prefs,
            )
            fun transferFixture() {
                fresh().save(card(), 100)
                transferStores.filterKeys { it != PersonalizationStore.PREFS_NAME }.forEach { (name, values) ->
                    check(values.edit().clear().putString("fixture", name).putBoolean("enabled", true).commit())
                }
            }
            test("Legacy settings exports restore known stores and reset new personalization defaults") {
                transferFixture()
                val legacy = LocalSettingsTransfer.export(transferStores.filterKeys { it != PersonalizationStore.PREFS_NAME })
                transferStores.getValue("home").edit().putString("fixture", "changed").commit()
                LocalSettingsTransfer.restore(legacy, transferStores)
                check(transferStores.getValue("home").getString("fixture", "") == "home")
                check(transferStores.getValue("apps").getString("fixture", "") == "apps")
                check(prefs.all.isEmpty())
                check(PersonalizationStore(context).watchlist().isEmpty())
                check(PersonalizationStore(context).rowPreferences() == RowPreferences())
            }
            test("Malformed settings and personalization are rejected before any store changes") {
                transferFixture()
                val valid = JSONObject(String(Base64.decode(LocalSettingsTransfer.export(transferStores), Base64.DEFAULT), Charsets.UTF_8))
                val bad = ArrayList<JSONObject>()
                fun copy() = JSONObject(valid.toString())
                bad += copy().put("version", 2)
                bad += copy().put("version", "1")
                bad += copy().put(PersonalizationStore.PREFS_NAME, "not an object")
                bad += copy().put(PersonalizationStore.PREFS_NAME, JSONObject.NULL)
                bad += copy().put("home", JSONArray())
                bad += copy().put("stock-recovery", JSONObject().put("secret", "must not import"))
                bad += copy().apply { getJSONObject("home").put("fixture", JSONObject().put("type", "string").put("value", 123)) }
                bad += copy().apply { getJSONObject("home").put("enabled", JSONObject().put("type", "boolean").put("value", "true")) }
                bad += copy().apply { getJSONObject("apps").put("overflow", JSONObject().put("type", "int").put("value", Long.MAX_VALUE)) }
                bad += copy().apply { getJSONObject("apps").put("not-strings", JSONObject().put("type", "set").put("value", JSONArray().put(42))) }
                bad += copy().apply { getJSONObject("home").put("layout", JSONObject().put("type", "int").put("value", 1)) }
                bad += copy().apply { getJSONObject("home").put("discovery", JSONObject().put("type", "string").put("value", "false")) }
                bad += copy().apply { getJSONObject("home").put("hiddenTitleLabel:fixture", JSONObject().put("type", "boolean").put("value", true)) }
                bad += copy().apply { getJSONObject("home").put("preferredServices", JSONObject().put("type", "string").put("value", "fixture")) }
                bad += copy().apply { getJSONObject("apps").put("fav_init", JSONObject().put("type", "int").put("value", 1)) }
                bad += copy().apply { getJSONObject("apps").put("fav", JSONObject().put("type", "set").put("value", JSONArray().put("fixture"))) }
                bad += copy().apply { getJSONObject("playback").put("played:fixture|title", JSONObject().put("type", "int").put("value", 1)) }
                bad += copy().apply { getJSONObject("playback").put("event:fixture|title", JSONObject().put("type", "string").put("value", "1")) }
                bad += copy().apply { getJSONObject("playback").put("known", JSONObject().put("type", "string").put("value", "{}")) }
                bad += copy().apply { getJSONObject(PersonalizationStore.PREFS_NAME).put("state", JSONObject().put("type", "boolean").put("value", true)) }
                for (state in listOf("""{"version":2}""", """{"version":1,"watchlist":"bad"}""",
                    """{"version":1,"reminders":"true"}""", """{"version":1,"followed":[{"program":false}]}""", "{broken")) {
                    bad += copy().apply { getJSONObject(PersonalizationStore.PREFS_NAME).put("state", JSONObject().put("type", "string").put("value", state)) }
                }
                val before = transferStores.mapValues { it.value.all }
                for (root in bad) {
                    val encoded = Base64.encodeToString(root.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    var rejected = false
                    try { LocalSettingsTransfer.restore(encoded, transferStores) } catch (_: Exception) { rejected = true }
                    check(rejected) { "Invalid restore was accepted" }
                    check(transferStores.mapValues { it.value.all } == before) { "Rejected restore changed preferences" }
                }
                var oversizedRejected = false
                try { LocalSettingsTransfer.restore("A".repeat(16 * 1024 * 1024 + 1), transferStores) }
                catch (_: IllegalArgumentException) { oversizedRejected = true }
                check(oversizedRejected && transferStores.mapValues { it.value.all } == before)
                var recoveryRejected = false
                try { LocalSettingsTransfer.export(mapOf("stock-recovery" to prefs)) }
                catch (_: IllegalArgumentException) { recoveryRejected = true }
                check(recoveryRejected)
            }
            test("Concurrent store instances do not lose independent saves") {
                val store = fresh()
                val complete = CountDownLatch(2)
                val failure = AtomicReference<Throwable>()
                repeat(2) { worker ->
                    Thread {
                        try { repeat(20) { index -> check(PersonalizationStore(context).save(card("Concurrent $worker $index"), 100L + index)) } }
                        catch (t: Throwable) { failure.set(t) }
                        finally { complete.countDown() }
                    }.start()
                }
                check(complete.await(10, TimeUnit.SECONDS))
                failure.get()?.let { throw it }
                check(store.watchlist().size == 40)
            }
        } finally {
            check(prefs.edit().clear().apply { if (original != null) putString("state", original) }.commit())
            val edit = playback.edit().clear()
            previousPlayback.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(edit.commit())
        }
    }
}
