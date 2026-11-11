package fi.jommi.rivi

import android.app.Instrumentation
import android.os.SystemClock
import android.security.NetworkSecurityPolicy
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Native Android HttpURLConnection against an in-process IPv4 loopback server.
 * No fetch lambda or fake clock. The recording decorator forwards all I/O to the real connection.
 * Header negative control suppresses ONLY the watchdog's disconnect (not finally/cancel/timeout).
 * Body control reads the same drip with the initial socket timeout but without a total budget.
 * These controls establish causality; a fast error or a read-timeout alone cannot earn a pass. */
internal object StartupReliabilityTransportChecks {
    private const val BUDGET_MS=1_200L
    private const val SCHEDULING_ALLOWANCE_MS=500L
    private const val DRIPS=60
    private const val INTERVAL_MS=70L

    fun run(i:Instrumentation,test:(String,()->Unit)->Unit) {
        check(i.targetContext.packageName=="dev.zane.pretttv.stability")
        test("Stability cleartext exception is IPv4 loopback only") {
            val policy=NetworkSecurityPolicy.getInstance()
            check(policy.isCleartextTrafficPermitted("127.0.0.1"))
            check(!policy.isCleartextTrafficPermitted && !policy.isCleartextTrafficPermitted("api.tvmaze.com") &&
                !policy.isCleartextTrafficPermitted("192.168.1.1") && !policy.isCleartextTrafficPermitted("localhost"))
        }
        test("Android native slow headers are cut off by the real deadline, unlike a disabled-watchdog control") {
            val control=exercise(i,body=false,suppressWatchdog=true)
            check(control.watchdogNs>0 && control.drips==DRIPS && control.responseComplete) { "Disabled-watchdog control did not exercise the complete drip: $control" }
            check(control.transportMs>=DRIPS*INTERVAL_MS) { "A socket timeout, not the total deadline, ended the control: $control" }
            val bounded=exercise(i,body=false)
            check(bounded.watchdogNs in (bounded.headerStartNs+1)..bounded.headerEndNs) { "Deadline did not disconnect the connection while native headers were blocked: $bounded" }
            check(bounded.drips in 3 until DRIPS && !bounded.responseComplete)
            check(bounded.transportMs<=BUDGET_MS+SCHEDULING_ALLOWANCE_MS) { "Native header transport exceeded the fixed deadline allowance: $bounded" }
            check(bounded.transportMs<control.transportMs) { "Watchdog did not cause earlier transport completion" }
        }
        test("Android native slow body obeys the real total budget while ordinary socket-timeout control consumes the full drip") {
            val control=exercise(i,body=true,rawControl=true)
            check(control.drips==DRIPS && control.responseComplete && control.bytesRead==DRIPS+2)
            check(control.transportMs>=DRIPS*INTERVAL_MS) { "Slow body fixture timed out rather than dripping" }
            val bounded=exercise(i,body=true)
            check(bounded.bytesRead in 3 until DRIPS+2 && bounded.drips in 3 until DRIPS && !bounded.responseComplete) { "Did not exercise a partially read native body: $bounded" }
            // Either watchdog disconnect or the budget's shrinking read timeout / between-read
            // budget check ends real I/O. The full-drip control excludes an ordinary read timeout.
            check(bounded.readTimeouts.any { it in 1..(BUDGET_MS/3).toInt() }) { "Real native read timeout did not shrink with the total budget" }
            check(bounded.transportMs in (BUDGET_MS-INTERVAL_MS-100)..(BUDGET_MS+SCHEDULING_ALLOWANCE_MS)) { "Body exited too early or exceeded the fixed deadline allowance: $bounded" }
            check(bounded.transportMs<control.transportMs)
        }
        test("Repository-style artwork cancellation disconnects native slow headers and does not deliver stale hints") {
            exercise(i,body=false,cancel=true).also {
                check(it.cancelled && it.updates==0 && it.drips in 3 until DRIPS && !it.responseComplete)
                check(it.afterCancelMs in 0..SCHEDULING_ALLOWANCE_MS) { "Native cancellation failed to release in-flight headers promptly: $it" }
            }
        }
    }

    private data class Result(val transportMs:Long,val totalMs:Long,val headerStartNs:Long,val headerEndNs:Long,
        val watchdogNs:Long,val drips:Int,val responseComplete:Boolean,val bytesRead:Int,val readTimeouts:List<Int>,
        val cancelled:Boolean,val afterCancelMs:Long,val updates:Int,val nativeClass:String)

    private fun exercise(i:Instrumentation,body:Boolean,suppressWatchdog:Boolean=false,rawControl:Boolean=false,cancel:Boolean=false):Result {
        val root=File(i.targetContext.cacheDir,"native-reliability-${SystemClock.elapsedRealtimeNanos()}").apply { check(mkdirs()) }
        val server=DripServer(body)
        val connection=AtomicReference<RecordingConnection>()
        val updates=AtomicInteger()
        val resolver=PresentationArtworkResolver(File(root,"artwork.bin"),paceMillis=0,batchMillis=if(cancel)8_000 else BUDGET_MS,
            openConnection={ address ->
                check(BalancedArtworkPolicy.apiAddress(address.toString()))
                // Only the connection factory redirects an allowlisted logical URL to the fixture.
                // Production URL validation and transport branches are unchanged.
                RecordingConnection(server.open(),suppressWatchdog).also { connection.set(it) }
            })
        val started=System.nanoTime();val ended=AtomicLong();val cancelledAt=AtomicLong()
        val task=FutureTask {
            try {
                if(rawControl) {
                    val con=RecordingConnection(server.open(),false);connection.set(con)
                    try {
                        con.connectTimeout=(BUDGET_MS/2).toInt();con.readTimeout=(BUDGET_MS/2).toInt();con.instanceFollowRedirects=false
                        check(con.responseCode==200);con.inputStream.use { input -> while(input.read()!=-1) { /* Real unbudgeted control. */ } }
                    } finally { con.disconnect() }
                } else {
                    val p=Program("native-transport-fixture","Native Transport Fixture","","","fixture","Fixture",mediaType="series",releaseYear=2020)
                    resolver.enrich(listOf(p),null,System.currentTimeMillis(),true,{true}) { updates.incrementAndGet() }
                }
            } finally { ended.set(System.nanoTime()) }
        }
        val worker=Thread(task,"startup-native-transport-check").apply { isDaemon=true;start() }
        try {
            check(server.accepted.await(3,TimeUnit.SECONDS)) { "Android never reached loopback; native transport or stability security config failed" }
            if(cancel) {
                val until=SystemClock.elapsedRealtime()+2_000
                while(server.writes.get()<4) { check(SystemClock.elapsedRealtime()<until);SystemClock.sleep(10) }
                cancelledAt.set(System.nanoTime());resolver.cancel()
            }
            task.get(12,TimeUnit.SECONDS)
            if(rawControl || suppressWatchdog)check(server.finished.await(2,TimeUnit.SECONDS)) { "Control server did not finish" }
            val con=connection.get() ?: error("Real Android connection was never opened")
            val transportMs=TimeUnit.NANOSECONDS.toMillis(con.lastIoEndNs.get()-con.headerStartNs.get())
            val result=Result(transportMs,TimeUnit.NANOSECONDS.toMillis(ended.get()-started),con.headerStartNs.get(),con.headerEndNs.get(),
                con.watchdogNs.get(),server.writes.get(),server.complete.get()==true,con.bytesRead.get(),con.readTimeouts.toList(),cancel,
                if(cancel)TimeUnit.NANOSECONDS.toMillis(con.lastIoEndNs.get()-cancelledAt.get()) else -1,updates.get(),con.nativeClass)
            StartupReliabilityAndroidChecks.timing(i,"Android loopback body=$body suppressedWatchdog=$suppressWatchdog rawControl=$rawControl cancel=$cancel",
                "$result; criteria: budgetMs=$BUDGET_MS fixedSchedulingAllowanceMs=$SCHEDULING_ALLOWANCE_MS dripIntervalMs=$INTERVAL_MS dripCount=$DRIPS; transport time excludes cache fsync, totalMs includes it")
            check(server.failure.get()==null) { "Server failed before deadline/cancellation evidence: ${server.failure.get()}" }
            check(con.headerStartNs.get()>0 && con.lastIoEndNs.get()>=con.headerStartNs.get())
            return result
        } finally {
            // Even a failed assertion cannot leave a listening server or reader behind.
            server.close()
            resolver.cancel();connection.get()?.disconnect()
            worker.join(5_000)
            try { check(!worker.isAlive) { "Native transport worker leaked" } }
            finally { check(root.deleteRecursively()) }
        }
    }

    private class RecordingConnection(private val real:HttpURLConnection,private val suppressWatchdog:Boolean):HttpURLConnection(real.url) {
        val nativeClass=real.javaClass.name
        val headerStartNs=AtomicLong();val headerEndNs=AtomicLong();val lastIoEndNs=AtomicLong();val watchdogNs=AtomicLong()
        val bytesRead=AtomicInteger();val readTimeouts=CopyOnWriteArrayList<Int>()
        override fun connect()=real.connect()
        override fun usingProxy()=real.usingProxy()
        override fun disconnect() {
            if(Thread.currentThread().name=="pretttv-artwork-deadline") {
                watchdogNs.compareAndSet(0,System.nanoTime())
                if(suppressWatchdog)return
            }
            real.disconnect()
        }
        override fun setConnectTimeout(timeout:Int) { real.connectTimeout=timeout }
        override fun getConnectTimeout()=real.connectTimeout
        override fun setReadTimeout(timeout:Int) { readTimeouts+=timeout;real.readTimeout=timeout }
        override fun getReadTimeout()=real.readTimeout
        override fun setInstanceFollowRedirects(follow:Boolean) { real.instanceFollowRedirects=follow }
        override fun getInstanceFollowRedirects()=real.instanceFollowRedirects
        override fun setRequestProperty(key:String,value:String)=real.setRequestProperty(key,value)
        override fun getResponseCode():Int {
            headerStartNs.compareAndSet(0,System.nanoTime())
            try { return real.responseCode }
            finally { val now=System.nanoTime();headerEndNs.set(now);lastIoEndNs.set(now) }
        }
        override fun getContentLengthLong()=real.contentLengthLong
        override fun getHeaderField(name:String)=real.getHeaderField(name)
        override fun getInputStream():InputStream=object:FilterInputStream(real.inputStream) {
            override fun read():Int {
                try { return `in`.read().also { if(it>=0)bytesRead.incrementAndGet() } }
                finally { lastIoEndNs.set(System.nanoTime()) }
            }
            override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
                try { return `in`.read(buffer,offset,length).also { if(it>0)bytesRead.addAndGet(it) } }
                finally { lastIoEndNs.set(System.nanoTime()) }
            }
        }
    }

    private class DripServer(private val body:Boolean) {
        private val server=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).apply { soTimeout=3_000 }
        private val socket=AtomicReference<Socket>();private val closing=AtomicReference(false)
        val accepted=CountDownLatch(1);val writes=AtomicInteger();val complete=AtomicReference(false);val failure=AtomicReference<Throwable>()
        val finished=CountDownLatch(1)
        private val sender=Thread({
            try {
                server.accept().use { client ->
                    socket.set(client);client.soTimeout=2_000;client.tcpNoDelay=true
                    val input=client.getInputStream().bufferedReader(Charsets.US_ASCII)
                    var lines=0
                    while(true) {
                        val line=input.readLine() ?: error("No HTTP request")
                        check(++lines<=50 && line.length<=8192)
                        if(line.isEmpty())break
                    }
                    accepted.countDown()
                    val out=client.getOutputStream()
                    val start=if(body)"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${DRIPS+2}\r\nConnection: close\r\n\r\n["
                        else "HTTP/1.1 200 OK\r\nX-Slow-Fixture: "
                    out.write(start.toByteArray(Charsets.US_ASCII));out.flush()
                    repeat(DRIPS) {
                        out.write(' '.code);out.flush();writes.incrementAndGet();Thread.sleep(INTERVAL_MS)
                    }
                    out.write((if(body)"]" else "\r\nContent-Length: 2\r\nConnection: close\r\n\r\n[]").toByteArray(Charsets.US_ASCII));out.flush()
                    complete.set(true)
                }
            } catch(e:Exception) {
                // After repeated successful writes, a socket close is expected from the deadline.
                if(!closing.get() && (writes.get()<3 || e !is java.io.IOException))failure.set(e)
            } finally { finished.countDown() }
        },"startup-loopback-drip").apply { isDaemon=true;start() }
        fun open()=URL("http://127.0.0.1:${server.localPort}/synthetic-metadata").openConnection() as HttpURLConnection
        fun close() {
            closing.set(true);server.close();socket.get()?.close();sender.interrupt();sender.join(5_000)
            check(!sender.isAlive) { "Loopback sender leaked" }
        }
    }
}
