package fi.jommi.rivi

import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
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
                    manage.isFocusableInTouchMode=true
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
    }
    private fun <T> main(instrumentation:Instrumentation,block:()->T):T {
        val result=AtomicReference<T>();val failure=AtomicReference<Throwable?>()
        instrumentation.runOnMainSync { try { result.set(block()) } catch(problem:Throwable) { failure.set(problem) } }
        failure.get()?.let { throw it };return result.get()
    }
}
