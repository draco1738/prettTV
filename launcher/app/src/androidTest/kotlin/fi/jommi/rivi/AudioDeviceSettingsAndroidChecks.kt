package fi.jommi.rivi

import android.content.ActivityNotFoundException
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import java.util.concurrent.atomic.AtomicReference

/** Records navigation only; never opens settings or changes the Bluetooth radio. */
internal object AudioDeviceSettingsAndroidChecks {
    fun run(instrumentation:Instrumentation,test:(String,()->Unit)->Unit) {
        val context=instrumentation.targetContext
        check(context.packageName=="dev.zane.pretttv.stability")
        class RecordingContext:ContextWrapper(context) {
            val attempts=ArrayList<Intent>()
            var accepted:String?=AudioDeviceSettings.TV_DEVICES_ACTION
            var denied=false
            override fun startActivity(intent:Intent) {
                attempts.add(Intent(intent))
                if(intent.action!=accepted) {
                    if(denied)throw SecurityException("Fixture destination denied")
                    throw ActivityNotFoundException("Fixture destination missing")
                }
            }
        }
        test("Audio devices opens the TV devices page and falls back when destinations are missing or denied") {
            for(denied in listOf(false,true)) {
                val recording=RecordingContext().apply { this.denied=denied }
                for((action,expected,count) in listOf(
                    Triple(AudioDeviceSettings.TV_DEVICES_ACTION,AudioDeviceSettings.Destination.DEVICES,1),
                    Triple(Settings.ACTION_BLUETOOTH_SETTINGS,AudioDeviceSettings.Destination.BLUETOOTH,2),
                    Triple(Settings.ACTION_SETTINGS,AudioDeviceSettings.Destination.SETTINGS,3),
                    Triple(null,AudioDeviceSettings.Destination.UNAVAILABLE,3),
                )) {
                    recording.attempts.clear(); recording.accepted=action
                    check(AudioDeviceSettings.open(recording)==expected)
                    check(recording.attempts.size==count)
                    check(recording.attempts.first().`package`=="com.android.tv.settings")
                    check(recording.attempts.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK!=0 && it.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK==0 })
                }
            }
        }
        test("Inputs cards keep separate native source and audio actions and return without changing Bluetooth") {
            val activity=instrumentation.startActivitySync(Intent(context,CardFocusTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CardFocusTestActivity
            try {
                var inputs=0; var audio=0; var dismissed=0
                for(selectAudio in listOf(false,true)) {
                    val result=AtomicReference<Throwable?>()
                    instrumentation.runOnMainSync {
                        try {
                            val panel=InputsPanel.show(activity,{ inputs++ },{ audio++ },{ dismissed++ })
                            fun cards(view:View):List<PrettTvCard> = when(view) {
                                is PrettTvCard->listOf(view)
                                is ViewGroup->(0 until view.childCount).flatMap { cards(view.getChildAt(it)) }
                                else->emptyList()
                            }
                            val entries=cards(checkNotNull(panel.window).decorView)
                            try {
                                check(entries.map { it.title }==listOf("TV inputs","Audio devices")) { "Inputs panel did not expose both labeled cards" }
                                check(entries.all { it.isFocusable && it.art!=null && it.contentDescription.isNotBlank() }) { "Inputs cards lost focusability, artwork, or accessible labels" }
                                check(entries[0].nextFocusLeftId==entries[0].id && entries[1].nextFocusRightId==entries[1].id) { "Inputs card boundaries were not finite" }
                                check(entries[if(selectAudio)1 else 0].performClick()) { "Selected Inputs card did not handle its click" }
                                check(!panel.isShowing) { "Selecting a card left its Inputs panel open" }
                            } finally { if(panel.isShowing)panel.dismiss() }
                        } catch(failure:Throwable) { result.set(failure) }
                    }
                    result.get()?.let { throw it }
                    // Dialog dispatches OnDismiss through its Handler. The selected action runs
                    // there after the window closes, so wait before checking or finishing its Activity.
                    instrumentation.waitForIdleSync()
                    instrumentation.runOnMainSync {
                        try {
                            val expectedAudio=if(selectAudio)1 else 0
                            val expectedDismissed=if(selectAudio)2 else 1
                            check(inputs==1 && audio==expectedAudio && dismissed==expectedDismissed) {
                                "After ${if(selectAudio)"Audio devices" else "TV inputs"}: inputs=$inputs audio=$audio dismissed=$dismissed; expected 1/$expectedAudio/$expectedDismissed"
                            }
                        } catch(failure:Throwable) { result.set(failure) }
                    }
                    result.get()?.let { throw it }
                }
            } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }
}
