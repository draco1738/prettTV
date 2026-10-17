package fi.jommi.rivi

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs real Android decoders and worker queues in a separate app ID, never the owner's Home. */
class StabilityInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        var passed=0
        try {
            check(targetContext.packageName=="dev.zane.pretttv.stability") { "Refusing to run against the production Home" }
            fun test(name:String,body:()->Unit) { body();passed++;sendStatus(0,Bundle().apply { putString("stream","PASS $name\n") }) }
            test("real decoder accepts fixture and downsamples large image") {
                withLoader { loader ->
                    check(load(loader,"good")!=null)
                    val large=load(loader,"large") ?: error("Large image did not decode")
                    check(large.width<=640 && large.height<=360 && large.allocationByteCount<=8_000_000)
                }
            }
            test("invalid and oversized artwork fail then recover") {
                withLoader { loader ->
                    check(load(loader,"broken")==null)
                    check(load(loader,"oversized")==null)
                    check(load(loader,"good/recovery")!=null)
                }
            }
            test("saturated queue completes every request, including rejected work") {
                withLoader { loader ->
                    val completed=CountDownLatch(100);val failures=AtomicInteger();val successes=AtomicInteger()
                    runOnMainSync { repeat(100) { i -> loader.load(url("slow/$i"),320,180) {
                        if(it==null)failures.incrementAndGet() else successes.incrementAndGet();completed.countDown()
                    } } }
                    check(completed.await(20,TimeUnit.SECONDS)) { "Some queued/rejected requests never completed" }
                    check(failures.get()>0 && successes.get()>0)
                }
            }
            test("cancelled generation cannot deliver into a replacement screen") {
                withLoader { loader ->
                    val cancelled=AtomicBoolean();val stale=AtomicInteger()
                    runOnMainSync {
                        repeat(20) { i -> loader.load(url("slow/cancel-$i"),320,180) { if(cancelled.get())stale.incrementAndGet() } }
                        cancelled.set(true);loader.cancel()
                    }
                    check(load(loader,"good/after-cancel")!=null)
                    Thread.sleep(300)
                    check(stale.get()==0) { "Cancelled artwork reached the screen" }
                }
            }
            test("closed loader suppresses late callbacks") {
                val deliveries=AtomicInteger();val loader=ArtworkLoader(targetContext)
                runOnMainSync { loader.load(url("slow/close"),320,180) { deliveries.incrementAndGet() };loader.close() }
                Thread.sleep(400)
                check(deliveries.get()==0)
            }
            test("corrupt app tile cache recovers without losing the app") {
                val repo=AppRepository(targetContext)
                val entry=AppEntry("fixture/icon","com.netflix.ninja","Fixture",android.content.Intent(),true,1)
                val file=java.io.File(targetContext.cacheDir,"tiles/${repo.cacheName(entry,320,180,0f)}")
                file.parentFile!!.mkdirs();file.writeBytes(byteArrayOf(1,2,3))
                val bitmap=repo.loadTile(entry,320,180,0f) ?: error("Corrupt cache prevented icon recovery")
                check(bitmap.width==320 && bitmap.height==180)
                check(file.length()>3)
            }
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","STABILITY PASS: $passed Android fault tests\n");putInt("passed",passed) })
        } catch(t:Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","STABILITY FAIL after $passed: ${t.javaClass.simpleName}: ${t.message}\n");putString("failure",t.stackTraceToString()) })
        }
    }
    private fun url(path:String)="content://${targetContext.packageName}.art/$path"
    private fun withLoader(body:(ArtworkLoader)->Unit) {
        val loader=ArtworkLoader(targetContext)
        try { body(loader) } finally { runOnMainSync { loader.close() } }
    }
    private fun load(loader:ArtworkLoader,path:String):Bitmap? {
        val result=AtomicReference<Bitmap?>();val done=CountDownLatch(1)
        runOnMainSync { loader.load(url(path),320,180) { result.set(it);done.countDown() } }
        check(done.await(10,TimeUnit.SECONDS)) { "Artwork request never completed: $path" }
        return result.get()
    }
}
