package fi.jommi.rivi

// Built on Rivi v0.15, Copyright (c) 2026 Jommi, MIT. See assets/rivi-license.txt.
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var artwork: ArtworkLoader
    private val settings by lazy { getSharedPreferences("pretttv", MODE_PRIVATE) }
    private val playback by lazy { PlaybackHistory(this) }
    private var lastContentFocus:View?=null
    private var sidebar:View?=null
    private var layout = HomeLayout.BALANCED
    private var content = HomeContent()
    private var apps = emptyList<AppEntry>()
    private var inputs: AppEntry? = null
    private var visible = false
    private var dirty = true
    private var generation = 0
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
    private val appCards = HashMap<String, MutableList<PrettTvCard>>()
    private data class CardArt(val card:PrettTvCard,val url:String,val w:Int,val h:Int,var requested:Boolean=false)
    private val cardArt=ArrayList<CardArt>()
    private val ui = Handler(Looper.getMainLooper())
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
            val filtered=withTitlePreferences(value)
            if(filtered!=content) { content=filtered; render() }
        }
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
        RbtvHandoffService.cancel()
        registerReceiver(tickReceiver,IntentFilter(Intent.ACTION_TIME_TICK).apply { addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) })
        updateClock()
        if(dirty || apps.isEmpty())reload() else startContent()
    }
    override fun onStop() {
        visible=false; contentRepo.stop(); artwork.cancel(); unregisterReceiver(tickReceiver)
        (currentFocus?.tag as? String)?.let { settings.edit().putString("focus",it).apply() }
        super.onStop()
    }
    override fun onDestroy() { generation++; unregisterReceiver(packageReceiver); background.removeCallbacksAndMessages(null); loader.quitSafely(); contentRepo.close(); artwork.close(); super.onDestroy() }
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
        if(event.action==KeyEvent.ACTION_DOWN && event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT && sidebar?.visibility!=View.VISIBLE) {
            val target=focusables.values.firstOrNull { it.id==currentFocus?.nextFocusLeftId && it.tag.toString().startsWith("rail-") }
            if(target!=null) { sidebar?.visibility=View.VISIBLE; root.alpha=.45f; target.requestFocus(); return true }
        }
        return super.dispatchKeyEvent(event)
    }
    private fun hideRail(restore:Boolean=true) {
        sidebar?.visibility=View.GONE; root.alpha=1f
        if(restore)(lastContentFocus ?: firstCard)?.requestFocus()
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
    private fun updateClock() { if(::clock.isInitialized)clock.text=android.text.format.DateFormat.getTimeFormat(this).format(Date()) }

    private fun render(reset:Boolean=false) {
        if(isFinishing)return
        if(reset)homeRequested=true
        val focus=if(homeRequested)null else (currentFocus?.tag as? String) ?: settings.getString("focus",null)
        val oldScroll=if(ready && !homeRequested)root.scrollY else 0
        artwork.cancel(); cardArt.clear(); focusables.clear(); appCards.clear(); firstCard=null; appAnchor=null; libraryAnchor=null; showAnchor=null
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
        root.viewTreeObserver.addOnScrollChangedListener { loadVisibleArt() }
        val renderedRoot=root
        root.post {
            if(root!==renderedRoot)return@post
            (focusables[focus] ?: firstCard ?: focusables.values.firstOrNull())?.requestFocus()
            root.scrollTo(0,oldScroll)
            loadVisibleArt()
            homeRequested=false
        }
    }
    private fun buildHeader() {
        val header=row()
        header.addView(text("Home",36,0xFFADC1B6.toInt(),true),LinearLayout.LayoutParams(px(260),px(56)))
        header.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        clock=text("",26,ink).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.END }
        header.addView(clock,LinearLayout.LayoutParams(px(146),px(56)))
        page.addView(header,LinearLayout.LayoutParams(-1,px(56)))
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
        buildShowRow("Continue watching · Shows",content.visibleWatching.filter { it.mediaType=="series" },218,"watching-shows",4,"Shared by your apps","No shared shows yet\nOpen your apps to start watching")
        gap(24)
        buildShowRow("Continue watching · Movies",content.visibleWatching.filter { it.mediaType=="movie" },218,"watching-movies",4,"Shared by your apps","No shared movies yet\nOpen your apps to start watching")
        gap(24)
        buildLiveSports()
        gap(24)
        buildShowRow("Suggestions",content.suggestions,218,"discover",4,"Your apps · TVmaze")
        gap(24)
        buildShowRow("Trending",content.trending,218,"trending",4,"Cinemeta · popular now")
        gap(24)
        buildShowRow("New releases",content.newReleases,218,"new",4,"Cinemeta · past 120 days")
    }
    private fun buildLiveSports() {
        if(content.games.isNotEmpty()) {
            page.addView(section("Live sports","Live & upcoming · ESPN · Opens ${sportsDestination()?.label ?: "sports apps"}"))
            val strip=row(); val width=(inner-4*20-46)/4
            val now=System.currentTimeMillis()
            content.games.forEachIndexed { i,event ->
                val tile=PrettTvCard(this,scale,PrettTvCard.Mode.SPORT).apply {
                    register(this,"game-${event.id}"); title=event.title; summary=event.heading(now); detail=event.detail(now); action=event.network.ifBlank { event.league }
                    val destination=sportsDestination()?.label ?: "sports apps"
                    contentDescription="$title. $summary. $detail. Open $destination; select the channel in the app."
                    setOnClickListener { openSports(event) }
                    setOnLongClickListener { sportsInfo(event); true }
                }
                if(event.logo.isNotBlank())cardArt.add(CardArt(tile,event.logo,px(width),px(218)))
                strip.addView(tile,LinearLayout.LayoutParams(px(width),px(218)).apply { if(i<content.games.lastIndex)rightMargin=px(20) })
            }
            constrainRow(strip)
            val scroll=HorizontalScrollView(this).apply {
                isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
                clipChildren=true; clipToPadding=true; setPadding(0,px(6),0,px(6)); addView(strip,ViewGroup.LayoutParams(-2,px(218)))
            }
            page.addView(scroll,LinearLayout.LayoutParams(-1,px(230)))
            gap(12)
        }
        if(content.liveSports.isNotEmpty()) {
            buildShowRow(if(content.games.isEmpty())"Live sports" else "From your sports apps",content.liveSports,218,"live-events",4,"Live events shared by apps")
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
        val scroll=HorizontalScrollView(this).apply {
            isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true; clipToPadding=true; setPadding(0,px(5),0,px(5))
            addView(strip,ViewGroup.LayoutParams(-2,px(152)))
        }
        page.addView(scroll,LinearLayout.LayoutParams(-1,px(162)))
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
        if(showAnchor==null)showAnchor=card
        return card
    }
    private fun buildShowRow(title:String,programs:List<Program>,height:Int,key:String,count:Int,note:String="",emptyText:String="") {
        page.addView(section(title,if(programs.isNotEmpty())"${programs.size} titles · $note" else note))
        val strip=row()
        // Leave a visible slice of the next card to make horizontal scrolling discoverable.
        val width=(inner-count*20-46)/count
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
            strip.addView(programCard(p,PrettTvCard.Mode.SHOW,"$key-${p.id}",width,height),LinearLayout.LayoutParams(px(width),px(height)).apply { if(i<programs.lastIndex)rightMargin=px(20) })
        }
        if(programs.isEmpty())page.addView(strip) else {
            val scroll=HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
                isFocusable=false; clipChildren=true; clipToPadding=true; setPadding(0,px(6),0,px(6))
                addView(strip,ViewGroup.LayoutParams(-2,px(height)))
            }
            page.addView(scroll,LinearLayout.LayoutParams(-1,px(height+12)))
        }
        constrainRow(strip)
    }
    private fun loadVisibleArt() {
        val rect=Rect()
        for(item in cardArt) {
            if(item.card.getGlobalVisibleRect(rect) && rect.width()>px(20) && rect.height()>px(20)) {
                if(!item.requested) {
                    item.requested=true
                    artwork.load(item.url,item.w,item.h) { bitmap ->
                        if(item.card.getGlobalVisibleRect(Rect()))item.card.art=bitmap else item.requested=false
                    }
                }
            } else if(item.requested) { item.card.art=null; item.requested=false }
        }
    }
    private fun orderedApps():List<AppEntry> {
        val items=apps.map { HomeModel.Item(it.key,it.packageName,it.label) }
        if(!prefs.favouritesInitialised && apps.isNotEmpty()) { prefs.favourites=HomeModel.initialFavourites(repo.defaultFavouritePackages,items); prefs.favouritesInitialised=true }
        val arranged=HomeModel.arrange(items,prefs.favourites,emptySet(),prefs.recentOrder(),emptyList())
        val byKey=apps.associateBy { it.key }
        return (arranged.favourites+arranged.rest).mapNotNull { byKey[it] }.distinctBy { it.key }
    }
    private fun appCard(e:AppEntry,key:String):PrettTvCard=PrettTvCard(this,scale,PrettTvCard.Mode.APP).apply {
        title=e.label; contentDescription=e.label; logoBounds=e.logoBounds; art=e.bitmap; register(this,key)
        appCards.getOrPut(e.key){ArrayList()}.add(this)
        setOnClickListener { launch(e) }; setOnLongClickListener { appMenu(e); true }
        if(appAnchor==null)appAnchor=this
        if(firstCard==null)firstCard=this
    }
    private fun buildApps(height:Int) {
        val entries=orderedApps().filter { it.key !in prefs.hidden }
        page.addView(section("Your apps","${entries.size} apps"))
        val strip=row(); val width=(inner-5*18)/6
        if(entries.isEmpty()) {
            val choose=button(if(apps.isEmpty())"Loading your apps…" else "Choose apps for this row","empty-app-row") { reviewApps() }
            appAnchor=choose; if(firstCard==null)firstCard=choose
            choose.nextFocusLeftId=focusables["rail-apps"]!!.id
            firstCard?.nextFocusDownId=choose.id
            strip.addView(choose,LinearLayout.LayoutParams(-1,px(height)))
            page.addView(strip); return
        }
        val scroll=HorizontalScrollView(this).apply {
            isFocusable=false; isHorizontalScrollBarEnabled=false; overScrollMode=View.OVER_SCROLL_NEVER
            clipChildren=true; clipToPadding=true; isSmoothScrollingEnabled=false; setPadding(0,px(4),0,px(4))
        }
        entries.forEachIndexed { i,e ->
            strip.addView(appCard(e,"favorite-${e.key}"),LinearLayout.LayoutParams(px(width),px(height)).apply { if(i<entries.lastIndex)rightMargin=px(18) })
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
            entries.forEachIndexed { i,e -> strip.addView(appCard(e,"app-${e.key}"),LinearLayout.LayoutParams(px(width),px(154)).apply { if(i<5)rightMargin=px(18) }) }
            page.addView(strip,LinearLayout.LayoutParams(-1,px(154)).apply { bottomMargin=px(20) }); constrainRow(strip)
        }
        all.firstOrNull()?.let { libraryAnchor=focusables["app-${it.key}"] }
    }
    private fun constrainRow(row:LinearLayout) {
        if(row.childCount==0)return
        val first=row.getChildAt(0); val last=row.getChildAt(row.childCount-1)
        if(first.id!=View.NO_ID)first.nextFocusLeftId=focusables[if(first is PrettTvCard && first.tag.toString().contains("favorite-"))"rail-apps" else "rail-shows"]!!.id
        if(last.id!=View.NO_ID)last.nextFocusRightId=last.id
    }
    private fun reload() {
        dirty=false; val g=++generation
        val previous=apps.associateBy { it.key }
        background.post {
            val found=repo.discover(); val input=repo.inputsEntry()
            found.forEach { it.bitmap=previous[it.key]?.bitmap; it.logoBounds=previous[it.key]?.logoBounds }
            ui.post { if(g==generation) { apps=found; inputs=input; render(); if(visible)startContent() } }
            for(e in found) {
                if(g!=generation)break
                val image=repo.loadTile(e,320,180,0f)
                e.bitmap=image
                ui.post { if(g==generation)appCards[e.key]?.forEach { it.logoBounds=e.logoBounds; it.art=image } }
            }
            if(g==generation)repo.pruneCache(found.map { repo.cacheName(it,320,180,0f) }.toSet())
        }
    }
    private fun startContent() {
        val recent=prefs.recentOrder().entries.sortedByDescending { it.value }.mapNotNull { entry -> apps.firstOrNull { it.key==entry.key }?.packageName }.distinct()
        contentRepo.start(apps.map { it.packageName }.toSet(),emptySet(),discovery,recent)
    }
    private fun launch(e:AppEntry) { prefs.touchRecent(e.key); prefs.lastFocus=e.key; safeStart(repo.launchIntent(e),e.label) }
    private fun safeStart(i:Intent,label:String):Boolean = try { startActivity(i); true } catch(_:Exception) { Toast.makeText(this,"Could not open $label",Toast.LENGTH_SHORT).show(); false }
    private fun openInputs() { inputs?.let { safeStart(it.intent,"Inputs") } ?: repo.settingsIntent()?.let { safeStart(it,"TV settings") } }
    private fun openProgram(p:Program) {
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
        apps.firstOrNull { it.packageName==p.packageName }?.let { launch(it); if(p.catalog)Toast.makeText(this,"Find ${p.title} in ${p.app}",Toast.LENGTH_LONG).show() }
    }
    private fun dialog(title:String,items:List<Pair<String,()->Unit>>) {
        AlertDialog.Builder(this).setTitle(title).setItems(items.map { it.first }.toTypedArray()) { _,i -> items[i].second() }.setNegativeButton("Close",null).show()
    }
    private fun appMenu(e:AppEntry) {
        val favs=prefs.favourites.toMutableList(); val index=favs.indexOf(e.key)
        val items=ArrayList<Pair<String,()->Unit>>()
        fun changed() { render(); startContent() }
        if(index>=0) {
            items+="Remove from favorites" to { favs.remove(e.key); prefs.favourites=favs; changed() }
            if(index>0)items+="Move left" to { prefs.favourites=HomeModel.move(favs,e.key,-1); changed() }
            if(index<favs.size-1)items+="Move right" to { prefs.favourites=HomeModel.move(favs,e.key,1); changed() }
        } else items+="Add to favorites" to { favs.add(e.key); prefs.favourites=favs; changed() }
        if(e.key in prefs.hidden)items+="Show in Your apps row" to { prefs.hidden=prefs.hidden-e.key; changed() }
        else items+="Hide from Your apps row" to { prefs.hidden=prefs.hidden+e.key; changed() }
        items+="App info" to { safeStart(repo.appInfoIntent(e.packageName),e.label) }
        dialog(e.label,items)
    }
    private fun menu() {
        val items=ArrayList<Pair<String,()->Unit>>()
        items+="Choose apps one by one" to { reviewApps() }
        items+="Live sports apps" to { chooseSportsApps() }
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
        dialog("prettTV",items)
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
            (content.watching+content.suggestions+content.trending+content.newReleases).filter { it.title.contains(query,true) }.distinctBy { it.id }.forEach { p -> choices+="${p.title} · ${p.app}" to { openProgram(p) } }
            if(choices.isEmpty())Toast.makeText(this,"No matching apps or loaded shows",Toast.LENGTH_LONG).show() else dialog("Results",choices)
        }.setNegativeButton("Cancel",null).show()
    }
    private fun programInfo(p:Program) {
        dialog(p.title,listOf(
            "Open ${p.app}" to { openProgram(p) },
            "I watched this most recently" to { playback.confirm(p); startContent(); Toast.makeText(this,"Recent viewing corrected on this TV",Toast.LENGTH_SHORT).show() },
            "Hide from large hero" to { hideTitle(p,hero=true,watching=false) },
            "Hide from Continue watching" to { hideTitle(p,hero=false,watching=true) },
            "Hide from both" to { hideTitle(p,hero=true,watching=true) },
            "About this title / source" to { programDetails(p) }
        ))
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
        val edit=settings.edit().putString("hiddenTitleLabel:${p.titleKey}",p.title)
        if(hero)edit.putStringSet("hiddenHeroTitles",content.hiddenHeroTitles+p.titleKey)
        if(watching)edit.putStringSet("hiddenWatchingTitles",content.hiddenWatchingTitles+p.titleKey)
        edit.apply(); content=withTitlePreferences(content); render()
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
        val detail="${p.title}\n${p.detail}\n\n"+if(p.catalog)"General discovery from ${p.sourceName}. Availability depends on your service, subscription and region. Catalog listings do not guarantee a playable source.\n\n${p.sourceUrl}" else "Published by ${p.app} through Android TV."
        AlertDialog.Builder(this).setTitle("About this show").setMessage(detail).setPositiveButton("Open ${p.app}") { _,_ -> openProgram(p) }.setNeutralButton("Source") { _,_ -> if(p.sourceUrl.isNotBlank())safeStart(Intent(Intent.ACTION_VIEW,Uri.parse(p.sourceUrl)),"TVmaze") }.setNegativeButton("Close",null).show()
    }
    private fun credits() {
        dialog("prettTV 0.1.2",listOf(
            "Built on Rivi · Jommi · MIT license" to { readLicense("Rivi — Copyright (c) 2026 Jommi","rivi-license.txt") },
            "All credits and third-party notices" to { readLicense("Credits and notices","third-party-notices.txt") },
            "Kotlin / JetBrains · Apache 2.0" to { readLicense("Apache License 2.0","apache-2.0.txt") },
            "TVmaze · show data · CC BY-SA" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.tvmaze.com/api#licensing")),"TVmaze") },
            "Cinemeta · popular and recent titles" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/Stremio/stremio-official-addons")),"Cinemeta") },
            "ESPN · sports schedules and broadcasts" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.espn.com/")),"ESPN") },
            "Project source · github.com/draco1738/prettTV" to { safeStart(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/draco1738/prettTV")),"Project source") },
            "Privacy" to { AlertDialog.Builder(this).setTitle("Your home, your choice").setMessage("prettTV has no analytics, ad identifiers or registration. App arrangement, viewing records and launch recency stay on this TV. Optional helpers use active media-session metadata (Notification access) or navigate RBTV during a game request (Accessibility access). No notification text is read or retained. Helpers can be turned off in Android settings.\n\nWith discovery on, prettTV requests public catalogs and artwork from TVmaze, Stremio Cinemeta, ESPN scoreboards and their image services. Those servers receive your IP address and requested catalog/show/league IDs. Published TV rows can also link to provider artwork. Switch discovery off to stop public catalog requests.\n\nCurrently watching uses progress that apps publish to Android TV. prettTV cannot inspect their login status or credentials. Continue watching and the hero use locally confirmed viewing first, with shared timestamps as an initial baseline. Timestamp refreshes alone do not prove playback. Supported media sessions can update local recency; otherwise hold OK on a title to correct it. Discovery prefers other apps ahead of Stremio; a purple S identifies Stremio. prettTV does not remove ads inside other apps.").setPositiveButton("Close",null).show() }
        ))
    }
    private fun readLicense(title:String,file:String) {
        val body=assets.open(file).bufferedReader().use { it.readText() }
        val label=TextView(this).apply { text=body; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP,18f); setPadding(px(30),px(20),px(30),px(20)) }
        val scroll=ScrollView(this).apply { isFocusable=true; addView(label) }
        AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("Close",null).show()
    }
    override fun dump(prefix:String,fd:java.io.FileDescriptor?,out:java.io.PrintWriter,args:Array<out String>?) {
        val stores=mapOf("home" to settings,"apps" to getSharedPreferences("rivi",MODE_PRIVATE),"playback" to getSharedPreferences("prett-playback",MODE_PRIVATE))
        if(args?.contains("--export-local-settings")==true) { out.println("PRETTTV_SETTINGS="+LocalSettingsTransfer.export(stores)); return }
        val restore=args?.indexOf("--restore-local-settings") ?: -1
        if(restore>=0) {
            try { LocalSettingsTransfer.restore(args!![restore+1],stores); layout=HomeLayout.parse(settings.getString("layout",null)); content=HomeContent(); render(reset=true); reload(); out.println("PRETTTV_SETTINGS_RESTORED") }
            catch(_:Exception) { out.println("PRETTTV_SETTINGS_RESTORE_FAILED") }
            return
        }
        super.dump(prefix,fd,out,args)
        out.println("prettTV layout=${layout.name} apps=${apps.size} watching=${content.watching.size} suggestions=${content.suggestions.size} trending=${content.trending.size} new=${content.newReleases.size} games=${content.games.size} discovery=$discovery")
        out.println("prettTV focus=${currentFocus?.tag} visible=$visible artwork=${appCards.values.flatten().count { it.art!=null }}")
        if(args?.contains("--content-diagnostics")==true)for(p in content.watching)
            out.println("WATCH ${p.packageName} | ${p.title} | ${p.id} | engagement=${p.engagedAt} | position=${p.position} | duration=${p.duration}")
    }
}
