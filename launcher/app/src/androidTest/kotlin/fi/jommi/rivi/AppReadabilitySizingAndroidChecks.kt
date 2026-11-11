package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Isolated, read-only UI fixtures. Parent registers run() with the stability runner. */
internal object AppReadabilitySizingAndroidChecks {
    fun run(i: Instrumentation, test: (String, () -> Unit) -> Unit) {
        val context = i.targetContext
        check(context.packageName == "dev.zane.pretttv.stability")
        val prefs = context.getSharedPreferences("pretttv", Context.MODE_PRIVATE)
        val original = prefs.all["interfaceSize"]
        check(original == null || original is String)
        fun size(value: HomeInterfaceSize) { check(prefs.edit().putString("interfaceSize", value.name).commit()) }
        val activity = i.startActivitySync(Intent(context, CardFocusTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        try {
            test("Readability default and invalid preference leave native dimensions and Resources unchanged") {
                main(i) {
                    check(prefs.edit().remove("interfaceSize").commit())
                    val density = activity.resources.displayMetrics.density
                    val scaledDensity = activity.resources.displayMetrics.scaledDensity
                    val fontScale = activity.resources.configuration.fontScale
                    val native = TextView(activity).apply { textSize = 25f; setPadding(20, 12, 20, 12); minimumHeight = 60 }
                    val base = native.textSize
                    AppReadability.nativeContent(activity, native)
                    near(base, native.textSize); check(native.paddingTop == 12 && native.minimumHeight == 60)
                    check(prefs.edit().putString("interfaceSize", "invalid").commit())
                    check(AppReadability.dimensions(activity).size == HomeInterfaceSize.STANDARD)
                    check(prefs.edit().putInt("interfaceSize", 104).commit())
                    check(AppReadability.dimensions(activity).size == HomeInterfaceSize.STANDARD)
                    size(HomeInterfaceSize.LARGER)
                    AppReadability.nativeContent(activity, native)
                    near(base * 1.04f, native.textSize)
                    check(activity.resources.displayMetrics.density == density)
                    check(activity.resources.displayMetrics.scaledDensity == scaledDensity)
                    check(activity.resources.configuration.fontScale == fontScale)
                }
            }
            test("Readability keeps local system font scaling independent and native sizing idempotent") {
                main(i) {
                    for (font in listOf(1f, 1.3f, 1.5f)) {
                        val config = Configuration(activity.resources.configuration).apply { fontScale = font }
                        val themed = ContextThemeWrapper(activity.createConfigurationContext(config), R.style.Theme_Rivi)
                        val native = TextView(themed).apply {
                            textSize = 25f; setPadding(20, 25, 20, 25); minimumHeight = 100
                            layoutParams = LinearLayout.LayoutParams(-1, -2)
                        }
                        val base = native.textSize
                        size(HomeInterfaceSize.LARGER)
                        repeat(3) { AppReadability.nativeContent(themed, native) }
                        near(base * 1.04f, native.textSize)
                        check(native.paddingTop == 26 && native.minimumHeight == 104)
                        check(native.layoutParams.width == -1 && native.layoutParams.height == -2)
                        check(themed.resources.configuration.fontScale == font)
                        size(HomeInterfaceSize.STANDARD)
                        AppReadability.nativeContent(themed, native)
                        near(base, native.textSize); check(native.paddingTop == 25 && native.minimumHeight == 100)
                    }
                }
            }
            test("Readability dialog preserves custom content, Cancel focus and native controls") {
                main(i) {
                    size(HomeInterfaceSize.LARGER)
                    val custom = TextView(activity).apply { AppActionStyle.textSize(this, 24, AppReadability.referenceScale(activity)); text = "Already sized body" }
                    val title = TextView(activity).apply { AppActionStyle.textSize(this, 30, AppReadability.referenceScale(activity)); text = "Already sized title" }
                    val bodySize = custom.textSize; val titleSize = title.textSize
                    var accepts = 0
                    val dialog = AlertDialog.Builder(activity).setCustomTitle(title).setView(custom)
                        .setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> accepts++ }.create()
                    try {
                        dialog.show()
                        val cancel = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                        cancel.isFocusableInTouchMode = true; cancel.requestFocus()
                        val nativeSize = cancel.textSize
                        repeat(3) { AppReadability.styleDialog(dialog, title) }
                        near(nativeSize * 1.04f, cancel.textSize)
                        near(bodySize, custom.textSize); near(titleSize, title.textSize)
                        check(cancel.hasFocus() && accepts == 0)
                        check(dialog.getButton(AlertDialog.BUTTON_POSITIVE).hasOnClickListeners())
                    } finally { dialog.dismiss() }
                }
            }
            test("Readability scales offscreen and recycled native checkbox rows without losing choices") {
                main(i) {
                    size(HomeInterfaceSize.LARGER)
                    val labels = Array(80) { "Service $it" }
                    val selected = BooleanArray(80).apply { this[70] = true }
                    var clicked = -1
                    val dialog = AlertDialog.Builder(activity).setTitle("Services")
                        .setMultiChoiceItems(labels, selected) { _, which, _ -> clicked = which }.setNegativeButton("Close", null).create()
                    try {
                        dialog.show()
                        val list = dialog.listView
                        val originalAdapter = list.adapter
                        val baseline = originalAdapter.getView(70, null, list) as TextView
                        val baseSize = baseline.textSize; val baseHeight = baseline.minHeight
                        AppReadability.styleDialog(dialog)
                        check(list.isItemChecked(70))
                        val row = list.adapter.getView(70, null, list) as TextView
                        near(baseSize * 1.04f, row.textSize)
                        if (baseHeight > 0) check(row.minHeight == ReadabilityDimensions(HomeInterfaceSize.LARGER).pixels(baseHeight))
                        repeat(4) {
                            val recycled = list.adapter.getView(1, row, list) as TextView
                            near(baseSize * 1.04f, recycled.textSize)
                        }
                        AppReadability.styleDialog(dialog)
                        check(list.isItemChecked(70))
                        list.performItemClick(row, 1, list.adapter.getItemId(1))
                        check(clicked == 1 && selected[1])
                    } finally { dialog.dismiss() }
                }
            }
            test("Readability native single-choice selection survives renderer wrapping") {
                main(i) {
                    size(HomeInterfaceSize.LARGER)
                    val dialog = AlertDialog.Builder(activity).setTitle("Size")
                        .setSingleChoiceItems(arrayOf("Standard", "Larger"), 1, null).setNegativeButton("Close", null).create()
                    try {
                        dialog.show(); AppReadability.styleDialog(dialog)
                        check(dialog.listView.checkedItemPosition == 1)
                        check(dialog.listView.adapter.count == 2)
                    } finally { dialog.dismiss() }
                }
            }
            test("Readability audio and first-aid Views consume the saved size once") {
                main(i) {
                    size(HomeInterfaceSize.STANDARD)
                    val aid = AppFirstAidView(activity, "App", {}, {}, {}, {}, {}, {})
                    val audio = BluetoothControlView(activity, {}, {}, {})
                    val aidSize = aid.findViewWithTag<TextView>("first-aid-network").textSize
                    val audioSize = audio.findViewWithTag<TextView>("bluetooth-status").textSize
                    size(HomeInterfaceSize.LARGER)
                    val largeAid = AppFirstAidView(activity, "App", {}, {}, {}, {}, {}, {})
                    val largeAudio = BluetoothControlView(activity, {}, {}, {})
                    near(aidSize * 1.04f, largeAid.findViewWithTag<TextView>("first-aid-network").textSize)
                    near(audioSize * 1.04f, largeAudio.findViewWithTag<TextView>("bluetooth-status").textSize)
                }
            }
            test("Readability Inputs and app actions resize once and retain finite focus") {
                main(i) {
                    val anchor = TextView(activity).apply { text = "App"; isFocusable = true }
                    activity.setContentView(anchor)
                    var inputTitleSize = 0f; var actionSize = 0f
                    for (choice in HomeInterfaceSize.entries) {
                        size(choice)
                        val inputs = InputsPanel.show(activity, {}, {}, {})
                        try {
                            val root = checkNotNull(inputs.window).decorView
                            val title = texts(root).single { it.text == "Inputs" }
                            if (choice == HomeInterfaceSize.STANDARD) inputTitleSize = title.textSize
                            near(inputTitleSize * choice.factor, title.textSize)
                            val cards = descendants(root).filterIsInstance<PrettTvCard>()
                            check(cards.size == 2)
                            check(cards.first().nextFocusLeftId == cards.first().id)
                            check(cards.last().nextFocusRightId == cards.last().id)
                            check(checkNotNull(inputs.window).attributes.width < activity.resources.displayMetrics.widthPixels)
                        } finally { inputs.dismiss() }
                        var opened = 0
                        val actions = AppActionPanel.show(activity, anchor, "App", listOf("Open" to { opened++ }, "App info" to {}), {})
                        try {
                            val open = buttons(checkNotNull(actions.window).decorView).single { it.text == "Open" }
                            if (choice == HomeInterfaceSize.STANDARD) actionSize = open.textSize
                            near(actionSize * choice.factor, open.textSize)
                            check(open.nextFocusUpId == open.id && opened == 0)
                            check(open.hasOnClickListeners())
                        } finally { actions.dismiss() }
                    }
                }
            }
            test("Discovery session Undo appears without stealing focus and exhausted Undo returns to Back") {
                main(i) {
                    val store = PersonalizationStore(activity)
                    val separateStore = PersonalizationStore(activity)
                    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    fun control(label: String) = Button(activity).apply {
                        id = View.generateViewId(); text = label; isFocusableInTouchMode = true
                        body.addView(this, LinearLayout.LayoutParams(-1, -2))
                    }
                    val other = control("Title")
                    val action = control("Undo")
                    val back = control("Back")
                    activity.setContentView(body)
                    store.onUndoChanged = { updateDiscoveryUndoAction(action, back, store.undoLabel()) }
                    try {
                        updateDiscoveryUndoAction(action, back, store.undoLabel())
                        other.requestFocus(); check(other.hasFocus() && action.visibility == View.GONE)
                        var value = 1
                        store.recordUndo("Save title") { value = 0 }
                        check(action.visibility == View.VISIBLE && other.hasFocus())
                        check(action.contentDescription == "Undo: Save title" && !separateStore.canUndo())
                        action.requestFocus(); check(action.hasFocus())
                        check(store.undo() == "Save title" && value == 0)
                        check(action.visibility == View.GONE && back.hasFocus())
                        check(back.nextFocusLeftId == back.id)
                        other.requestFocus()
                        store.recordUndo("Another save") {}
                        store.clearUndoHistory()
                        check(other.hasFocus() && action.visibility == View.GONE)
                    } finally { store.onUndoChanged = null }
                }
            }
            test("Readability Search preserves results, query and finite Back action at both sizes") {
                main(i) {
                    var baseSize = 0f
                    for (choice in HomeInterfaceSize.entries) {
                        size(choice)
                        val panel = HomeSearchPanel(activity, HomeSearchState("title")) {
                            listOf(HomeSearchResult("one", "One result", "Shows") {})
                        }
                        try {
                            panel.show()
                            val root = checkNotNull(panel.window).decorView
                            val result = root.findViewWithTag<Button>("search-one")
                            if (choice == HomeInterfaceSize.STANDARD) baseSize = result.textSize
                            near(baseSize * choice.factor, result.textSize)
                            check(result.nextFocusDownId == result.id)
                            buttons(root).single { it.text == "Back" }.performClick()
                            check(!panel.isShowing)
                        } finally { panel.dismiss() }
                    }
                }
            }
        } finally {
            main(i) {
                val editor = prefs.edit()
                if (original == null) editor.remove("interfaceSize") else editor.putString("interfaceSize", original as String)
                check(editor.commit())
                activity.finish()
            }
            i.waitForIdleSync()
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()
    private fun near(expected: Float, actual: Float) { check(abs(expected - actual) < .02f) { "$expected != $actual" } }
    private fun buttons(view: View): List<Button> = when (view) {
        is Button -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { buttons(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun main(i: Instrumentation, body: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        i.runOnMainSync { try { body() } catch (t: Throwable) { failure.set(t) } }
        failure.get()?.let { throw it }
    }
}
