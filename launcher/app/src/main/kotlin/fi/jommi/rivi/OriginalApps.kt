package fi.jommi.rivi

import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Context

/** First-party Library destinations. Never part of AppRepository or the Your apps row. */
internal enum class OriginalApp(val label: String, val route: String) {
    BLIND_PICK("Blind Pick", "blind-pick"),
    RABBIT_HOLE("Rabbit Hole", "rabbit-hole");

    companion object {
        fun parse(route: String?) = entries.firstOrNull { it.route == route } ?: BLIND_PICK
    }
}

/** Small original vector marks, rendered once per Home instance; no downloaded assets. */
internal object OriginalAppArtwork {
    fun render(context: Context, app: OriginalApp): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        context.getDrawable(if (app == OriginalApp.BLIND_PICK) R.drawable.ic_blind_pick else R.drawable.ic_rabbit_hole)!!
            .apply { setBounds(0, 0, 96, 96); draw(canvas) }
        return bitmap
    }
}
