package fi.jommi.rivi

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** One bounded operation at a time. Activity recreation cannot duplicate a restore. */
internal object StockRecoverySession {
    enum class Phase { IDLE, CHECKING, READY, RESTORING, FINISHED, FAILED }
    data class State(val phase: Phase = Phase.IDLE, val message: String = "", val packageCount: Int = 0)
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "pretttv-stock-recovery") }
    @Volatile private var client: LocalAdbClient? = null
    @Volatile var state = State(); private set
    @Volatile private var generation = 0
    private var approved: StockRestoreSnapshot? = null
    private var engine: StockRestoreEngine? = null
    private var listener: ((State) -> Unit)? = null
    private var listenerOwner: Any? = null
    val busy get() = state.phase in setOf(Phase.CHECKING, Phase.READY, Phase.RESTORING)

    fun listen(owner: Any, callback: (State) -> Unit) { listenerOwner = owner; listener = callback; callback(state) }
    fun unlisten(owner: Any) { if(listenerOwner === owner) { listener = null; listenerOwner = null } }
    private fun publish(value: State) { state = value; ui.post { if (state == value) listener?.invoke(value) } }

    @Synchronized fun check(context: Context) {
        check(!busy)
        if (AppForceStopSession.busy) {
            publish(State(Phase.FAILED, "An app force stop is still finishing. Nothing was restored. Wait a moment, then open recovery again."))
            return
        }
        val app = context.applicationContext
        val store = StockRecoveryStore(app)
        val token = ++generation
        publish(State(Phase.CHECKING, "Checking the saved recovery record…"))
        worker.execute {
            var connection: LocalAdbClient? = null
            try {
                val plan = store.plan() ?: error("No original setup record is saved. Configure recovery from the original setup backup first.")
                connection = LocalAdbClient(store.keyPair())
                synchronized(this) {
                    if (token != generation) return@execute
                    client = connection
                }
                connection.connect { synchronized(this) { if(token == generation) publish(State(Phase.CHECKING, "Accept Android’s debugging prompt for prettTV on this TV. Then the connection check will continue.")) } }
                val restore = StockRestoreEngine(plan, RestoreShell { command -> connection.shell(command) })
                val snapshot = restore.preflight()
                synchronized(this) {
                    if (token != generation) return@execute
                    engine = restore; approved = snapshot
                    store.status("Connection and recovery record checked. No TV settings changed.")
                    publish(State(Phase.READY, "Connection and original setup verified.", plan.packages.size))
                    ui.postDelayed({ synchronized(this) {
                        if(token == generation && state.phase == Phase.READY) {
                            client?.close(); client = null; engine = null; approved = null
                            publish(State(Phase.FAILED, "The confirmation expired. Nothing was restored. Open recovery again to check the TV and confirm."))
                        }
                    } }, 120_000)
                }
            } catch (e: Exception) {
                android.util.Log.w("prettTV-recovery", "Recovery connection check failed", e)
                connection?.close()
                synchronized(this) { if (token == generation) {
                    val message = "Nothing was restored. ${e.message ?: "Connection check failed."}\n\nEnable network debugging on this TV and approve prettTV’s key, then try again."
                    runCatching { store.status(message) }; publish(State(Phase.FAILED, message))
                } }
            } finally { if (token != generation) connection?.close() }
        }
    }

    /** Only the second, separate confirmation calls this method. No dump/intent execution endpoint. */
    @Synchronized fun restore(context: Context) {
        check(state.phase == Phase.READY)
        val restore = checkNotNull(engine); val snapshot = checkNotNull(approved)
        val app = context.applicationContext; val store = StockRecoveryStore(app)
        publish(State(Phase.RESTORING, "Rechecking the TV before restoring…"))
        worker.execute {
            try {
                store.status("Restore started. If interrupted, open recovery again to check the remaining steps.")
                val result = restore.restore(snapshot,
                    progress = { publish(State(Phase.RESTORING, it)) },
                    checkpoint = { store.status("Restore in progress: ${it.stage}. If interrupted, check recovery again." ) })
                if (result.success) {
                    StockRecoveryStore.pause(app)
                    ui.post { PlaybackTracker.pauseForStock(); RbtvHandoffService.cancel() }
                }
                store.status(result.message)
                publish(State(if (result.success) Phase.FINISHED else Phase.FAILED, result.message))
            } catch (e: Exception) {
                android.util.Log.w("prettTV-recovery", "Recovery did not finish", e)
                val message = "Restore did not finish. ${e.message ?: "The connection was interrupted."}\nOpen recovery again to check what remains. prettTV and your streaming data have been kept."
                runCatching { store.status(message) }; publish(State(Phase.FAILED, message))
            } finally { client?.close(); client = null; engine = null; approved = null }
        }
    }

    @Synchronized fun cancel() {
        if (state.phase == Phase.RESTORING) return
        generation++; client?.close(); client = null; engine = null; approved = null
        publish(State())
    }
}
