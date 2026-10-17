package fi.jommi.rivi

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** No pending request = no tree access. An explicit game click is the only activation path. */
class RbtvHandoffService:AccessibilityService() {
    companion object {
        private var request:SportsEvent?=null
        private var expires=0L
        private var instance:RbtvHandoffService?=null
        fun enabled(c:Context)=c.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(-1).any { ComponentName.unflattenFromString(it.id)==ComponentName(c,RbtvHandoffService::class.java) }
        fun supported(c:Context,event:SportsEvent):Boolean = try {
            c.packageManager.getPackageInfo("com.rblive.app",0).versionName=="3.0.320" && RbtvMatchPolicy.tab(event.league)!=null && event.teams.size==2
        } catch(_:Exception) { false }
        fun begin(event:SportsEvent) { cancel(); request=event; expires=SystemClock.elapsedRealtime()+30_000; instance?.start() }
        fun cancel() { request=null; instance?.reset() }
    }
    private val ui=Handler(Looper.getMainLooper())
    private var selected=false
    private var resets=0
    private var scrolls=0
    private var lastStep=0L
    private val step=Runnable { advance() }
    private val deadline=Runnable { if(request!=null)finish("Game not found automatically. Choose it in RBTV.") }
    override fun onServiceConnected() { instance=this; if(request!=null)start() }
    override fun onAccessibilityEvent(event:AccessibilityEvent?) { if(request!=null)schedule() }
    override fun onInterrupt() { cancel() }
    override fun onDestroy() { cancel(); if(instance===this)instance=null; super.onDestroy() }
    private fun reset() { ui.removeCallbacksAndMessages(null); selected=false; resets=0; scrolls=0; lastStep=0 }
    private fun start() { ui.postDelayed(deadline,(expires-SystemClock.elapsedRealtime()).coerceAtLeast(0)); schedule() }
    private fun schedule() { ui.removeCallbacks(step); ui.postDelayed(step,600) }
    private fun finish(message:String) { cancel(); Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun nodes(root:AccessibilityNodeInfo,id:String)=root.findAccessibilityNodeInfosByViewId("com.rblive.app:id/$id").orEmpty()
    private fun label(root:AccessibilityNodeInfo,id:String)=nodes(root,id).firstOrNull()?.text?.toString().orEmpty()
    private fun advance() {
        val event=request ?: return
        if(SystemClock.elapsedRealtime()>expires) { finish("Game not found automatically. Choose it in RBTV."); return }
        // Throttle repeated content events and never leave the allowed package.
        if(SystemClock.elapsedRealtime()-lastStep<550) { schedule(); return }
        lastStep=SystemClock.elapsedRealtime()
        val tree=rootInActiveWindow
        if(tree==null || tree.packageName?.toString()!="com.rblive.app") { schedule(); return }
        // Never interact with a player, server picker, update dialog or any unrecognized layout.
        val tabs=nodes(tree,"gv_tab").firstOrNull()
        val list=nodes(tree,"rv_match").firstOrNull()
        if(tabs==null || list==null) { schedule(); return }
        if(!selected) {
            // Return the tab list to its known beginning before using the version-pinned mapping.
            if(resets<8 && tabs.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) { resets++; schedule(); return }
            if(resets>=8) { finish("Choose the sport in RBTV to continue."); return }
            val choices=nodes(tabs,"cly_tab")
            val tab=choices.getOrNull(RbtvMatchPolicy.tab(event.league) ?: return)
            if(tab==null || !tab.isVisibleToUser) { finish("Choose the sport in RBTV to continue."); return }
            if(!tab.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) { finish("Choose the sport in RBTV to continue."); return }
            selected=true; schedule(); return
        }
        val matches=nodes(list,"cly_match").filter { node ->
            node.isVisibleToUser && RbtvMatchPolicy.matches(event,listOf(label(node,"tv_home_name"),label(node,"tv_away_name")),
                label(node,"tv_day"),label(node,"tv_time"),System.currentTimeMillis())
        }
        if(matches.size>1) { finish("More than one matching game. Choose it in RBTV."); return }
        if(matches.size==1) {
            val match=matches.single(); request=null; reset()
            if(!match.performAction(AccessibilityNodeInfo.ACTION_CLICK))Toast.makeText(this,"Select the matching game in RBTV.",Toast.LENGTH_LONG).show()
            return
        }
        if(scrolls++<16 && list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))schedule()
        else finish("Game not listed with matching teams and time. Choose it in RBTV.")
    }
}
