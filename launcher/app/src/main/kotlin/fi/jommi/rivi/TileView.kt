package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.animation.ValueAnimator
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator

/**
 * A single home-screen tile: rounded artwork + focus ring + label under the focused tile.
 * Draws itself with a few paints and no child views, so a focus change costs one invalidate
 * of this view only. Scale animation is a hardware layer property (no relayout). With [Motion.fade]
 * the two tiles involved redraw for the 140 ms of the fade; nothing redraws at rest.
 */
class TileView(context: Context, val imgW: Int, val imgH: Int, private val labelH: Int, val radius: Float, private val motion: Motion = Motion.NONE) : View(context) {

    /** Space reserved around the artwork for the focus ring (drawn outside the artwork, with a gap). */
    val pad: Int = maxOf(6, (imgH * 0.045f).toInt())

    var label: String = ""
        set(v) { field = v; contentDescription = v }   // visible to accessibility and uiautomator
    var entryKey: String = ""
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF161B25.toInt() }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE9EDF4.toInt(); textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val ringW: Float = maxOf(3f, imgH * 0.02f)

    /** Called when this tile takes focus (the home screen moves the shadow under it). */
    var onFocused: ((TileView) -> Unit)? = null

    // 0 = unfocused, 1 = focused: opacity of the ring and the label. Jumps without Motion.fade.
    private var focusT = 0f
    private var focusAnim: ValueAnimator? = null
    // 0..1 while OK is held down: how much of the hold ring is filled
    private var holdT = 0f
    private var holdAnim: ValueAnimator? = null
    private val holdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        isClickable = true
        ringPaint.strokeWidth = ringW
        textPaint.textSize = labelH * 0.48f
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(imgW + 2 * pad, pad + imgH + labelH)

    // OK / long-press OK are driven by key events (tracking + FLAG_LONG_PRESS) rather than View's
    // timer-based long click: the result no longer depends on a race between the timer and key-up.
    private fun isConfirm(k: Int) = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirm(keyCode)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) { event.startTracking(); startHold() }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirm(keyCode)) return super.onKeyLongPress(keyCode, event)
        stopHold(); performLongClick(); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirm(keyCode)) return super.onKeyUp(keyCode, event)
        stopHold()
        if (event.isTracking && !event.isCanceled) performClick()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) FOCUS_SCALE else 1f
        val a = animate().scaleX(s).scaleY(s)
        if (motion.settle) a.setDuration(if (gainFocus) SETTLE_IN_MS else SETTLE_OUT_MS).setInterpolator(if (gainFocus) SETTLE_IN else SETTLE_OUT)
        else a.setDuration(ANIM_MS)
        a.start()
        if (!gainFocus) stopHold()
        val to = if (gainFocus) 1f else 0f
        focusAnim?.cancel()
        if (motion.fade && isLaidOut) {
            focusAnim = ValueAnimator.ofFloat(focusT, to).apply {
                duration = (FADE_MS * Math.abs(to - focusT)).toLong(); interpolator = LINEAR
                addUpdateListener { focusT = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { focusT = to; invalidate() }
        if (gainFocus) onFocused?.invoke(this)
    }

    // Holding OK opens the tile menu after the system long-press time. The ring shows that something
    // is coming. It starts after a short delay so that an ordinary press does not flash it.
    private fun startHold() {
        if (!motion.hold) return
        holdAnim?.cancel()
        val total = ViewConfiguration.getLongPressTimeout().toLong()
        holdAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = HOLD_DELAY_MS; duration = maxOf(100L, total - HOLD_DELAY_MS); interpolator = LINEAR
            addUpdateListener { holdT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopHold() {
        holdAnim?.cancel(); holdAnim = null
        if (holdT != 0f) { holdT = 0f; invalidate() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) stopHold()
    }

    override fun onDetachedFromWindow() {
        focusAnim?.cancel(); focusAnim = null; stopHold()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val p = pad.toFloat()
        val f = focusT
        if (f > 0f) {
            // ring outside the artwork: gap + stroke, visible on any artwork colour
            val o = p - ringW / 2f - 1f
            rect.set(p - o, p - o, p + imgW + o, p + imgH + o)
            ringPaint.alpha = (255 * ease(minOf(1f, f * FADE_MS / RING_FADE_MS))).toInt()
            c.drawRoundRect(rect, radius + o, radius + o, ringPaint)
        }
        val b = bitmap
        if (b != null) c.drawBitmap(b, p, p, imgPaint)
        else { rect.set(p, p, p + imgW, p + imgH); c.drawRoundRect(rect, radius, radius, placeholderPaint) }
        if (f > 0f && label.isNotEmpty()) {
            // the label drops into place as it fades in
            val e = ease(f)
            textPaint.alpha = (255 * e).toInt()
            c.drawText(label, p + imgW / 2f, p + imgH + labelH * (0.66f - 0.14f * (1f - e)), textPaint)
        }
        if (holdT > 0f) {
            // Hold ring in the middle of the artwork: a dark track, and a white arc with a dark edge.
            // The edge is what keeps the arc readable on white artwork (seen on a white banner on the
            // test device: a plain white arc only looked like the track disappearing).
            val w = imgH * 0.099f; val edge = maxOf(2f, imgH * 0.012f)
            val cx = p + imgW / 2f; val cy = p + imgH / 2f; val r = imgH * 0.33f - w / 2f
            rect.set(cx - r, cy - r, cx + r, cy + r)
            holdPaint.strokeWidth = w; holdPaint.color = 0x59000000; c.drawOval(rect, holdPaint)
            holdPaint.strokeWidth = w + 2 * edge; holdPaint.color = 0xCC000000.toInt(); c.drawArc(rect, -90f, 360f * holdT, false, holdPaint)
            holdPaint.strokeWidth = w; holdPaint.color = Color.WHITE; c.drawArc(rect, -90f, 360f * holdT, false, holdPaint)
        }
    }

    private fun ease(x: Float) = 1f - (1f - x) * (1f - x)

    companion object {
        const val FOCUS_SCALE = 1.08f
        const val ANIM_MS = 110L
        // the values below are the ones tuned in tools/motion-study
        const val SETTLE_IN_MS = 190L
        const val SETTLE_OUT_MS = 130L
        const val FADE_MS = 140f
        const val RING_FADE_MS = 110f
        const val HOLD_DELAY_MS = 120L
        private val SETTLE_IN = PathInterpolator(0.2f, 1.45f, 0.35f, 1f)   // overshoots, then settles
        private val SETTLE_OUT = PathInterpolator(0.3f, 0.7f, 0.4f, 1f)
        private val LINEAR = LinearInterpolator()
    }
}
