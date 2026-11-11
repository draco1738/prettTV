package fi.jommi.rivi

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real loopback HTTP, not a fake connection. This exercises the host JVM transport, not Android. */
class PresentationArtworkTransportTest {
    @Test fun drippingHeadersCannotOccupyTheWorkerUntilTheServerFinishes()=slowResponse(false)
    @Test fun drippingBodyCannotOccupyTheWorkerUntilTheServerFinishes()=slowResponse(true)

    private fun slowResponse(body:Boolean) {
        val root=Files.createTempDirectory("pretttv-real-transport-").toFile()
        val accepted=CountDownLatch(1)
        val writes=AtomicInteger()
        val server=ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress()).apply { soTimeout=3000 }
        val sender=Thread {
            try {
                server.accept().use { socket ->
                    socket.soTimeout=2000
                    val input=socket.getInputStream().bufferedReader()
                    while(true) { val line=input.readLine() ?: break; if(line.isEmpty())break }
                    accepted.countDown()
                    val output=socket.getOutputStream()
                    val start=if(body)"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 10000\r\nConnection: close\r\n\r\n["
                              else "HTTP/1.1 200 OK\r\nX-Slow: "
                    output.write(start.toByteArray()); output.flush()
                    repeat(60) {
                        output.write(' '.code); output.flush(); writes.incrementAndGet(); Thread.sleep(40)
                    }
                }
            } catch(_:Exception) { /* Expected disconnect or cleanup. */ }
        }.apply { isDaemon=true; start() }
        val resolver=PresentationArtworkResolver(File(root,"artwork.bin"),paceMillis=0,batchMillis=400,
            openConnection={ URL("http://127.0.0.1:${server.localPort}/metadata").openConnection() as HttpURLConnection })
        try {
            val program=Program("transport-fixture","Transport Fixture","","","fixture","Fixture",mediaType="series",releaseYear=2020)
            val start=System.nanoTime()
            resolver.enrich(listOf(program),null,System.currentTimeMillis(),true,{true}) { }
            val elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)
            assertTrue("Server must receive the real request",accepted.await(1,TimeUnit.SECONDS))
            assertTrue("Response must drip repeatedly rather than simply time out",writes.get()>=3)
            // Cache fsync and platform cleanup are not covered by the request-work budget.
            // Assert the real failure mode instead of a machine-load-sensitive wall-clock SLA:
            // without the independent disconnect, headers keep arriving until all 60 writes end.
            println("Transport body=$body elapsedMs=$elapsed serverWrites=${writes.get()}")
            assertTrue("Transport waited for the server to finish its entire drip",writes.get()<60)
        } finally {
            resolver.cancel(); server.close(); sender.join(3000); root.deleteRecursively()
        }
    }
}
