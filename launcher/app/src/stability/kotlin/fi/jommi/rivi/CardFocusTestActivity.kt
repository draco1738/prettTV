package fi.jommi.rivi

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import android.widget.FrameLayout

/** Empty window for card lifecycle checks. This class is absent from release builds. */
class CardFocusTestActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        // Only the visible isolated test window keeps the display awake. This neither
        // wakes the TV nor dismisses a dream, lock screen, overlay or another activity.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(FrameLayout(this))
    }
}
