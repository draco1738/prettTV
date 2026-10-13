package fi.jommi.rivi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The menu for a tile (hold OK) and for Rivi itself (MENU key): a sheet that slides in from the
 * right over a dimmed home screen, in Rivi's own colours.
 *
 * It does not take focus. The tile underneath stays focused and lit, and the activity hands the
 * remote's keys to [handleKey] while the sheet is open. Hidden (GONE) when closed, so it costs
 * nothing at rest.
 */
class SideMenu(context: Context, private val theme: Theme, private val px: (Int) -> Int) : FrameLayout(context) {

    private val scrim = View(context).apply { setBackgroundColor(0x66000000) }
    private val sheet = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(theme.sheet); setPadding(0, px(120), 0, 0) }
    private val slide = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val art = ImageView(context)
    private val title = TextView(context).apply {
        setTextColor(theme.sheetInk); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(38).toFloat())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); maxLines = 2
    }
    private val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val highlight = GradientDrawable().apply { setColor(theme.sheetHighlight); cornerRadius = px(14).toFloat() }

    private var actions: List<() -> Unit> = emptyList()
    private var index = 0
    private var armed = false      // OK went down while the sheet was open (not the press that opened it)

    var isOpen = false
        private set

    init {
        visibility = View.GONE
        addView(scrim, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // a soft edge on the sheet's left side, moving with it
        slide.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x00000000, 0x55000000))
        }, LinearLayout.LayoutParams(px(60), ViewGroup.LayoutParams.MATCH_PARENT))
        slide.addView(sheet, LinearLayout.LayoutParams(px(WIDTH), ViewGroup.LayoutParams.MATCH_PARENT))
        sheet.addView(art, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(48); bottomMargin = px(6) })
        sheet.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sheet.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(slide, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
    }

    fun open(heading: String, picture: Bitmap?, items: List<Pair<String, () -> Unit>>) {
        if (items.isEmpty()) return
        title.text = heading
        title.setPadding(px(48), px(if (picture != null) 22 else 0), px(48), px(22))
        art.setImageBitmap(picture); art.visibility = if (picture != null) View.VISIBLE else View.GONE
        list.removeAllViews()
        for ((label, _) in items) list.addView(TextView(context).apply {
            text = label; setTextColor(theme.sheetInk); setTextSize(TypedValue.COMPLEX_UNIT_PX, px(33).toFloat()); maxLines = 1
            setPadding(px(28), px(26), px(28), px(26))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = px(20); rightMargin = px(20) })
        actions = items.map { it.second }
        isOpen = true; armed = false
        select(0)
        visibility = View.VISIBLE
        slide.animate().cancel(); scrim.animate().cancel()
        if (!wasShowing()) { slide.translationX = px(WIDTH + 60).toFloat(); scrim.alpha = 0f }
        slide.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(SLIDE).withEndAction(null).start()
        scrim.animate().alpha(1f).setDuration(DIM_MS).start()
    }

    private fun wasShowing() = slide.translationX == 0f && scrim.alpha == 1f

    /** [animate] false closes at once (the home screen is going away or coming back). */
    fun close(animate: Boolean = true) {
        if (!isOpen && visibility == View.GONE) return
        isOpen = false; armed = false
        slide.animate().cancel(); scrim.animate().cancel()
        if (!animate) { slide.translationX = px(WIDTH + 60).toFloat(); scrim.alpha = 0f; visibility = View.GONE; return }
        slide.animate().translationX(px(WIDTH + 60).toFloat()).setDuration(SLIDE_MS).setInterpolator(SLIDE)
            .withEndAction { if (!isOpen) visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(DIM_MS).start()
    }

    private fun select(i: Int) {
        index = i.coerceIn(0, list.childCount - 1)
        for (n in 0 until list.childCount) list.getChildAt(n).background = if (n == index) highlight else null
        (list.getChildAt(index) as? TextView)?.let { announceForAccessibility(it.text) }
    }

    /** Keys while the sheet is open. Returns false for keys the menu has no use for (volume, Home, ...). */
    fun handleKey(e: KeyEvent): Boolean {
        val down = e.action == KeyEvent.ACTION_DOWN
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (down) select(index - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down) select(index + 1)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {}
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // the press that opened the sheet is still held when it appears: only a fresh press chooses
                if (down) { if (e.repeatCount == 0) armed = true }
                else if (armed && !e.isCanceled) { val a = actions.getOrNull(index); close(); a?.invoke() }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) close()
            KeyEvent.KEYCODE_MENU -> if (down && e.repeatCount == 0) close()
            else -> return false
        }
        return true
    }

    companion object {
        const val WIDTH = 620
        const val SLIDE_MS = 200L
        const val DIM_MS = 180L
        private val SLIDE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    }
}
