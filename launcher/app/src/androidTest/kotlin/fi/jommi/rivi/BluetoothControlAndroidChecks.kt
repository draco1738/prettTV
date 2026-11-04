package fi.jommi.rivi

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.util.concurrent.atomic.AtomicReference

/** Uses recording actions and a fake shell; no local connection or radio command is sent. */
internal object BluetoothControlAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        test("Home Bluetooth switch keeps focus, rejects repeated OK and exposes recovery without changing radio on bind") {
            val activity=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
            try {
                main(instrumentation) {
                    var toggles=0;var managed=0;var accepted=true
                    val button=TextView(activity).apply { isFocusable=true;isFocusableInTouchMode=true;isClickable=true }
                    activity.setContentView(button)
                    val quick=BluetoothQuickSwitch(button,{toggles++;accepted},{managed++})
                    quick.bind(BluetoothControlSession.State(enabled=false,message="Bluetooth off"))
                    check(toggles==0 && button.text.isEmpty() && button.contentDescription.contains("turn on"))
                    check(button.compoundDrawables[0] is BluetoothStatusDrawable)
                    check(button.requestFocus() && activity.currentFocus===button)
                    button.performClick();repeat(5) { button.performClick() }
                    check(toggles==1 && button.contentDescription.contains("Please wait"))
                    check(activity.currentFocus===button && button.isEnabled)
                    quick.bind(BluetoothControlSession.State(enabled=true,message="Bluetooth on"))
                    check(button.text.isEmpty() && button.contentDescription.contains("turn off"))
                    button.performClick();check(toggles==2)
                    quick.bind(BluetoothControlSession.State(message="Connection unavailable"))
                    check(button.contentDescription.contains("unavailable") && activity.currentFocus===button)
                    button.performLongClick();check(managed==1 && toggles==2)
                    accepted=false;button.performClick();check(toggles==3 && button.contentDescription.contains("unavailable"))
                    button.performClick();check(toggles==4) { "Rejected operation left the switch stuck busy" }
                    quick.bind(BluetoothControlSession.State(enabled=false))
                    check(button.contentDescription.contains("turn on") && activity.currentFocus===button)
                }
            } finally { main(instrumentation) { activity.finish() };instrumentation.waitForIdleSync() }
        }
        test("Bluetooth icon states have distinct shapes and readable focused variants without changing size") {
            main(instrumentation) {
                val icon=BluetoothStatusDrawable(72).apply { setBounds(0,0,72,72) }
                val states=linkedMapOf("on" to BluetoothControlSession.State(enabled=true),
                    "off" to BluetoothControlSession.State(enabled=false),
                    "pending" to BluetoothControlSession.State(enabled=true,working=true),
                    "unknown" to BluetoothControlSession.State())
                val shapes=HashSet<Int>();val renders=HashSet<Int>()
                val output=java.io.File(checkNotNull(context.getExternalFilesDir(null)),"visual-review").apply { mkdirs() }
                for(focused in listOf(false,true))for((name,value) in states) {
                    icon.state=if(focused)intArrayOf(android.R.attr.state_focused) else intArrayOf()
                    icon.bind(value)
                    val bitmap=Bitmap.createBitmap(72,72,Bitmap.Config.ARGB_8888)
                    try {
                        icon.draw(Canvas(bitmap))
                        val pixels=IntArray(72*72);bitmap.getPixels(pixels,0,72,0,0,72,72)
                        check(pixels.count { it ushr 24>0 }>100) { "Empty $name icon" }
                        check(renders.add(pixels.contentHashCode())) { "Indistinguishable $name focus=$focused" }
                        if(!focused)check(shapes.add(pixels.map { it ushr 24>0 }.hashCode())) { "State only differs by color: $name" }
                        check(icon.intrinsicWidth==72 && icon.bounds.width()==72)
                        java.io.File(output,"bluetooth-$name-focus-$focused.png").outputStream().use {
                            check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))
                        }
                    } finally { bitmap.recycle() }
                }
            }
        }
        test("Bluetooth panel is private and opens read-only with explicit On Off and Manage devices actions") {
            check(!context.packageManager.getActivityInfo(ComponentName(context,BluetoothControlActivity::class.java),0).exported)
            val failure=AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try {
                    val actions=ArrayList<Boolean?>();var managed=0;var closed=0
                    val panel=BluetoothControlView(context,{ actions.add(it) },{ managed++ },{ closed++ })
                    val toggle=panel.findViewWithTag<Button>("bluetooth-toggle")
                    val status=panel.findViewWithTag<TextView>("bluetooth-status")
                    check(actions.isEmpty()) { "Opening the panel changed Bluetooth" }
                    panel.bind(BluetoothControlSession.State(enabled=true,message="Bluetooth is on"))
                    check(toggle.text=="Turn Bluetooth off" && status.text=="Bluetooth is on")
                    check(toggle.performClick() && actions==listOf(false))
                    panel.bind(BluetoothControlSession.State(working=true,message="Turning Bluetooth off…"))
                    check(toggle.isEnabled);toggle.performClick();check(actions==listOf(false))
                    panel.bind(BluetoothControlSession.State(enabled=false,message="Bluetooth is off"))
                    check(toggle.text=="Turn Bluetooth on");toggle.performClick();check(actions==listOf(false,true))
                    panel.bind(BluetoothControlSession.State(message="Local control is unavailable. Use Manage devices."))
                    check(toggle.text=="Retry Bluetooth check");toggle.performClick();check(actions==listOf(false,true,null))
                    panel.findViewWithTag<View>("bluetooth-manage").performClick()
                    panel.findViewWithTag<View>("bluetooth-close").performClick()
                    check(managed==1 && closed==1)
                } catch(problem:Throwable) { failure.set(problem) }
            }
            failure.get()?.let { throw it }
        }
        test("Bluetooth pending and verified states preserve remote focus without stealing deliberate navigation") {
            val activity=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
            try {
                val actions=ArrayList<Boolean?>()
                val panel=main(instrumentation) {
                    BluetoothControlView(activity,{ actions.add(it) },{},{}).also { activity.setContentView(it) }
                }
                val deadline=SystemClock.uptimeMillis()+10_000
                while(!main(instrumentation) { activity.hasWindowFocus() && panel.isLaidOut && panel.isShown }) {
                    check(SystemClock.uptimeMillis()<deadline) { "Bluetooth focus fixture did not become ready" }
                    SystemClock.sleep(40)
                }
                main(instrumentation) {
                    val toggle=panel.findViewWithTag<Button>("bluetooth-toggle")
                    val manage=panel.findViewWithTag<Button>("bluetooth-manage")
                    panel.bind(BluetoothControlSession.State(enabled=true,message="Bluetooth is on"))
                    check(toggle.requestFocus() && activity.currentFocus===toggle) { "Toggle did not receive initial focus" }
                    toggle.performClick();check(actions==listOf(false))
                    panel.bind(BluetoothControlSession.State(working=true,message="Turning Bluetooth off…"))
                    check(activity.currentFocus===toggle && toggle.isEnabled) { "Pending change moved focus away from the toggle" }
                    repeat(3) { toggle.performClick() }
                    check(actions==listOf(false)) { "Pending repeated OK presses queued extra changes" }
                    panel.bind(BluetoothControlSession.State(enabled=false,message="Bluetooth is off"))
                    check(activity.currentFocus===toggle) { "Verified result moved focus away from the toggle" }
                    toggle.performClick();check(actions==listOf(false,true)) { "Next OK did not turn Bluetooth back on" }
                    panel.bind(BluetoothControlSession.State(working=true,message="Turning Bluetooth on…"))
                    check(manage.requestFocus() && activity.currentFocus===manage) { "Manage devices was not reachable while pending" }
                    panel.bind(BluetoothControlSession.State(enabled=true,message="Bluetooth is on"))
                    check(activity.currentFocus===manage) { "Completion stole focus after deliberate navigation" }
                    panel.bind(BluetoothControlSession.State(message="Local control is unavailable. Use Manage devices."))
                    check(activity.currentFocus===manage) { "Failure stole focus from Manage devices" }
                }
                instrumentation.waitForIdleSync()
                main(instrumentation) {
                    check(activity.currentFocus?.tag=="bluetooth-manage") { "A deferred layout stole the selected button" }
                }
            } finally {
                main(instrumentation) { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
        test("Shortcut messages stay optional and preserve focus across denied granted and legacy states") {
            val activity=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
            try {
                var requests=0
                val actions=ArrayList<Boolean?>()
                val panel=main(instrumentation) {
                    BluetoothControlView(activity,{ actions.add(it) },{},{},{ requests++ })
                        .also { activity.setContentView(it) }
                }
                val deadline=SystemClock.uptimeMillis()+10_000
                while(!main(instrumentation) { activity.hasWindowFocus() && panel.isLaidOut && panel.isShown }) {
                    check(SystemClock.uptimeMillis()<deadline) { "Bluetooth permission fixture did not become ready" }
                    SystemClock.sleep(40)
                }
                main(instrumentation) {
                    val toggle=panel.findViewWithTag<Button>("bluetooth-toggle")
                    val messages=panel.findViewWithTag<Button>("bluetooth-messages")
                    val notice=panel.findViewWithTag<TextView>("bluetooth-messages-notice")
                    val manage=panel.findViewWithTag<Button>("bluetooth-manage")
                    val close=panel.findViewWithTag<Button>("bluetooth-close")
                    fun checkChain(vararg buttons:Button) {
                        buttons.forEachIndexed { index,button ->
                            check(button.isFocusableInTouchMode) { "${button.tag} cannot receive focus after touch input" }
                            check(button.nextFocusLeftId==button.id && button.nextFocusRightId==button.id)
                            check(button.nextFocusUpId==buttons[(index-1).coerceAtLeast(0)].id)
                            check(button.nextFocusDownId==buttons[(index+1).coerceAtMost(buttons.lastIndex)].id)
                        }
                    }
                    panel.bind(BluetoothControlSession.State(enabled=true,message="Bluetooth is on"))
                    check(toggle.requestFocus())
                    panel.bindShortcutMessages(33,false)
                    check(messages.visibility==View.VISIBLE && notice.visibility==View.VISIBLE)
                    check(messages.text=="Enable shortcut messages" && requests==0 && actions.isEmpty())
                    check(activity.currentFocus===toggle) { "Permission offer stole Bluetooth toggle focus" }
                    checkChain(toggle,messages,manage,close)
                    check(messages.requestFocus() && messages.performClick())
                    check(requests==1 && actions.isEmpty()) { "Permission action changed Bluetooth or did not request permission" }
                    panel.bindShortcutMessages(33,false,true)
                    repeat(3) { messages.performClick() }
                    check(requests==1) { "Pending permission action requested more dialogs" }
                    panel.bindShortcutMessages(33,false,denied=true)
                    check(messages.visibility==View.VISIBLE && activity.currentFocus===messages && requests==1)
                    check(notice.text=="Messages remain off. You can enable prettTV notifications in Android app settings.")
                    checkChain(toggle,messages,manage,close)
                    panel.bindShortcutMessages(33,true)
                    check(messages.visibility==View.GONE && notice.visibility==View.GONE)
                    check(activity.currentFocus===toggle && toggle.nextFocusDownId==manage.id)
                    checkChain(toggle,manage,close)
                    panel.bindShortcutMessages(34,false)
                    check(activity.currentFocus===toggle && messages.visibility==View.VISIBLE)
                    checkChain(toggle,messages,manage,close)
                    check(manage.requestFocus())
                    panel.bindShortcutMessages(34,true)
                    check(activity.currentFocus===manage) { "Grant refresh stole a deliberate selection" }
                    panel.bindShortcutMessages(32,false)
                    check(messages.visibility==View.GONE && notice.visibility==View.GONE)
                    check(toggle.nextFocusDownId==manage.id && activity.currentFocus===manage)
                    checkChain(toggle,manage,close)
                    check(close.requestFocus() && activity.currentFocus===close)
                    panel.bindShortcutMessages(34,false)
                    check(activity.currentFocus===close) { "Permission offer stole Back button focus" }
                    checkChain(toggle,messages,manage,close)
                    panel.bindShortcutMessages(34,true)
                    check(activity.currentFocus===close) { "Grant refresh stole Back button focus" }
                    checkChain(toggle,manage,close)
                    check(requests==1 && actions.isEmpty()) { "Permission state refresh dispatched an action" }
                }
            } finally {
                main(instrumentation) { activity.finish() }
                instrumentation.waitForIdleSync()
            }
        }
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<T>();val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch(problem:Throwable) { failure.set(problem) } }
        failure.get()?.let { throw it };return result.get()
    }
}
