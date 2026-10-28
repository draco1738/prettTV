package fi.jommi.rivi

/** A refresh is busy because work is running, never because its result changed. */
internal data class ContentRefreshStatus(
    val refreshing:Boolean=false,
    val failed:Boolean=false,
    val offline:Boolean=false,
) {
    fun applyTo(content:HomeContent)=content.copy(refreshing=refreshing,refreshFailed=failed,offline=offline)
}

/** The worker and delayed UI callback must agree on the same refresh generation. */
internal class ContentRefreshState {
    private var generation:Int?=null
    private var startedAt=0L
    private var running=false
    private var status=ContentRefreshStatus()

    @Synchronized fun begin(token:Int,now:Long,networkAvailable:Boolean?) {
        generation=token; startedAt=now; running=true
        status=ContentRefreshStatus(offline=networkAvailable==false)
    }

    @Synchronized fun reveal(token:Int,now:Long):Boolean {
        if(generation!=token || !running || status.refreshing || now-startedAt<DELAY_MILLIS)return false
        status=status.copy(refreshing=true)
        return true
    }

    @Synchronized fun finish(token:Int,failed:Boolean,networkAvailable:Boolean?):Boolean {
        if(generation!=token || !running)return false
        running=false
        // A provider error or an unknown connectivity check cannot establish that the TV is offline.
        status=ContentRefreshStatus(failed=failed && networkAvailable!=false,offline=networkAvailable==false)
        return true
    }

    @Synchronized fun cancel():Boolean {
        generation=null; running=false
        val changed=status.refreshing
        status=status.copy(refreshing=false)
        return changed
    }

    @Synchronized fun snapshot():ContentRefreshStatus=status

    companion object { const val DELAY_MILLIS=700L }
}
