package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rivi home screen. One Activity, native Views, no AndroidX.
 *
 * Layout (designed on a 1920×1080 grid, scaled to the real display):
 *   clock (top right)
 *   FAVOURITES  – large 16:9 tiles in a horizontal strip
 *   ALL APPS    – smaller tiles, 6 per row
 * Nothing redraws at idle except the clock once a minute.
 */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var root: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var favRow: LinearLayout
    private lateinit var favScroll: HorizontalScrollView
    private lateinit var grid: LinearLayout
    private lateinit var clock: TextView
    private var motion = Motion.DEFAULT
    private var shadow: ShadowView? = null      // only with Motion.shadow
    private var menu: SideMenu? = null          // created the first time a menu is opened

    private var apps: List<AppEntry> = emptyList()
    private var inputs: AppEntry? = null
    private var lastArranged: HomeModel.Arranged? = null
    private var reloads = 0
    private val tiles = HashMap<String, TileView>()
    private var scale = 1f          // design px (1920 wide) -> real px
    private var gen = 0             // discovery generation, drops stale results

    private val worker = HandlerThread("rivi-loader").apply { start() }
    private val bg = Handler(worker.looper)
    private val ui = Handler(Looper.getMainLooper())

    // Registered for the whole activity lifetime so installs/removals that happen while a streaming
    // app is in front are not missed. While we are not visible we only set a flag (no work).
    private var started = false
    private var packagesDirty = false
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (started) reload() else packagesDirty = true }
    }
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // ---- design units -------------------------------------------------------------------------
    private fun px(design: Int) = (design * scale + 0.5f).toInt()
    private val favW get() = px(400); private val favH get() = px(225)
    private val gridW get() = px(256); private val gridH get() = px(144)
    private val labelH get() = px(56)
    private val gap get() = px(28)
    private val marginX get() = px(96)
    private val favR get() = px(18).toFloat(); private val gridR get() = px(14).toFloat()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepository(this)
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_MOTION)?.let { prefs.motion = it; intent.removeExtra(EXTRA_MOTION) }
        motion = Motion.parse(prefs.motion)
        val dm = resources.displayMetrics
        scale = dm.widthPixels / 1920f
        buildChrome()
        setContentView(root)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(packageReceiver, f)
        reload()
    }

    private fun buildChrome() {
        root = ScrollView(this).apply {
            isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false
            clipChildren = false; clipToPadding = false
            isSmoothScrollingEnabled = true
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPadding(marginX - px(10), px(56), marginX - px(10), px(96))
        }
        // The shadow lives in its own view underneath the page, so it is behind every tile.
        val stage = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        if (motion.shadow) shadow = ShadowView(this).also {
            val (w, h) = it.sizeFor(favW, favH)
            stage.addView(it, FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.START))
        }
        stage.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(stage, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        // Header: wordmark left, clock right
        val header = FrameLayout(this).apply { setPadding(px(10), 0, px(10), 0) }
        header.addView(TextView(this).apply {
            text = "Rivi"; setTextColor(0xFFB7BECC.toInt()); letterSpacing = 0.08f
            setTextSize(TypedValue.COMPLEX_UNIT_PX, px(26).toFloat()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL))
        clock = TextView(this).apply {
            setTextColor(0xFFB7BECC.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(30).toFloat())
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        header.addView(clock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
        page.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(48) })

        page.addView(sectionTitle(getString(R.string.favourites)))
        favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false; clipToPadding = false
        }
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, px(8), 0, 0) }
        shadow?.let { sh ->
            // keep the shadow under its tile while the favourites row scrolls, and after any layout pass
            favScroll.setOnScrollChangeListener { _, _, _, _, _ -> sh.place() }
            root.viewTreeObserver.addOnGlobalLayoutListener { sh.place() }
        }
        favScroll.addView(favRow, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        page.addView(favScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(40) })

        page.addView(sectionTitle(getString(R.string.all_apps)))
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, px(16), 0, 0) }
        page.addView(grid, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sectionTitle(t: String) = TextView(this).apply {
        text = t.uppercase(); setPadding(px(10), 0, 0, 0); setTextColor(0xFF7E8798.toInt()); letterSpacing = 0.14f
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(22).toFloat()); typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    // ---- lifecycle ------------------------------------------------------------------------------
    override fun onStart() {
        super.onStart()
        started = true
        if (packagesDirty) { packagesDirty = false; reload() }
        else if (apps.isNotEmpty()) {
            // Coming back from an app: put the most recently opened apps first in the grid.
            val a = arrange(apps, inputs); val l = lastArranged
            if (l == null || a.rest != l.rest || a.favourites != l.favourites) rebuild()
        }
    }

    override fun onResume() {
        super.onResume()
        // the minute tick, plus the clock or the time zone being changed in Settings
        registerReceiver(tickReceiver, IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        updateClock()
    }

    override fun onPause() {
        unregisterReceiver(tickReceiver)
        currentFocus?.let { v -> if (v is TileView) prefs.lastFocus = v.entryKey }
        super.onPause()
    }

    override fun onStop() {
        started = false
        menu?.close(animate = false)
        super.onStop()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        worker.quitSafely()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val m = intent?.getStringExtra(EXTRA_MOTION)
        if (m != null && Motion.parse(m) != motion) { prefs.motion = m; recreate(); return }
        // HOME pressed while we are already alive: close any menu and make sure a tile has focus.
        menu?.close()
        if (currentFocus !is TileView) restoreFocus()
    }

    // While the side menu is open it takes the remote's keys; the tile underneath keeps the focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val m = menu
        if (m != null && m.isOpen && m.handleKey(event)) return true
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Home cannot be closed. BACK jumps to the first favourite.
        favRow.getChildAt(0)?.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { showMenu(); return true }
        return super.onKeyDown(keyCode, event)
    }

    private fun updateClock() {
        clockFormat.timeZone = java.util.TimeZone.getDefault()   // a formatter keeps the zone it was created in
        clock.text = clockFormat.format(Date())
    }

    /** `adb shell dumpsys activity fi.jommi.rivi` prints this; used by tests/ui_test.sh. Costs nothing at runtime. */
    override fun dump(prefix: String, fd: java.io.FileDescriptor?, w: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, w, args)
        val a = lastArranged
        w.println("RIVI version=${try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }} reloads=$reloads started=$started")
        w.println("RIVI favourites=${a?.favourites?.joinToString(",") ?: ""}")
        w.println("RIVI rest=${a?.rest?.joinToString(",") ?: ""}")
        w.println("RIVI hidden=${prefs.hidden.sorted().joinToString(",")}")
        w.println("RIVI focus=${(currentFocus as? TileView)?.entryKey ?: "none"}")
        w.println("RIVI tiles=${tiles.size} withBitmap=${tiles.values.count { it.bitmap != null }}")
        w.println("RIVI motion=${motion.names.joinToString(",").ifEmpty { "none" }} menu=${if (menu?.isOpen == true) "open" else "closed"}")
        w.println("RIVI homeService=${HomeKeyService.connected} homeKeys=${HomeKeyService.homeKeyPresses} redirects=${HomeKeyService.redirects} keysSeen=${HomeKeyService.keysSeen}")
    }

    // ---- data -----------------------------------------------------------------------------------
    private fun reload() {
        val g = ++gen; reloads++
        bg.post {
            val found = repo.discover()
            val inp = repo.inputsEntry()
            ui.post { if (g == gen) { inp?.bitmap = inputs?.bitmap; inputs = inp; onDiscovered(found) } }
            // Artwork: favourites first (large), then grid tiles. Each result is posted individually
            // so the screen fills progressively on first run and in one burst from the disk cache later.
            val favs = resolveFavourites(found, inp)
            val favSet = favs.map { it.key }.toHashSet()
            for (e in favs) loadInto(e, favW, favH, g)
            for (e in found) if (e.key !in favSet) loadInto(e, gridW, gridH, g)
            inp?.let { if (it.key !in favSet) loadInto(it, gridW, gridH, g) }
            if (g == gen) repo.pruneCache(
                (favs.map { repo.cacheName(it, favW, favH, favR) } +
                 (found + listOfNotNull(inp)).filter { it.key !in favSet }.map { repo.cacheName(it, gridW, gridH, gridR) }).toHashSet())
        }
    }

    private fun loadInto(e: AppEntry, w: Int, h: Int, g: Int) {
        val b = repo.loadTile(e, w, h, if (w == favW) favR else gridR) ?: return
        e.bitmap = b
        ui.post { if (g == gen) tiles[e.key]?.bitmap = b }
    }

    private fun items(found: List<AppEntry>) = found.map { HomeModel.Item(it.key, it.packageName, it.label) }

    private fun arrange(found: List<AppEntry>, inp: AppEntry?): HomeModel.Arranged {
        if (!prefs.favouritesInitialised && found.isNotEmpty()) {
            prefs.favourites = HomeModel.initialFavourites(repo.defaultFavouritePackages, items(found)); prefs.favouritesInitialised = true
        }
        return HomeModel.arrange(items(found), prefs.favourites, prefs.hidden, prefs.recentOrder(), if (inp != null) listOf(inp.key) else emptyList())
    }

    private fun resolveFavourites(found: List<AppEntry>, inp: AppEntry?): List<AppEntry> {
        val byKey = (found + listOfNotNull(inp)).associateBy { it.key }
        return arrange(found, inp).favourites.mapNotNull { byKey[it] }
    }

    private fun onDiscovered(found: List<AppEntry>) {
        // keep already-decoded bitmaps when re-discovering
        val old = apps.associateBy { it.key }
        for (e in found) if (e.bitmap == null) e.bitmap = old[e.key]?.bitmap
        apps = found
        rebuild()
    }

    private fun rebuild() {
        val focusedKey = (currentFocus as? TileView)?.entryKey ?: prefs.lastFocus
        tiles.clear(); favRow.removeAllViews(); grid.removeAllViews()
        val byKey = (apps + listOfNotNull(inputs)).associateBy { it.key }
        val arranged = arrange(apps, inputs)
        val favs = arranged.favourites.mapNotNull { byKey[it] }
        val rest = arranged.rest.mapNotNull { byKey[it] }
        lastArranged = arranged

        val r = favR
        for (e in favs) {
            val t = TileView(this, favW, favH, labelH, r, motion).apply { label = e.label; entryKey = e.key; bitmap = e.bitmap }
            wire(t, e)
            favRow.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = gap - 2 * t.pad })
        }
        val cols = 6
        var row: LinearLayout? = null
        rest.forEachIndexed { i, e ->
            if (i % cols == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false; setPadding(0, 0, 0, 0) }
                grid.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val t = TileView(this, gridW, gridH, labelH, gridR, motion).apply { label = e.label; entryKey = e.key; bitmap = e.bitmap }
            wire(t, e)
            row!!.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = gap - 2 * t.pad })
        }
        // Grid rows: left/right at the edge should not wrap to another row.
        for (i in 0 until grid.childCount) {
            val rl = grid.getChildAt(i) as LinearLayout
            val first = rl.getChildAt(0); val last = rl.getChildAt(rl.childCount - 1)
            first.nextFocusLeftId = first.id.takeIf { it != View.NO_ID } ?: run { first.id = View.generateViewId(); first.id }
            last.nextFocusRightId = last.id.takeIf { it != View.NO_ID } ?: run { last.id = View.generateViewId(); last.id }
        }
        if (favRow.childCount > 0) {
            val first = favRow.getChildAt(0); val last = favRow.getChildAt(favRow.childCount - 1)
            if (first.id == View.NO_ID) first.id = View.generateViewId(); first.nextFocusLeftId = first.id
            if (last.id == View.NO_ID) last.id = View.generateViewId(); last.nextFocusRightId = last.id
        }
        if (tiles.isEmpty()) shadow?.follow(null)
        restoreFocus(HomeModel.focusTarget(focusedKey, arranged))
    }

    private fun wire(t: TileView, e: AppEntry) {
        tiles[e.key] = t
        t.setOnClickListener { launch(e) }
        t.setOnLongClickListener { showTileMenu(e); true }
        shadow?.let { sh -> t.onFocused = { sh.follow(it) } }
    }

    private fun restoreFocus(key: String? = prefs.lastFocus) {
        val v = key?.let { tiles[it] } ?: favRow.getChildAt(0) ?: (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)
        v?.requestFocus()
    }

    // ---- actions --------------------------------------------------------------------------------
    private fun launch(e: AppEntry) {
        prefs.touchRecent(e.key)
        prefs.lastFocus = e.key
        safeStart(repo.launchIntent(e), e.label)
    }

    private fun safeStart(i: Intent, label: String) {
        try { startActivity(i) } catch (ex: Exception) {
            Toast.makeText(this, "${getString(R.string.launch_failed)} $label", Toast.LENGTH_SHORT).show()
            reload()
        }
    }

    private fun showTileMenu(e: AppEntry) {
        val favs = prefs.favourites.toMutableList()
        val isFav = e.key in favs
        val idx = favs.indexOf(e.key)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (isFav) {
            items += getString(R.string.menu_remove_fav) to { favs.remove(e.key); prefs.favourites = favs; rebuild() }
            if (idx > 0) items += getString(R.string.menu_move_left) to { prefs.favourites = HomeModel.move(favs, e.key, -1); rebuild() }
            if (idx < favs.size - 1) items += getString(R.string.menu_move_right) to { prefs.favourites = HomeModel.move(favs, e.key, 1); rebuild() }
        } else {
            items += getString(R.string.menu_add_fav) to { favs.add(e.key); prefs.favourites = favs; rebuild() }
        }
        items += getString(R.string.menu_hide) to { prefs.hidden = prefs.hidden + e.key; rebuild() }
        items += getString(R.string.menu_app_info) to { safeStart(repo.appInfoIntent(e.packageName), e.label) }
        dialog(e.label, items, e.bitmap)
    }

    private fun showMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        repo.settingsIntent()?.let { si -> items += getString(R.string.menu_android_settings) to { safeStart(si, getString(R.string.settings)) } }
        if (prefs.hidden.isNotEmpty()) items += getString(R.string.menu_unhide_all) to { prefs.hidden = emptySet(); rebuild() }
        stockHomeIntent()?.let { sh -> items += getString(R.string.menu_stock_home) to { HomeKeyService.allowStockHome = true; safeStart(sh, "Google TV") } }
        items += getString(R.string.menu_home_button) to {
            Toast.makeText(this, getString(if (HomeKeyService.connected) R.string.home_button_on else R.string.home_button_off), Toast.LENGTH_LONG).show()
            if (!HomeKeyService.connected) safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), getString(R.string.settings))
        }
        items += getString(R.string.menu_refresh) to { reload() }
        items += getString(R.string.menu_about) to {
            val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
            Toast.makeText(this, "Rivi $v", Toast.LENGTH_SHORT).show()
        }
        dialog("Rivi", items)
    }

    companion object {
        /** `--es motion settle,fade` on the launch intent picks the refinements; see [Motion]. */
        const val EXTRA_MOTION = "motion"
    }

    /** The stock home screen, if one other than us is installed and enabled. */
    private fun stockHomeIntent(): Intent? {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = packageManager.queryIntentActivities(q, 0).firstOrNull { HomeKeyService.isStockHomeScreen(it.activityInfo.packageName, it.activityInfo.name) } ?: return null
        return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun dialog(title: String, items: List<Pair<String, () -> Unit>>, picture: Bitmap? = null) {
        if (motion.hold) {
            val m = menu ?: SideMenu(this, ::px).also {
                menu = it
                addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            m.open(title, picture, items)
            return
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }
}
