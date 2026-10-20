package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random

/** Two on-demand, first-party Library apps. No launcher, account or background service changes. */
class DiscoveryActivity : Activity() {
    private val settings by lazy { getSharedPreferences("pretttv", MODE_PRIVATE) }
    private val personal by lazy { PersonalizationStore(this) }
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var repository: ContentRepository
    private lateinit var artwork: ArtworkLoader
    private lateinit var appRepository: AppRepository
    private lateinit var scroll: ScrollView
    private lateinit var page: LinearLayout
    private var app = OriginalApp.BLIND_PICK
    private var installed = emptyList<AppEntry>()
    private var home = HomeContent()
    private var candidates = emptyList<Program>()
    private var dismissedTitleKeys = emptySet<String>()
    private var filters = BlindPickFilters()
    private var picks = emptyList<BlindPickCard>()
    private val revealed = mutableSetOf<String>()
    private var roundSeed = Random.nextLong()
    private var rabbitSeed: Program? = null
    private val trail = ArrayList<Program>()
    private var relation = "all"
    private var choosing = false
    private var query = ""
    private var chooserPage = 0
    private var loaded = false
    private var lookupBusy = false
    private var lookupAttempted = false
    private var cacheHydrated = false
    private var cacheBusy = false
    private var metadata = emptyMap<String, TitleMetadata>()
    private var pendingFocus: String? = null
    private var dialog: AlertDialog? = null
    private val controls = LinkedHashMap<String, View>()
    private var scale = 1f
    private var renderGeneration = 0
    @Volatile private var active = false
    @Volatile private var generation = 0
    @Volatile private var lookup: DiscoveryMetadataRepository? = null
    private val ink = 0xFFF4F1E9.toInt()
    private val muted = 0xFFB2C0B5.toInt()
    private val ground = 0xFF0F1510.toInt()
    private fun px(value: Int) = (value * scale + .5f).toInt()
    private fun key(p: Program) = ContentIdentity.key(p)
    private fun blockedTitles() = settings.getStringSet("hiddenHeroTitles", emptySet()).orEmpty() +
        settings.getStringSet("hiddenWatchingTitles", emptySet()).orEmpty() + dismissedTitleKeys
    private fun blindBlockedTitles() = blockedTitles() + home.watching.map { it.titleKey }
    private fun preferred() = if (settings.contains("preferredServices"))
        settings.getStringSet("preferredServices", emptySet()).orEmpty().toSet()
        else RecommendationPolicy.inferredServices(home.watching)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = OriginalApp.parse(intent.getStringExtra("originalApp"))
        scale = resources.displayMetrics.widthPixels / 1920f
        appRepository = AppRepository(this)
        artwork = ArtworkLoader(this)
        repository = ContentRepository(this, ::receive)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(ground))
        render(false)
        window.decorView.post {
            if (!isDestroyed && Build.VERSION.SDK_INT >= 30) {
                window.decorView.windowInsetsController?.apply {
                    hide(WindowInsets.Type.systemBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        active = true
        // onStop cancels in-flight artwork. Rebind even when the refreshed catalog is unchanged.
        if (loaded) render()
        val token = ++generation
        worker.execute {
            val apps = try { appRepository.discover() } catch (_: Exception) { emptyList() }
            ui.post {
                if (!active || token != generation) return@post
                installed = apps
                val services = if (settings.contains("preferredServices"))
                    settings.getStringSet("preferredServices", emptySet()).orEmpty().toSet() else null
                repository.start(apps.map { it.packageName }.toSet(), emptySet(),
                    settings.getBoolean("discovery", true), services = services)
            }
        }
    }

    override fun onStop() {
        active = false
        generation++
        lookup?.close()
        lookup = null
        if (lookupBusy) lookupAttempted = false
        lookupBusy = false
        cacheBusy = false
        repository.stop()
        artwork.cancel()
        dialog?.dismiss()
        dialog = null
        super.onStop()
    }

    override fun onDestroy() {
        repository.close()
        artwork.close()
        worker.shutdownNow()
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        scale = resources.displayMetrics.widthPixels / 1920f
        render()
    }

    private fun receive(value: HomeContent) {
        if (!active) return
        home = value
        val feedback = personal.recommendationFeedback()
        val rows = value.visibleWatching + personal.watchlist() + value.suggestions +
            value.discover + value.trending + value.newReleases
        dismissedTitleKeys = feedback.dismissedTitleKeys +
            rows.filter { feedback.isDismissed(it) }.map { it.titleKey }
        val blocked = blockedTitles()
        val packages = installed.map { it.packageName }.toSet()
        candidates = rows
            .filter { it.title.isNotBlank() && !it.liveSports && it.packageName in packages &&
                it.titleKey !in blocked && !feedback.isDismissed(it) }
            .take(400)
        val first = !loaded
        loaded = true
        var choicesChanged = false
        if (app == OriginalApp.BLIND_PICK) {
            if (picks.size < 3 || picks.any { card -> card.program.titleKey in blindBlockedTitles() ||
                    candidates.none { key(it) == key(card.program) } }) {
                val before = picks
                drawPicks()
                choicesChanged = before != picks
            }
        } else if (rabbitSeed == null) {
            val requested = intent.getStringExtra("seed")
            rabbitSeed = candidates.firstOrNull { key(it) == requested }
                ?: candidates.firstOrNull { item -> value.visibleWatching.any { key(it) == key(item) } }
                ?: candidates.firstOrNull()
        }
        if (first || choicesChanged || picks.isEmpty() || app == OriginalApp.RABBIT_HOLE) render()
        if (!cacheHydrated && !cacheBusy && candidates.isNotEmpty()) hydrateMetadata()
        else if (!lookupAttempted) enrich()
    }

    private fun drawPicks() {
        picks = BlindPickPolicy.pick(candidates, filters, preferredServices = preferred(),
            blockedKeys = personal.recommendationFeedback().dismissed,
            blockedTitleKeys = blindBlockedTitles(), seed = roundSeed, metadata = metadata)
        revealed.retainAll(picks.map { key(it.program) }.toSet())
    }

    private fun changeFilters(next: BlindPickFilters) {
        filters = next
        revealed.clear()
        lookupAttempted = false
        drawPicks()
        render()
        enrich()
    }

    private fun render(preserve: Boolean = true) {
        val focus = pendingFocus ?: if (preserve) currentFocus?.tag as? String else null
        pendingFocus = null
        val oldScroll = if (preserve && ::scroll.isInitialized) scroll.scrollY else 0
        val visualToken = ++renderGeneration
        artwork.cancel()
        controls.clear()
        page = column().apply { setPadding(px(56), px(30), px(56), px(32)); setBackgroundColor(ground) }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(page)
        }
        setContentView(scroll)
        val header = row()
        header.addView(label(app.label, 48, bold = true), LinearLayout.LayoutParams(0, px(66), 1f))
        header.addView(button("Back", "back") { back() }, LinearLayout.LayoutParams(px(150), px(66)))
        page.addView(header)
        page.addView(label(if (app == OriginalApp.BLIND_PICK)
            "Choose the feeling. Reveal something worth watching."
            else "Follow a connection. Find your next story.", 26, muted), lp(44))
        if (app == OriginalApp.BLIND_PICK) renderBlind() else renderRabbit()
        page.addView(label(if (home.offline) "Saved catalog · offline" else
            "Your app listings and public catalog data · availability can vary", 21, muted), lp(42))
        scroll.post {
            if (visualToken != renderGeneration || isDestroyed) return@post
            (controls[focus] ?: controls.values.firstOrNull { it.tag != "back" } ?: controls["back"])?.requestFocus()
            scroll.scrollTo(0, oldScroll)
        }
    }

    private fun renderBlind() {
        chips(DiscoveryMediaType.entries.map { type ->
            Triple(when (type) { DiscoveryMediaType.BOTH -> "Movies + TV"; DiscoveryMediaType.MOVIE -> "Movies"; DiscoveryMediaType.SHOW -> "TV shows" },
                "type-$type", { changeFilters(filters.copy(mediaType = type)) })
        }, "type-${filters.mediaType}")
        chips(BlindPickMood.entries.map { mood ->
            Triple(mood.label, "mood-$mood", { changeFilters(filters.copy(mood = mood)) })
        }, "mood-${filters.mood}")
        chips(listOf(null, 30, 60, 120).map { minutes ->
            Triple(minutes?.let { "Up to $it min" } ?: "Any length", "time-$minutes",
                { changeFilters(filters.copy(maxMinutes = minutes)) })
        }, "time-${filters.maxMinutes}")
        page.addView(label("Uses runtimes reported by providers. Unknown runtimes only appear under Any length.", 22, muted), lp(40))
        if (!loaded) {
            empty("Loading your choices…", "Your app listings will appear here shortly.")
            return
        }
        if (picks.isEmpty()) empty(if (lookupBusy) "Checking available runtimes…" else "No matches for these filters",
            "Try another mood or Any length. Only titles with matching metadata qualify.")
        else {
            val strip = row()
            picks.forEachIndexed { index, pick ->
                val program = pick.program
                val id = key(program)
                val shown = id in revealed
                val tile = column().apply {
                    setPadding(px(26), px(24), px(26), px(20))
                    background = rounded(0xFF1C241E.toInt(), 0xFF4B584B.toInt())
                }
                if (shown) {
                    val art = PrettTvCard(this, scale, PrettTvCard.Mode.SHOW).apply {
                        title = program.title; detail = program.detail
                        contentDescription = "${program.title}. ${program.app}. Open details"
                        register(this, "pick-$index")
                        setOnClickListener { details(program) }
                    }
                    tile.addView(art, lp(230))
                    artwork.load(program.art, px(520), px(230)) { if (active && art.isAttachedToWindow) art.art = it }
                    tile.addView(label(program.app, 25, muted), lp(40))
                    tile.addView(button("Open ${program.app}", "open-$index") { open(program) }, lp(60))
                } else {
                    tile.addView(label("Mystery ${index + 1}", 34, bold = true), lp(64))
                    tile.addView(label(pick.clue, 26, muted, lines = 6).apply {
                        contentDescription = pick.accessibilityLabel
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    tile.addView(button("Reveal this pick", "pick-$index") {
                        revealed.add(id); pendingFocus = "pick-$index"; render()
                    }.apply { contentDescription = "Reveal mystery ${index + 1}. ${pick.accessibilityLabel}" }, lp(66))
                }
                strip.addView(tile, LinearLayout.LayoutParams(0, px(380), 1f).apply { if (index < picks.lastIndex) rightMargin = px(20) })
            }
            page.addView(strip, lp(380))
        }
        val actions = row()
        actions.addView(button("Three new picks", "shuffle") {
            roundSeed = Random.nextLong(); revealed.clear(); lookupAttempted = false; drawPicks(); render(); enrich()
        }, LinearLayout.LayoutParams(px(300), px(66)))
        page.addView(actions, lp(66).apply { topMargin = px(16) })
        if (picks.size in 1..2) page.addView(label("Only ${picks.size} matching choices are available with these filters.", 23, muted), lp(42))
    }

    private fun renderRabbit() {
        if (!loaded) { empty("Loading your titles…", "Start with something you already enjoy."); return }
        if (choosing || rabbitSeed == null) { renderChooser(); return }
        val seed = rabbitSeed ?: return
        val seedPanel = row()
        val seedArt = PrettTvCard(this, scale, PrettTvCard.Mode.SHOW).apply {
            title = seed.title; detail = seed.app
            contentDescription = "${seed.title}. ${seed.app}. Open details"
            register(this, "seed")
            setOnClickListener { details(seed) }
        }
        seedPanel.addView(seedArt, LinearLayout.LayoutParams(px(530), px(250)).apply { rightMargin = px(30) })
        artwork.load(seed.art, px(530), px(250)) { if (active && seedArt.isAttachedToWindow) seedArt.art = it }
        val info = column()
        info.addView(label(seed.title, 38, bold = true, lines = 2), lp(94))
        info.addView(label(seed.genres.ifBlank { seed.detail }, 25, muted, lines = 2), lp(70))
        val actions = row()
        actions.addView(button("Change title", "choose") { choosing = true; chooserPage = 0; render(false) }, LinearLayout.LayoutParams(px(250), px(66)).apply { rightMargin = px(16) })
        actions.addView(button("Open ${seed.app}", "open-seed") { open(seed) }, LinearLayout.LayoutParams(px(330), px(66)))
        info.addView(actions)
        seedPanel.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(seedPanel, lp(270))
        chips(listOf("all" to "All connections", "cast" to "Shared cast", "director" to "Same director", "genre" to "Similar genres").map { (id, title) ->
            Triple(title, "relation-$id", { relation = id; render() })
        }, "relation-$relation")
        page.addView(label(if (lookupBusy) "Checking available cast and director credits…"
            else "Connections use published credits and genres. Select a title to keep exploring.", 23, muted), lp(50))
        val kind = when (relation) {
            "cast" -> DiscoveryConnectionKind.CAST
            "director" -> DiscoveryConnectionKind.DIRECTOR
            "genre" -> DiscoveryConnectionKind.GENRE
            else -> null
        }
        val related = RabbitHolePolicy.related(seed, candidates, preferredServices = preferred(),
            blockedKeys = personal.recommendationFeedback().dismissed,
            blockedTitleKeys = blockedTitles(), metadata = metadata, connectionKind = kind)
        if (related.isEmpty()) empty("No ${if (relation == "all") "verified" else relation} connections here yet",
            "Choose another title or connection type. Missing credits are never guessed.")
        related.take(24).chunked(3).forEach { group ->
            val strip = row()
            group.forEachIndexed { index, item ->
                val tile = column()
                val card = PrettTvCard(this, scale, PrettTvCard.Mode.SHOW).apply {
                    title = item.program.title; detail = item.program.app
                    contentDescription = "${item.program.title}. ${item.reason}. Explore connections. Hold OK for watching options."
                    register(this, "related-${key(item.program)}")
                    setOnClickListener { selectSeed(item.program) }
                    setOnLongClickListener { details(item.program); true }
                }
                tile.addView(card, lp(230))
                tile.addView(label(item.reason, 24, muted, lines = 2), lp(70))
                artwork.load(item.program.art, px(570), px(230)) { if (active && card.isAttachedToWindow) card.art = it }
                strip.addView(tile, LinearLayout.LayoutParams(0, px(310), 1f).apply { if (index < group.lastIndex) rightMargin = px(20) })
            }
            if (group.size < 3) repeat(3 - group.size) { strip.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f)) }
            page.addView(strip)
        }
        if (!lookupBusy) page.addView(button("Refresh connections", "retry") { lookupAttempted = false; enrich() }, LinearLayout.LayoutParams(px(340), px(66)))
        page.addView(label("Credits: Cinemeta / TVmaze (CC BY-SA). Shared genres describe a connection, not a creative influence.", 21, muted, lines = 2), lp(65))
        page.addView(button("Catalog sources", "sources") { sources() }, LinearLayout.LayoutParams(px(250), px(60)))
    }

    private fun renderChooser() {
        page.addView(button(if (query.isBlank()) "Search your available titles" else "Search: $query", "search") { search() },
            LinearLayout.LayoutParams(px(640), px(66)).apply { bottomMargin = px(20) })
        val results = candidates.filter { it.title.contains(query, ignoreCase = true) }.distinctBy { it.titleKey }
        if (results.isEmpty()) { empty("No titles found", "Try a different search or open your streaming apps to refresh their listings."); return }
        chooserPage = chooserPage.coerceIn(0, (results.size - 1) / 18)
        results.drop(chooserPage * 18).take(18).chunked(3).forEach { group ->
            val strip = row()
            group.forEach { p ->
                strip.addView(button("${p.title}\n${p.app}", "choose-${key(p)}", lines = 2) {
                    choosing = false; selectSeed(p)
                }, LinearLayout.LayoutParams(0, px(110), 1f).apply { rightMargin = px(16); bottomMargin = px(16) })
            }
            page.addView(strip)
        }
        val nav = row()
        if (chooserPage > 0) nav.addView(button("Previous titles", "previous") { chooserPage--; render(false) }, LinearLayout.LayoutParams(px(280), px(66)))
        if ((chooserPage + 1) * 18 < results.size) nav.addView(button("More titles", "more") { chooserPage++; render(false) }, LinearLayout.LayoutParams(px(280), px(66)))
        page.addView(nav)
    }

    private fun selectSeed(program: Program) {
        rabbitSeed?.takeIf { key(it) != key(program) }?.let { trail.add(it); if (trail.size > 12) trail.removeAt(0) }
        rabbitSeed = program
        lookupAttempted = false
        relation = "all"
        render(false)
        enrich()
    }

    private fun hydrateMetadata() {
        if (!active || cacheBusy || candidates.isEmpty()) return
        cacheBusy = true
        val token = generation
        val rows = candidates.toList()
        worker.execute {
            val service = DiscoveryMetadataRepository(File(cacheDir, "discovery-meta"))
            val result = try {
                rows.take(200).mapNotNull { p ->
                    if (!active || token != generation) null else service.cached(p)?.let { key(p) to it }
                }.toMap()
            } catch (_: Exception) { emptyMap() } finally { service.close() }
            ui.post {
                if (!active || token != generation) return@post
                cacheBusy = false; cacheHydrated = true
                metadata = (metadata + result).entries.toList().takeLast(200).associate { it.toPair() }
                if (app == OriginalApp.BLIND_PICK) drawPicks()
                render(); enrich()
            }
        }
    }

    private fun enrich() {
        val seed = if (app == OriginalApp.RABBIT_HOLE) rabbitSeed else picks.firstOrNull()?.program
            ?: BlindPickPolicy.pick(candidates, filters.copy(maxMinutes = null),
                preferredServices = preferred(), blockedKeys = personal.recommendationFeedback().dismissed,
                blockedTitleKeys = blindBlockedTitles(), seed = roundSeed, metadata = metadata).firstOrNull()?.program
        if (seed == null) return
        if (!active || lookupBusy || lookupAttempted || !settings.getBoolean("discovery", true)) return
        lookupBusy = true
        lookupAttempted = true
        val token = generation
        val seedKey = key(seed)
        val rows = (picks.map { it.program } + candidates).distinctBy { key(it) }
        val oldFilters = filters
        val oldRound = roundSeed
        render()
        worker.execute {
            if (!active || token != generation) return@execute
            val service = DiscoveryMetadataRepository(File(cacheDir, "discovery-meta"))
            lookup = service
            val result = try { service.load(seed, rows) { active && token == generation } } catch (_: Exception) { emptyMap() }
            finally { service.close() }
            ui.post {
                if (lookup === service) lookup = null
                if (!active || token != generation) return@post
                lookupBusy = false
                metadata = (metadata + result).entries.toList().takeLast(200).associate { it.toPair() }
                if (app == OriginalApp.BLIND_PICK) {
                    if (filters.maxMinutes != null || picks.size < 3) drawPicks()
                    else picks = picks.mapIndexed { index, pick ->
                        val clue = BlindPickPolicy.clue(pick.program, metadata[key(pick.program)])
                        pick.copy(clue = clue, accessibilityLabel = "Mystery pick ${index + 1}. $clue")
                    }
                    render()
                    if (oldFilters != filters || oldRound != roundSeed) { lookupAttempted = false; enrich() }
                } else if (key(rabbitSeed ?: seed) == seedKey) render()
                else { lookupAttempted = false; enrich() }
            }
        }
    }

    private fun sources() {
        val links = linkedMapOf(
            "TVmaze · show and credit data · CC BY-SA" to "https://www.tvmaze.com/api#licensing",
            "Cinemeta · movie and series metadata" to "https://github.com/Stremio/stremio-addon-sdk"
        )
        rabbitSeed?.let { metadata[key(it)] }?.takeIf { it.sourceUrl.startsWith("https://www.tvmaze.com/") }
            ?.let { links["Starting title · ${it.sourceName}"] = it.sourceUrl }
        dialog = AlertDialog.Builder(this).setTitle("Catalog sources")
            .setItems(links.keys.toTypedArray()) { _, index ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(links.values.elementAt(index)))) }
                catch (_: Exception) { Toast.makeText(this, "No browser is available", Toast.LENGTH_SHORT).show() }
            }.setNegativeButton("Back", null).create().also { it.show() }
    }

    private fun details(p: Program) {
        val actions = listOf("Open ${p.app}", if (personal.isSaved(p)) "Already in Watchlist" else "Add to Watchlist", "Explore in Rabbit Hole")
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setTitle(p.title).setItems(actions.toTypedArray()) { _, index ->
            when (index) {
                0 -> open(p)
                1 -> {
                    val saved = personal.isSaved(p) || personal.save(p)
                    Toast.makeText(this, if (saved) "Saved in Watchlist" else personal.lastError ?: "Could not save title", Toast.LENGTH_LONG).show()
                }
                2 -> if (app == OriginalApp.RABBIT_HOLE) selectSeed(p) else startActivity(Intent(this, DiscoveryActivity::class.java)
                    .putExtra("originalApp", OriginalApp.RABBIT_HOLE.route).putExtra("seed", key(p)))
            }
        }.setNegativeButton("Back", null).create().also { it.show() }
    }

    private fun search() {
        val input = EditText(this).apply { setSingleLine(); setText(query); hint = "Movie or TV show" }
        dialog = AlertDialog.Builder(this).setTitle("Choose a starting title").setView(input)
            .setPositiveButton("Search") { _, _ -> query = input.text.toString().trim().take(120); chooserPage = 0; render(false) }
            .setNegativeButton("Cancel", null).create().also { it.show() }
    }

    private fun open(p: Program) {
        fun launch(intent: Intent): Boolean = try { startActivity(intent); true } catch (_: Exception) { false }
        if (p.catalog && p.packageName == "com.stremio.one" && p.detailUri.matches(Regex("stremio:///detail/(movie|series)/tt[0-9]{5,12}"))) {
            if (launch(Intent(Intent.ACTION_VIEW, Uri.parse(p.detailUri)).setPackage(p.packageName))) return
        }
        if (!p.catalog && p.intentUri.isNotBlank()) {
            val target = try { Intent.parseUri(p.intentUri, Intent.URI_INTENT_SCHEME).apply {
                component = null; selector = null; setPackage(p.packageName); flags = Intent.FLAG_ACTIVITY_NEW_TASK; clipData = null
            } } catch (_: Exception) { null }
            if (target != null && launch(target)) return
        }
        val destination = installed.firstOrNull { it.packageName == p.packageName }
        if (destination != null && launch(appRepository.launchIntent(destination)))
            Toast.makeText(this, "Find ${p.title} in ${p.app}", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "${p.app} is unavailable", Toast.LENGTH_LONG).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = back()
    private fun back() {
        if (choosing && rabbitSeed != null) { choosing = false; render(false) }
        else if (app == OriginalApp.RABBIT_HOLE && trail.isNotEmpty()) {
            rabbitSeed = trail.removeAt(trail.lastIndex); lookupAttempted = false; render(false); enrich()
        } else finish()
    }

    private fun register(view: View, id: String) { view.id = View.generateViewId(); view.tag = id; controls[id] = view }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun lp(height: Int) = LinearLayout.LayoutParams(-1, px(height))
    private fun rounded(color: Int, edge: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = px(18).toFloat(); if (edge != 0) setStroke(px(2), edge)
    }
    private fun label(value: String, size: Int, color: Int = ink, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
        text = value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size) * resources.configuration.fontScale)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        maxLines = lines; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL; includeFontPadding = false
    }
    private fun button(title: String, id: String, selected: Boolean = false, lines: Int = 1, action: () -> Unit): TextView =
        label(title, 26, lines = lines).apply {
            gravity = Gravity.CENTER; isFocusable = true; isClickable = true
            setPadding(px(20), 0, px(20), 0); register(this, id)
            fun paint(focused: Boolean) {
                setTextColor(if (focused) ground else ink)
                background = rounded(if (focused) 0xFFCDD9C7.toInt() else 0xFF1B2922.toInt(),
                    if (focused) ink else if (selected) 0xFF89958A.toInt() else 0xFF354032.toInt())
            }
            paint(false); setOnFocusChangeListener { _, focused -> paint(focused) }; setOnClickListener { action() }
        }
    private fun chips(items: List<Triple<String, String, () -> Unit>>, selected: String) {
        val strip = row()
        items.forEachIndexed { index, item ->
            strip.addView(button(item.first, item.second, item.second == selected, action = item.third),
                LinearLayout.LayoutParams(0, px(56), 1f).apply { if (index < items.lastIndex) rightMargin = px(12) })
        }
        page.addView(strip, lp(66))
    }
    private fun empty(title: String, detail: String) {
        page.addView(column().apply {
            setPadding(px(30), px(20), px(30), px(20)); background = rounded(0xFF171F18.toInt(), 0xFF364033.toInt())
            addView(label(title, 32, bold = true), lp(70))
            addView(label(detail, 26, muted, lines = 3), lp(120))
        }, lp(240))
    }
}
