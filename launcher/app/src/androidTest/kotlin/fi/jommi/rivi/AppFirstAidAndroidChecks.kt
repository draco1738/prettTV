package fi.jommi.rivi

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

internal object AppFirstAidAndroidChecks {
    fun run(i:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=i.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        val output=File(checkNotNull(context.getExternalFilesDir(null)),"visual-review").apply { mkdirs() }
        fun capture(view:View,name:String) {
            val b=Bitmap.createBitmap(1920,1080,Bitmap.Config.ARGB_8888)
            try {
                view.measure(View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY))
                view.layout(0,0,1920,1080);view.draw(Canvas(b))
                File(output,name).outputStream().use { check(b.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } finally { b.recycle() }
        }
        fun buttons(view:View):List<Button> = when(view) { is Button->listOf(view);is ViewGroup->(0 until view.childCount).flatMap { buttons(view.getChildAt(it)) };else->emptyList() }
        val activity=i.startActivitySync(Intent(context,CardFocusTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as Activity
        try {
            test("First aid is read-only on bind and restart is guarded until ready") {
                main(i) {
                    var restarts=0;var checks=0;var audio=0
                    val view=AppFirstAidView(activity,"HBO Max",{checks++},{restarts++},{audio++},{},{},{})
                    activity.setContentView(view)
                    val restart=view.findViewWithTag<Button>("first-aid-restart")
                    restart.performClick();check(restarts==0)
                    view.bindNetwork("Wi-Fi · Android reports internet access")
                    view.bindAudio(BluetoothControlSession.State(enabled=true,message="Bluetooth is on",audio=AudioOutputReport("TV speakers",false)))
                    check(restarts==0 && checks==0 && audio==0)
                    check(view.findViewWithTag<TextView>("first-aid-audio").text.toString()=="TV speakers · not muted")
                    view.allowRestart(true);restart.performClick();check(restarts==1)
                    view.allowRestart(false);restart.performClick();check(restarts==1)
                    buttons(view).single { it.text=="Check again" }.performClick();check(checks==1)
                    buttons(view).single { it.text=="Audio devices" }.performClick();check(audio==1)
                    view.allowRestart(true);capture(view,"first-aid-ready.png")
                }
            }
            test("First aid failure state stays readable at large fonts without inventing audio state") {
                main(i) {
                    val config=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=1.3f }
                    val large=android.view.ContextThemeWrapper(activity.createConfigurationContext(config),R.style.Theme_Rivi)
                    val view=AppFirstAidView(large,"An app with a long name for troubleshooting",{},{},{},{},{},{})
                    activity.setContentView(view)
                    view.bindNetwork(NetworkHealth.describe(true,false,true,"Wi-Fi"))
                    view.bindAudio(BluetoothControlSession.State(message="Local control is unavailable. Open Audio devices."))
                    check(view.findViewWithTag<TextView>("first-aid-audio").text.contains("Couldn’t verify"))
                    capture(view,"first-aid-large-error.png")
                    val action=buttons(view).single { it.text=="Back" };check(action.nextFocusDownId==action.id)
                }
            }
            test("Search keeps query and results after launch and explains empty matches inline") {
                main(i) {
                    var opens=0
                    val state=HomeSearchState("game")
                    val panel=HomeSearchPanel(activity,state) { query ->
                        if(query=="game")listOf(HomeSearchResult("got","Game of Thrones · HBO Max","Shows and movies") {opens++}) else emptyList()
                    }
                    try {
                        panel.show()
                        val decor=checkNotNull(panel.window).decorView
                        val result=decor.findViewWithTag<Button>("search-got")
                        check(result!=null);result.performClick();check(opens==1 && panel.isShowing && state.query=="game")
                        capture(decor,"search-results.png")
                        decor.findViewWithTag<EditText>("home-search-query").setText("no such title")
                        buttons(decor).single { it.text=="Search" }.performClick()
                        check(panel.isShowing && decor.findViewWithTag<TextView>("home-search-status").text.contains("No matches"))
                        buttons(decor).single { it.text=="Clear" }.performClick()
                        check(state.query.isEmpty() && decor.findViewWithTag<EditText>("home-search-query").text.isEmpty())
                    } finally { panel.dismiss() }
                }
            }
            test("Search pages long results and keeps access to editing and back") {
                main(i) {
                    val panel=HomeSearchPanel(activity,HomeSearchState("title")) { (1..70).map { HomeSearchResult("$it","Title $it","Shows and movies") {} } }
                    try {
                        panel.show();val decor=checkNotNull(panel.window).decorView
                        check(decor.findViewWithTag<Button>("search-61")==null)
                        buttons(decor).single { it.text=="More results" }.performClick()
                        check(decor.findViewWithTag<Button>("search-70")!=null)
                        val first=decor.findViewWithTag<Button>("search-1")
                        check(first.nextFocusUpId==decor.findViewWithTag<EditText>("home-search-query").id)
                        buttons(decor).single { it.text=="Back" }.performClick();check(!panel.isShowing)
                    } finally { panel.dismiss() }
                }
            }
        } finally { main(i) { activity.finish() };i.waitForIdleSync() }
        test("First aid and restart are private; restart intent requires confirmation and Cancel sends no operation") {
            check(!context.packageManager.getActivityInfo(ComponentName(context,AppFirstAidActivity::class.java),0).exported)
            check(!context.packageManager.getActivityInfo(ComponentName(context,AppForceStopActivity::class.java),0).exported)
            check(StockRecoveryStore(context).plan()==null)
            val before=AppForceStopSession.state
            val screen=i.startActivitySync(Intent(context,AppForceStopActivity::class.java).putExtra(AppForceStopActivity.EXTRA_PACKAGE,"fi.jommi.rivi.testapp")
                .putExtra(AppForceStopActivity.EXTRA_REOPEN,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppForceStopActivity
            try {
                i.waitForIdleSync()
                main(i) {
                    val panel=AppForceStopActivity::class.java.getDeclaredField("modal").apply { isAccessible=true }.get(screen) as AlertDialog
                    check(panel.getButton(AlertDialog.BUTTON_POSITIVE).text=="Restart")
                    check(panel.getButton(AlertDialog.BUTTON_NEGATIVE).hasFocus())
                    check(AppForceStopSession.state==before && !AppForceStopSession.busy)
                    panel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
                }
                i.waitForIdleSync()
                main(i) { check(AppForceStopSession.state==before && screen.isFinishing) }
            } finally { main(i) { screen.finish() };i.waitForIdleSync() }
        }
        test("Restart reopens only a matching verified success while foreground and only once") {
            val screen=i.startActivitySync(Intent(context,AppForceStopActivity::class.java).putExtra(AppForceStopActivity.EXTRA_PACKAGE,"fi.jommi.rivi.testapp")
                .putExtra(AppForceStopActivity.EXTRA_REOPEN,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppForceStopActivity
            val monitor=i.addMonitor(android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER) },null,true)
            val stateField=AppForceStopSession::class.java.getDeclaredField("state").apply { isAccessible=true }
            val before=AppForceStopSession.state
            fun field(name:String,value:Any)=AppForceStopActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(screen,value)
            fun attempt()=AppForceStopActivity::class.java.getDeclaredMethod("reopenIfReady").apply { isAccessible=true }.invoke(screen)
            try {
                i.waitForIdleSync()
                main(i) {
                    field("operationStarted",true)
                    stateField.set(AppForceStopSession,AppForceStopSession.State(AppForceStopSession.Phase.FAILED,"fi.jommi.rivi.testapp"))
                    attempt();check(monitor.hits==0)
                    stateField.set(AppForceStopSession,AppForceStopSession.State(AppForceStopSession.Phase.SUCCEEDED,"different.app"))
                    attempt();check(monitor.hits==0)
                    stateField.set(AppForceStopSession,AppForceStopSession.State(AppForceStopSession.Phase.SUCCEEDED,"fi.jommi.rivi.testapp"))
                    field("resumed",false);attempt();check(monitor.hits==0)
                    field("resumed",true);field("reopenHandled",true);attempt();check(monitor.hits==0)
                    field("reopenHandled",false);attempt();check(monitor.hits==1)
                    attempt();check(monitor.hits==1)
                }
            } finally {
                main(i) { stateField.set(AppForceStopSession,before);screen.finish() }
                i.removeMonitor(monitor);i.waitForIdleSync()
            }
        }
    }
    private fun main(i:Instrumentation,body:()->Unit) {
        val failure=AtomicReference<Throwable?>();i.runOnMainSync { try { body() } catch(t:Throwable) { failure.set(t) } };failure.get()?.let { throw it }
    }
}
