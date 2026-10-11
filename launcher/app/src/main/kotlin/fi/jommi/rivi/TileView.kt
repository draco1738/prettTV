package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.View

/**
 * A single home-screen tile: rounded artwork + focus ring + label under the focused tile.
 * Draws itself with 3 paints and no child views, so a focus change costs one invalidate
 * of this view only. Scale animation is a hardware layer property (no relayout).
 */
class TileView(context: Context, val imgW: Int, val imgH: Int, private val labelH: Int, private val radius: Float) : View(context) {

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
        if (event.repeatCount == 0) event.startTracking()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirm(keyCode)) return super.onKeyLongPress(keyCode, event)
        performLongClick(); return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!isConfirm(keyCode)) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) performClick()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) FOCUS_SCALE else 1f
        animate().scaleX(s).scaleY(s).setDuration(ANIM_MS).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val p = pad.toFloat()
        if (isFocused) {
            // ring outside the artwork: gap + stroke, visible on any artwork colour
            val o = p - ringW / 2f - 1f
            rect.set(p - o, p - o, p + imgW + o, p + imgH + o)
            c.drawRoundRect(rect, radius + o, radius + o, ringPaint)
        }
        val b = bitmap
        if (b != null) c.drawBitmap(b, p, p, imgPaint)
        else { rect.set(p, p, p + imgW, p + imgH); c.drawRoundRect(rect, radius, radius, placeholderPaint) }
        if (isFocused && label.isNotEmpty()) c.drawText(label, p + imgW / 2f, p + imgH + labelH * 0.66f, textPaint)
    }

    companion object {
        const val FOCUS_SCALE = 1.08f
        const val ANIM_MS = 110L
    }
}
