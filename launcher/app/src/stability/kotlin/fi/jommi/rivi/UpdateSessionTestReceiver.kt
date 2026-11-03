package fi.jommi.rivi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Stability APK only: records installer callbacks and never opens/approves their confirmation. */
class UpdateSessionTestReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(context.packageName!="dev.zane.pretttv.stability" || intent.action!=ACTION)return
        val id=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1)
        if(id!=expected)return
        status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,Int.MIN_VALUE)
        signal.countDown()
    }
    companion object {
        const val ACTION="dev.zane.pretttv.stability.INSTALL_SESSION_FIXTURE"
        @Volatile private var expected=-1
        @Volatile private var status=Int.MIN_VALUE
        @Volatile private var signal=CountDownLatch(1)
        fun expect(id:Int) { expected=id;status=Int.MIN_VALUE;signal=CountDownLatch(1) }
        fun await():Int { check(signal.await(20,TimeUnit.SECONDS)) { "No installer fixture callback" };return status }
        fun reset() { expected=-1;status=Int.MIN_VALUE }
    }
}
