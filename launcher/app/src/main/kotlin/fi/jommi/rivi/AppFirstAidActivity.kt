package fi.jommi.rivi

import android.app.Activity
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Read-only checks on entry. Repair requires a separate, explicit confirmation. */
class AppFirstAidActivity:Activity() {
    private var target=""
    private lateinit var checks:AppFirstAidView
    private var pendingAudio=false
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        target=intent.getStringExtra(AppForceStopActivity.EXTRA_PACKAGE).orEmpty()
        val label=runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(target,0)).toString().filterNot(Char::isISOControl).take(100) }.getOrDefault("App")
        checks=AppFirstAidView(this,label,{ refresh() },{
            if(!BluetoothControlSession.busy && AppForceStopSession.canOffer(this,target))
                startActivity(Intent(this,AppForceStopActivity::class.java).putExtra(AppForceStopActivity.EXTRA_PACKAGE,target).putExtra(AppForceStopActivity.EXTRA_REOPEN,true))
        },{ startActivity(Intent(this,BluetoothControlActivity::class.java)) },{
            openSettings(Intent(Settings.ACTION_WIFI_SETTINGS))
        },{ openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:$target"))) },{ finish() })
        setContentView(checks)
    }
    override fun onResume() {
        super.onResume()
        BluetoothControlSession.listen(this) { state ->
            if(!state.working && pendingAudio) { pendingAudio=false;refresh();return@listen }
            checks.bindAudio(state)
            checks.allowRestart(!state.working && !BluetoothControlSession.busy && AppForceStopSession.canOffer(this,target))
        }
        refresh()
    }
    override fun onPause() {
        pendingAudio=false
        BluetoothControlSession.cancel(this);BluetoothControlSession.unlisten(this)
        super.onPause()
    }
    private fun refresh() {
        checks.bindNetwork(runCatching {
            val manager=getSystemService(ConnectivityManager::class.java)
            val caps=manager?.getNetworkCapabilities(manager.activeNetwork)
            NetworkHealth.describe(caps!=null,caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)==true,
                when { caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true->"VPN";caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true->"Ethernet";caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true->"Wi-Fi";else->"Network" })
        }.getOrDefault("Connection check unavailable. Open network settings to check."))
        checks.allowRestart(false)
        if(!BluetoothControlSession.start(this,includeAudio=true)) {
            pendingAudio=BluetoothControlSession.busy
            checks.bindAudio(BluetoothControlSession.State(message="Another TV action is running. Wait, then check again."))
        }
    }
    private fun openSettings(intent:Intent) {
        try { startActivity(intent) } catch(_:Exception) {
            android.widget.Toast.makeText(this,"This settings page is unavailable. Open TV settings.",android.widget.Toast.LENGTH_LONG).show()
        }
    }
}

internal object NetworkHealth {
    fun describe(connected:Boolean,validated:Boolean,captive:Boolean,transport:String):String=when {
        !connected->"No network connection. Open network settings to reconnect."
        captive->"$transport needs sign-in. Open network settings."
        validated->"$transport · Android reports internet access"
        else->"$transport connected · internet access not confirmed"
    }
}

@android.annotation.SuppressLint("ViewConstructor")
internal class AppFirstAidView(context:android.content.Context,label:String,refresh:()->Unit,restart:()->Unit,
    audio:()->Unit,network:()->Unit,appInfo:()->Unit,close:()->Unit):LinearLayout(context) {
    private val scale=resources.displayMetrics.widthPixels/1920f
    private fun px(n:Int)=(n*scale+.5f).toInt().coerceAtLeast(1)
    private fun text(value:String,size:Int,color:Int=AppActionStyle.IVORY)=TextView(context).apply {
        text=value;setTextColor(color);AppActionStyle.textSize(this,size,scale);setLineSpacing(px(5).toFloat(),1f)
    }
    private val networkStatus=text("Checking connection…",28).apply { tag="first-aid-network" }
    private val audioStatus=text("Checking media output…",28).apply { tag="first-aid-audio" }
    private val radioStatus=text("Checking Bluetooth…",23,AppActionStyle.MUTED)
    private var canRestart=false
    private val restartButton=Button(context).apply {
        tag="first-aid-restart";id=View.generateViewId();setText(R.string.first_aid_restart);AppActionStyle.button(this,scale)
        setOnClickListener { if(canRestart)restart() }
    }
    init {
        orientation=VERTICAL;setBackgroundColor(0xFF0F1510.toInt());setPadding(px(80),px(56),px(80),px(56))
        addView(text("App first aid",42))
        addView(text(label,28,AppActionStyle.MUTED),LayoutParams(-1,-2).apply { topMargin=px(8);bottomMargin=px(36) })
        val body=LinearLayout(context).apply { orientation=HORIZONTAL }
        val results=LinearLayout(context).apply {
            orientation=VERTICAL
            fun section(title:String,value:View) {
                addView(text(title,23,AppActionStyle.MUTED),LayoutParams(-1,-2).apply { topMargin=px(24);bottomMargin=px(10) })
                addView(value,LayoutParams(-1,-2))
            }
            section("Connection",networkStatus);section("Media output",audioStatus)
            addView(radioStatus,LayoutParams(-1,-2).apply { topMargin=px(12) })
            addView(text("These checks describe this TV. A service outage, account problem or playback inside the app cannot be diagnosed here.",23,AppActionStyle.MUTED),LayoutParams(-1,-2).apply { topMargin=px(38) })
        }
        body.addView(ScrollView(context).apply { addView(results) },LayoutParams(0,-1,1f).apply { rightMargin=px(64) })
        val actions=LinearLayout(context).apply { orientation=VERTICAL }
        val buttons=mutableListOf<Button>()
        fun action(title:String,fn:()->Unit) {
            val b=Button(context).apply { id=View.generateViewId();text=title;AppActionStyle.button(this,scale);setOnClickListener { fn() } }
            buttons+=b;actions.addView(b,LayoutParams(-1,-2).apply { bottomMargin=px(8) })
        }
        action("Check again",refresh)
        buttons+=restartButton;actions.addView(restartButton,LayoutParams(-1,-2).apply { bottomMargin=px(8) })
        actions.addView(text("Restart closes and reopens this app. Login and saved data stay intact.",21,AppActionStyle.MUTED),LayoutParams(-1,-2).apply { bottomMargin=px(22) })
        action("Audio devices",audio);action("Network settings",network);action("App info",appInfo);action("Back",close)
        body.addView(ScrollView(context).apply { addView(actions) },LayoutParams(px(580),-1))
        addView(body,LayoutParams(-1,0,1f))
        buttons.forEachIndexed { i,b ->
            b.nextFocusUpId=buttons[(i-1).coerceAtLeast(0)].id;b.nextFocusDownId=buttons[(i+1).coerceAtMost(buttons.lastIndex)].id
            b.nextFocusRightId=b.id;b.nextFocusLeftId=b.id
        }
        buttons.first().isFocusableInTouchMode=true;buttons.first().requestFocus()
        allowRestart(false)
    }
    fun bindNetwork(value:String) { networkStatus.text=value }
    fun bindAudio(value:BluetoothControlSession.State) {
        audioStatus.text=if(value.working)"Checking media output…" else value.audio?.description ?: "Couldn’t verify output. Open Audio devices to check."
        radioStatus.text=value.message
    }
    fun allowRestart(allowed:Boolean) {
        canRestart=allowed
        restartButton.alpha=if(allowed)1f else .55f
        restartButton.contentDescription=if(allowed)"Restart app. Confirmation required." else "Restart unavailable while checking, or for protected TV apps."
    }
}
