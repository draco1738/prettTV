package fi.jommi.rivi

// Built on Rivi v0.15, Copyright (c) 2026 Jommi, MIT. See assets/rivi-license.txt.
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.*
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.text.TextUtils
import android.util.TypedValue
import android.view.*
import android.widget.*
import java.util.Date

/** Three native compositions sharing Rivi's app discovery, preferences and arrangement model. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepository
    private lateinit var contentRepo: ContentRepository
    private lateinit var weatherRepo: WeatherRepository
    private val settingsKeys=SettingsKeyPolicy(Build.MANUFACTURER)
    private val appShortcutKeys=AppShortcutKeyPolicy(Build.MANUFACTURER)
    private var weatherHeader:WeatherHeaderView?=null
    private var bluetoothQuickSwitch:BluetoothQuickSwitch?=null
    private var weatherLoadingDialog:AlertDialog?=null
    private lateinit var artwork: ArtworkLoader
    private val settings by lazy { getSharedPreferences("pretttv", MODE_PRIVATE) }
    private val playback by lazy { PlaybackHistory(this) }
    private val personal by lazy { PersonalizationStore(this) }
    private val rowSections=LinkedHashMap<String,LinearLayout>()
    private val rowValues=HashMap<String,Any>()
    private val boundPrograms=HashMap<String,Program?>()
    private var renderedRowPreferences:RowPreferences?=null
    private var homeStatus:TextView?=null
    private var appActionPanel:Dialog?=null
    private var searchPanel:HomeSearchPanel?=null
    private val searchState=HomeSearchState()
    private var deferActiveRefresh=false
    private var providerRefreshPending=false
    private var pausedForContent=false
    private val deferredRows=HashSet<String>()
    private var appReturnViewport:Viewport?=null
    private var reminderBanner:TextView?=null
    private var teamLoadingDialog:AlertDialog?=null
    private val dismissedReminders=HashSet<String>()
    private var watchlistOffset=0
    private var nextRowFocus:String?=null
    private var lastContentFocus:View?=null
    private var navigationBar:View?=null
    private var navigationSelection="nav-home"
    private var layout = HomeLayout.BALANCED
    private var content = HomeContent()
    private var apps = emptyList<AppEntry>()
    private var inputs: AppEntry? = null
    private var visible = false
    private var dirty = true
    // Private, default-off seam used only by isolated instrumentation injecting fixed rows.
    private var fixtureRefreshSuspended=false
    @Volatile private var generation = 0
    private var appLoadError=false
    private var appDiscoveryComplete=false
    private var scale = 1f
    private var ready = false
    private var homeRequested=false
    private lateinit var root: ScrollView
    private lateinit var page: LinearLayout
    private lateinit var clock: TextView
    private var firstCard: View? = null
    private var appAnchor: View? = null
    private var libraryAnchor:View?=null
    private var showAnchor: View? = null
    private val focusables = LinkedHashMap<String, View>()
    private val horizontalRows=LinkedHashMap<String,HorizontalScrollView>()
    private data class FocusRow(val container:ViewGroup,val cards:List<View>)
    private val windowedRows=LinkedHashMap<String,WindowedRow>()
    private val windowedPrograms=HashMap<String,List<Program>>()
    private val focusRows=HashMap<View,FocusRow>()
    private val rowMotion=HashMap<HorizontalScrollView,ValueAnimator>()
    private data class Viewport(val focus:String?=null,val y:Int=0,val rows:Map<String,Int> = emptyMap(),val contentFocus:String?=null)
    private var pendingViewport:Viewport?=null
    private var viewportRevision=0
    private val appCards = HashMap<String, MutableList<PrettTvCard>>()
    private val originalAppArt by lazy { OriginalApp.entries.associateWith { OriginalAppArtwork.render(this,it) } }
    private data class CardArt(val card:PrettTvCard,val url:String,val w:Int,val h:Int,val request:ArtworkRequest=ArtworkRequest(),var loaded:Boolean=false,var ticket:ArtworkLoader.Ticket?=null)
    private val cardArt=ArrayList<CardArt>()
    private val ui = Handler(Looper.getMainLooper())
    private var artworkScanScheduled=false
    private val scanArtwork=Runnable { artworkScanScheduled=false; loadVisibleArt() }
    private val retryArtwork = Runnable { loadVisibleArt() }
    private val retryApps = Runnable { if(visible && dirty)reload() }
    private var scrollDirection=1
    @Volatile private var iconEpoch=0
    private var iconsLoading=false
    private val failedIcons=HashSet<String>()
    private var iconsReleased=false
    private val releaseBackgroundArt=Runnable { if(!visible)releaseArtwork(true) }
    private val loader = HandlerThread("pretttv-apps").apply { start() }
    private val background = Handler(loader.looper)
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty=true; if(visible)reload() }
    }
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateClock() }
    }
    private val ink = PrettTvColors.TEXT
    private val muted = PrettTvColors.MUTED
    private val bgColor = PrettTvColors.BACKGROUND
    private fun px(n:Int) = (n*scale+.5f).toInt()
    private val wide get() = layout==HomeLayout.BALANCED
    private val inner get() = if(wide)BalancedHomeLayout.WIDTH else 1808
    private val balancedFont get() = resources.configuration.fontScale
    private val discovery get() = settings.getBoolean("discovery",true)
    private val sportsPackages get() = settings.getStringSet("sportsPackages",setOf("com.rblive.app")).orEmpty().toSet()

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        prefs=Prefs(this); repo=AppRepository(this); artwork=ArtworkLoader(this)
        layout=HomeLayout.parse(settings.getString("layout",null))
        applyIntent(intent)
        scale=resources.displayMetrics.widthPixels/1920f
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        contentRepo=ContentRepository(this) { value ->
            val removed=personal.consumePlaybackHistory(value.watching)
            val filtered=withTitlePreferences(value)
            if(filtered!=content || removed>0) {
                val statusOnly=removed==0 && filtered.copy(refreshing=content.refreshing,refreshFailed=content.refreshFailed,offline=content.offline)==content
                content=filtered
                if(statusOnly)updateHomeStatus() else refreshProviderRows()
            }
            if(removed>0)Toast.makeText(this,"Started watching · removed from Watchlist. Undo in Settings.",Toast.LENGTH_LONG).show()
        }
        weatherRepo=WeatherRepository(this) { updateWeatherHeader() }
        render(reset=true)
        if(checkSelfPermission("android.permission.READ_TV_LISTINGS")!=android.content.pm.PackageManager.PERMISSION_GRANTED && !settings.getBoolean("tvPermissionAsked",false)) {
            settings.edit().putBoolean("tvPermissionAsked",true).apply()
            requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"),41)
        }
        val filter=IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        if(Build.VERSION.SDK_INT>=33)registerReceiver(packageReceiver,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(packageReceiver,filter)
    }
    override fun onStart() {
        super.onStart(); visible=true
        ui.removeCallbacks(releaseBackgroundArt)
        val savedLayout=HomeLayout.parse(settings.getString("layout",null))
        if(layout!=savedLayout) { layout=savedLayout; render(reset=true) }
        AppUpdater.checkWhenHomeVisible(this)
        RbtvHandoffService.cancel()
        registerReceiver(tickReceiver,IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        updateClock()
        weatherRepo.start()
        if(dirty || apps.isEmpty())reload() else { if(iconsReleased)loadAppArtwork(); startContent() }
        ui.post(retryArtwork)
        // onStop removes timers; preserve recovery for cards still in their failure backoff.
        ui.postDelayed(retryArtwork,30_000)
    }
    override fun onStop() {
        settingsKeys.reset()
        appShortcutKeys.reset()
        AppUpdater.cancelHomeCheck()
        visible=false; contentRepo.stop(); weatherRepo.stop(); artwork.cancel(); unregisterReceiver(tickReceiver)
        iconEpoch++;iconsReleased=iconsReleased || iconsLoading;iconsLoading=false
        ui.postDelayed(releaseBackgroundArt,30_000)
        appActionPanel?.dismiss(); appActionPanel=null
        weatherLoadingDialog?.dismiss(); weatherLoadingDialog=null
        teamLoadingDialog?.dismiss(); teamLoadingDialog=null
        stopRowMotion(); cancelArtworkScan(); ui.removeCallbacks(retryArtwork); ui.removeCallbacks(retryApps); cardArt.forEach { it.request.cancel() }
        (currentFocus?.tag as? String)?.let { settings.edit().putString("focus",it).apply() }
        super.onStop()
    }
    override fun onTrimMemory(level:Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN is not critical pressure: preserve the short app-return path for 30 seconds.
        if(level==ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)artwork.trimTo(3*1024*1024)
        else if(level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
            releaseArtwork(!visible)
        else if(level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)artwork.trimTo(3*1024*1024)
    }
    @Deprecated("Deprecated in Java")
    override fun onLowMemory() { super.onLowMemory();releaseArtwork(!visible) }
    private fun releaseArtwork(all:Boolean) {
        iconEpoch++;iconsLoading=false
        if(all)artwork.cancel()
        artwork.trimTo(0)
        val bounds=Rect()
        cardArt.forEach { item->if(all || !item.card.getGlobalVisibleRect(bounds)) {
            item.ticket?.cancel();item.ticket=null;item.request.cancel();item.card.art=null;item.loaded=false
        } }
        apps.forEach { entry->
            val cards=appCards[entry.key].orEmpty()
            if(all || cards.none { !it.isLibraryCard && it.getGlobalVisibleRect(bounds) }) {
                entry.bitmap=null;cards.filterNot { it.isLibraryCard }.forEach { it.art=null }
            }
            if(all || cards.none { it.isLibraryCard && it.getGlobalVisibleRect(bounds) }) {
                entry.libraryBitmap=null;cards.filter { it.isLibraryCard }.forEach { it.art=null }
            }
        }
        iconsReleased=true
    }
    override fun onPause() {
        pausedForContent=true;stopRowMotion()
        BluetoothControlSession.cancel(this);BluetoothControlSession.unlisten(this)
        super.onPause()
    }
    override fun onResume() {
        super.onResume(); pausedForContent=false
        // Audio devices is translucent: returning from it can resume Home without onStart.
        // Reclaim the listener here so its switch cannot retain a stale/busy panel state.
        BluetoothControlSession.listen(this) { bluetoothQuickSwitch?.bind(it) }
        if(!BluetoothControlSession.busy)BluetoothControlSession.start(this)
        if(providerRefreshPending)refreshProviderRows()
        val saved=appReturnViewport ?: return
        appReturnViewport=null
        val revision=viewportRevision
        ui.post {
            if(visible && !pausedForContent && !isFinishing && !isDestroyed && revision==viewportRevision) {
                restoreRows(saved.rows)
                focusTarget(saved.focus)?.requestFocusFromTouch()
                root.scrollTo(0,saved.y); revealFocus(currentFocus)
            }
        }
    }
    override fun onDestroy() { searchPanel?.dismiss();searchPanel=null; generation++; ui.removeCallbacksAndMessages(null); unregisterReceiver(packageReceiver); background.removeCallbacksAndMessages(null); loader.quitSafely(); contentRepo.close(); weatherRepo.close(); artwork.close(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig:Configuration) { super.onConfigurationChanged(newConfig); scale=resources.displayMetrics.widthPixels/1920f; render() }
    override fun onNewIntent(i:Intent?) {
        super.onNewIntent(i)
        searchPanel?.dismiss();searchPanel=null
        val configureLayout=i?.hasExtra("layout")==true
        applyIntent(i)
        if(configureLayout) { render(reset=true); if(visible)startContent() } else resetHomePosition()
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==41 && visible)startContent()
    }
    private fun applyIntent(i:Intent?) {
        i?.getStringExtra("layout")?.let { value -> layout=HomeLayout.parse(value.uppercase(java.util.Locale.ROOT)); settings.edit().putString("layout",layout.name).apply(); i.removeExtra("layout") }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { firstCard?.requestFocusFromTouch(); root.smoothScrollTo(0,0); selectNavigation("nav-home") }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        // The global helper consumes its gestures before they reach this foreground fallback.
        // Arrows/OK take the direct navigation path without recovery storage or device lookups.
        if(appShortcutKeys.packageFor(event.keyCode)!=null) {
            val appKey=appShortcutKeys.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,event.downTime,
                event.isCanceled,StockRecoveryStore.paused(this),RemoteAppLauncher.isController(event))
            if(appKey!=AppShortcutKeyPolicy.Decision.PASS) {
                if(appKey==AppShortcutKeyPolicy.Decision.OPEN_APP) {
                    val target=appShortcutKeys.packageFor(event.keyCode)
                    if(target!=null && !RemoteAppLauncher.open(this,target))
                        Toast.makeText(this,"This shortcut's app is unavailable",Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        if(settingsKeys.isSettingsKey(event.keyCode)) {
            val settingsKey=settingsKeys.handle(event.action,event.keyCode,event.repeatCount,event.deviceId,event.downTime,
                event.isCanceled,StockRecoveryStore.paused(this))
            if(settingsKey!=SettingsKeyPolicy.Decision.PASS) {
                if(settingsKey==SettingsKeyPolicy.Decision.OPEN_SETTINGS && !TvSettingsLauncher.open(this))
                    Toast.makeText(this,"Could not open TV settings",Toast.LENGTH_SHORT).show()
                return true
            }
        }
        if(event.action==KeyEvent.ACTION_DOWN &&
            (event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val row=focusRows[currentFocus]
            if(row!=null) {
                val scroll=row.container.parent as? HorizontalScrollView
                val window=row.container as? WindowedRow
                scrollDirection=if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1
                when(val move=RowNavigation.move(window?.indexOf(currentFocus) ?: row.cards.indexOf(currentFocus),window?.keys?.size ?: row.cards.size,
                    event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT,scroll?.scrollX ?: 0,event.repeatCount)) {
                    is RowNavigation.Move.Focus -> {
                        val from=scroll?.scrollX ?: 0
                        if(scroll!=null)rowMotion.remove(scroll)?.cancel()
                        val target=window?.ensure(move.index) ?: row.cards[move.index]
                        if(target.requestFocus(if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)View.FOCUS_RIGHT else View.FOCUS_LEFT) && scroll!=null) {
                            val destination=scroll.scrollX
                            scroll.scrollTo(from,0); moveRow(scroll,destination)
                        }
                        return true
                    }
                    RowNavigation.Move.RevealStart -> { if(scroll!=null)moveRow(scroll,0); return true }
                    RowNavigation.Move.Stay -> {
                        if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT && event.repeatCount==0)
                            (currentFocus as? PrettTvCard)?.showBoundaryFeedback()
                        return true
                    }
                    RowNavigation.Move.Sidebar -> { if(event.repeatCount==0)(currentFocus as? PrettTvCard)?.showBoundaryFeedback(); return true }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun moveRow(scroll:HorizontalScrollView,destination:Int) {
        rowMotion.remove(scroll)?.cancel()
        if(destination==scroll.scrollX)return
        if(!ValueAnimator.areAnimatorsEnabled()) { scroll.scrollTo(destination,0); return }
        val motion=ValueAnimator.ofInt(scroll.scrollX,destination).apply {
            duration=180; interpolator=android.view.animation.DecelerateInterpolator()
            addUpdateListener { scroll.scrollTo(it.animatedValue as Int,0) }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) { if(rowMotion[scroll]===animation)rowMotion.remove(scroll) }
            })
        }
        rowMotion[scroll]=motion; motion.start()
    }
    private fun stopRowMotion() {
        // Finish at the focused card before saving/restoring a viewport or leaving Home.
        val running=rowMotion.values.toList(); rowMotion.clear(); running.forEach { it.end() }
    }
    private fun selectNavigation(key:String) { navigationSelection=key; updateNavigation() }
    private fun updateNavigation() {
        focusables.filterKeys { it.startsWith("nav-") }.forEach { (key,view)->
            (view as? TextView)?.apply {
                val selected=key==navigationSelection
                background=round(if(isFocused || selected)PrettTvColors.SELECTED else PrettTvColors.BACKGROUND,if(isFocused)PrettTvColors.FOCUS else 0)
                setTextColor(ink)
                paintFlags=if(selected)paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG else paintFlags and android.graphics.Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }
    }
    override fun onKeyDown(key:Int,event:KeyEvent):Boolean { if(key==KeyEvent.KEYCODE_MENU) { menu(); return true }; return super.onKeyDown(key,event) }

    private fun text(value:String,size:Int,color:Int=ink,bold:Boolean=false)=TextView(this).apply {
        text=value; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_PX,px(size)*resources.configuration.fontScale)
        typeface=Typeface.create(if(bold)"sans-serif-medium" else "sans-serif",Typeface.NORMAL)
        maxLines=1; ellipsize=TextUtils.TruncateAt.END; gravity=Gravity.CENTER_VERTICAL; includeFontPadding=false
    }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; clipChildren=false; clipToPadding=false }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; clipChildren=false; clipToPadding=false }
    private fun round(color:Int,stroke:Int=0)=GradientDrawable().apply { setColor(color); cornerRadius=px(15).toFloat(); if(stroke!=0)setStroke(px(2),stroke) }
    private fun register(v:View,key:String) { v.tag=key; v.id=View.generateViewId(); focusables[key]=v }
    private fun button(label:String,key:String,action:()->Unit):TextView=text(label,26).apply {
        isFocusable=true; isClickable=true; gravity=Gravity.CENTER; setPadding(px(17),0,px(17),0)
        background=round(PrettTvColors.RAISED); register(this,key)
        setOnFocusChangeListener { _,focus -> background=round(if(focus)PrettTvColors.SELECTED else PrettTvColors.RAISED,if(focus)PrettTvColors.FOCUS else 0); setTextColor(ink) }
        setOnClickListener { action() }
    }
    private fun section(title:String,extra:String=""):View=row().apply {
        addView(text(title,28,ink,true),LinearLayout.LayoutParams(0,px(44),1f))
        if(extra.isNotEmpty())addView(text(extra,19,muted),LinearLayout.LayoutParams(-2,px(44)))
    }
    private fun gap(n:Int) { page.addView(View(this),LinearLayout.LayoutParams(1,px(n))) }
    private fun updateClock() { if(::clock.isInitialized)clock.text=android.text.format.DateFormat.getTimeFormat(this).format(Date()); updateHomeStatus() }
    private fun updateWeatherHeader() {
        if(::weatherRepo.isInitialized)weatherHeader?.bind(weatherRepo.state,weatherRepo.prefs.enabled,weatherRepo.prefs.units)
    }

    private fun viewport(focus:String?=null)=Viewport(
        focus ?: (currentFocus?.tag as? String) ?: (lastContentFocus?.tag as? String) ?: settings.getString("focus",null),
        if(ready)root.scrollY else 0,horizontalRows.mapValues { it.value.scrollX },lastContentFocus?.tag as? String)

    private fun focusTarget(key:String?):View? {
        fun available(view:View?)=view?.takeIf { it.isFocusable && it.visibility==View.VISIBLE }
        available(focusables[key])?.let { return it }
        if(wide && (key?.startsWith("next-")==true || key=="discovery-empty"))
            return (0 until BalancedHomeLayout.POSTER_COUNT).firstNotNullOfOrNull { available(focusables["next-$it"]) }
                ?: available(focusables["discovery-empty"])
        return key?.let { value -> windowedRows.values.firstNotNullOfOrNull { available(it.ensure(value)) } }
    }
    private fun restoreRows(rows:Map<String,Int>) {
        rows.forEach { (key,x)->
            windowedRows[key]?.updateWindow(x,horizontalRows[key]?.width ?: px(inner))
            horizontalRows[key]?.scrollTo(x,0)
        }
    }

    /** Home resets navigation, not the unchanged hierarchy and already decoded artwork. */
    private fun resetHomePosition() {
        appReturnViewport=null
        if(!ready || pendingViewport!=null || !root.isLaidOut || root.isLayoutRequested) { render(reset=true); return }
        stopRowMotion()
        // A pending restore from the app we just left must not undo the explicit Home action.
        pendingViewport=null; viewportRevision++; homeRequested=false
        selectNavigation("nav-home")
        firstCard?.requestFocusFromTouch(); lastContentFocus=firstCard
        horizontalRows.values.forEach { it.scrollTo(0,0) }
        root.scrollTo(0,0)
        loadVisibleArt()
    }

    private fun render(reset:Boolean=false,restore:Viewport?=null) {
        if(isFinishing || isDestroyed)return
        stopRowMotion()
        deferredRows.clear()
        if(reset) { homeRequested=true; navigationSelection="nav-home" }
        val saved=if(homeRequested)Viewport() else restore ?: pendingViewport ?: viewport()
        pendingViewport=saved
        val revision=++viewportRevision
        artwork.cancel(); cancelArtworkScan(); ui.removeCallbacks(retryArtwork); cardArt.clear(); focusables.clear(); horizontalRows.clear(); windowedRows.clear(); windowedPrograms.clear(); focusRows.clear(); appCards.clear(); rowSections.clear(); rowValues.clear(); boundPrograms.clear(); firstCard=null; appAnchor=null; libraryAnchor=null; showAnchor=null
        root=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER; clipChildren=false; clipToPadding=false }
        page=column().apply { val inset=if(wide)BalancedHomeLayout.INSET else 56; setPadding(px(inset),0,px(inset),px(40)) }
        val stage=FrameLayout(this).apply { setBackgroundColor(bgColor); clipChildren=false; clipToPadding=false }
        root.addView(page,ViewGroup.LayoutParams(-1,-2)); stage.addView(root,FrameLayout.LayoutParams(-1,-1).apply { topMargin=px(104) })
        buildHeader(stage)
        when(layout) {
            HomeLayout.BALANCED -> buildBalanced()
            HomeLayout.CINEMA -> buildCinema()
            HomeLayout.QUIET -> buildQuiet()
        }
        gap(if(wide)56 else 66); buildAllApps()
        gap(28)
        val foot=button("About prettTV · Credits","credits") { credits() }
        page.addView(text("Up to navigation.  •  Hold OK for options.  •  Menu for settings.",21,muted),LinearLayout.LayoutParams(-1,px(48)))
        page.addView(foot,LinearLayout.LayoutParams(px(330),px(54)))
        firstCard?.nextFocusUpId=focusables["nav-home"]!!.id
        firstCard?.nextFocusLeftId=firstCard!!.id
        lastContentFocus=firstCard
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _,next ->
            if(next?.tag is String && !(next.tag as String).startsWith("nav-"))lastContentFocus=next
        }
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _,next ->
            if(next!=null && deferredRows.any { key->rowSections[key]?.let { !descendant(next,it) }==true })
                ui.post { if(visible && !isFinishing && !isDestroyed)refreshProviderRows() }
        }
        setContentView(stage); ready=true; updateClock()
        updateHomeStatus()
        root.viewTreeObserver.addOnScrollChangedListener { scheduleArtworkScan() }
        val renderedRoot=root
        // Restore after measurement, when horizontal scroll bounds are known. A feed refresh
        // that rebuilds again before this frame inherits the same pending viewport.
        renderedRoot.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                renderedRoot.viewTreeObserver.removeOnPreDrawListener(this)
                if(root!==renderedRoot || revision!=viewportRevision)return true
                restoreRows(saved.rows)
                if(saved.focus?.startsWith("nav-")==true)lastContentFocus=focusTarget(saved.contentFocus) ?: firstCard
                val target=focusTarget(saved.focus) ?: firstCard ?: focusables.values.firstOrNull()
                if(target?.requestFocus()!=true)firstCard?.requestFocus()
                root.scrollTo(0,saved.y)
                revealFocus(currentFocus)
                pendingViewport=null; homeRequested=false
                loadVisibleArt()
                return true
            }
        })
    }
    private fun buildHeader(stage:FrameLayout) {
        val header=row().apply { gravity=Gravity.CENTER_VERTICAL; isBaselineAligned=false; setPadding(px(56),px(16),px(56),px(16)); setBackgroundColor(PrettTvColors.BACKGROUND) }
        val navigation=row()
        val entries=listOf(Triple("Home","nav-home",{ resetHomePosition() }),
            Triple("Shows","nav-shows",{ focusTarget(showAnchor?.tag as? String)?.requestFocusFromTouch(); Unit }),
            Triple("Library","nav-apps",{ libraryAnchor?.requestFocusFromTouch(); Unit }),
            Triple("Inputs","nav-inputs",{ openInputs() }),
            Triple("Search","nav-search",{ search() }),Triple("Settings","nav-settings",{ menu() }),
            Triple("Bluetooth","nav-bluetooth",{ Unit }))
        val buttons=entries.map { (label,key,action)->
            button(label,key) { selectNavigation(key); action() }.apply {
                setPadding(px(22),0,px(22),0)
                setOnFocusChangeListener { _,_->updateNavigation() }
                setOnKeyListener { _,code,event ->
                    when(code) {
                        KeyEvent.KEYCODE_DPAD_UP->true
                        KeyEvent.KEYCODE_DPAD_DOWN->{ if(event.action==KeyEvent.ACTION_DOWN)(focusTarget(lastContentFocus?.tag as? String) ?: firstCard)?.requestFocusFromTouch(); true }
                        else->false
                    }
                }
                if(key!="nav-bluetooth")navigation.addView(this,LinearLayout.LayoutParams(-2,px(56)).apply { rightMargin=px(12) })
                if(key=="nav-bluetooth") {
                    setPadding(px(18),0,px(18),0)
                    minWidth=px(72)
                    bluetoothQuickSwitch=BluetoothQuickSwitch(this,{
                        BluetoothControlSession.toggle(this@MainActivity)
                    },{ safeStart(Intent(this@MainActivity,BluetoothControlActivity::class.java),"Audio devices") })
                        .also { it.bind(BluetoothControlSession.state) }
                }
            }
        }
        buttons.forEachIndexed { index,button ->
            button.nextFocusLeftId=buttons[(index-1).coerceAtLeast(0)].id
            button.nextFocusRightId=buttons[(index+1).coerceAtMost(buttons.lastIndex)].id
        }
        val navScroll=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false; isFocusable=false; overScrollMode=View.OVER_SCROLL_NEVER
            addView(navigation,ViewGroup.LayoutParams(-2,-1))
        }
        header.addView(navScroll,LinearLayout.LayoutParams(0,px(56),1f))
        homeStatus=text("",19,muted).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.END; maxWidth=px(270) }
        header.addView(homeStatus,LinearLayout.LayoutParams(-2,px(56)).apply { leftMargin=px(14) })
        weatherHeader=WeatherHeaderView(this,scale)
        header.addView(weatherHeader,LinearLayout.LayoutParams(-2,px(56)).apply { leftMargin=px(22) })
        clock=text("",26,ink).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.END }
        header.addView(clock,LinearLayout.LayoutParams(-2,px(56)))
        // Keep the quick switch at the trailing edge, after the passive weather and clock.
        header.addView(buttons.last(),LinearLayout.LayoutParams(-2,px(56)).apply { leftMargin=px(22) })
        updateWeatherHeader()
        val bar=FrameLayout(this).apply {
            setBackgroundColor(PrettTvColors.BACKGROUND)
            addView(header,FrameLayout.LayoutParams(-1,-1))
            addView(View(this@MainActivity).apply { setBackgroundColor(PrettTvColors.DIVIDER) },FrameLayout.LayoutParams(-1,px(1),Gravity.BOTTOM))
        }
        navigationBar=bar
        stage.addView(bar,FrameLayout.LayoutParams(-1,px(88)))
        updateNavigation()
        reminderBanner=button("","kickoff-reminder") {
            visibleReminders().firstOrNull()?.let { dismissedReminders.add(SportsPolicy.reminderKey(it)); contentRepo.acknowledgeReminder(it) }
            updateHomeStatus()
        }.apply { visibility=View.GONE; gravity=Gravity.CENTER_VERTICAL; maxLines=2 }
        page.addView(reminderBanner,LinearLayout.LayoutParams(-1,px(70)))
    }
    private fun buildBalanced() {
        gap(BalancedHomeLayout.TOP_GAP)
        val upper=row()
        val feature=content.feature
        val stageHeight=BalancedHomeLayout.stageHeight(balancedFont)
        val hero=programCard(feature,PrettTvCard.Mode.SPLIT_HERO,"feature",BalancedHomeLayout.HERO_WIDTH,stageHeight)
        upper.addView(hero,LinearLayout.LayoutParams(px(BalancedHomeLayout.HERO_WIDTH),px(stageHeight)).apply { rightMargin=px(BalancedHomeLayout.SPLIT_GAP) })
        firstCard=hero
        val side=column()
        side.addView(text("Discover",29,ink,true).apply { gravity=Gravity.TOP },LinearLayout.LayoutParams(-1,px(BalancedHomeLayout.headingHeight(balancedFont))))
        val shelf=FrameLayout(this).apply { clipChildren=false; clipToPadding=false }
        val posters=row()
        val next=balancedDiscovery()
        repeat(BalancedHomeLayout.POSTER_COUNT) { i ->
            posters.addView(programCard(next.getOrNull(i),PrettTvCard.Mode.POSTER,"next-$i",BalancedHomeLayout.POSTER_WIDTH,BalancedHomeLayout.posterHeight(balancedFont)),
                LinearLayout.LayoutParams(px(BalancedHomeLayout.POSTER_WIDTH),px(BalancedHomeLayout.posterHeight(balancedFont))).apply { if(i<2)rightMargin=px(BalancedHomeLayout.POSTER_GAP) })
        }
        shelf.addView(posters,FrameLayout.LayoutParams(-1,-1))
        val empty=button("No recommendations yet\nOpen your apps to explore","discovery-empty") { appAnchor?.requestFocusFromTouch() }.apply {
            maxLines=3; gravity=Gravity.CENTER; setPadding(px(24),px(24),px(24),px(24))
        }
        shelf.addView(empty,FrameLayout.LayoutParams(-1,-1))
        side.addView(shelf,LinearLayout.LayoutParams(-1,px(BalancedHomeLayout.posterHeight(balancedFont))))
        side.addView(View(this),LinearLayout.LayoutParams(1,0,1f))
        val browse=button("Browse shows","browse-shows") { browseDiscovery() }.apply {
            gravity=Gravity.CENTER_VERTICAL; setPadding(px(19),0,px(19),0)
            background=round(PrettTvColors.SURFACE,PrettTvColors.EDGE)
            setOnFocusChangeListener { _,focused -> background=round(PrettTvColors.SURFACE,if(focused)ink else PrettTvColors.EDGE) }
        }
        side.addView(browse,LinearLayout.LayoutParams(-1,px(BalancedHomeLayout.browseHeight(balancedFont))))
        upper.addView(side,LinearLayout.LayoutParams(px(BalancedHomeLayout.DISCOVERY_WIDTH),px(stageHeight)))
        page.addView(upper); gap(36); buildApps(94); gap(36)
        updateBalancedDiscovery(next)
        buildContentRows()
    }
    private fun browseDiscovery() {
        val target=listOf("explore","discover","trending").firstNotNullOfOrNull { key ->
            windowedRows[key]?.ensure(0) ?: rowSections[key]?.let { section ->
                focusables.values.firstOrNull { it.isFocusable && it.visibility==View.VISIBLE && descendant(it,section) }
            }
        } ?: showAnchor ?: appAnchor
        target?.requestFocusFromTouch()
        revealFocus(target)
    }
    private fun updateBalancedDiscovery(next:List<Program>) {
        val hero=focusables["feature"] ?: return
        val browse=focusables["browse-shows"] ?: return
        val empty=focusables["discovery-empty"] ?: return
        val posters=(0 until BalancedHomeLayout.POSTER_COUNT).mapNotNull { focusables["next-$it"] }
        val focused=currentFocus
        val focusedProgram=if(focused in posters)boundPrograms[focused?.tag as? String]?.id else null
        posters.forEachIndexed { i,card ->
            updateBoundProgram("next-$i",next.getOrNull(i),BalancedHomeLayout.POSTER_WIDTH,BalancedHomeLayout.posterHeight(balancedFont))
            card.visibility=if(i<next.size)View.VISIBLE else View.INVISIBLE
        }
        val available=posters.take(next.size)
        empty.visibility=if(available.isEmpty())View.VISIBLE else View.GONE
        val entry=available.firstOrNull() ?: empty
        hero.nextFocusRightId=entry.id
        available.forEachIndexed { i,card ->
            card.nextFocusLeftId=available.getOrNull(i-1)?.id ?: hero.id
            card.nextFocusRightId=available.getOrNull(i+1)?.id ?: card.id
            card.nextFocusUpId=focusables.getValue("nav-home").id
            card.nextFocusDownId=browse.id
        }
        empty.nextFocusLeftId=hero.id; empty.nextFocusRightId=empty.id
        empty.nextFocusUpId=focusables.getValue("nav-home").id; empty.nextFocusDownId=browse.id
        browse.nextFocusLeftId=hero.id; browse.nextFocusRightId=browse.id
        browse.nextFocusUpId=entry.id; browse.nextFocusDownId=appAnchor?.id ?: hero.id
        focusables.filterKeys { it.startsWith("favorite-") || it=="empty-app-row" }.values.forEachIndexed { i,view ->
            view.nextFocusUpId=if(i<5)hero.id else browse.id
        }
        val retained=focusedProgram?.let { id->next.indexOfFirst { it.id==id }.takeIf { it>=0 }?.let(available::getOrNull) }
        if(retained!=null && retained!==focused)retained.requestFocusFromTouch()
        else if((focused in posters || focused===empty) && focused?.visibility!=View.VISIBLE)entry.requestFocusFromTouch()
    }
    private fun buildCinema() {
        val hero=programCard(content.feature,PrettTvCard.Mode.HERO,"feature",inner,470)
        firstCard=hero; page.addView(hero,LinearLayout.LayoutParams(-1,px(470))); gap(18)
        buildApps(116); gap(15)
        buildContentRows()
    }
    private fun buildQuiet() {
        page.addView(text("A little less noise. More of what you love.",28,muted),LinearLayout.LayoutParams(-1,px(42))); gap(20)
        buildApps(176); gap(20)
        buildContentRows()
    }
    private fun buildContentRows() {
        showAnchor=null
        val config=personal.rowPreferences(); renderedRowPreferences=config
        for(key in config.ordered(rowLabels.keys).filter { it !in config.hidden && (it!="episodes" || content.newEpisodes.isNotEmpty()) }) {
            val container=column(); page.addView(container); rowSections[key]=container
            fillContentRow(key,container,config); rowValues[key]=rowValue(key)
        }
    }
    private val rowLabels get()=linkedMapOf("watching-shows" to "Continue watching · Shows","watching-movies" to "Continue watching · Movies",
        "watchlist" to "Watchlist","episodes" to "New episodes","sports" to "Live sports","discover" to "Suggestions","explore" to "Discover","trending" to "Trending","new" to "New releases")
    private fun programsFor(key:String):List<Program> = when(key) {
        "watching-shows"->content.visibleWatching.filter { it.mediaType=="series" }
        "watching-movies"->content.visibleWatching.filter { it.mediaType=="movie" }
        "watchlist"->personal.watchlist()
        "episodes"->content.newEpisodes.map { it.asProgram() }
        "discover"->content.suggestions
        "explore"->content.discover
        "trending"->content.trending
        "new"->content.newReleases
        else->emptyList()
    }
    private fun rowValue(key:String):Any = if(key=="sports")listOf(content.games,content.liveSports,sportsPackages) else programsFor(key)
    private fun fillContentRow(key:String,container:LinearLayout,config:RowPreferences) {
        val outer=page; page=container
        try {
            if(key=="sports")buildLiveSports() else {
                val continuation=wide && key.startsWith("watching-") && config.size(key)==RowCardSize.STANDARD
                val height=if(continuation)BalancedHomeLayout.continuationHeight(balancedFont) else when(config.size(key)) { RowCardSize.SMALL->190; RowCardSize.STANDARD->218; RowCardSize.LARGE->270 }
                val note=when(key) { "watchlist"->"Saved by you"; "episodes"->"Episode released · availability unconfirmed"; "new"->"Past 120 days"; "watching-shows","watching-movies"->"Most recent first"; else->"" }
                val empty=when(key) { "watchlist"->"Save something for later\nHold OK on a title → Add to Watchlist"; "watching-shows"->"No shared shows yet\nOpen your apps to start watching"; "watching-movies"->"No shared movies yet\nOpen your apps to start watching"; else->"" }
                val all=programsFor(key)
                val titles=when {
                    key=="watchlist"->{ watchlistOffset=watchlistOffset.coerceIn(0,((all.size-1).coerceAtLeast(0)/60)*60);all.drop(watchlistOffset).take(60) }
                    key in setOf("discover","explore","trending","new")->all.take(config.limit(key))
                    else->all
                }
                fun turnSavedPage(offset:Int) {
                    watchlistOffset=offset; rowValues.remove("watchlist")
                    nextRowFocus=personal.watchlist().getOrNull(offset)?.let { "watchlist-${ContentIdentity.key(it)}" }
                    refreshHomeRows()
                }
                val previous=if(key=="watchlist" && watchlistOffset>0)"Newer saved titles" to { turnSavedPage(watchlistOffset-60) } else null
                val next=if(key=="watchlist" && watchlistOffset+60<all.size)"Older saved titles" to { turnSavedPage(watchlistOffset+60) } else null
                val sourceNote=if(key=="watchlist" && all.size>60)"${watchlistOffset+1}–${watchlistOffset+titles.size} of ${all.size} saved" else note
                buildShowRow(rowLabels[key].orEmpty(),titles,height,key,if(continuation)3 else config.size(key).visibleCards,sourceNote,empty,previous,next,
                    if(continuation)PrettTvCard.Mode.CONTINUATION else PrettTvCard.Mode.SHOW)
            }
            gap(24)
        } finally { page=outer }
    }
    private fun refreshProviderRows() {
        if(pausedForContent || appActionPanel?.isShowing==true) { providerRefreshPending=true; return }
        providerRefreshPending=false; deferActiveRefresh=true
        try { refreshHomeRows() } finally { deferActiveRefresh=false }
    }
    /** Keep browsing rows stable, but apply Continue watching chronology immediately. */
    private fun refreshHomeRows() {
        if(!ready || isFinishing || isDestroyed)return
        val config=personal.rowPreferences()
        val keys=config.ordered(rowLabels.keys).filter { it !in config.hidden && (it!="episodes" || content.newEpisodes.isNotEmpty()) }
        if(config!=renderedRowPreferences || keys!=rowSections.keys.toList()) { render(); return }
        val requestedFocus=nextRowFocus
        var saved=if(requestedFocus!=null)viewport(requestedFocus) else pendingViewport ?: viewport()
        nextRowFocus=null
        var rebuilt=false
        val oldFocus=currentFocus
        var replacement:String?=null
        for((key,container) in rowSections) {
            val value=rowValue(key)
            if(rowValues[key]==value)continue
            val oldCards=focusables.values.filter { descendant(it,container) }
            val oldIndex=windowedRows[key]?.indexOf(oldFocus) ?: oldCards.indexOf(oldFocus)
            // Playback return normally preserves focus in this row. Deferring its order
            // would refresh the episode/progress yet leave the newest viewing buried.
            val keepOrder=deferActiveRefresh && oldIndex>=0 && key!="watching-shows" && key!="watching-movies"
            if(key!="sports" && rebindStableRow(key,oldCards,keepOrder)) {
                if(keepOrder && rowValues[key]!=value)deferredRows.add(key)
                else { rowValues[key]=value; deferredRows.remove(key) }
                continue
            }
            deferredRows.remove(key)
            if(!rebuilt) {
                stopRowMotion(); rebuilt=true
                saved=if(requestedFocus!=null)viewport(requestedFocus) else pendingViewport ?: viewport()
            }
            forgetRow(container); container.removeAllViews()
            fillContentRow(key,container,config); rowValues[key]=value
            if(oldIndex>=0)replacement=windowedRows[key]?.let { window->
                window.ensure(oldIndex.coerceAtMost(window.keys.lastIndex))?.tag as? String
            } ?: focusables.values.filter { descendant(it,container) }.let { it.getOrNull(oldIndex.coerceAtMost(it.lastIndex))?.tag as? String }
        }
        if(layout!=HomeLayout.QUIET) {
            val feature=content.feature
            updateBoundProgram("feature",feature,if(wide)BalancedHomeLayout.HERO_WIDTH else inner,if(wide)BalancedHomeLayout.stageHeight(balancedFont) else 470)
            if(wide)updateBalancedDiscovery(balancedDiscovery())
        }
        updateHomeStatus()
        showAnchor=rowSections.values.asSequence().flatMap { section -> focusables.values.asSequence().filter { descendant(it,section) } }.firstOrNull() ?: firstCard
        if(!rebuilt) { loadVisibleArt(); return }
        pendingViewport=saved
        val revision=++viewportRevision
        if(focusTarget(saved.focus)==null && replacement!=null)pendingViewport=saved.copy(focus=replacement)
        val renderedRoot=root
        renderedRoot.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                renderedRoot.viewTreeObserver.removeOnPreDrawListener(this)
                if(root!==renderedRoot || revision!=viewportRevision)return true
                restoreRows(saved.rows)
                (focusTarget(saved.focus) ?: focusTarget(replacement) ?: firstCard)?.requestFocus()
                renderedRoot.scrollTo(0,saved.y); revealFocus(currentFocus)
                pendingViewport=null; homeRequested=false
                loadVisibleArt(); return true
            }
        })
    }
    private fun rebindStableRow(key:String,views:List<View>,keepOrder:Boolean):Boolean {
        if(key=="watchlist")return false // Pagination controls are part of this row's structure.
        val cards=views.filterIsInstance<PrettTvCard>()
        if(cards.isEmpty() || cards.any { it.width<=0 || it.height<=0 })return false
        val config=personal.rowPreferences()
        val next=programsFor(key).let { if(key in setOf("discover","explore","trending","new"))it.take(config.limit(key)) else it }
        val old=windowedPrograms[key] ?: cards.map { boundPrograms[it.tag as? String] ?: return false }
        if(next.map { it.id }.distinct().size!=next.size)return false
        val byId=next.associateBy { it.id }
        // Removed/expired content must never linger just because a row has focus.
        if(old.any { it.id !in byId })return false
        if(!keepOrder && old.map { it.id }!=next.map { it.id })return false
        if(key in windowedPrograms)windowedPrograms[key]=old.map { byId.getValue(it.id) }
        cards.forEach { card->
            val previous=boundPrograms[card.tag as? String] ?: return false
            val updated=byId.getValue(previous.id)
            if(previous!=updated)bindProgram(card,updated,(card.width/scale).toInt(),(card.height/scale).toInt())
        }
        return true
    }
    private fun descendant(view:View,parent:ViewGroup):Boolean {
        var item:android.view.ViewParent?=view.parent
        while(item!=null) { if(item===parent)return true; item=item.parent }
        return false
    }
    /** Retain the old viewport where possible, but never strand focus after a row moves. */
    private fun revealFocus(target:View?) {
        if(target==null || !descendant(target,root))return
        for(scroll in horizontalRows.values)if(descendant(target,scroll)) {
            val bounds=Rect(0,0,target.width,target.height)
            scroll.offsetDescendantRectToMyCoords(target,bounds)
            val left=scroll.scrollX; val right=left+scroll.width
            val dx=when { bounds.left<left->bounds.left-left; bounds.right>right->bounds.right-right; else->0 }
            if(dx!=0)scroll.scrollBy(dx,0)
        }
        val bounds=Rect(0,0,target.width,target.height)
        root.offsetDescendantRectToMyCoords(target,bounds)
        val top=root.scrollY; val bottom=top+root.height
        val dy=when { bounds.top<top->bounds.top-top; bounds.bottom>bottom->bounds.bottom-bottom; else->0 }
        if(dy!=0)root.scrollBy(0,dy)
    }
    private fun forgetRow(container:ViewGroup) {
        val removed=focusables.values.filter { descendant(it,container) }.toSet()
        removed.forEach { view->focusables.remove(view.tag as? String); boundPrograms.remove(view.tag as? String); focusRows.remove(view) }
        horizontalRows.entries.removeAll { descendant(it.value,container) }
        windowedRows.entries.removeAll { if(descendant(it.value,container)) { windowedPrograms.remove(it.key);true } else false }
        cardArt.removeAll { if(it.card in removed) { it.request.cancel();it.ticket?.cancel(); true } else false }
        appCards.values.forEach { cards->cards.removeAll { it in removed } }
        if(lastContentFocus in removed)lastContentFocus=null
    }
    private fun updateHomeStatus() {
        val age=if(content.snapshotAt>0)android.text.format.DateUtils.getRelativeTimeSpanString(content.snapshotAt,System.currentTimeMillis(),60_000).toString() else ""
        homeStatus?.text=when {
            content.refreshing->"Updating…"
            content.offline->"Offline · ${if(age.isEmpty())"saved content" else "saved $age"}"
            content.refreshFailed->"Update unavailable${if(age.isEmpty())"" else " · saved $age"}"
            else->""
        }
        val reminders=visibleReminders()
        reminderBanner?.apply { text=reminders.firstOrNull()?.let { "Starting soon · ${it.title} · OK to dismiss" }.orEmpty(); visibility=if(reminders.isEmpty())View.GONE else View.VISIBLE }
    }
    private fun visibleReminders():List<SportsEvent> {
        val now=System.currentTimeMillis()
        return content.kickoffReminders.filter { SportsPolicy.reminderKey(it) !in dismissedReminders && it.fresh(now) && it.startsAt>now }
    }
    private fun buildLiveSports() {
        val size=personal.rowPreferences().size("sports")
        val count=size.visibleCards
        val cardHeight=when(size) { RowCardSize.SMALL->190; RowCardSize.STANDARD->218; RowCardSize.LARGE->270 }
        if(content.games.isNotEmpty()) {
            page.addView(section("Live sports","Live & upcoming · ESPN · Opens ${sportsDestination()?.label ?: "sports apps"}"))
            val strip=row(); val width=(inner-count*20-46)/count
            val now=System.currentTimeMillis()
            content.games.forEachIndexed { i,event ->
                val tile=PrettTvCard(this,scale,PrettTvCard.Mode.SPORT).apply {
                    register(this,"game-${event.id}"); title=event.title; summary=event.heading(now); detail=event.detail(now); action=event.network.ifBlank { event.league }
                    val destination=sportsDestination()?.label ?: "sports apps"
                    contentDescription="$title. $summary. $detail. Open $destination; select the channel in the app."
                    setOnClickListener { openSports(event) }
                    setOnLongClickListener { sportsInfo(event); true }
                }
                if(event.logo.isNotBlank())cardArt.add(CardArt(tile,event.logo,px(width),px(cardHeight)))
                strip.addView(tile,LinearLayout.LayoutParams(px(width),px(cardHeight)).apply { if(i<content.games.lastIndex)rightMargin=px(20) })
            }
            constrainRow(strip)
            val scroll=HorizontalScrollView(this).apply {
                isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
                clipChildren=true; clipToPadding=true; setPadding(0,px(6),0,px(6)); addView(strip,ViewGroup.LayoutParams(-2,px(cardHeight)))
            }
            page.addView(scroll,LinearLayout.LayoutParams(-1,px(cardHeight+12)))
            horizontalRows["sports-games"]=scroll
            gap(12)
        }
        if(content.liveSports.isNotEmpty()) {
            buildShowRow(if(content.games.isEmpty())"Live sports" else "From your sports apps",content.liveSports,cardHeight,"live-events",count,"Live events shared by apps")
            gap(12)
        } else if(content.games.isEmpty())page.addView(section("Live sports","Open a sports app"))
        val chosen=orderedApps().filter { it.packageName in sportsPackages }
        if(chosen.isEmpty()) {
            page.addView(button("Choose your sports apps","choose-sports") { chooseSportsApps() },LinearLayout.LayoutParams(-1,px(100)))
            return
        }
        val strip=row()
        chosen.forEach { e ->
            val tile=column()
            tile.addView(appCard(e,"sports-${e.key}"),LinearLayout.LayoutParams(px(320),px(112)))
            tile.addView(text("Open ${e.label}",22,muted),LinearLayout.LayoutParams(px(320),px(40)))
            strip.addView(tile,LinearLayout.LayoutParams(px(320),px(152)).apply { rightMargin=px(20) })
        }
        constrainRow(strip)
        val scroll=HorizontalScrollView(this).apply {
            isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true; clipToPadding=true; setPadding(0,px(5),0,px(5))
            addView(strip,ViewGroup.LayoutParams(-2,px(152)))
        }
        page.addView(scroll,LinearLayout.LayoutParams(-1,px(162)))
        horizontalRows["sports-apps"]=scroll
    }
    private fun sportsDestination():AppEntry? = orderedApps().filter { it.packageName in sportsPackages }
        .let { entries -> entries.firstOrNull { it.packageName=="com.rblive.app" } ?: entries.firstOrNull() }
    private fun openSports(event:SportsEvent) {
        val target=sportsDestination()
        if(target==null) { chooseSportsApps(); return }
        if(target.packageName=="com.rblive.app" && RbtvHandoffService.supported(this,event)) {
            if(RbtvHandoffService.enabled(this)) {
                RbtvHandoffService.begin(event)
                val home=Intent().setClassName("com.rblive.app","com.rblive.tv.ui.home.HomeActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if(safeStart(home,target.label))return
                RbtvHandoffService.cancel()
            } else {
                AlertDialog.Builder(this).setTitle("Open the matching game")
                    .setMessage("prettTV can find this game's two teams and kickoff time in RBTV, then leave the server choice to you. Enable prettTV game handoff in Accessibility. The helper operates only inside RBTV after you choose a game, for up to 30 seconds.")
                    .setPositiveButton("Enable helper") { _,_ -> safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),"Accessibility settings") }
                    .setNeutralButton("Open RBTV") { _,_ -> launch(target) }.setNegativeButton("Cancel",null).show()
                return
            }
        }
        launch(target)
        Toast.makeText(this,"${event.title}: look for ${event.network.ifBlank { "the game" }} in ${target.label}. Channel availability varies.",Toast.LENGTH_LONG).show()
    }
    private fun sportsInfo(event:SportsEvent) {
        AlertDialog.Builder(this).setTitle(event.title)
            .setMessage("${event.heading(System.currentTimeMillis())}\n${event.detail(System.currentTimeMillis())}\n\nSchedule: ESPN. Broadcast labels do not guarantee availability. With game handoff enabled, prettTV looks for matching teams and kickoff in RBTV 3.0.320 for NFL, college football, NBA and Premier League. Unmatched events and other versions/leagues open RBTV for manual selection. You choose the server.")
            .setPositiveButton("Open ${sportsDestination()?.label ?: "sports apps"}") { _,_ -> openSports(event) }.setNegativeButton("Close",null).show()
    }
    private fun programCard(p:Program?,mode:PrettTvCard.Mode,key:String,w:Int,h:Int):PrettTvCard {
        val card=PrettTvCard(this,scale,mode)
        register(card,key)
        bindProgram(card,p,w,h)
        if(showAnchor==null)showAnchor=card
        return card
    }
    private fun updateBoundProgram(key:String,p:Program?,w:Int,h:Int) {
        val card=focusables[key] as? PrettTvCard ?: return
        if(boundPrograms[key]==p && cardArt.firstOrNull { it.card===card }?.url.orEmpty()==programArtwork(key,p))return
        bindProgram(card,p,w,h)
    }
    private fun programArtwork(key:String?,program:Program?):String {
        if(program==null)return ""
        val display=if(wide)content.presentationArtwork[program.id] else null
        return when {
            wide && key?.startsWith("next-")==true -> display?.poster?.ifBlank { null } ?: program.posterArt.ifBlank { program.art }
            wide && key=="feature" -> display?.backdrop?.ifBlank { null } ?: program.art
            else -> program.art
        }
    }
    private fun bindProgram(card:PrettTvCard,p:Program?,w:Int,h:Int) {
        val old=boundPrograms[card.tag as? String]
        val sameTitle=old!=null && p!=null && ContentIdentity.key(old)==ContentIdentity.key(p) && old.packageName==p.packageName
        fun artworkFor(program:Program?)=programArtwork(card.tag as? String,program)
        val artUrl=artworkFor(p).ifBlank { if(sameTitle)artworkFor(old) else "" }
        val existingArt=cardArt.firstOrNull { it.card===card }
        if(!sameTitle || (artUrl.isBlank() && existingArt!=null))card.art=null
        if(existingArt==null || existingArt.url!=artUrl || existingArt.w!=px(w) || existingArt.h!=px(h))
            cardArt.removeAll { if(it.card===card) { it.request.cancel();it.ticket?.cancel(); true } else false }
        boundPrograms[card.tag as String]=p
        val presentation=p?.let(ProgramPresentation::from)
        card.title=p?.title ?: if(discovery)"Find your next favorite" else "Make yourself at home"
        card.detail=if(card.tag=="feature" && presentation!=null)presentation.metadata else p?.detail ?: if(discovery)"Browse your streaming apps" else "Your apps, one quiet place"
        card.sourceLabel=presentation?.source.orEmpty()
        card.secondaryDetail=if(card.tag=="feature")presentation?.remaining.orEmpty() else presentation?.selectedDetail.orEmpty()
        card.summary=p?.summary.orEmpty()
        card.action=if(p==null)"Browse apps" else if(presentation?.canResume==true)"Resume" else "Open ${p.app}"
        card.progress=if(p!=null && !p.catalog && p.position>0 && p.duration>p.position)p.progress else 0f
        card.stremio=p?.packageName=="com.stremio.one"
        card.contentDescription=listOf(card.title,p?.detail ?: card.detail,card.action,card.sourceLabel).filter(String::isNotBlank).distinct().joinToString(". ")
        card.setOnClickListener { if(p==null)appAnchor?.requestFocus() else openProgram(p) }
        card.setOnLongClickListener { if(p!=null)programInfo(p) else menu(); true }
        if(artUrl.isNotBlank() && cardArt.none { it.card===card })cardArt.add(CardArt(card,artUrl,px(w),px(h)))
        card.invalidate()
    }
    private fun buildShowRow(title:String,programs:List<Program>,height:Int,key:String,count:Int,note:String="",emptyText:String="",previous:Pair<String,()->Unit>?=null,next:Pair<String,()->Unit>?=null,mode:PrettTvCard.Mode=PrettTvCard.Mode.SHOW) {
        page.addView(section(title,if(programs.isNotEmpty())listOf("${programs.size} titles",note).filter(String::isNotBlank).joinToString(" · ") else note))
        if(wide)gap(10)
        // Leave a visible slice of the next card to make horizontal scrolling discoverable.
        val cardGap=if(mode==PrettTvCard.Mode.CONTINUATION)16 else 20
        val width=if(mode==PrettTvCard.Mode.CONTINUATION)(inner-(count-1)*cardGap)/count else (inner-count*cardGap-46)/count
        if(programs.size>16 && key in setOf("discover","explore","trending","new")) {
            windowedPrograms[key]=programs
            val strip=WindowedRow(this,programs.map { "$key-${it.id}" },px(width),px(height),px(20),{ index->
                val p=windowedPrograms.getValue(key)[index]
                programCard(p,PrettTvCard.Mode.SHOW,"$key-${p.id}",width,height)
            },{ view->
                focusables.remove(view.tag as? String);boundPrograms.remove(view.tag as? String);focusRows.remove(view)
                cardArt.removeAll { if(it.card===view) { it.ticket?.cancel();it.request.cancel();true } else false }
                (view as? PrettTvCard)?.art=null
                // Keep its tag for header/Back restoration even when the card is remounted later.
            },::constrainRow)
            windowedRows[key]=strip
            val scroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;isFocusable=false
                clipChildren=true;clipToPadding=true;setPadding(0,px(6),0,px(6))
                addView(strip,ViewGroup.LayoutParams(-2,px(height)))
                setOnScrollChangeListener { _,x,_,_,_->strip.updateWindow(x,this.width) }
            }
            horizontalRows[key]=scroll
            strip.updateWindow(0,px(inner))
            page.addView(scroll,LinearLayout.LayoutParams(-1,px(height+12)))
            return
        }
        val strip=row()
        previous?.let { (label,action)->strip.addView(button(label,"$key-page-previous",action).apply { maxLines=2 },LinearLayout.LayoutParams(px(width),px(height)).apply { rightMargin=px(20) }) }
        if(programs.isEmpty()) {
            val needsPermission=key.startsWith("watching") && checkSelfPermission("android.permission.READ_TV_LISTINGS")!=android.content.pm.PackageManager.PERMISSION_GRANTED
            val label=if(needsPermission)"Allow TV listings to see your progress\nPress OK to enable" else emptyText.ifBlank { if(discovery)"No titles available yet\nOpen apps or refresh from Settings" else "Discovery is off\nEnable discovery in Settings" }
            val empty=button(label, "$key-empty") {
                if(key.startsWith("watching") && checkSelfPermission("android.permission.READ_TV_LISTINGS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf("android.permission.READ_TV_LISTINGS"),41)
                else if(discovery || key.startsWith("watching"))appAnchor?.requestFocus() else menu()
            }.apply {
                maxLines=2; gravity=Gravity.CENTER_VERTICAL; setPadding(px(32),0,px(32),0)
                background=round(PrettTvColors.SURFACE,PrettTvColors.EDGE)
                setOnFocusChangeListener { _,f -> background=round(if(f)PrettTvColors.SELECTED else PrettTvColors.SURFACE,if(f)ink else PrettTvColors.EDGE); setTextColor(ink) }
            }
            if(showAnchor==null)showAnchor=empty
            strip.addView(empty,LinearLayout.LayoutParams(-1,px(height)))
        } else programs.forEachIndexed { i,p ->
            val identity=if(key=="watchlist")ContentIdentity.key(p) else p.id
            strip.addView(programCard(p,mode,"$key-$identity",width,height),LinearLayout.LayoutParams(px(width),px(height)).apply { if(i<programs.lastIndex)rightMargin=px(cardGap) })
        }
        next?.let { (label,action)->strip.addView(button(label,"$key-page-next",action).apply { maxLines=2 },LinearLayout.LayoutParams(px(width),px(height)).apply { leftMargin=px(20) }) }
        if(programs.isEmpty())page.addView(strip) else {
            val scroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
                isFocusable=false; clipChildren=true; clipToPadding=true; setPadding(0,px(6),0,px(6))
                addView(strip,ViewGroup.LayoutParams(-2,px(height)))
            }
            horizontalRows[key]=scroll
            page.addView(scroll,LinearLayout.LayoutParams(-1,px(height+12)))
        }
        constrainRow(strip)
    }
    // Scrolling remains frame-driven; visibility/network work runs at most once per 80 ms.
    // Keep one trailing scan so the final viewport is always populated after movement stops.
    private fun scheduleArtworkScan() {
        if(!visible || artworkScanScheduled)return
        artworkScanScheduled=true; ui.postDelayed(scanArtwork,80)
    }
    private fun cancelArtworkScan() {
        ui.removeCallbacks(scanArtwork); artworkScanScheduled=false
    }
    private fun loadVisibleArt() {
        if(!visible || isDestroyed)return
        val rect=Rect()
        if(!iconsLoading && apps.any { entry->appCards[entry.key].orEmpty().any {
            it.art==null && "${entry.key}:${it.isLibraryCard}" !in failedIcons && it.getGlobalVisibleRect(rect)
        } })loadAppArtwork(visibleOnly=true)
        val visibility=ArtworkVisibilityScan()
        val shown=cardArt.filter { visibility.isVisible(it.card,rect) && rect.width()>px(20) && rect.height()>px(20) }.toSet()
        // At most two near neighbours across the screen, in the user's current direction.
        val neighbours=cardArt.asSequence().filter { it !in shown }.filter { item->
            val scroll=item.card.parent?.parent as? HorizontalScrollView ?: return@filter false
            if(!scroll.getGlobalVisibleRect(rect))return@filter false
            val left=scroll.scrollX;val right=left+scroll.width
            if(scrollDirection>0)item.card.left>=right && item.card.left<right+item.w+px(20)
            else item.card.right<=left && item.card.right>left-item.w-px(20)
        }.take(2).toSet()
        for(item in cardArt.toList().sortedBy { if(it in shown)0 else 1 }) {
            if(item in shown || item in neighbours) {
                if(item in shown)item.ticket?.promote()
                if((!item.loaded || item.card.art==null) && item.request.begin(SystemClock.uptimeMillis())) {
                    item.ticket=artwork.load(item.url,item.w,item.h,prefetch=item !in shown) { bitmap ->
                        // A replaced row/hero can still have a decoding request in flight.
                        if(cardArt.none { it===item })return@load
                        item.request.complete(bitmap!=null,SystemClock.uptimeMillis())
                        if(bitmap!=null && visible && item.card.getGlobalVisibleRect(Rect())) { item.card.art=bitmap; item.loaded=true }
                        if(bitmap==null && visible) { ui.removeCallbacks(retryArtwork); ui.postDelayed(retryArtwork,30_000) }
                    }
                }
            } else {
                item.ticket?.cancel();item.ticket=null;item.request.cancel()
                if(item.card.art!=null) { item.card.art=null; item.loaded=false }
            }
        }
    }
    private fun orderedApps():List<AppEntry> {
        val items=apps.map { HomeModel.Item(it.key,it.packageName,it.label) }
        if(!prefs.favouritesInitialised && apps.isNotEmpty()) { prefs.favourites=HomeModel.initialFavourites(repo.defaultFavouritePackages,items); prefs.favouritesInitialised=true }
        val arranged=HomeModel.arrange(items,prefs.favourites,emptySet(),prefs.recentOrder(),emptyList())
        val byKey=apps.associateBy { it.key }
        return (arranged.favourites+arranged.rest).mapNotNull { byKey[it] }.distinctBy { it.key }
    }
    private fun appCard(e:AppEntry,key:String,cornerRadius:Float=15f,library:Boolean=false):PrettTvCard=PrettTvCard(this,scale,if(library)PrettTvCard.Mode.LIBRARY else PrettTvCard.Mode.APP,cornerRadius).apply {
        title=e.label; contentDescription=e.label; logoBounds=e.logoBounds; art=if(library)e.libraryBitmap else e.bitmap; register(this,key)
        appCards.getOrPut(e.key){ArrayList()}.add(this)
        setOnClickListener { launch(e) }; setOnLongClickListener { appMenu(e,key); true }
        if(appAnchor==null)appAnchor=this
        if(firstCard==null)firstCard=this
    }
    private fun buildApps(height:Int) {
        val entries=orderedApps().filter { it.key !in prefs.hidden }
        page.addView(section("Your apps","Hold OK for app actions"))
        if(wide)gap(14)
        val strip=row(); val width=208
        if(entries.isEmpty()) {
            val choose=button(if(apps.isEmpty()) { if(appLoadError)"Apps unavailable · Retry" else if(appDiscoveryComplete)"No apps found · Retry" else "Loading your apps…" } else "Choose apps for this row","empty-app-row") { if(apps.isEmpty())reload() else reviewApps() }
            appAnchor=choose; if(firstCard==null)firstCard=choose
            choose.nextFocusLeftId=choose.id
            choose.nextFocusUpId=focusables["nav-home"]!!.id
            firstCard?.nextFocusDownId=choose.id
            strip.addView(choose,LinearLayout.LayoutParams(-1,px(height)))
            page.addView(strip); return
        }
        val scroll=HorizontalScrollView(this).apply {
            isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true; clipToPadding=true; isSmoothScrollingEnabled=android.animation.ValueAnimator.areAnimatorsEnabled(); setPadding(0,px(4),0,px(4))
        }
        horizontalRows["your-apps"]=scroll
        entries.forEachIndexed { i,e ->
            strip.addView(appCard(e,"favorite-${e.key}",36f),LinearLayout.LayoutParams(px(width),px(height)).apply { if(i<entries.lastIndex)rightMargin=px(12) })
        }
        if(firstCard?.tag=="feature")firstCard?.nextFocusDownId=strip.getChildAt(0).id
        constrainRow(strip)
        scroll.addView(strip,ViewGroup.LayoutParams(-2,px(height)))
        page.addView(scroll,LinearLayout.LayoutParams(-1,px(height+8)))
    }
    private fun buildAllApps() {
        page.addView(section("Library · All apps"))
        val all=orderedApps(); val width=(inner-5*18)/6
        all.chunked(6).forEach { entries ->
            val strip=row()
            entries.forEachIndexed { i,e -> strip.addView(appCard(e,"app-${e.key}",library=true),LinearLayout.LayoutParams(px(width),px(154)).apply { if(i<5)rightMargin=px(18) }) }
            page.addView(strip,LinearLayout.LayoutParams(-1,px(154)).apply { bottomMargin=px(20) }); constrainRow(strip)
        }
        all.firstOrNull()?.let { libraryAnchor=focusables["app-${it.key}"] }
        val originals=row()
        OriginalApp.entries.forEach { app ->
            val card=PrettTvCard(this,scale,PrettTvCard.Mode.LIBRARY).apply {
                title=app.label; art=originalAppArt[app]; contentDescription=app.label
                register(this,"app-original-${app.route}")
                setOnClickListener { safeStart(Intent(this@MainActivity,DiscoveryActivity::class.java)
                    .putExtra("originalApp",app.route),app.label) }
            }
            originals.addView(card,LinearLayout.LayoutParams(px(width),px(154)).apply { rightMargin=px(18) })
            if(libraryAnchor==null)libraryAnchor=card
        }
        page.addView(originals,LinearLayout.LayoutParams(-1,px(154)).apply { bottomMargin=px(20) })
        constrainRow(originals)
    }
    private fun constrainRow(row:ViewGroup) {
        val cards=ArrayList<View>()
        fun collect(view:View) {
            if(view.isFocusable)cards.add(view)
            else if(view is ViewGroup)for(i in 0 until view.childCount)collect(view.getChildAt(i))
        }
        collect(row)
        if(cards.isEmpty())return
        val group=FocusRow(row,cards)
        cards.forEachIndexed { index,card ->
            focusRows[card]=group
            card.nextFocusLeftId=cards.getOrNull(index-1)?.id ?: card.id
            if(layout==HomeLayout.QUIET && card.tag.toString().startsWith("favorite-"))card.nextFocusUpId=focusables["nav-home"]!!.id
            card.nextFocusRightId=cards.getOrNull(index+1)?.id ?: card.id
        }
    }
    private fun reload() {
        if(fixtureRefreshSuspended)return
        dirty=false; val g=++generation
        iconEpoch++;iconsLoading=false;failedIcons.clear()
        ui.removeCallbacks(retryApps); background.removeCallbacksAndMessages(null)
        val previous=apps.associateBy { it.key }
        background.post {
            val found=AppRefreshWork.attempt({repo.discover()}) { e ->
                android.util.Log.w("PrettTvApps","Discovery failed: ${e.javaClass.simpleName}")
                ui.post { if(g==generation) {
                    dirty=true; appLoadError=true
                    if(apps.isEmpty() && visible)render()
                    if(visible)ui.postDelayed(retryApps,30_000)
                } }
            } ?: return@post
            if(g!=generation)return@post
            val input=AppRefreshWork.attempt({repo.inputsEntry()}) { }
            found.forEach { entry->previous[entry.key]?.takeIf { it.versionCode==entry.versionCode }?.let {
                entry.bitmap=it.bitmap;entry.logoBounds=it.logoBounds;entry.libraryBitmap=it.libraryBitmap
            } }
            ui.post { if(g==generation) {
                appLoadError=false; appDiscoveryComplete=true; apps=found; inputs=input; render()
                if(visible) { loadAppArtwork();startContent() } else iconsReleased=true
            } }
            if(g==generation)AppRefreshWork.attempt({repo.pruneCache(found.map { repo.cacheName(it,320,180,0f) }.toSet())}) { }
        }
    }
    /** Snapshot priority on the UI thread; all file reads and rasterisation stay on the worker. */
    private fun loadAppArtwork(visibleOnly:Boolean=false) {
        iconsReleased=false
        iconsLoading=true
        val epoch=++iconEpoch;val g=generation
        val bounds=Rect()
        val visibleTiles=appCards.filterValues { cards->cards.any { !it.isLibraryCard && it.getGlobalVisibleRect(bounds) } }.keys
        val visibleMarks=appCards.filterValues { cards->cards.any { it.isLibraryCard && it.getGlobalVisibleRect(bounds) } }.keys
        val ordered=orderedApps().sortedBy { entry->
            if(appCards[entry.key].orEmpty().any { it.getGlobalVisibleRect(bounds) })0
            else if(entry.key !in prefs.hidden)1 else 2
        }
        val jobs=listOf(false,true).flatMap { library->
            ordered.filter { !visibleOnly || it.key in if(library)visibleMarks else visibleTiles }.map { it to library }
        }.sortedBy { (entry,library)->
            if(entry.key in if(library)visibleMarks else visibleTiles)0 else if(library)2 else 1
        }
        background.post {
            fun current()=g==generation && epoch==iconEpoch
            // Visible artwork first, then other Home tiles, then offscreen Library marks.
            AppRefreshWork.each(jobs,::current,{ (entry,library)->
                if(library)entry.libraryBitmap ?: repo.loadLibraryMark(entry)
                else entry.bitmap ?: repo.loadTile(entry,320,180,0f)
            },{ (entry,library),image->ui.post { if(current() && visible) {
                if(library)entry.libraryBitmap=image else entry.bitmap=image
                if(image==null)failedIcons.add("${entry.key}:$library")
                appCards[entry.key]?.filter { it.isLibraryCard==library }?.forEach { it.logoBounds=entry.logoBounds;it.art=image }
            } } },{ (entry,library),error->
                ui.post { if(current())failedIcons.add("${entry.key}:$library") }
                android.util.Log.w("PrettTvApps","Icon failed: ${error.javaClass.simpleName}")
            })
            ui.post { if(current()) { iconsLoading=false;scheduleArtworkScan() } }
        }
    }
    private fun recentServices()=prefs.recentOrder().entries.sortedByDescending { it.value }
        .mapNotNull { entry -> apps.firstOrNull { it.key==entry.key }?.packageName }.distinct()
    private fun balancedDiscovery()=BalancedHomeLayout.discovery(content,contentRepo.recommendationServices,recentServices(),personal.recommendationFeedback())
    private fun startContent() {
        if(fixtureRefreshSuspended)return
        val recent=recentServices()
        val services=if(settings.contains("preferredServices"))settings.getStringSet("preferredServices",emptySet()).orEmpty().toSet() else null
        contentRepo.start(apps.map { it.packageName }.toSet(),emptySet(),discovery,recent,services,balancedArtwork=wide)
    }
    private fun launch(e:AppEntry) { appReturnViewport=viewport(); prefs.touchRecent(e.key); prefs.lastFocus=e.key; if(!safeStart(repo.launchIntent(e),e.label))appReturnViewport=null }
    private fun safeStart(i:Intent,label:String):Boolean = try { startActivity(i); true } catch(_:Exception) { Toast.makeText(this,"Could not open $label",Toast.LENGTH_SHORT).show(); false }
    private fun openInputs() {
        if(appActionPanel?.isShowing==true)return
        val origin=currentFocus
        appActionPanel=InputsPanel.show(this,onInputs={
            inputs?.let { safeStart(it.intent,"Inputs") } ?: repo.settingsIntent()?.let { safeStart(it,"TV settings") }
        },onAudio={
            safeStart(Intent(this,BluetoothControlActivity::class.java),"Audio devices")
        },onDismiss={
            appActionPanel=null
            if(visible && !isFinishing && !isDestroyed) {
                origin?.requestFocusFromTouch()
                ui.post { if(visible && providerRefreshPending && !isFinishing && !isDestroyed)refreshProviderRows() }
            }
        })
    }
    private fun actionProgram(p:Program)=content.newEpisodes.firstOrNull { it.asProgram().id==p.id }?.program ?: p
    private fun openProgram(selected:Program) {
        val p=actionProgram(selected)
        if(p.catalog && p.packageName=="com.stremio.one" && p.detailUri.matches(Regex("stremio:///detail/(movie|series)/tt[0-9]{5,12}"))) {
            val target=Intent(Intent.ACTION_VIEW,Uri.parse(p.detailUri)).setPackage(p.packageName)
            if(packageManager.resolveActivity(target,0)!=null && safeStart(target,"Stremio"))return
        }
        if(!p.catalog && p.intentUri.isNotBlank()) {
            try {
                val target=Intent.parseUri(p.intentUri,Intent.URI_INTENT_SCHEME).apply {
                    component=null; selector=null; setPackage(p.packageName)
                    flags=Intent.FLAG_ACTIVITY_NEW_TASK; clipData=null
                }
                if(packageManager.resolveActivity(target,0)!=null && safeStart(target,p.app))return
            } catch(_:Exception) { }
        }
        val installed=apps.firstOrNull { it.packageName==p.packageName }
        if(installed!=null) { launch(installed); Toast.makeText(this,"Find ${p.title} in ${p.app}",Toast.LENGTH_LONG).show() }
        else Toast.makeText(this,"${p.app} is unavailable. Your saved title is kept.",Toast.LENGTH_LONG).show()
    }
    private fun dialog(title:String,items:List<Pair<String,()->Unit>>) {
        var action:(()->Unit)?=null
        val panel=AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _,i -> action=items[i].second }.setNegativeButton("Close",null).create()
        panel.setOnDismissListener { if(!isFinishing && !isDestroyed)action?.invoke() }; panel.show()
    }
    private fun appMenu(e:AppEntry,origin:String) {
        if(appActionPanel?.isShowing==true)return
        val anchor=focusables[origin] ?: return
        val place=viewport(origin)
        val before=orderedApps().filter { it.key !in prefs.hidden }.map { it.key }
        val favs=prefs.favourites.toMutableList(); val index=favs.indexOf(e.key)
        val beforeFavourites=prefs.favourites.toList(); val beforeHidden=prefs.hidden.toSet()
        val items=ArrayList<Pair<String,()->Unit>>()
        items+="Open" to { launch(e) }
        fun changed() {
            personal.recordUndo("Arrange ${e.label}") { prefs.favourites=beforeFavourites; prefs.hidden=beforeHidden; render(restore=place) }
            val target=if(origin.startsWith("favorite-")) {
                val after=orderedApps().filter { it.key !in prefs.hidden }.map { it.key }
                HomeModel.focusAfterChange(e.key,before,after)?.let { "favorite-$it" } ?: "empty-app-row"
            } else origin
            // Row visibility and arrangement do not change recommendation sources.
            render(restore=place.copy(focus=target))
        }
        if(index>=0) {
            items+="Remove from favorites" to { favs.remove(e.key); prefs.favourites=favs; changed() }
            if(index>0)items+="Move left" to { prefs.favourites=HomeModel.move(favs,e.key,-1); changed() }
            if(index<favs.size-1)items+="Move right" to { prefs.favourites=HomeModel.move(favs,e.key,1); changed() }
        } else items+="Add to favorites" to { favs.add(e.key); prefs.favourites=favs; changed() }
        if(e.key in prefs.hidden)items+="Show in Your apps row" to { prefs.hidden=prefs.hidden-e.key; changed() }
        else items+="Hide from Your apps row" to { prefs.hidden=prefs.hidden+e.key; changed() }
        items+="App first aid" to {
            focusables[origin]?.requestFocusFromTouch();appReturnViewport=place
            if(!safeStart(Intent(this,AppFirstAidActivity::class.java).putExtra(AppForceStopActivity.EXTRA_PACKAGE,e.packageName),"App first aid"))appReturnViewport=null
        }
        if(AppForceStopSession.canOffer(this,e.packageName))items+="Force stop" to {
            // Touch menu selection clears D-pad focus. Keep the originating tile selected
            // before the separate activity opens so Cancel returns to the same place.
            focusables[origin]?.requestFocusFromTouch()
            appReturnViewport=place
            val target=AppForceStopActivity.putAnchor(Intent(this,AppForceStopActivity::class.java)
                .putExtra(AppForceStopActivity.EXTRA_PACKAGE,e.packageName),anchor)
            if(!safeStart(target,"Force stop"))appReturnViewport=null
        }
        items+="App info" to { safeStart(repo.appInfoIntent(e.packageName),e.label) }
        appActionPanel=AppActionPanel.show(this,anchor,e.label,items) {
            appActionPanel=null
            if(visible && !isFinishing && !isDestroyed) {
                focusables[origin]?.requestFocusFromTouch()
                restoreRows(place.rows)
                root.scrollTo(0,place.y)
                ui.post { if(visible && providerRefreshPending && !isFinishing && !isDestroyed)refreshProviderRows() }
            }
        }
    }
    private fun menu() {
        val items=ArrayList<Pair<String,()->Unit>>()
        personal.undoLabel()?.let { label->items+="Undo · $label" to {
            val undone=personal.undo(); content=withTitlePreferences(content); refreshHomeRows(); contentRepo.refresh()
            Toast.makeText(this,if(undone==null)"Nothing to undo" else "Undone · $undone",Toast.LENGTH_SHORT).show()
        } }
        items+="Customize rows" to { customizeRows() }
        items+="Followed shows" to { followedShows() }
        items+="Favorite teams" to { chooseFavoriteTeams() }
        items+="Kickoff reminders: ${if(personal.kickoffRemindersEnabled())"On" else "Off"}" to {
            personal.setKickoffRemindersEnabled(!personal.kickoffRemindersEnabled()); contentRepo.refresh()
        }
        items+="Recommendation feedback" to { feedbackSettings() }
        items+="Updates" to { safeStart(Intent(this,UpdateActivity::class.java),"Updates") }
        items+="App first aid" to { dialog("Choose an app",orderedApps().map { e -> e.label to {
            safeStart(Intent(this,AppFirstAidActivity::class.java).putExtra(AppForceStopActivity.EXTRA_PACKAGE,e.packageName),"App first aid");Unit
        } }) }
        items+="Audio devices" to { safeStart(Intent(this,BluetoothControlActivity::class.java),"Audio devices") }
        items+="Weather" to { weatherSettings() }
        items+="Choose apps one by one" to { reviewApps() }
        items+="Live sports apps" to { chooseSportsApps() }
        items+="Recommendation services" to { chooseRecommendationServices() }
        items+="Layout: ${layout.label}" to {
            dialog("Choose your home",HomeLayout.entries.map { choice -> choice.label to { layout=choice; settings.edit().putString("layout",choice.name).apply(); settings.edit().remove("focus").apply(); render(); root.smoothScrollTo(0,0); firstCard?.requestFocus(); if(visible)startContent() } })
        }
        items+="Focus motion: ${if(settings.getBoolean("quietMotion",false))"Quiet" else "Classic"}" to {
            dialog("Focus motion",listOf("Quiet · smaller lift, still artwork" to {
                settings.edit().putBoolean("quietMotion",true).apply();render()
            },"Classic · lift and artwork slide" to {
                settings.edit().putBoolean("quietMotion",false).apply();render()
            }))
        }
        items+="Show discovery: ${if(discovery)"On" else "Off"}" to {
            settings.edit().putBoolean("discovery",!discovery).apply(); content=HomeContent(); render(); startContent()
        }
        repo.settingsIntent()?.let { i -> items+="TV settings" to { safeStart(i,"TV settings") } }
        items+="Inputs" to { openInputs() }
        items+="Choose default Home app" to { safeStart(Intent(android.provider.Settings.ACTION_HOME_SETTINGS),"Home settings") }
        items+="Home compatibility settings" to { safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),"Accessibility settings") }
        items+="Other home screens" to { otherHomes() }
        if(prefs.hidden.isNotEmpty())items+="Restore apps to Your apps row" to { prefs.hidden=emptySet(); render(); startContent() }
        items+="Hidden titles" to { hiddenTitles() }
        items+="Playback tracking" to { playbackSettings() }
        items+="Game handoff settings" to { safeStart(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),"Accessibility settings") }
        items+="Refresh apps and shows" to { reload() }
        items+="About and credits" to { credits() }
        if(StockRecoveryStore.paused(this))items+="Resume prettTV helpers" to {
            StockRecoveryStore.resume(this)
            PlaybackTracker.resumeAfterStock()
            runCatching { android.service.notification.NotificationListenerService.requestRebind(ComponentName(this,PlaybackTracker::class.java)) }
            Toast.makeText(this,"prettTV helpers resumed",Toast.LENGTH_SHORT).show()
        }
        items+="Return to stock Google TV" to { safeStart(Intent(this,StockRecoveryActivity::class.java),"Stock recovery") }
        val top=items.filter { it.first.startsWith("Undo ·") }.toMutableList()
        SettingsGroups.names.forEach { group -> top+=group to {
            val entries=items.filter { SettingsGroups.group(it.first)==group }
            var action:(()->Unit)?=null
            val panel=AlertDialog.Builder(this).setTitle(group).setItems(entries.map { it.first }.toTypedArray()) { _,i -> action=entries[i].second }
                .setNegativeButton("Back") { _,_->action={ menu() } }.create()
            panel.setOnCancelListener { action={ menu() } }
            panel.setOnDismissListener { if(!isFinishing && !isDestroyed)action?.invoke() };panel.show()
        } }
        top+=items.last()
        dialog("Settings",top)
    }
    private fun weatherSettings() {
        val preferences=weatherRepo.prefs
        val location=preferences.location
        dialog("Weather",listOf(
            "Location: ${location?.let { "${it.city}, ${it.region}" } ?: "Choose a US ZIP code"}" to { chooseWeatherZip() },
            "Show on Home: ${if(preferences.enabled)"On" else "Off"}" to {
                if(!preferences.enabled && location==null)chooseWeatherZip()
                else { weatherRepo.setEnabled(!preferences.enabled);weatherSettings() }
            },
            "Temperature: °${preferences.units}" to {
                weatherRepo.setUnits(if(preferences.units=="F")"C" else "F");weatherSettings()
            },
            "Refresh weather" to {
                if(location==null)chooseWeatherZip()
                else if(!preferences.enabled)Toast.makeText(this,"Turn on Show on Home to refresh weather",Toast.LENGTH_SHORT).show()
                else { weatherRepo.refresh(force=true);Toast.makeText(this,"Weather refresh requested",Toast.LENGTH_SHORT).show() }
            },
            "Weather details and sources" to { weatherDetails() }
        ))
    }
    private fun chooseWeatherZip() {
        val entry=EditText(this).apply {
            inputType=android.text.InputType.TYPE_CLASS_NUMBER
            filters=arrayOf(android.text.InputFilter.LengthFilter(5))
            hint="5-digit US ZIP code";setSingleLine(true);setText(weatherRepo.prefs.location?.zip.orEmpty());selectAll()
            setTextSize(TypedValue.COMPLEX_UNIT_SP,22f)
        }
        val form=column().apply {
            setPadding(px(30),px(16),px(30),px(16))
            addView(text("Choose the area for weather on Home.",22,muted),LinearLayout.LayoutParams(-1,px(44)))
            addView(entry,LinearLayout.LayoutParams(-1,px(64)))
        }
        val prompt=AlertDialog.Builder(this).setTitle("Weather location").setView(form)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create()
        prompt.setOnShowListener { prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val zip=entry.text.toString().trim()
            if(!zip.matches(Regex("[0-9]{5}"))) { entry.error="Enter a 5-digit US ZIP code";return@setOnClickListener }
            prompt.dismiss();lookupWeatherZip(zip)
        } }
        prompt.show();entry.requestFocus()
    }
    private fun lookupWeatherZip(zip:String) {
        fun cancel() { weatherRepo.stop();if(visible)weatherRepo.start();weatherLoadingDialog=null }
        weatherLoadingDialog=AlertDialog.Builder(this).setTitle("Finding your weather location")
            .setMessage("Checking this ZIP code…")
            .setNegativeButton("Cancel") { _,_->cancel() }
            .setOnCancelListener { cancel() }.create().also { it.setCanceledOnTouchOutside(false);it.show() }
        weatherRepo.configureZip(zip) { result ->
            weatherLoadingDialog?.dismiss();weatherLoadingDialog=null
            if(!visible || isFinishing || isDestroyed)return@configureZip
            if(result.isSuccess) {
                weatherRepo.setEnabled(true)
                Toast.makeText(this,"Weather location saved",Toast.LENGTH_SHORT).show()
                weatherSettings()
            } else AlertDialog.Builder(this).setTitle("Couldn't set weather location")
                .setMessage("Check the ZIP code and internet connection, then try again. Your previous location has been kept.")
                .setPositiveButton("Try again") { _,_->chooseWeatherZip() }.setNegativeButton("Close",null).show()
        }
    }
    private fun weatherDetails() {
        val state=weatherRepo.state
        val reading=state.reading
        val observed=reading?.let { android.text.format.DateFormat.getDateFormat(this).format(Date(it.observedAt))+" "+android.text.format.DateFormat.getTimeFormat(this).format(Date(it.observedAt)) }
        val detail=buildString {
            append(state.location?.let { "${it.city}, ${it.region}\n\n" } ?: "Choose a US ZIP code to enable weather.\n\n")
            if(reading!=null) {
                append(WeatherPolicy.formatTemperature(reading.temperatureCelsius,weatherRepo.prefs.units))
                if(reading.description.isNotBlank())append(" · ${reading.description}")
                append("\nNearby station: ${reading.stationName}\nObserved: $observed")
                if(state.stale)append("\nCached reading; waiting for a fresh observation.")
            } else append(if(state.loading)"Updating weather…" else "A recent observation is not available right now.")
            append("\n\nWeather: National Weather Service. ZIP lookup: Zippopotam.us, with postal data from GeoNames. Weather checks run while Home is visible. No GPS or account access is used.")
        }
        AlertDialog.Builder(this).setTitle("Weather details").setMessage(detail)
            .setPositiveButton("Close",null)
            .setNeutralButton("Sources") { _,_->dialog("Weather sources",listOf(
                "National Weather Service" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.weather.gov/documentation/services-web-api")),"Weather source") },
                "Zippopotam.us ZIP lookup" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://api.zippopotam.us/")),"ZIP lookup source") },
                "GeoNames postal data" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.geonames.org/export/zip/readme.txt")),"Postal data source") },
                "Credits and licences" to { readLicense("Credits and notices","third-party-notices.txt") }
            )) }.show()
    }
    private fun customizeRows() {
        val config=personal.rowPreferences()
        val choices=config.copy(hidden=emptySet()).ordered(rowLabels.keys).map { key->
            "${rowLabels[key]} · ${if(key in config.hidden)"Hidden" else config.size(key).label}" to { customizeRow(key) }
        }.toMutableList()
        choices+="Restore default rows" to { personal.setRowPreferences(RowPreferences()); refreshHomeRows() }
        dialog("Customize rows",choices)
    }
    private fun customizeRow(key:String) {
        val config=personal.rowPreferences(); val order=config.copy(hidden=emptySet()).ordered(rowLabels.keys).toMutableList(); val index=order.indexOf(key)
        fun save(value:RowPreferences) { personal.setRowPreferences(value); refreshHomeRows(); customizeRows() }
        val choices=arrayListOf<Pair<String,()->Unit>>(
            (if(key in config.hidden)"Show row" else "Hide row") to { save(config.copy(hidden=if(key in config.hidden)config.hidden-key else config.hidden+key)) },
            "Card size · ${config.size(key).label}" to {
                dialog("Card size",RowCardSize.entries.map { size->size.label to { save(config.copy(sizes=config.sizes+(key to size))) } })
            }
        )
        if(index>0)choices+="Move up" to { java.util.Collections.swap(order,index,index-1); save(config.copy(order=order)) }
        if(index<order.lastIndex)choices+="Move down" to { java.util.Collections.swap(order,index,index+1); save(config.copy(order=order)) }
        if(key in setOf("discover","explore","trending","new"))choices+="Title limit · ${config.limit(key)}" to {
            dialog("Titles in ${rowLabels[key]}",listOf(20,40,60).map { limit->"$limit titles" to { save(config.copy(limits=config.limits+(key to limit))) } })
        }
        dialog(rowLabels[key].orEmpty(),choices)
    }
    private fun followedShows() {
        val shows=personal.followedShows()
        if(shows.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Follow new episodes").setMessage("Hold OK on a show and choose Follow new episodes. prettTV checks verified release metadata while Home is open. An episode release does not guarantee availability in your streaming app.").setPositiveButton("Close",null).show(); return
        }
        val choices=shows.map { followed->followed.program.title to {
            dialog(followed.program.title,listOf("Show details" to { programDetails(followed.program) },"Unfollow" to { personal.unfollow(followed.key); contentRepo.refresh() }))
        } }.toMutableList()
        choices+="Episode check status" to { AlertDialog.Builder(this).setTitle("New episodes").setMessage(content.episodeStatus.ifBlank { "Release metadata has not been checked yet." }).setPositiveButton("Close",null).show() }
        dialog("Followed shows",choices)
    }
    private fun chooseFavoriteTeams() {
        teamLoadingDialog?.dismiss()
        val loading=AlertDialog.Builder(this).setTitle("Favorite teams").setMessage("Loading available teams…").setNegativeButton("Cancel",null).show()
        teamLoadingDialog=loading
        loading.setOnDismissListener { if(teamLoadingDialog===loading)teamLoadingDialog=null }
        contentRepo.requestTeams { teams->
            if(!visible || isFinishing || isDestroyed || !loading.isShowing)return@requestTeams
            loading.dismiss()
            if(teams.isEmpty()) {
                AlertDialog.Builder(this).setTitle("Teams unavailable").setMessage("Check your connection and try again. Existing favorites are kept.").setPositiveButton("Close",null).show(); return@requestTeams
            }
            val groups=teams.groupBy { it.league }.toSortedMap()
            dialog("Choose a league",groups.map { (league,entries)->league to { chooseLeagueTeams(league,entries) } })
        }
    }
    private fun chooseLeagueTeams(league:String,teams:List<SportsTeam>) {
        val ordered=teams.sortedBy { it.name }; val selected=personal.favoriteTeams().toMutableSet()
        AlertDialog.Builder(this).setTitle("$league · Favorite teams")
            .setMultiChoiceItems(ordered.map { it.name }.toTypedArray(),ordered.map { it.id in selected }.toBooleanArray()) { _,index,on->
                if(on)selected.add(ordered[index].id) else selected.remove(ordered[index].id)
            }.setPositiveButton("Save") { _,_->personal.setFavoriteTeams(selected); contentRepo.refresh() }
            .setNegativeButton("Cancel",null).show()
    }
    private fun feedbackSettings() {
        AlertDialog.Builder(this).setTitle("Recommendation feedback")
            .setMessage("Not interested hides that title from recommendations and gently lowers similar genres within your service priorities. Your Watchlist and viewing history stay intact. Undo the latest change in Settings, or reset all recommendation feedback here.")
            .setPositiveButton("Reset feedback") { _,_->personal.resetRecommendationFeedback();contentRepo.refresh() }
            .setNegativeButton("Close",null).show()
    }
    private fun reviewApps(index:Int=0) {
        val choices=apps.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
        if(index>=choices.size) { Toast.makeText(this,"App choices saved",Toast.LENGTH_SHORT).show(); return }
        val e=choices[index]
        fun choose(include:Boolean) {
            prefs.hidden=if(include)prefs.hidden-e.key else prefs.hidden+e.key
            render(); startContent(); reviewApps(index+1)
        }
        AlertDialog.Builder(this).setTitle("${index+1} of ${choices.size} · ${e.label}")
            .setMessage("Show this app in Your apps? Currently ${if(e.key in prefs.hidden)"hidden from the row" else "included"}. It stays available in Library, search and viewing history.")
            .setPositiveButton("Include") { _,_ -> choose(true) }
            .setNegativeButton("Hide") { _,_ -> choose(false) }
            .setNeutralButton("Done for now",null).show()
    }
    private fun chooseRecommendationServices() {
        val choices=apps.filter { it.packageName in contentRepo.availableServices || it.packageName in RecommendationPolicy.knownServices }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
        val selected=(if(settings.contains("preferredServices"))settings.getStringSet("preferredServices",emptySet()).orEmpty() else contentRepo.recommendationServices).toMutableSet()
        AlertDialog.Builder(this).setTitle("Prioritize your signed-in services")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(),choices.map { it.packageName in selected }.toBooleanArray()) { _,i,on ->
                if(on)selected.add(choices[i].packageName) else selected.remove(choices[i].packageName)
            }.setPositiveButton("Save") { _,_ -> settings.edit().putStringSet("preferredServices",selected).apply(); startContent() }
            .setNeutralButton("Use viewing activity") { _,_ -> settings.edit().remove("preferredServices").apply(); startContent() }
            .setNegativeButton("Cancel",null).show()
    }
    private fun chooseSportsApps() {
        val choices=apps.sortedBy { it.label.lowercase(java.util.Locale.ROOT) }
        val selected=sportsPackages.toMutableSet()
        AlertDialog.Builder(this).setTitle("Live sports apps")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(),choices.map { it.packageName in selected }.toBooleanArray()) { _,i,on ->
                if(on)selected.add(choices[i].packageName) else selected.remove(choices[i].packageName)
            }.setPositiveButton("Save") { _,_ -> settings.edit().putStringSet("sportsPackages",selected).apply(); render() }
            .setNegativeButton("Cancel",null).show()
    }
    private fun otherHomes() {
        val q=Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val targets=packageManager.queryIntentActivities(q,0).filter { it.activityInfo.packageName!=packageName }
        dialog("Other home screens",targets.map { ri -> ri.loadLabel(packageManager).toString() to {
            safeStart(Intent(q).setClassName(ri.activityInfo.packageName,ri.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),"Home")
        } })
    }
    private fun search() {
        if(searchPanel?.isShowing==true)return
        searchPanel=HomeSearchPanel(this,searchState) { query ->
            val choices=ArrayList<HomeSearchResult>()
            orderedApps().filter { it.label.contains(query,true) }.forEach { e -> choices+=HomeSearchResult(e.key,e.label,"Apps") { launch(e) } }
            (content.watching+personal.watchlist()+content.suggestions+content.discover+content.trending+content.newReleases)
                .filter { it.title.contains(query,true) }.distinctBy { ContentIdentity.key(it) }.forEach { p ->
                    choices+=HomeSearchResult(ContentIdentity.key(p),"${p.title} · ${p.app}","Shows and movies") { openProgram(p) }
                }
            choices
        }.also { panel -> panel.setOnDismissListener { searchPanel=null };panel.show() }
    }
    private fun programInfo(selected:Program) {
        val p=actionProgram(selected)
        val items=arrayListOf<Pair<String,()->Unit>>(
            "Open ${p.app}" to { openProgram(p) },
            (if(personal.isSaved(p))"Remove from Watchlist" else "Add to Watchlist") to {
                val changed=if(personal.isSaved(p))personal.removeSaved(p) else personal.save(p)
                refreshHomeRows(); Toast.makeText(this,if(changed)"Watchlist updated · Undo in Settings" else personal.lastError ?: "Already saved",Toast.LENGTH_SHORT).show()
            },
            "I watched this most recently" to { playback.confirm(p); startContent(); Toast.makeText(this,"Recent viewing corrected on this TV",Toast.LENGTH_SHORT).show() },
            "Hide from large hero" to { hideTitle(p,hero=true,watching=false) },
            "Hide from Continue watching" to { hideTitle(p,hero=false,watching=true) },
            "Hide from both" to { hideTitle(p,hero=true,watching=true) },
            "About this title / source" to { programDetails(selected) }
        )
        if(p.mediaType=="series")items.add(2,(if(personal.isFollowed(p))"Unfollow new episodes" else "Follow new episodes") to {
            val changed=if(personal.isFollowed(p))personal.unfollow(p) else personal.follow(p)
            contentRepo.refresh(); Toast.makeText(this,if(changed)"Following updated. Alerts require a verified show match." else personal.lastError ?: "Already followed",Toast.LENGTH_LONG).show()
        })
        if((content.suggestions+content.discover+content.trending+content.newReleases).any { ContentIdentity.key(it)==ContentIdentity.key(p) })items+="Not interested" to {
            personal.dismissRecommendation(p); contentRepo.refresh()
            content=content.copy(suggestions=content.suggestions.filterNot { ContentIdentity.key(it)==ContentIdentity.key(p) },discover=content.discover.filterNot { ContentIdentity.key(it)==ContentIdentity.key(p) },trending=content.trending.filterNot { ContentIdentity.key(it)==ContentIdentity.key(p) },newReleases=content.newReleases.filterNot { ContentIdentity.key(it)==ContentIdentity.key(p) })
            refreshHomeRows(); Toast.makeText(this,"Recommendation dismissed · Undo in Settings",Toast.LENGTH_SHORT).show()
        }
        content.newEpisodes.firstOrNull { it.asProgram().id==selected.id }?.let { episode->items+="Dismiss episode alert" to { contentRepo.dismissEpisode(episode.id) } }
        dialog(p.title,items)
    }
    private fun withTitlePreferences(value:HomeContent)=value.copy(
        hiddenHeroTitles=settings.getStringSet("hiddenHeroTitles",emptySet()).orEmpty().toSet(),
        hiddenWatchingTitles=settings.getStringSet("hiddenWatchingTitles",emptySet()).orEmpty().toSet())
    private fun playbackSettings() {
        AlertDialog.Builder(this).setTitle("Playback tracking")
            .setMessage("Use active media sessions to recognize supported titles you play. Android requires Notification access for this. prettTV does not read or store notification text. Viewing records stay on this TV. Apps that omit title metadata cannot be identified reliably.\n\nYou can always hold OK on a title and choose ‘I watched this most recently’ to correct stale shared history.")
            .setPositiveButton("Open access settings") { _,_ -> safeStart(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),"Notification access") }
            .setNegativeButton("Close",null).show()
    }
    private fun hideTitle(p:Program,hero:Boolean,watching:Boolean) {
        val oldHero=content.hiddenHeroTitles.toSet(); val oldWatching=content.hiddenWatchingTitles.toSet()
        val oldLabel=settings.getString("hiddenTitleLabel:${p.titleKey}",null)
        personal.recordUndo("Hide ${p.title}") {
            settings.edit().putStringSet("hiddenHeroTitles",oldHero).putStringSet("hiddenWatchingTitles",oldWatching).putString("hiddenTitleLabel:${p.titleKey}",oldLabel).apply()
        }
        val edit=settings.edit().putString("hiddenTitleLabel:${p.titleKey}",p.title)
        if(hero)edit.putStringSet("hiddenHeroTitles",content.hiddenHeroTitles+p.titleKey)
        if(watching)edit.putStringSet("hiddenWatchingTitles",content.hiddenWatchingTitles+p.titleKey)
        edit.apply(); content=withTitlePreferences(content); refreshHomeRows()
        Toast.makeText(this,"Hidden in prettTV. Restore from Settings → Hidden titles.",Toast.LENGTH_LONG).show()
    }
    private fun hiddenTitles() {
        val keys=(content.hiddenHeroTitles+content.hiddenWatchingTitles).sorted()
        if(keys.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Hidden titles").setMessage("Hold OK on a title to hide it from the large hero, Continue watching, or both. This only changes prettTV on this TV; shared streaming accounts stay unchanged.").setPositiveButton("Close",null).show()
            return
        }
        dialog("Restore a hidden title",keys.map { key ->
            val places=listOfNotNull(if(key in content.hiddenHeroTitles)"hero" else null,if(key in content.hiddenWatchingTitles)"Continue watching" else null).joinToString(" + ")
            "${settings.getString("hiddenTitleLabel:$key",key)} · $places" to {
                settings.edit().putStringSet("hiddenHeroTitles",content.hiddenHeroTitles-key).putStringSet("hiddenWatchingTitles",content.hiddenWatchingTitles-key).remove("hiddenTitleLabel:$key").apply()
                content=withTitlePreferences(content); render()
            }
        })
    }
    private fun programDetails(p:Program) {
        val original=actionProgram(p)
        val progress=if(p.duration>0)"Progress: ${(p.progress*100).toInt()}% · ${(p.duration-p.position).coerceAtLeast(0)/60_000} min remaining\n" else ""
        val detail=listOf(p.detail,progress,p.summary.ifBlank { "No synopsis supplied by this source." },
            if(p.catalog)"${p.sourceName} catalog. Streaming availability is unconfirmed." else "Shared by ${p.app}. Availability depends on that app and your subscription.").joinToString("\n\n")
        val body=text(detail,24).apply { maxLines=Int.MAX_VALUE; ellipsize=null; setPadding(px(30),px(20),px(30),px(20)) }
        val scroll=ScrollView(this).apply { isFocusable=true; addView(body) }
        AlertDialog.Builder(this).setTitle(p.title).setView(scroll)
            .setPositiveButton("Open / actions") { _,_->titleDestinations(p) }
            .setNeutralButton(if(personal.isSaved(original))"Remove saved" else "Save to Watchlist") { _,_->
                val changed=if(personal.isSaved(original))personal.removeSaved(original) else personal.save(original)
                if(!changed && personal.lastError!=null)Toast.makeText(this,personal.lastError,Toast.LENGTH_LONG).show()
                refreshHomeRows()
            }
            .setNegativeButton("Close",null).show()
    }
    private fun titleDestinations(selected:Program) {
        val p=actionProgram(selected)
        val matches=(listOf(p)+content.watching+personal.watchlist()+content.suggestions+content.discover+content.trending+content.newReleases)
            .filter { ContentIdentity.key(it)==ContentIdentity.key(p) }.distinctBy { it.packageName }
        val choices=matches.map { item->"Open ${item.app}" to { openProgram(item) } }.toMutableList()
        if(selected.sourceUrl.startsWith("https://"))choices+="View source" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse(selected.sourceUrl)),"Source") }
        dialog(p.title,choices)
    }
    private fun credits() {
        dialog("prettTV 0.2.3",listOf(
            "Built on Rivi · Jommi · MIT license" to { readLicense("Rivi — Copyright (c) 2026 Jommi","rivi-license.txt") },
            "All credits and third-party notices" to { readLicense("Credits and notices","third-party-notices.txt") },
            "Kotlin / JetBrains · Apache 2.0" to { readLicense("Apache License 2.0","apache-2.0.txt") },
            "TVmaze · show data · CC BY-SA" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.tvmaze.com/api#licensing")),"TVmaze") },
            "Cinemeta · popular and recent titles" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/Stremio/stremio-official-addons")),"Cinemeta") },
            "ESPN · sports schedules and broadcasts" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.espn.com/")),"ESPN") },
            "Project source · github.com/draco1738/prettTV" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/draco1738/prettTV")),"Project source") },
            "Privacy" to { AlertDialog.Builder(this).setTitle("Your home, your choice").setMessage("prettTV has no analytics, ad identifiers or registration. App arrangement, viewing records and launch recency stay on this TV. Optional helpers use active media-session metadata (Notification access) or navigate RBTV during a game request (Accessibility access). No notification text is read or retained. Helpers can be turned off in Android settings.\n\nWith discovery on, prettTV requests public catalogs and artwork from TVmaze, Stremio Cinemeta, ESPN scoreboards and their image services. Those servers receive your IP address and requested catalog/show/league IDs. Published TV rows can also link to provider artwork. Switch discovery off to stop general catalog requests. Shows you explicitly follow can still request episode data from TVmaze; unfollow them to stop those checks. Watchlist, followed shows, favorite teams and cached Home data stay on this TV. Cached recommendations expire after seven days. Update checks contact the official prettTV GitHub repository; APK downloads require your action and installation requires Android confirmation.\n\nOptional weather sends your chosen US ZIP code to Zippopotam.us and its area coordinates to the National Weather Service; these providers also receive your IP address. Location, units and a bounded weather cache stay on this TV. Weather does not use GPS or access streaming accounts. Turn off Show on Home in Weather settings to stop weather requests. Local settings exports include your weather location.\n\nCurrently watching uses progress that apps publish to Android TV. prettTV cannot inspect their login status or credentials. Continue watching and the hero use local viewing confirmations and observed changes in published playback progress, with shared timestamps as an initial baseline. Timestamp refreshes alone do not prove playback. Provider progress may arrive late or from a shared account; it cannot identify who watched or the exact playback time. Provider observations stay separate from confirmed local playback and do not automatically consume Watchlist. Supported media sessions can update local recency; you can also hold OK on a title to correct it. Recommendations use shared viewing activity to prioritize services, without assuming an app launch proves a login. Choose your signed-in apps in Recommendation services to override this inference. Providers are balanced before row limits; general/free feeds follow your services and Stremio stays last with a purple S badge. prettTV does not remove ads inside other apps.").setPositiveButton("Close",null).show() }
        ))
    }
    private fun readLicense(title:String,file:String) {
        val body=assets.open(file).bufferedReader().use { it.readText() }
        val label=TextView(this).apply { text=body; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP,18f); setPadding(px(30),px(20),px(30),px(20)) }
        val scroll=ScrollView(this).apply { isFocusable=true; addView(label) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close",null).show()
    }
    override fun dump(prefix:String,fd:java.io.FileDescriptor?,out:java.io.PrintWriter,args:Array<out String>?) {
        val recovery=args?.indexOf("--configure-stock-recovery") ?: -1
        if(recovery>=0) {
            try { StockRecoveryStore(this).configure(args!![recovery+1]); out.println("PRETTTV_RECOVERY_CONFIGURED") }
            catch(_:Exception) { out.println("PRETTTV_RECOVERY_CONFIGURATION_FAILED") }
            return
        }
        if(args?.contains("--stock-recovery-status")==true) {
            val store=StockRecoveryStore(this)
            out.println("PRETTTV_RECOVERY configured=${try { store.plan()!=null } catch(_:Exception) { false }} paused=${StockRecoveryStore.paused(this)} phase=${StockRecoverySession.state.phase}")
            out.println(store.status()); return
        }
        val stores=mapOf("home" to settings,"apps" to getSharedPreferences("rivi",MODE_PRIVATE),"playback" to getSharedPreferences("prett-playback",MODE_PRIVATE),PersonalizationStore.PREFS_NAME to getSharedPreferences(PersonalizationStore.PREFS_NAME,MODE_PRIVATE))
        if(args?.contains("--export-local-settings")==true) { out.println("PRETTTV_SETTINGS="+LocalSettingsTransfer.export(stores)); return }
        val restore=args?.indexOf("--restore-local-settings") ?: -1
        if(restore>=0) {
            try { LocalSettingsTransfer.restore(args!![restore+1],stores); personal.clearUndoHistory(); weatherRepo.stop();if(visible)weatherRepo.start();layout=HomeLayout.parse(settings.getString("layout",null)); content=HomeContent(); render(reset=true); reload(); out.println("PRETTTV_SETTINGS_RESTORED") }
            catch(_:Exception) { out.println("PRETTTV_SETTINGS_RESTORE_FAILED") }
            return
        }
        super.dump(prefix,fd,out,args)
        out.println("prettTV layout=${layout.name} apps=${apps.size} watching=${content.watching.size} suggestions=${content.suggestions.size} discover=${content.discover.size} trending=${content.trending.size} new=${content.newReleases.size} games=${content.games.size} discovery=$discovery")
        out.println("prettTV focus=${currentFocus?.tag} visible=$visible artwork=${appCards.values.flatten().count { it.art!=null }}")
        out.println("prettTV contentArtwork=${cardArt.count { it.card.art!=null }}")
        out.println("prettTV appRefreshError=$appLoadError pending=$dirty")
        out.println("prettTV watchlist=${personal.watchlist().size} episodes=${content.newEpisodes.size} followed=${personal.followedShows().size} offline=${content.offline} rows=${rowSections.keys.joinToString(",")}")
        if(args?.contains("--viewport-diagnostics")==true) {
            out.println("VIEWPORT y=${root.scrollY} rows="+horizontalRows.mapValues { it.value.scrollX })
            out.println("VIEWPORT_CONTENT signature=${listOf(rowValues,apps.map { it.key },content.feature).hashCode()}")
        }
        if(args?.contains("--feed-diagnostics")==true)out.println(contentRepo.diagnostics())
        if(args?.contains("--artwork-diagnostics")==true && wide) {
            for(key in listOf("feature","next-0","next-1","next-2")) {
                val program=boundPrograms[key] ?: continue
                val card=focusables[key] as? PrettTvCard ?: continue
                val display=content.presentationArtwork[program.id]
                val expected=if(key=="feature")display?.backdrop.orEmpty() else display?.poster.orEmpty()
                val bitmap=card.art
                out.println("BALANCED_ART "+org.json.JSONObject().put("key",key).put("title",program.title)
                    .put("package",program.packageName).put("mediaType",program.mediaType)
                    .put("verifiedSource",display?.sourceUrl.orEmpty())
                    .put("presentationBound",expected.isNotBlank() && cardArt.any { it.card===card && it.url==expected })
                    .put("width",bitmap?.width ?: 0).put("height",bitmap?.height ?: 0))
            }
        }
        if(args?.contains("--weather-diagnostics")==true) {
            val weather=weatherRepo.state
            out.println("WEATHER configured=${weather.location!=null} enabled=${weatherRepo.prefs.enabled} units=${weatherRepo.prefs.units} reading=${weather.reading!=null} loading=${weather.loading} stale=${weather.stale} unavailable=${weather.error!=null}")
            out.println("WEATHER_RENDER headerVisible=${weatherHeader?.visibility==View.VISIBLE} clockVisible=${clock.visibility==View.VISIBLE}")
        }
        if(args?.contains("--content-diagnostics")==true)for(p in content.watching)
            out.println("WATCH ${p.packageName} | ${p.title} | ${p.id} | engagement=${p.engagedAt} | position=${p.position} | duration=${p.duration}")
    }
}

/** One scroll scan: an entirely clipped row cannot contain visible artwork. */
internal class ArtworkVisibilityScan {
    private val rows=java.util.IdentityHashMap<HorizontalScrollView,Boolean>()
    private val rowBounds=Rect()

    fun isVisible(card:View,bounds:Rect):Boolean {
        var parent=card.parent
        while(parent is View) {
            if(parent is HorizontalScrollView && parent.clipChildren && parent.clipToPadding) {
                val row=parent
                val visible=rows[row] ?: row.getGlobalVisibleRect(rowBounds).also { rows[row]=it }
                if(!visible) { bounds.setEmpty(); return false }
                break
            }
            parent=parent.parent
        }
        return card.getGlobalVisibleRect(bounds)
    }
}
