package fi.jommi.rivi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.FileOutputStream

/** One launchable thing on the home screen. */
class AppEntry(
    val key: String,            // "pkg/cls" – stable id
    val packageName: String,
    val label: String,
    val intent: Intent,
    val isBanner: Boolean,      // true: 16:9 leanback banner, false: square icon on a card
    val versionCode: Long,
) {
    @Volatile var bitmap: Bitmap? = null
    @Volatile var logoBounds: Rect? = null
}

/** Persisted user configuration. Tiny; plain SharedPreferences. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("rivi", Context.MODE_PRIVATE)

    var favourites: List<String>
        get() = sp.getString("fav", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(v) = sp.edit().putString("fav", v.joinToString("\n")).apply()
    var favouritesInitialised: Boolean
        get() = sp.getBoolean("fav_init", false)
        set(v) = sp.edit().putBoolean("fav_init", v).apply()
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()
    var lastFocus: String?
        get() = sp.getString("focus", null)
        set(v) = sp.edit().putString("focus", v).apply()
    /** Theme chosen with `--es theme …`; null means the default. See [Theme]. */
    var theme: String?
        get() = sp.getString("theme", null)
        set(v) = sp.edit().putString("theme", v).apply()
    /** Refinements chosen with `--es motion …`; null means the built-in default. See [Motion]. */
    var motion: String?
        get() = sp.getString("motion", null)
        set(v) = sp.edit().putString("motion", v).apply()

    /** Launch timestamps for "recent" ordering; no background tracking, only our own launches. */
    fun recentOrder(): Map<String, Long> = HomeModel.parseRecent(sp.getString("recent", null))

    fun touchRecent(key: String) =
        sp.edit().putString("recent", HomeModel.touchRecent(recentOrder(), key, System.currentTimeMillis())).apply()
}

/**
 * App discovery + icon cache. All heavy work runs on the caller's (background) thread.
 * Bitmaps are decoded once at exact tile size and cached as PNG under cacheDir so that
 * later cold starts never touch other packages' resources.
 */
class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val cacheDir = File(context.cacheDir, "tiles").apply { mkdirs() }

    /** Packages that are never useful on a TV home screen (they are launchers or ourselves). */
    private val excluded = setOf(
        context.packageName,
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.tungsten.setupwraith",
        "com.spocky.projengmenu",
    )

    val defaultFavouritePackages = listOf(
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.google.android.youtube.tv",
        "com.apple.atve.androidtv.appletv",
        "com.disney.disneyplus",
        "com.hbo.hbonow", "com.wbd.stream",
        "com.spotify.tv.android",
    )

    fun discover(): List<AppEntry> {
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val leanback = pm.queryIntentActivities(q, 0)
        val phone = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()
        for (ri in leanback+phone) {
            val ai = ri.activityInfo
            if (ai.packageName in excluded || ai.packageName.startsWith("com.leanbitlab.ltv",ignoreCase=true) || !seen.add(ai.packageName)) continue
            val cn = ComponentName(ai.packageName, ai.name)
            val intent = Intent(Intent.ACTION_MAIN).addCategory(if(ri in leanback)Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            val vc = try { pm.getPackageInfo(ai.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } } catch (e: Exception) { 0L }
            val hasBanner = ai.banner != 0 || ai.applicationInfo.banner != 0
            out.add(AppEntry(cn.flattenToShortString(), ai.packageName, ri.loadLabel(pm).toString(), intent, hasBanner, vc))
        }
        return out
    }

    /** Returns a bitmap of exactly w×h with rounded corners (radius r) baked in, from disk cache if possible. */
    fun loadTile(e: AppEntry, w: Int, h: Int, r: Float): Bitmap? {
        val f = File(cacheDir, cacheName(e, w, h, r))
        if (f.exists()) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            BitmapFactory.decodeFile(f.path, opts)?.let { if (it.width == w && it.height == h) { e.logoBounds=logoBounds(it,e.packageName); return it } else it.recycle() }
        }
        val packed=AppIconPack.render(context,e.packageName,w,h)
        val raw = packed ?: render(e, w, h) ?: return null
        if(packed==null)darkTile(e,raw)
        val bmp = rounded(raw, r); raw.recycle()
        e.logoBounds=logoBounds(bmp,e.packageName)
        try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
        return bmp
    }

    fun cacheName(e: AppEntry, w: Int, h: Int, r: Float) = "pretttv-icons3_${e.key.replace('/', '_')}_${e.versionCode}_${w}x${h}r${r.toInt()}.png"

    /** Owner-requested dark tiles, derived locally from installed-app banners; no copied logo assets. */
    private fun darkTile(e: AppEntry,b: Bitmap) {
        val youtube=e.packageName=="com.google.android.youtube.tv"
        val netflix=e.packageName=="com.netflix.ninja"
        val prime=e.packageName=="com.amazon.amazonvideo.livingroom"
        if(!youtube && !netflix && !prime)return
        val w=b.width; val h=b.height; val pixels=IntArray(w*h)
        b.getPixels(pixels,0,w,0,0,w,h)
        val edge=pixels[(h/2)*w+2]
        // Leave materially changed artwork untouched instead of applying a stale brand-specific transform.
        if(!prime && minOf(Color.red(edge),Color.green(edge),Color.blue(edge))<210)return
        if(prime && !(Color.blue(edge)>160 && Color.red(edge)<80))return
        var redLeft=w; var redRight=0; var redTop=h; var redBottom=0
        if(youtube)for(y in 0 until h)for(x in 0 until w) {
            val p=pixels[y*w+x]
            if(Color.red(p)>170 && Color.green(p)<90 && Color.blue(p)<90) {
                redLeft=minOf(redLeft,x); redRight=maxOf(redRight,x); redTop=minOf(redTop,y); redBottom=maxOf(redBottom,y)
            }
        }
        for(y in 0 until h)for(x in 0 until w) {
            val index=y*w+x; val p=pixels[index]
            val r=Color.red(p); val g=Color.green(p); val blue=Color.blue(p)
            if(prime) {
                val alpha=(r/255f).coerceIn(0f,1f)
                pixels[index]=Color.rgb((12+231*alpha).toInt(),(31+211*alpha).toInt(),(51+186*alpha).toInt())
            } else if(youtube && x in redLeft..redRight && y in redTop..redBottom) {
                // Preserve the white play triangle enclosed by the red mark.
                pixels[index]=p
            } else if(maxOf(r,g,blue)-minOf(r,g,blue)<35) {
                val alpha=if(youtube)1-minOf(r,g,blue)/255f else 0f
                pixels[index]=Color.rgb((19+225*alpha).toInt(),(27+214*alpha).toInt(),(22+211*alpha).toInt())
            } else {
                val background=minOf(r,g,blue)/255f
                pixels[index]=Color.rgb((r-236*background).toInt().coerceIn(0,255),(g-228*background).toInt().coerceIn(0,255),(blue-233*background).toInt().coerceIn(0,255))
            }
        }
        b.setPixels(pixels,0,w,0,0,w,h)
    }

    /** Find a logo on a quiet banner, off the UI thread. Photographic banners keep their full framing. */
    private fun logoBounds(b: Bitmap,packageName:String): Rect? {
        val w=b.width; val h=b.height; val pixels=IntArray(w*h)
        b.getPixels(pixels,0,w,0,0,w,h)
        var left=w; var top=h; var right=0; var bottom=0; var ink=0
        for(y in 4 until h-4) {
            val a=pixels[y*w+4]; val z=pixels[y*w+w-5]
            for(x in 5 until w-5) {
                val t=x.toFloat()/(w-1); val p=pixels[y*w+x]
                val dr=kotlin.math.abs(Color.red(p)-(Color.red(a)*(1-t)+Color.red(z)*t))
                val dg=kotlin.math.abs(Color.green(p)-(Color.green(a)*(1-t)+Color.green(z)*t))
                val db=kotlin.math.abs(Color.blue(p)-(Color.blue(a)*(1-t)+Color.blue(z)*t))
                if(maxOf(dr,dg,db)>58f) {
                    left=minOf(left,x); right=maxOf(right,x); top=minOf(top,y); bottom=maxOf(bottom,y); ink++
                }
            }
        }
        val fraction=ink.toFloat()/(w*h)
        // Disney's installed square icon has a fine wordmark with little ink. Size that
        // mark to the same visual bounds as landscape logos instead of shrinking the square.
        val minimumInk=if(packageName=="com.disney.disneyplus") .003f else .025f
        if(fraction !in minimumInk.. .45f || left<8 || top<8 || right>w-9 || bottom>h-9 || right<=left || bottom<=top)return null
        return Rect(left-2,top-2,right+3,bottom+3)
    }

    /** Delete cached tiles that are not in [wanted] (uninstalled apps, old versions, old sizes). */
    fun pruneCache(wanted: Set<String>) {
        val names = cacheDir.list()?.toList() ?: return
        for (n in HomeModel.staleCacheFiles(names, wanted)) File(cacheDir, n).delete()
    }

    private fun rounded(src: Bitmap, r: Float): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { shader = android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) }
        c.drawRoundRect(RectF(0f, 0f, src.width.toFloat(), src.height.toFloat()), r, r, p)
        return out
    }

    private fun render(e: AppEntry, w: Int, h: Int): Bitmap? {
        if (e.key == "inputs") return hdmiTile(w, h)
        val cn = e.intent.component ?: return null
        val ai = try { pm.getActivityInfo(cn, 0) } catch (ex: Exception) { return null }
        val banner: Drawable? = if (e.isBanner) try { ai.loadBanner(pm) } catch (_: Exception) { null } else null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (banner != null) {
            // Banners are 16:9 artwork; fill the tile (center-crop).
            drawFitted(c, banner, w, h, crop = true)
        } else {
            val icon = try { ai.loadIcon(pm) } catch (_: Exception) { null } ?: return null
            c.drawColor(0xFF1A1F2A.toInt())
            val size = (h * 0.62f).toInt()
            val left = (w - size) / 2; val top = (h - size) / 2
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(c)
        }
        return bmp
    }

    private fun drawFitted(c: Canvas, d: Drawable, w: Int, h: Int, crop: Boolean) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: w
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: h
        val s = if (crop) maxOf(w / iw.toFloat(), h / ih.toFloat()) else minOf(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * s).toInt(); val dh = (ih * s).toInt()
        val l = (w - dw) / 2; val t = (h - dh) / 2
        if (d is BitmapDrawable && d.bitmap != null) {
            val p = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.drawBitmap(d.bitmap, Rect(0, 0, d.bitmap.width, d.bitmap.height), RectF(l.toFloat(), t.toFloat(), (l + dw).toFloat(), (t + dh).toFloat()), p)
        } else {
            d.setBounds(l, t, l + dw, t + dh); d.draw(c)
        }
    }

    /** Google TV's built-in HDMI/input switcher, if this device has one. */
    fun inputsEntry(): AppEntry? {
        val i = Intent("com.android.tv.action.VIEW_INPUTS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ri = pm.resolveActivity(i, 0) ?: return null
        return AppEntry("inputs", ri.activityInfo.packageName, context.getString(R.string.inputs), i, false, 0L)
    }

    // Settings is opened with CLEAR_TASK. Without it, every visit that is left with Home instead of
    // Back stays alive, and the next one is stacked on top: five stacked Settings screens were found
    // on the test device, 160 MB and visibly slow. An app's own place is never cleared, only Settings.
    private val freshTask = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK

    fun settingsIntent(): Intent? {
        val i = Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(freshTask)
        return if (pm.resolveActivity(i, 0) != null) i else null
    }

    /** The package that answers ACTION_SETTINGS, so its tile can be opened the same way. */
    val settingsPackage: String? by lazy {
        pm.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName
    }

    fun launchIntent(e: AppEntry): Intent =
        if (e.packageName == settingsPackage) Intent(e.intent).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) else e.intent

    fun appInfoIntent(pkg: String): Intent =
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:$pkg")).addFlags(freshTask)

    companion object {
        /** HDMI plug glyph for the Inputs tile; drawn, so no artwork is shipped. */
        fun hdmiTile(w: Int, h: Int): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF1A1F2A.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9EDF4.toInt(); style = Paint.Style.STROKE; strokeWidth = h * 0.045f; strokeJoin = Paint.Join.ROUND }
            val cx = w / 2f; val cy = h / 2f; val pw = h * 0.42f; val ph = h * 0.26f
            val path = android.graphics.Path().apply {
                moveTo(cx - pw / 2, cy - ph / 2); lineTo(cx + pw / 2, cy - ph / 2); lineTo(cx + pw / 2, cy + ph * 0.15f)
                lineTo(cx + pw * 0.32f, cy + ph / 2); lineTo(cx - pw * 0.32f, cy + ph / 2); lineTo(cx - pw / 2, cy + ph * 0.15f); close()
            }
            c.drawPath(path, p)
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9EDF4.toInt(); textAlign = Paint.Align.CENTER; textSize = h * 0.16f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
            c.drawText("HDMI", cx, cy + ph / 2 + h * 0.2f, lp)
            return bmp
        }
    }
}
