package fi.jommi.rivi

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService

/** Uses media sessions only. No notification text is read, logged, retained or transmitted. */
class PlaybackTracker:NotificationListenerService() {
    companion object {
        private var instance:PlaybackTracker?=null
        fun pauseForStock() { instance?.close() }
        fun resumeAfterStock() { instance?.let { it.close(); it.onListenerConnected() } }
    }
    private val ui=Handler(Looper.getMainLooper())
    private val callbacks=HashMap<MediaController,MediaController.Callback>()
    private val firstPlaying=HashMap<String,Long>()
    private lateinit var history:PlaybackHistory
    private lateinit var manager:MediaSessionManager
    private val changed=MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }
    private val sample=object:Runnable { override fun run() { scan() } }
    override fun onListenerConnected() {
        instance=this
        if(StockRecoveryStore.paused(this))return
        history=PlaybackHistory(this); manager=getSystemService(MediaSessionManager::class.java)
        try { manager.addOnActiveSessionsChangedListener(changed,ComponentName(this,PlaybackTracker::class.java),ui); attach(manager.getActiveSessions(ComponentName(this,PlaybackTracker::class.java))) } catch(_:SecurityException) { }
    }
    private fun attach(controllers:List<MediaController>) {
        if(StockRecoveryStore.paused(this)) { close(); return }
        callbacks.forEach { (c,cb)->c.unregisterCallback(cb) }; callbacks.clear()
        controllers.forEach { c ->
            val cb=object:MediaController.Callback() {
                override fun onPlaybackStateChanged(state:PlaybackState?) { scan() }
                override fun onMetadataChanged(metadata:MediaMetadata?) { scan() }
                override fun onSessionDestroyed() { firstPlaying.clear(); scan() }
            }
            c.registerCallback(cb,ui); callbacks[c]=cb
        }
        scan()
    }
    private fun scan() {
        if(StockRecoveryStore.paused(this)) { close(); return }
        ui.removeCallbacks(sample); var playing=false; val active=HashSet<String>()
        for(c in callbacks.keys) {
            val state=c.playbackState ?: continue
            if(state.state!=PlaybackState.STATE_PLAYING)continue
            val m=c.metadata
            val labels=listOf(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,MediaMetadata.METADATA_KEY_TITLE,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,MediaMetadata.METADATA_KEY_ALBUM)
                .mapNotNull { m?.getString(it)?.takeIf(String::isNotBlank) }
            val p=history.match(c.packageName,labels) ?: continue
            val k=p.packageName+"|"+p.titleKey; active.add(k); playing=true
            val now=SystemClock.elapsedRealtime(); val began=firstPlaying.getOrPut(k) { now }
            val position=state.position+(if(state.lastPositionUpdateTime>0)(now-state.lastPositionUpdateTime).coerceAtLeast(0)*state.playbackSpeed else 0f).toLong()
            val duration=m?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it>0 } ?: p.duration
            if(now-began>=15_000 && position>=15_000 && duration>=180_000)history.confirm(p,position.coerceAtMost(duration),duration)
        }
        firstPlaying.keys.retainAll(active)
        if(playing)ui.postDelayed(sample,5_000)
    }
    override fun onListenerDisconnected() { close() }
    override fun onDestroy() { close(); if(instance===this)instance=null; super.onDestroy() }
    private fun close() {
        ui.removeCallbacksAndMessages(null)
        if(::manager.isInitialized)try { manager.removeOnActiveSessionsChangedListener(changed) } catch(_:Exception) { }
        callbacks.forEach { (c,cb)->c.unregisterCallback(cb) }; callbacks.clear(); firstPlaying.clear()
    }
}
