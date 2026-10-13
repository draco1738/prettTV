package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * The soft shadow under the focused tile. One view that sits behind every tile, so a neighbour is
 * never shaded by it. The shadow is blurred once per tile size into a small bitmap; after that the
 * view only moves (translation) and fades (alpha), which are render properties: it does not redraw
 * while fading, and nothing happens at rest.
 */
class ShadowView(context: Context) : View(context) {

    private class Sprite(val bitmap: Bitmap, val margin: Float, val w: Float, val h: Float)

    private val sprites = HashMap<Long, Sprite>()
    private var sprite: Sprite? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private var target: TileView? = null
    private var shown = false
    private val origin = android.graphics.Rect()

    init { alpha = 0f }

    // alpha is applied to the single bitmap draw; no off-screen layer needed
    override fun hasOverlappingRendering() = false

    /** Put the shadow under [t]. It appears with a short fade once the tile has a position. */
    fun follow(t: TileView?) {
        target = t; shown = false
        animate().cancel(); alpha = 0f
        place()
    }

    /** Recompute the position (after a layout pass or while the favourites row scrolls). Cheap when nothing moved. */
    fun place() {
        val t = target ?: return
        val host = parent as? android.view.ViewGroup ?: return
        if (!t.isLaidOut || !t.isAttachedToWindow) return
        // artwork rectangle of the tile at its focused scale, in the coordinates of our parent
        origin.set(0, 0, 0, 0)
        try { host.offsetDescendantRectToMyCoords(t, origin) } catch (_: IllegalArgumentException) { return }
        val s = TileView.FOCUS_SCALE
        val cx = origin.left + t.width / 2f; val cy = origin.top + t.height / 2f
        val aw = t.imgW * s; val ah = t.imgH * s
        val ax = cx + (t.pad - t.width / 2f) * s; val ay = cy + (t.pad - t.height / 2f) * s
        val sp = spriteFor(aw * 0.9f, ah * 0.88f, t.radius, ah * 0.14f)
        if (sp !== sprite) { sprite = sp; invalidate() }
        translationX = ax + aw * 0.05f - sp.margin
        translationY = ay + ah * 0.2f - sp.margin
        if (!shown) {
            shown = true
            animate().alpha(OPACITY).setDuration(FADE_MS).setInterpolator(EASE_OUT).start()
        }
    }

    /** The largest shadow we will draw, so the view can be given a fixed size once. */
    fun sizeFor(imgW: Int, imgH: Int): Pair<Int, Int> {
        val s = TileView.FOCUS_SCALE; val m = margin(imgH * s * 0.14f)
        return (imgW * s * 0.9f + 2 * m + 1).toInt() to (imgH * s * 0.88f + 2 * m + 1).toInt()
    }

    // BlurMaskFilter's radius is about 1.73 times the Gaussian sigma that the study's CSS blur() takes
    private fun blurRadius(sigma: Float) = maxOf(1f, (sigma - 0.5f) / 0.57735f)
    private fun margin(sigma: Float) = blurRadius(sigma)

    private fun spriteFor(w: Float, h: Float, r: Float, sigma: Float): Sprite {
        sprite?.let { if (it.w == w && it.h == h) return it }
        val key = (w.toLong() shl 32) or h.toLong()
        return sprites.getOrPut(key) {
            // drawn at half size and stretched: a blur has no detail to lose, and it costs a quarter
            val k = 0.5f; val m = margin(sigma)
            val b = Bitmap.createBitmap(((w + 2 * m) * k + 1).toInt(), ((h + 2 * m) * k + 1).toInt(), Bitmap.Config.ARGB_8888)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COLOR; maskFilter = BlurMaskFilter(blurRadius(sigma) * k, BlurMaskFilter.Blur.NORMAL) }
            Canvas(b).drawRoundRect(RectF(m * k, m * k, (m + w) * k, (m + h) * k), r * k, r * k, p)
            Sprite(b, m, w, h)
        }
    }

    override fun onDraw(c: Canvas) {
        val sp = sprite ?: return
        dst.set(0f, 0f, sp.w + 2 * sp.margin, sp.h + 2 * sp.margin)
        c.drawBitmap(sp.bitmap, null, dst, paint)
    }

    companion object {
        const val COLOR = 0xB8000000.toInt()   // black at 72%
        const val OPACITY = 0.9f
        const val FADE_MS = 200L
        private val EASE_OUT = DecelerateInterpolator()
    }
}
