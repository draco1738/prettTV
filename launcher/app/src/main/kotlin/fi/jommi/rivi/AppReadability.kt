package fi.jommi.rivi

import android.app.AlertDialog
import android.content.Context
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ListAdapter
import android.widget.TextView
import java.util.WeakHashMap

/** Pure app-local dimensions. Font scale belongs to Android/SP or the reference-text caller. */
internal class ReadabilityDimensions(val size: HomeInterfaceSize = HomeInterfaceSize.STANDARD) {
    val factor: Float get() = size.factor
    fun referenceScale(widthPixels: Int) = widthPixels / 1920f * factor
    /** Physical app-owned pixels; retain MATCH_PARENT, WRAP_CONTENT and zero-weight dimensions. */
    fun pixels(value: Int) = if (value <= 0) value else (value * factor + .5f).toInt()
    /** Keep the existing dp utility's truncation at Standard. */
    fun dp(value: Int, density: Float) = (value * density * factor).toInt()
    /** Pass to TextView.textSize/setTextSize(SP), not to a PX setter. */
    fun sp(value: Float) = value * factor
}

/**
 * One existing preference for prettTV-owned geometry, never a Resources/density override.
 *
 * Reference Views: use referenceScale(context) in the existing px()/AppActionStyle helpers.
 * dp/sp screens: dimensions(context).dp(value, density) and .sp(value), respectively.
 * Native AlertDialogs: call styleDialog(dialog) AFTER show(), or inside the existing onShow.
 * It preserves listeners, focus, choice state, colors and native scrolling. Only native text
 * and its row spacing are resized; custom setView() content is deliberately excluded.
 * Size custom native forms once with nativeContent(context, form), or build them using the
 * reference/dp/sp helpers. Pass any already-sized custom TITLE in alreadySized to skip it too.
 * Do not run nativeContent on a reference-sized page or AppActionStyle controls.
 */
internal object AppReadability {
    private const val PREFERENCES = "pretttv"
    private const val SIZE_KEY = "interfaceSize"

    fun dimensions(context: Context): ReadabilityDimensions {
        val value = try { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(SIZE_KEY, null) }
            catch (_: ClassCastException) { null }
        return ReadabilityDimensions(HomeInterfaceSize.parse(value))
    }

    fun referenceScale(context: Context) = dimensions(context).referenceScale(context.resources.displayMetrics.widthPixels)

    fun styleDialog(dialog: AlertDialog, vararg alreadySized: View) {
        val root = dialog.window?.decorView ?: return
        val dimensions = dimensions(dialog.context)
        resizeTree(root, dimensions, alreadySized.toSet(), skipCustom = true)
        dialog.listView?.let { list ->
            val adapter = list.adapter ?: return@let
            if (adapter is SizedListAdapter) {
                adapter.dimensions = dimensions
            } else if (dimensions.factor != 1f) {
                // ListView creates and recycles rows later; a one-shot decor traversal misses
                // offscreen choices. Wrap only rendering, retaining the real ListAdapter contract.
                val checked = list.checkedItemPositions?.clone()
                val selected = list.selectedItemPosition
                list.adapter = SizedListAdapter(adapter, dimensions)
                checked?.let { positions ->
                    for (index in 0 until positions.size())
                        list.setItemChecked(positions.keyAt(index), positions.valueAt(index))
                }
                if (selected >= 0) list.setSelection(selected)
            }
        }
    }

    /** For an otherwise unscaled custom native form only; safe to repeat, never compounds. */
    fun nativeContent(context: Context, root: View) = resizeTree(root, dimensions(context), emptySet(), skipCustom = false)

    private class SizedListAdapter(private val source: ListAdapter, var dimensions: ReadabilityDimensions) : ListAdapter by source {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            source.getView(position, convertView, parent).also { resizeTree(it, dimensions, emptySet(), skipCustom = false) }
    }

    // Weak keys and numeric-only values: dialogs/Activities are never retained by this helper.
    // Capture the native PX result, which already includes Android's system font scaling.
    private data class NativeText(
        val size: Float, val left: Int, val top: Int, val right: Int, val bottom: Int,
        val minimumHeight: Int, val textMinHeight: Int, val drawableGap: Int,
        val lineExtra: Float, val lineMultiplier: Float, val height: Int?,
        val marginLeft: Int, val marginTop: Int, val marginRight: Int, val marginBottom: Int
    )
    private val originals = WeakHashMap<TextView, NativeText>()

    private fun resizeTree(view: View, dimensions: ReadabilityDimensions, excluded: Set<View>, skipCustom: Boolean) {
        if (view in excluded || (skipCustom && view.id == android.R.id.custom)) return
        if (view is TextView) resizeText(view, dimensions)
        if (view is ViewGroup) for (index in 0 until view.childCount)
            resizeTree(view.getChildAt(index), dimensions, excluded, skipCustom)
    }

    private fun resizeText(view: TextView, dimensions: ReadabilityDimensions) {
        if (dimensions.factor == 1f && !originals.containsKey(view)) return // exact native default
        val original = originals.getOrPut(view) {
            val margins = view.layoutParams as? ViewGroup.MarginLayoutParams
            NativeText(view.textSize, view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom,
                view.minimumHeight, view.minHeight, view.compoundDrawablePadding, view.lineSpacingExtra,
                view.lineSpacingMultiplier, view.layoutParams?.height, margins?.leftMargin ?: 0,
                margins?.topMargin ?: 0, margins?.rightMargin ?: 0, margins?.bottomMargin ?: 0)
        }
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, original.size * dimensions.factor)
        view.setPadding(dimensions.pixels(original.left), dimensions.pixels(original.top),
            dimensions.pixels(original.right), dimensions.pixels(original.bottom))
        view.minimumHeight = dimensions.pixels(original.minimumHeight)
        if (original.textMinHeight >= 0) view.minHeight = dimensions.pixels(original.textMinHeight)
        view.compoundDrawablePadding = dimensions.pixels(original.drawableGap)
        view.setLineSpacing(original.lineExtra * dimensions.factor, original.lineMultiplier)
        view.layoutParams?.let { params ->
            original.height?.let { params.height = dimensions.pixels(it) }
            if (params is ViewGroup.MarginLayoutParams) {
                params.leftMargin = dimensions.pixels(original.marginLeft)
                params.topMargin = dimensions.pixels(original.marginTop)
                params.rightMargin = dimensions.pixels(original.marginRight)
                params.bottomMargin = dimensions.pixels(original.marginBottom)
            }
            view.layoutParams = params
        }
    }
}
