package fi.jommi.rivi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One bounded connection, shared with the existing force-stop and recovery operation gate. */
internal object BluetoothControlSession {
    data class State(val enabled:Boolean?=null,val working:Boolean=false,val message:String="Checking Bluetooth…",val changed:Boolean=false)
    private val ui=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor { Thread(it,"prettTV-bluetooth") }
    private val timer=Executors.newSingleThreadScheduledExecutor { Thread(it,"prettTV-bluetooth-timeout").apply { isDaemon=true } }
    @Volatile var state=State(); private set
    @Volatile var busy=false; private set
    @Volatile private var cancelled=false
    @Volatile private var timedOut=false
    @Volatile private var client:LocalAdbClient?=null
    private var generation=0L
    private var owner:Any?=null
    private var operationOwner:Any?=null
    private var listener:((State)->Unit)?=null
    fun listen(owner:Any,callback:(State)->Unit) { this.owner=owner; listener=callback; callback(state) }
    fun unlisten(owner:Any) { if(this.owner===owner) { this.owner=null; listener=null } }
    private fun publish(value:State) { state=value; ui.post { if(state===value)listener?.invoke(value) } }

    fun start(context:Context,desired:Boolean?=null):Boolean=begin(context,desired,false)
    /** A completed remote gesture only; keeps playback in place and reports verified completion. */
    fun toggle(context:Context):Boolean {
        val accepted=begin(context,null,true)
        if(!accepted) {
            val app=context.applicationContext
            ui.post { android.widget.Toast.makeText(app,R.string.bluetooth_busy,android.widget.Toast.LENGTH_SHORT).show() }
        }
        return accepted
    }
    private fun begin(context:Context,desired:Boolean?,toggle:Boolean):Boolean=synchronized(StockRecoverySession) { synchronized(this) {
        if(busy || StockRecoverySession.busy || AppForceStopSession.busy)return false
        val app=context.applicationContext
        if(StockRecoveryStore.paused(app)) {
            val paused=State(message="prettTV helpers are paused. Use Manage devices.")
            publish(paused);if(toggle)notifyResult(app,paused)
            return true
        }
        busy=true; cancelled=false; timedOut=false
        operationOwner=if(toggle)null else owner
        val token=++generation
        publish(State(working=true,message=if(desired==null)"Checking Bluetooth…" else if(desired)"Turning Bluetooth on…" else "Turning Bluetooth off…"))
        worker.execute { perform(app,desired,toggle,token) }; true
    } }
    private fun perform(app:Context,desired:Boolean?,toggle:Boolean,token:Long) {
        var connection:LocalAdbClient?=null
        var result=State(message="Bluetooth state could not be verified. Use Manage devices.")
        val timeout=timer.schedule({ synchronized(this) { if(generation==token && busy) { timedOut=true;cancelled=true;client?.close() } } },25,TimeUnit.SECONDS)
        try {
            val plan=StockRecoveryStore(app).plan() ?: error("Local control is not configured. Use Manage devices.")
            val key=runCatching { AppForceStopSession.existingKey(app) }.getOrElse { error("The existing local control key is unavailable. Use Manage devices.") }
            val connected=LocalAdbClient(key,limits=LocalAdbClient.Limits(
                connectMs=2000,authorizationMs=2000,commandMs=5000,outputBytes=524_288))
            connection=connected
            synchronized(this) { check(!cancelled) { "Check cancelled." };client=connection }
            connected.connect { error("The existing local key is not authorized. Use Manage devices.") }
            val engine=BluetoothControlEngine(AppForceStopIdentity(plan.deviceSerial,plan.fingerprint),Process.myUid()/100_000,
                BluetoothControlShell { connected.shell(it) },{ cancelled },
                { !StockRecoverySession.busy && !AppForceStopSession.busy && !StockRecoveryStore.paused(app) },
                { synchronized(this) { check(!cancelled) { "Check cancelled." }; publish(state.copy(changed=true)) } })
            val checked=if(toggle)engine.toggle() else engine.execute(desired)
            result=State(checked.enabled,message=checked.message,changed=checked.changed)
        } catch(failure:Exception) {
            result=State(message=(if(state.changed)"Bluetooth may have changed. " else "")+
                (failure.message?.take(180) ?: "Local control is unavailable. Use Manage devices."),changed=state.changed)
        } finally {
            timeout.cancel(false);connection?.close()
            synchronized(this) {
                client=null;busy=false;operationOwner=null
                if(timedOut)result=result.copy(enabled=null,message=if(result.changed)"Bluetooth may have changed. The connection timed out before verification." else "Bluetooth check timed out. Use Manage devices.")
                publish(result)
                if(toggle)notifyResult(app,result)
            }
        }
    }
    private fun notifyResult(context:Context,result:State) {
        ui.post {
            // A newer request may already be running by the time this queued message arrives.
            if(state!==result)return@post
            val message=when(result.enabled) {
                true->"Bluetooth is on"
                false->"Bluetooth is off"
                null->if(result.changed)"Bluetooth may have changed; check Inputs → Audio devices." else result.message
            }
            android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    @Synchronized fun cancel(owner:Any) { if(operationOwner===owner && busy) { cancelled=true;client?.close() } }
}
