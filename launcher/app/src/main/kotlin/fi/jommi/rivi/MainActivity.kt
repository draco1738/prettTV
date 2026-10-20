package fi.jommi.rivi

// Built on Rivi v0.15, Copyright (c) 2026 Jommi, MIT. See assets/rivi-license.txt.
import android.app.Activity
import android.app.AlertDialog
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
    private var weatherHeader:WeatherHeaderView?=null
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
    private var reminderBanner:TextView?=null
    private var teamLoadingDialog:AlertDialog?=null
    private val dismissedReminders=HashSet<String>()
    private var watchlistOffset=0
    private var nextRowFocus:String?=null
    private var lastContentFocus:View?=null
    private var sidebar:View?=null
    private var layout = HomeLayout.BALANCED
    private var content = HomeContent()
    private var apps = emptyList<AppEntry>()
    private var inputs: AppEntry? = null
    private var visible = false
    private var dirty = true
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
    private data class FocusRow(val container:LinearLayout,val cards:List<View>)
    private val focusRows=HashMap<View,FocusRow>()
    private val rowMotion=HashMap<HorizontalScrollView,ValueAnimator>()
    private data class Viewport(val focus:String?=null,val y:Int=0,val rows:Map<String,Int> = emptyMap(),val contentFocus:String?=null)
    private var pendingViewport:Viewport?=null
    private var viewportRevision=0
    private val appCards = HashMap<String, MutableList<PrettTvCard>>()
    private val originalAppArt by lazy { OriginalApp.entries.associateWith { OriginalAppArtwork.render(this,it) } }
    private data class CardArt(val card:PrettTvCard,val url:String,val w:Int,val h:Int,val request:ArtworkRequest=ArtworkRequest())
    private val cardArt=ArrayList<CardArt>()
    private val ui = Handler(Looper.getMainLooper())
    private var artworkScanScheduled=false
    private val scanArtwork=Runnable { artworkScanScheduled=false; loadVisibleArt() }
    private val retryArtwork = Runnable { loadVisibleArt() }
    private val retryApps = Runnable { if(visible && dirty)reload() }
    private val loader = HandlerThread("pretttv-apps").apply { start() }
    private val background = Handler(loader.looper)
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dirty=true; if(visible)reload() }
    }
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { updateClock() }
    }
    private val ink = 0xFFF4F1E9.toInt()
    private val muted = 0xFFB2C0B5.toInt()
    private val bgColor = 0xFF0F1510.toInt()
    private fun px(n:Int) = (n*scale+.5f).toInt()
    private val wide get() = layout==HomeLayout.BALANCED
    private val inner get() = 1808
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
            if(filtered!=content || removed>0) { content=filtered; refreshHomeRows() }
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
        val savedLayout=HomeLayout.parse(settings.getString("layout",null))
        if(layout!=savedLayout) { layout=savedLayout; render(reset=true) }
        AppUpdater.checkWhenHomeVisible(this)
        RbtvHandoffService.cancel()
        registerReceiver(tickReceiver,IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        updateClock()
        weatherRepo.start()
        if(dirty || apps.isEmpty())reload() else startContent()
        ui.post(retryArtwork)
        // onStop removes timers; preserve recovery for cards still in their failure backoff.
        ui.postDelayed(retryArtwork,30_000)
    }
    override fun onStop() {
        AppUpdater.cancelHomeCheck()
        visible=false; contentRepo.stop(); weatherRepo.stop(); artwork.cancel(); unregisterReceiver(tickReceiver)
        weatherLoadingDialog?.dismiss(); weatherLoadingDialog=null
        teamLoadingDialog?.dismiss(); teamLoadingDialog=null
        stopRowMotion(); cancelArtworkScan(); ui.removeCallbacks(retryArtwork); ui.removeCallbacks(retryApps); cardArt.forEach { it.request.cancel() }
        (currentFocus?.tag as? String)?.let { settings.edit().putString("focus",it).apply() }
        super.onStop()
    }
    override fun onDestroy() { generation++; ui.removeCallbacksAndMessages(null); unregisterReceiver(packageReceiver); background.removeCallbacksAndMessages(null); loader.quitSafely(); contentRepo.close(); weatherRepo.close(); artwork.close(); super.onDestroy() }
    override fun onConfigurationChanged(newConfig:Configuration) { super.onConfigurationChanged(newConfig); scale=resources.displayMetrics.widthPixels/1920f; render() }
    override fun onNewIntent(i:Intent?) { super.onNewIntent(i); applyIntent(i); render(reset=true) }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==41 && visible)startContent()
    }
    private fun applyIntent(i:Intent?) {
        i?.getStringExtra("layout")?.let { value -> layout=HomeLayout.parse(value.uppercase(java.util.Locale.ROOT)); settings.edit().putString("layout",layout.name).apply(); i.removeExtra("layout") }
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if(sidebar?.visibility==View.VISIBLE)hideRail() else { firstCard?.requestFocus(); root.smoothScrollTo(0,0) } }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean {
        if(event.action==KeyEvent.ACTION_DOWN && sidebar?.visibility!=View.VISIBLE &&
            (event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val row=focusRows[currentFocus]
            if(row!=null) {
                val scroll=row.container.parent as? HorizontalScrollView
                when(val move=RowNavigation.move(row.cards.indexOf(currentFocus),row.cards.size,
                    event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT,scroll?.scrollX ?: 0,event.repeatCount)) {
                    is RowNavigation.Move.Focus -> {
                        val from=scroll?.scrollX ?: 0
                        if(scroll!=null)rowMotion.remove(scroll)?.cancel()
                        if(row.cards[move.index].requestFocus(if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)View.FOCUS_RIGHT else View.FOCUS_LEFT) && scroll!=null) {
                            val destination=scroll.scrollX
                            scroll.scrollTo(from,0); moveRow(scroll,destination)
                        }
                        return true
                    }
                    RowNavigation.Move.RevealStart -> { if(scroll!=null)moveRow(scroll,0); return true }
                    RowNavigation.Move.Stay -> return true
                    RowNavigation.Move.Sidebar -> Unit
                }
            }
        }
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && sidebar?.visibility!=View.VISIBLE) {
            val target=focusables.values.firstOrNull { it.id==currentFocus?.nextFocusLeftId && it.tag.toString().startsWith("rail-") }
            if(target!=null) {
                if(event.repeatCount==0) { stopRowMotion(); sidebar?.visibility=View.VISIBLE; root.alpha=.45f; target.requestFocus() }
                return true
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
    private fun hideRail(restore:Boolean=true) {
        // Hiding the focused rail can synchronously focus the hero and update lastContentFocus.
        // Capture the intended return card before changing visibility.
        val returnTo=if(restore)lastContentFocus ?: firstCard else null
        sidebar?.visibility=View.GONE; root.alpha=1f
        returnTo?.requestFocus()
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
        background=round(0xFF1B2922.toInt()); register(this,key)
        setOnFocusChangeListener { _,focus -> background=round(if(focus)0xFFCDD9C7.toInt() else 0xFF1B2922.toInt()); setTextColor(if(focus)bgColor else ink) }
        setOnClickListener { action() }
    }
    private fun section(title:String,extra:String=""):View=row().apply {
        addView(text(title,24,ink),LinearLayout.LayoutParams(0,px(44),1f))
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

    private fun render(reset:Boolean=false,restore:Viewport?=null) {
        if(isFinishing || isDestroyed)return
        stopRowMotion()
        if(reset)homeRequested=true
        val saved=if(homeRequested)Viewport() else restore ?: pendingViewport ?: viewport()
        pendingViewport=saved
        val revision=++viewportRevision
        artwork.cancel(); cancelArtworkScan(); ui.removeCallbacks(retryArtwork); cardArt.clear(); focusables.clear(); horizontalRows.clear(); focusRows.clear(); appCards.clear(); rowSections.clear(); rowValues.clear(); boundPrograms.clear(); firstCard=null; appAnchor=null; libraryAnchor=null; showAnchor=null
        root=ScrollView(this).apply { isFillViewport=true; isVerticalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER; clipChildren=false; clipToPadding=false }
        page=column().apply { setPadding(px(56),px(30),px(56),px(40)) }
        val stage=FrameLayout(this).apply { setBackgroundColor(bgColor); clipChildren=false; clipToPadding=false }
        root.addView(page,ViewGroup.LayoutParams(-1,-2)); stage.addView(root,FrameLayout.LayoutParams(-1,-1))
        buildRail(stage)
        buildHeader(); gap(if(wide)18 else 24)
        when(layout) {
            HomeLayout.BALANCED -> buildBalanced()
            HomeLayout.CINEMA -> buildCinema()
            HomeLayout.QUIET -> buildQuiet()
        }
        gap(if(wide)56 else 66); buildAllApps()
        gap(28)
        val foot=button("About prettTV · Credits","credits") { credits() }
        page.addView(text("Left at the first card opens navigation.  •  Hold OK for options.  •  Menu for settings.",21,muted),LinearLayout.LayoutParams(-1,px(48)))
        page.addView(foot,LinearLayout.LayoutParams(px(330),px(54)))
        firstCard?.nextFocusLeftId=focusables["rail-home"]!!.id
        lastContentFocus=firstCard
        stage.viewTreeObserver.addOnGlobalFocusChangeListener { _,next ->
            if(next?.tag is String && !(next.tag as String).startsWith("rail-")) { lastContentFocus=next; hideRail(restore=false) }
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
                saved.rows.forEach { (key,x) -> horizontalRows[key]?.scrollTo(x,0) }
                if(saved.focus?.startsWith("rail-")==true) {
                    sidebar?.visibility=View.VISIBLE; root.alpha=.45f
                    lastContentFocus=focusables[saved.contentFocus] ?: firstCard
                }
                val target=focusables[saved.focus] ?: firstCard ?: focusables.values.firstOrNull()
                if(target?.requestFocus()!=true)firstCard?.requestFocus()
                root.scrollTo(0,saved.y)
                revealFocus(currentFocus)
                pendingViewport=null; homeRequested=false
                loadVisibleArt()
                return true
            }
        })
    }
    private fun buildHeader() {
        val header=row()
        header.addView(text("Home",36,0xFFADC1B6.toInt(),true),LinearLayout.LayoutParams(px(260),px(56)))
        homeStatus=text("",19,muted).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.END }
        header.addView(homeStatus,LinearLayout.LayoutParams(0,px(56),1f))
        weatherHeader=WeatherHeaderView(this,scale)
        header.addView(weatherHeader,LinearLayout.LayoutParams(-2,px(56)).apply { leftMargin=px(22) })
        clock=text("",26,ink).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.END; minWidth=px(146) }
        header.addView(clock,LinearLayout.LayoutParams(-2,px(56)))
        updateWeatherHeader()
        page.addView(header,LinearLayout.LayoutParams(-1,px(56)))
        reminderBanner=button("","kickoff-reminder") {
            visibleReminders().firstOrNull()?.let { dismissedReminders.add(SportsPolicy.reminderKey(it)); contentRepo.acknowledgeReminder(it) }
            updateHomeStatus()
        }.apply { visibility=View.GONE; gravity=Gravity.CENTER_VERTICAL; maxLines=2 }
        page.addView(reminderBanner,LinearLayout.LayoutParams(-1,px(70)))
    }
    private fun buildRail(stage:FrameLayout) {
        val rail=column().apply { setPadding(px(10),px(35),px(10),px(30)); background=round(0xFF15221B.toInt()) }
        rail.addView(View(this),LinearLayout.LayoutParams(1,px(100)))
        val entries=listOf(Triple("Home","rail-home",{ render(reset=true) }),Triple("Shows","rail-shows",{ showAnchor?.requestFocus() }),Triple("Library","rail-apps",{ libraryAnchor?.requestFocus() }),Triple("Inputs","rail-inputs",{ openInputs() }),Triple("Search","rail-search",{ search() }),Triple("Settings","rail-settings",{ menu() }))
        for((label,key,action) in entries)rail.addView(button(label,key) { hideRail(); action(); Unit }.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX,px(23)*resources.configuration.fontScale); setPadding(px(6),0,px(6),0)
            nextFocusLeftId=id
            setOnKeyListener { _,code,event ->
                if(code==KeyEvent.KEYCODE_DPAD_RIGHT) { if(event.action==KeyEvent.ACTION_DOWN)hideRail(); true } else false
            }
        },LinearLayout.LayoutParams(-1,px(70)).apply { bottomMargin=px(20) })
        rail.getChildAt(1).nextFocusUpId=rail.getChildAt(1).id
        rail.getChildAt(rail.childCount-1).nextFocusDownId=rail.getChildAt(rail.childCount-1).id
        sidebar=rail; rail.visibility=View.GONE
        stage.addView(rail,FrameLayout.LayoutParams(px(150),px(970)).apply { leftMargin=px(24); topMargin=px(24) })
    }
    private fun buildBalanced() {
        val upper=row()
        val hero=programCard(content.feature,PrettTvCard.Mode.HERO,"feature",1132,486)
        upper.addView(hero,LinearLayout.LayoutParams(px(1132),px(486)).apply { rightMargin=px(20) })
        firstCard=hero
        val side=column().apply {
            setPadding(px(24),px(13),px(24),px(16))
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(0xFF1D251E.toInt(),0xFF161E17.toInt())).apply { cornerRadius=px(16).toFloat(); setStroke(px(1),0xFF4B584B.toInt()) }
        }
        val next=content.suggestions.filter { it.id!=content.feature?.id }
        side.addView(text("Discover",26,ink),LinearLayout.LayoutParams(-1,px(49)))
        for(i in 0..1) {
            side.addView(programCard(next.getOrNull(i),PrettTvCard.Mode.COMPACT,"next-$i",608,184),LinearLayout.LayoutParams(-1,px(184)))
            if(i==0)side.addView(View(this).apply { setBackgroundColor(0xFF354032.toInt()) },LinearLayout.LayoutParams(-1,px(1)).apply { topMargin=px(14); bottomMargin=px(14) })
        }
        upper.addView(side,LinearLayout.LayoutParams(px(656),px(486)))
        page.addView(upper); gap(20); buildApps(94); gap(20)
        buildContentRows()
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
                val height=when(config.size(key)) { RowCardSize.SMALL->190; RowCardSize.STANDARD->218; RowCardSize.LARGE->270 }
                val note=when(key) { "watchlist"->"Saved by you"; "episodes"->"Episode released · availability unconfirmed"; "new"->"Cinemeta · past 120 days"; "watching-shows","watching-movies"->"Shared by your apps"; else->"Your services first" }
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
                buildShowRow(rowLabels[key].orEmpty(),titles,height,key,config.size(key).visibleCards,sourceNote,empty,previous,next)
            }
            gap(24)
        } finally { page=outer }
    }
    /** Feed refreshes replace only changed rows. Unchanged cards keep focus and artwork. */
    private fun refreshHomeRows() {
        if(!ready || isFinishing || isDestroyed)return
        val config=personal.rowPreferences()
        val keys=config.ordered(rowLabels.keys).filter { it !in config.hidden && (it!="episodes" || content.newEpisodes.isNotEmpty()) }
        if(config!=renderedRowPreferences || keys!=rowSections.keys.toList()) { render(); return }
        stopRowMotion()
        val saved=if(nextRowFocus!=null)viewport(nextRowFocus) else pendingViewport ?: viewport()
        nextRowFocus=null; pendingViewport=saved
        val revision=++viewportRevision
        val oldFocus=currentFocus
        var replacement:String?=null
        for((key,container) in rowSections) {
            val value=rowValue(key)
            if(rowValues[key]==value)continue
            val oldCards=focusables.values.filter { descendant(it,container) }
            val oldIndex=oldCards.indexOf(oldFocus)
            forgetRow(container); container.removeAllViews()
            fillContentRow(key,container,config); rowValues[key]=value
            if(oldIndex>=0)replacement=focusables.values.filter { descendant(it,container) }.let { it.getOrNull(oldIndex.coerceAtMost(it.lastIndex))?.tag as? String }
        }
        if(layout!=HomeLayout.QUIET) {
            updateBoundProgram("feature",content.feature,if(wide)1132 else inner,if(wide)486 else 470)
            if(wide)content.suggestions.filter { it.id!=content.feature?.id }.let { next ->
                for(i in 0..1)updateBoundProgram("next-$i",next.getOrNull(i),608,184)
            }
        }
        updateHomeStatus()
        showAnchor=rowSections.values.asSequence().flatMap { section -> focusables.values.asSequence().filter { descendant(it,section) } }.firstOrNull() ?: firstCard
        if(saved.focus !in focusables && replacement!=null)pendingViewport=saved.copy(focus=replacement)
        val renderedRoot=root
        renderedRoot.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw():Boolean {
                renderedRoot.viewTreeObserver.removeOnPreDrawListener(this)
                if(root!==renderedRoot || revision!=viewportRevision)return true
                saved.rows.forEach { (key,x)->horizontalRows[key]?.scrollTo(x,0) }
                (focusables[saved.focus] ?: focusables[replacement] ?: firstCard)?.requestFocus()
                renderedRoot.scrollTo(0,saved.y); revealFocus(currentFocus)
                pendingViewport=null; homeRequested=false
                loadVisibleArt(); return true
            }
        })
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
        cardArt.removeAll { if(it.card in removed) { it.request.cancel(); true } else false }
        appCards.values.forEach { cards->cards.removeAll { it in removed } }
        if(lastContentFocus in removed)lastContentFocus=null
    }
    private fun updateHomeStatus() {
        val age=if(content.snapshotAt>0)android.text.format.DateUtils.getRelativeTimeSpanString(content.snapshotAt,System.currentTimeMillis(),60_000).toString() else ""
        homeStatus?.text=if(content.offline)"Offline · ${if(age.isEmpty())"cached content" else "updated $age"}" else ""
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
        if(boundPrograms[key]==p)return
        (focusables[key] as? PrettTvCard)?.let { bindProgram(it,p,w,h) }
    }
    private fun bindProgram(card:PrettTvCard,p:Program?,w:Int,h:Int) {
        val old=boundPrograms[card.tag as? String]
        if(old?.art!=p?.art)card.art=null
        cardArt.removeAll { if(it.card===card) { it.request.cancel(); true } else false }
        boundPrograms[card.tag as String]=p
        card.title=p?.title ?: if(discovery)"Find your next favorite" else "Make yourself at home"
        card.detail=p?.detail ?: if(discovery)"Browse your streaming apps" else "Your apps, one quiet place"
        card.summary=p?.summary.orEmpty()
        card.action=if(p==null)"Browse apps" else if(p.resume && p.intentUri.isNotBlank())"Resume in ${p.app}" else "Open ${p.app}"
        card.progress=p?.progress ?: 0f
        card.stremio=p?.packageName=="com.stremio.one"
        card.contentDescription=listOf(card.title,card.detail,card.action).joinToString(". ")
        card.setOnClickListener { if(p==null)appAnchor?.requestFocus() else openProgram(p) }
        card.setOnLongClickListener { if(p!=null)programInfo(p) else menu(); true }
        if(p!=null && p.art.isNotBlank())cardArt.add(CardArt(card,p.art,px(w),px(h)))
        card.invalidate()
    }
    private fun buildShowRow(title:String,programs:List<Program>,height:Int,key:String,count:Int,note:String="",emptyText:String="",previous:Pair<String,()->Unit>?=null,next:Pair<String,()->Unit>?=null) {
        page.addView(section(title,if(programs.isNotEmpty())"${programs.size} titles · $note" else note))
        val strip=row()
        // Leave a visible slice of the next card to make horizontal scrolling discoverable.
        val width=(inner-count*20-46)/count
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
                background=round(0xFF171F18.toInt(),0xFF364033.toInt())
                setOnFocusChangeListener { _,f -> background=round(if(f)0xFF263126.toInt() else 0xFF171F18.toInt(),if(f)ink else 0xFF364033.toInt()); setTextColor(ink) }
            }
            if(showAnchor==null)showAnchor=empty
            strip.addView(empty,LinearLayout.LayoutParams(-1,px(height)))
        } else programs.forEachIndexed { i,p ->
            val identity=if(key=="watchlist")ContentIdentity.key(p) else p.id
            strip.addView(programCard(p,PrettTvCard.Mode.SHOW,"$key-$identity",width,height),LinearLayout.LayoutParams(px(width),px(height)).apply { if(i<programs.lastIndex)rightMargin=px(20) })
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
        for(item in cardArt) {
            if(item.card.getGlobalVisibleRect(rect) && rect.width()>px(20) && rect.height()>px(20)) {
                if(item.card.art==null && item.request.begin(SystemClock.uptimeMillis())) {
                    artwork.load(item.url,item.w,item.h) { bitmap ->
                        // A replaced row/hero can still have a decoding request in flight.
                        if(cardArt.none { it===item })return@load
                        item.request.complete(bitmap!=null,SystemClock.uptimeMillis())
                        if(visible && item.card.getGlobalVisibleRect(Rect()))item.card.art=bitmap
                        if(bitmap==null && visible) { ui.removeCallbacks(retryArtwork); ui.postDelayed(retryArtwork,30_000) }
                    }
                }
            } else if(item.card.art!=null) { item.card.art=null }
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
        page.addView(section("Your apps","${entries.size} apps"))
        val strip=row(); val width=208
        if(entries.isEmpty()) {
            val choose=button(if(apps.isEmpty()) { if(appLoadError)"Apps unavailable · Retry" else if(appDiscoveryComplete)"No apps found · Retry" else "Loading your apps…" } else "Choose apps for this row","empty-app-row") { if(apps.isEmpty())reload() else reviewApps() }
            appAnchor=choose; if(firstCard==null)firstCard=choose
            choose.nextFocusLeftId=focusables["rail-apps"]!!.id
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
    private fun constrainRow(row:LinearLayout) {
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
            val appRow=card.tag.toString().let { it.startsWith("favorite-") || it.startsWith("sports-") || it.startsWith("app-") }
            card.nextFocusLeftId=cards.getOrNull(index-1)?.id ?: focusables[if(appRow)"rail-apps" else "rail-shows"]!!.id
            card.nextFocusRightId=cards.getOrNull(index+1)?.id ?: card.id
        }
    }
    private fun reload() {
        dirty=false; val g=++generation
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
            found.forEach { it.bitmap=previous[it.key]?.bitmap; it.logoBounds=previous[it.key]?.logoBounds; it.libraryBitmap=previous[it.key]?.libraryBitmap }
            ui.post { if(g==generation) { appLoadError=false; appDiscoveryComplete=true; apps=found; inputs=input; render(); if(visible)startContent() } }
            AppRefreshWork.each(found,{g==generation},{e ->
                e.libraryBitmap=repo.loadLibraryMark(e)
                repo.loadTile(e,320,180,0f)
            }, { e,image ->
                e.bitmap=image
                ui.post { if(g==generation)appCards[e.key]?.forEach { it.logoBounds=e.logoBounds; it.art=if(it.isLibraryCard)e.libraryBitmap else image } }
            }, { _,e -> android.util.Log.w("PrettTvApps","Icon failed: ${e.javaClass.simpleName}") })
            if(g==generation)AppRefreshWork.attempt({repo.pruneCache(found.map { repo.cacheName(it,320,180,0f) }.toSet())}) { }
        }
    }
    private fun startContent() {
        val recent=prefs.recentOrder().entries.sortedByDescending { it.value }.mapNotNull { entry -> apps.firstOrNull { it.key==entry.key }?.packageName }.distinct()
        val services=if(settings.contains("preferredServices"))settings.getStringSet("preferredServices",emptySet()).orEmpty().toSet() else null
        contentRepo.start(apps.map { it.packageName }.toSet(),emptySet(),discovery,recent,services)
    }
    private fun launch(e:AppEntry) { prefs.touchRecent(e.key); prefs.lastFocus=e.key; safeStart(repo.launchIntent(e),e.label) }
    private fun safeStart(i:Intent,label:String):Boolean = try { startActivity(i); true } catch(_:Exception) { Toast.makeText(this,"Could not open $label",Toast.LENGTH_SHORT).show(); false }
    private fun openInputs() { inputs?.let { safeStart(it.intent,"Inputs") } ?: repo.settingsIntent()?.let { safeStart(it,"TV settings") } }
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
        val place=viewport(origin)
        val before=orderedApps().filter { it.key !in prefs.hidden }.map { it.key }
        val favs=prefs.favourites.toMutableList(); val index=favs.indexOf(e.key)
        val beforeFavourites=prefs.favourites.toList(); val beforeHidden=prefs.hidden.toSet()
        val items=ArrayList<Pair<String,()->Unit>>()
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
        items+="App info" to { safeStart(repo.appInfoIntent(e.packageName),e.label) }
        var chosen:(()->Unit)?=null
        val menu=AlertDialog.Builder(this).setTitle(e.label)
            .setItems(items.map { it.first }.toTypedArray()) { _,i -> chosen=items[i].second }
            .setNegativeButton("Close",null).create()
        // Let the modal release window focus before requesting focus on the replacement tile.
        menu.setOnDismissListener { chosen?.invoke() }
        menu.show()
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
        items+="Weather" to { weatherSettings() }
        items+="Choose apps one by one" to { reviewApps() }
        items+="Live sports apps" to { chooseSportsApps() }
        items+="Recommendation services" to { chooseRecommendationServices() }
        items+="Layout: ${layout.label}" to {
            dialog("Choose your home",HomeLayout.entries.map { choice -> choice.label to { layout=choice; settings.edit().putString("layout",choice.name).apply(); settings.edit().remove("focus").apply(); render(); root.smoothScrollTo(0,0); firstCard?.requestFocus() } })
        }
        items+="Show discovery: ${if(discovery)"On" else "Off"}" to {
            settings.edit().putBoolean("discovery",!discovery).apply(); content=HomeContent(); render(); startContent()
        }
        repo.settingsIntent()?.let { i -> items+="TV settings" to { safeStart(i,"TV settings") } }
        items+="Inputs" to { openInputs() }
        items+="Choose default Home app" to { safeStart(Intent(android.provider.Settings.ACTION_HOME_SETTINGS),"Home settings") }
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
        dialog("prettTV",items)
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
        val input=EditText(this).apply { hint="App or show name"; isSingleLine=true; setTextColor(ink) }
        AlertDialog.Builder(this).setTitle("Search your home").setView(input).setPositiveButton("Search") { _,_ ->
            val query=input.text.toString().trim()
            if(query.isEmpty())return@setPositiveButton
            val choices=ArrayList<Pair<String,()->Unit>>()
            orderedApps().filter { it.label.contains(query,true) }.forEach { e -> choices+=e.label to { launch(e) } }
            (content.watching+personal.watchlist()+content.suggestions+content.discover+content.trending+content.newReleases).filter { it.title.contains(query,true) }.distinctBy { ContentIdentity.key(it) }.forEach { p -> choices+="${p.title} · ${p.app}" to { openProgram(p) } }
            if(choices.isEmpty())Toast.makeText(this,"No matching apps or loaded shows",Toast.LENGTH_LONG).show() else dialog("Results",choices)
        }.setNegativeButton("Cancel",null).show()
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
            "Privacy" to { AlertDialog.Builder(this).setTitle("Your home, your choice").setMessage("prettTV has no analytics, ad identifiers or registration. App arrangement, viewing records and launch recency stay on this TV. Optional helpers use active media-session metadata (Notification access) or navigate RBTV during a game request (Accessibility access). No notification text is read or retained. Helpers can be turned off in Android settings.\n\nWith discovery on, prettTV requests public catalogs and artwork from TVmaze, Stremio Cinemeta, ESPN scoreboards and their image services. Those servers receive your IP address and requested catalog/show/league IDs. Published TV rows can also link to provider artwork. Switch discovery off to stop general catalog requests. Shows you explicitly follow can still request episode data from TVmaze; unfollow them to stop those checks. Watchlist, followed shows, favorite teams and cached Home data stay on this TV. Cached recommendations expire after seven days. Update checks contact the official prettTV GitHub repository; APK downloads require your action and installation requires Android confirmation.\n\nOptional weather sends your chosen US ZIP code to Zippopotam.us and its area coordinates to the National Weather Service; these providers also receive your IP address. Location, units and a bounded weather cache stay on this TV. Weather does not use GPS or access streaming accounts. Turn off Show on Home in Weather settings to stop weather requests. Local settings exports include your weather location.\n\nCurrently watching uses progress that apps publish to Android TV. prettTV cannot inspect their login status or credentials. Continue watching and the hero use locally confirmed viewing first, with shared timestamps as an initial baseline. Timestamp refreshes alone do not prove playback. Supported media sessions can update local recency; otherwise hold OK on a title to correct it. Recommendations use shared viewing activity to prioritize services, without assuming an app launch proves a login. Choose your signed-in apps in Recommendation services to override this inference. Providers are balanced before row limits; general/free feeds follow your services and Stremio stays last with a purple S badge. prettTV does not remove ads inside other apps.").setPositiveButton("Close",null).show() }
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
        if(args?.contains("--weather-diagnostics")==true) {
            val weather=weatherRepo.state
            out.println("WEATHER configured=${weather.location!=null} enabled=${weatherRepo.prefs.enabled} units=${weatherRepo.prefs.units} reading=${weather.reading!=null} loading=${weather.loading} stale=${weather.stale} unavailable=${weather.error!=null}")
            out.println("WEATHER_RENDER headerVisible=${weatherHeader?.visibility==View.VISIBLE} clockVisible=${clock.visibility==View.VISIBLE}")
        }
        if(args?.contains("--content-diagnostics")==true)for(p in content.watching)
            out.println("WATCH ${p.packageName} | ${p.title} | ${p.id} | engagement=${p.engagedAt} | position=${p.position} | duration=${p.duration}")
    }
}
