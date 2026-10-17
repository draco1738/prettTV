package fi.jommi.rivi

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout

/** Empty window for card lifecycle checks. This class is absent from release builds. */
class CardFocusTestActivity:Activity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
