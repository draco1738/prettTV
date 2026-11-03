package fi.jommi.rivi

import android.content.Context

/** Recover only our recorded, unfinished staging work. Android owns every sealed session. */
internal object UpdateSessionRecovery {
    enum class Outcome { ABSENT, WAITING_FOR_ANDROID, RECOVERED, RETRY_REQUIRED }
    data class Session(val installerPackage:String?,val appPackage:String?,val sealed:Boolean)
    interface Sessions {
        fun read(id:Int):Session?
        fun abandon(id:Int)
    }

    fun recover(context:Context,id:Int):Outcome = try {
        val installer=context.packageManager.packageInstaller
        recover(id,context.packageName,object:Sessions {
            override fun read(id:Int)=installer.getSessionInfo(id)?.let {
                // isSealed is available since API 26 (our minimum). Commit seals immediately,
                // including while Android is still waiting for the user's confirmation.
                Session(it.installerPackageName,it.appPackageName,it.isSealed)
            }
            override fun abandon(id:Int) { installer.abandonSession(id) }
        })
    } catch(_:Exception) { Outcome.RETRY_REQUIRED }

    fun recover(id:Int,ownPackage:String,sessions:Sessions):Outcome {
        if(id<0)return Outcome.ABSENT
        fun valid(session:Session)=session.installerPackage==ownPackage && session.appPackage==ownPackage
        return try {
            val session=sessions.read(id) ?: return Outcome.ABSENT
            if(!valid(session))return Outcome.RETRY_REQUIRED
            if(session.sealed)return Outcome.WAITING_FOR_ANDROID
            // Re-read before the destructive operation; never abandon a now-sealed session.
            val current=sessions.read(id) ?: return Outcome.ABSENT
            if(!valid(current))return Outcome.RETRY_REQUIRED
            if(current.sealed)return Outcome.WAITING_FOR_ANDROID
            sessions.abandon(id)
            when(val remaining=sessions.read(id)) {
                null -> Outcome.RECOVERED
                else -> if(valid(remaining) && remaining.sealed)Outcome.WAITING_FOR_ANDROID else Outcome.RETRY_REQUIRED
            }
        } catch(_:Exception) { Outcome.RETRY_REQUIRED }
    }
}
