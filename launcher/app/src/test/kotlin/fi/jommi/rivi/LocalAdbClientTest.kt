package fi.jommi.rivi

import fi.jommi.rivi.LocalAdbClient.Companion.AUTH
import fi.jommi.rivi.LocalAdbClient.Companion.CLSE
import fi.jommi.rivi.LocalAdbClient.Companion.CNXN
import fi.jommi.rivi.LocalAdbClient.Companion.MAX_PAYLOAD
import fi.jommi.rivi.LocalAdbClient.Companion.OKAY
import fi.jommi.rivi.LocalAdbClient.Companion.OPEN
import fi.jommi.rivi.LocalAdbClient.Companion.VERSION
import fi.jommi.rivi.LocalAdbClient.Companion.WRTE
import fi.jommi.rivi.LocalAdbClient.Companion.encodePacket
import fi.jommi.rivi.LocalAdbClient.Companion.readPacket
import fi.jommi.rivi.LocalAdbClient.Packet
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalAdbClientTest {
    companion object {
        private val pair by lazy { LocalAdbClient.generateKeyPair() }
        private fun connected(version: Int = VERSION) = Packet(CNXN, version, MAX_PAYLOAD,
            "device::features=shell_v2".toByteArray())
    }

    private class ScriptedConnection(vararg packets: Packet) : LocalAdbClient.Connection {
        override val input = ByteArrayInputStream(packets.flatMap { encodePacket(it).asIterable() }.toByteArray())
        override val output = ByteArrayOutputStream()
        var closed = false
        var connectTimeout = 0
        var latestReadTimeout = 0
        override fun connect(timeoutMs: Int) { connectTimeout = timeoutMs }
        override fun readTimeout(timeoutMs: Int) { latestReadTimeout = timeoutMs }
        override fun close() { closed = true }
        fun sent(): List<Packet> {
            val bytes = ByteArrayInputStream(output.toByteArray())
            return buildList { while (bytes.available() > 0) add(readPacket(bytes)) }
        }
    }

    private class BlockingConnection(private val blockWrite: Boolean = false) : LocalAdbClient.Connection {
        val waiting = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private fun block(): Nothing {
            waiting.countDown()
            if (!closed.await(3, TimeUnit.SECONDS)) throw AssertionError("Cancellation did not close I/O")
            throw IOException("Socket closed")
        }
        override val input = object : InputStream() { override fun read(): Int = block() }
        override val output = object : OutputStream() { override fun write(value: Int) { if (blockWrite) block() } }
        override fun connect(timeoutMs: Int) = Unit
        override fun readTimeout(timeoutMs: Int) = Unit
        override fun close() { closed.countDown() }
    }

    @Test fun packetHasLittleEndianHeaderAndUnsignedByteChecksum() {
        val payload = byteArrayOf(0xff.toByte(), 0x80.toByte(), 1)
        val bytes = encodePacket(Packet(WRTE, 0x01020304, 17, payload))
        assertArrayEquals("WRTE".toByteArray(), bytes.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(4, 3, 2, 1), bytes.copyOfRange(4, 8))
        assertEquals(384, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(16))
        val packet = readPacket(ByteArrayInputStream(bytes))
        assertEquals(0x01020304, packet.arg0)
        assertArrayEquals(payload, packet.payload)
    }

    @Test fun fragmentedReadsAreReassembled() {
        val original = ByteArrayInputStream(encodePacket(Packet(WRTE, 7, 1, "split payload".toByteArray())))
        val fragmented = object : InputStream() {
            override fun read(): Int = original.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = original.read(bytes, offset, minOf(2, length))
        }
        assertEquals("split payload", readPacket(fragmented).payload.toString(Charsets.UTF_8))
    }

    @Test fun truncatedHeaderAndPayloadAreRejected() {
        val complete = encodePacket(Packet(WRTE, 7, 1, "hello".toByteArray()))
        for (length in listOf(0, 23, 26)) {
            assertThrows(EOFException::class.java) { readPacket(ByteArrayInputStream(complete.copyOf(length))) }
        }
    }

    @Test fun malformedLengthsAreRejectedBeforeReadingPayload() {
        for (length in listOf(-1, 4097, Int.MAX_VALUE)) {
            val bytes = encodePacket(Packet(WRTE, 7, 1))
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(12, length)
            val error = assertThrows(IOException::class.java) { readPacket(ByteArrayInputStream(bytes)) }
            assertTrue(error.message!!.contains("size"))
        }
    }

    @Test fun corruptMagicChecksumAndUnknownCommandAreRejected() {
        for (offset in listOf(0, 16, 20)) {
            val bytes = encodePacket(Packet(WRTE, 7, 1, byteArrayOf(1)))
            bytes[offset] = (bytes[offset].toInt() xor 1).toByte()
            assertThrows(IOException::class.java) { readPacket(ByteArrayInputStream(bytes)) }
        }
    }

    @Test fun tokenSignatureMatchesSha1DigestWithoutHashingItTwice() {
        val message = "ADB independently supplies the digest".toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").digest(message)
        val signature = LocalAdbClient.signToken(pair, digest)
        assertEquals(256, signature.size)
        val verifier = Signature.getInstance("SHA1withRSA")
        verifier.initVerify(pair.public)
        verifier.update(message)
        assertTrue(verifier.verify(signature))
        verifier.initVerify(pair.public)
        verifier.update(digest)
        assertFalse(verifier.verify(signature))
        assertThrows(IllegalArgumentException::class.java) { LocalAdbClient.signToken(pair, byteArrayOf(1)) }
    }

    @Test fun androidPublicKeyCanBeIndependentlyDecodedAndVerifySignature() {
        val payload = LocalAdbClient.publicKeyPayload(pair.public as RSAPublicKey)
        assertEquals(0, payload.last().toInt())
        val text = payload.toString(Charsets.US_ASCII)
        assertTrue(text.endsWith(" prettTV@localhost\u0000"))
        val binary = Base64.getDecoder().decode(text.substringBefore(' '))
        assertEquals(524, binary.size)
        val data = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, data.int)
        val n0inv = BigInteger.valueOf(data.int.toLong() and 0xffffffffL)
        val modulus = ByteArray(256).also { data.get(it) }.reversedArray().let { BigInteger(1, it) }
        val rr = ByteArray(256).also { data.get(it) }.reversedArray().let { BigInteger(1, it) }
        val exponent = BigInteger.valueOf(data.int.toLong())
        val wordModulus = BigInteger.ONE.shiftLeft(32)
        assertEquals(wordModulus.subtract(BigInteger.ONE), modulus.multiply(n0inv).mod(wordModulus))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(modulus), rr)
        val decoded = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        assertArrayEquals(pair.public.encoded, decoded.encoded)
        val message = "public key proof".toByteArray()
        val signature = LocalAdbClient.signToken(pair, MessageDigest.getInstance("SHA-1").digest(message))
        assertTrue(Signature.getInstance("SHA1withRSA").run {
            initVerify(decoded); update(message); verify(signature)
        })
    }

    @Test fun knownKeyConnectSignsTokenWithoutRequestingApproval() {
        val token = ByteArray(20) { it.toByte() }
        val connection = ScriptedConnection(Packet(AUTH, 1, 0, token), connected())
        LocalAdbClient(pair, connection).use { client ->
            client.connect { fail("A known key must not ask approval") }
            val sent = connection.sent()
            assertEquals(listOf(CNXN, AUTH), sent.map { it.command })
            assertEquals(VERSION, sent[0].arg0)
            assertEquals(MAX_PAYLOAD, sent[0].arg1)
            assertEquals(2, sent[1].arg0)
            assertArrayEquals(LocalAdbClient.signToken(pair, token), sent[1].payload)
            assertEquals(2_000, connection.connectTimeout)
        }
        assertTrue(connection.closed)
    }

    @Test fun unknownKeySendsPublicKeyAndRequestsApprovalOnce() {
        val token = ByteArray(20) { it.toByte() }
        val connection = ScriptedConnection(Packet(AUTH, 1, 0, token), Packet(AUTH, 1, 0, token), connected())
        var approvals = 0
        LocalAdbClient(pair, connection).use { client ->
            client.connect { approvals++ }
            assertEquals(1, approvals)
            assertEquals(listOf(2, 3), connection.sent().drop(1).map { it.arg0 })
            assertArrayEquals(LocalAdbClient.publicKeyPayload(pair.public as RSAPublicKey), connection.sent().last().payload)
        }
    }

    @Test fun refusedApprovalDoesNotRetryForeverOrRunCommands() {
        val request = Packet(AUTH, 1, 0, ByteArray(20))
        val connection = ScriptedConnection(request, request, request)
        val client = LocalAdbClient(pair, connection)
        assertThrows(IOException::class.java) { client.connect() }
        assertTrue(connection.closed)
        assertFalse(connection.sent().any { it.command == OPEN })
        assertThrows(IOException::class.java) { client.shell("id") }
    }

    @Test fun malformedAuthorizationIsRejectedAndClosesConnection() {
        for (request in listOf(Packet(AUTH, 3, 0, ByteArray(20)), Packet(AUTH, 1, 1, ByteArray(20)),
            Packet(AUTH, 1, 0, ByteArray(19)))) {
            val connection = ScriptedConnection(request)
            assertThrows(IOException::class.java) { LocalAdbClient(pair, connection).connect() }
            assertTrue(connection.closed)
        }
    }

    @Test fun unexpectedProtocolOrBootloaderCannotBeUsedForRecovery() {
        for (response in listOf(connected(VERSION - 1), connected(VERSION + 2),
            Packet(CNXN, VERSION, 32), Packet(CNXN, VERSION, MAX_PAYLOAD, "bootloader::".toByteArray()),
            Packet(WRTE, 1, 1))) {
            val connection = ScriptedConnection(response)
            assertThrows(IOException::class.java) { LocalAdbClient(pair, connection).connect() }
            assertTrue(connection.closed)
        }
    }

    @Test fun shellAcknowledgesChunksAndReusesConnectionWithDifferentStreamIds() {
        val connection = ScriptedConnection(connected(VERSION + 1), Packet(OKAY, 8, 1),
            Packet(WRTE, 8, 1, "2000\n".toByteArray()), Packet(WRTE, 8, 1, "shell".toByteArray()),
            Packet(CLSE, 8, 1), Packet(OKAY, 9, 2), Packet(WRTE, 9, 2, "second".toByteArray()), Packet(CLSE, 9, 2))
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            assertEquals("2000\nshell", client.shell("id -u"))
            assertEquals("second", client.shell("getprop ro.product.model"))
            val sent = connection.sent()
            assertEquals(listOf(CNXN, OPEN, OKAY, OKAY, OPEN, OKAY), sent.map { it.command })
            assertEquals("shell:id -u\u0000", sent[1].payload.toString(Charsets.UTF_8))
            assertEquals(1, sent[2].arg0)
            assertEquals(8, sent[2].arg1)
            assertEquals(2, sent[4].arg0)
            assertEquals(15_000, connection.latestReadTimeout)
        }
    }

    @Test fun emptyOutputAndHistoricalZeroIdCloseAreSupported() {
        val connection = ScriptedConnection(connected(), Packet(OKAY, 8, 1), Packet(CLSE, 0, 1))
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            assertEquals("", client.shell("true"))
        }
    }

    @Test fun lateCloseFromCompletedStreamDoesNotBreakNextCommand() {
        // Observed on the TCL: after stream 2 completed, CLSE(2417,2) preceded stream 3's OKAY.
        val connection = ScriptedConnection(connected(), Packet(OKAY, 2416, 1), Packet(CLSE, 2416, 1),
            Packet(OKAY, 2417, 2), Packet(WRTE, 2417, 2, "second".toByteArray()), Packet(CLSE, 2417, 2),
            Packet(CLSE, 2417, 2), Packet(OKAY, 2418, 3),
            Packet(WRTE, 2418, 3, "third".toByteArray()), Packet(CLSE, 2418, 3))
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            assertEquals("", client.shell("true"))
            assertEquals("second", client.shell("id"))
            assertEquals("third", client.shell("getprop ro.product.model"))
        }
    }

    @Test fun lateDataAndAcknowledgementsNeverLeakIntoCurrentOutput() {
        val connection = ScriptedConnection(connected(), Packet(OKAY, 8, 1), Packet(CLSE, 8, 1),
            Packet(OKAY, 8, 1), Packet(WRTE, 8, 1, "old data".toByteArray()), Packet(OKAY, 9, 2),
            Packet(WRTE, 9, 2, "current".toByteArray()), Packet(WRTE, 8, 1, "more old data".toByteArray()),
            Packet(CLSE, 0, 1), Packet(CLSE, 9, 2))
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            assertEquals("", client.shell("true"))
            assertEquals("current", client.shell("id"))
            // The retired WRTE packets are ignored, not acknowledged or appended to the output.
            assertEquals(1, connection.sent().count { it.command == OKAY })
        }
    }

    @Test fun unknownStreamsWrongRetiredPeerAndMalformedRetiredControlsStillFail() {
        for (packet in listOf(Packet(CLSE, 10, 77), Packet(CLSE, 99, 1),
            Packet(CLSE, 8, 1, byteArrayOf(1)), Packet(OKAY, 8, 1, byteArrayOf(1)))) {
            val connection = ScriptedConnection(connected(), Packet(OKAY, 8, 1), Packet(CLSE, 8, 1),
                packet, Packet(OKAY, 9, 2), Packet(CLSE, 9, 2))
            val client = LocalAdbClient(pair, connection)
            client.connect()
            assertEquals("", client.shell("true"))
            assertThrows(IOException::class.java) { client.shell("id") }
            assertTrue(connection.closed)
        }
    }

    @Test fun latePacketFloodIsBoundedEvenWhenEveryPacketIsOtherwiseValid() {
        val packets = listOf(connected(), Packet(OKAY, 8, 1), Packet(CLSE, 8, 1)) +
            List(65) { Packet(CLSE, 8, 1) } + listOf(Packet(OKAY, 9, 2), Packet(CLSE, 9, 2))
        val connection = ScriptedConnection(*packets.toTypedArray())
        val client = LocalAdbClient(pair, connection)
        client.connect()
        assertEquals("", client.shell("true"))
        val error = assertThrows(IOException::class.java) { client.shell("id") }
        assertTrue(error.message!!.contains("Too many late"))
        assertTrue(connection.closed)
    }

    @Test fun deniedShellDoesNotReportSuccess() {
        val connection = ScriptedConnection(connected(), Packet(CLSE, 0, 1))
        val client = LocalAdbClient(pair, connection)
        client.connect()
        assertThrows(IOException::class.java) { client.shell("id") }
        assertTrue(connection.closed)
    }

    @Test fun mismatchedStreamsAndUnexpectedPacketsCloseConnection() {
        for (packet in listOf(Packet(WRTE, 9, 1), Packet(WRTE, 8, 2), Packet(OKAY, 8, 1),
            Packet(CLSE, 8, 1, byteArrayOf(1)), Packet(CNXN, VERSION, MAX_PAYLOAD))) {
            val connection = ScriptedConnection(connected(), Packet(OKAY, 8, 1), packet)
            val client = LocalAdbClient(pair, connection)
            client.connect()
            assertThrows(IOException::class.java) { client.shell("id") }
            assertTrue(connection.closed)
        }
    }

    @Test fun outputLimitFailsInsteadOfReturningTruncatedSuccess() {
        val connection = ScriptedConnection(connected(), Packet(OKAY, 8, 1),
            Packet(WRTE, 8, 1, "1234".toByteArray()), Packet(WRTE, 8, 1, "56".toByteArray()), Packet(CLSE, 8, 1))
        val client = LocalAdbClient(pair, connection, LocalAdbClient.Limits(outputBytes = 5))
        client.connect()
        assertThrows(IOException::class.java) { client.shell("id") }
        assertTrue(connection.closed)
        assertEquals(1, connection.sent().count { it.command == OKAY })
    }

    @Test fun invalidCommandsCannotWriteAnOpenPacket() {
        val connection = ScriptedConnection(connected())
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            for (command in listOf("", " ", "id\u0000reboot", "id\nreboot", "id\rreboot", "x".repeat(4096))) {
                assertThrows(IllegalArgumentException::class.java) { client.shell(command) }
            }
            assertEquals(listOf(CNXN), connection.sent().map { it.command })
        }
    }

    @Test fun explicitCloseInterruptsBlockedReadWithoutWaitingForClientLock() {
        val connection = BlockingConnection()
        val client = LocalAdbClient(pair, connection)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Exception?> { try { client.connect(); null } catch (error: Exception) { error } }
            assertTrue(connection.waiting.await(1, TimeUnit.SECONDS))
            client.close()
            assertTrue(result.get(1, TimeUnit.SECONDS) is IOException)
            client.close()
        } finally { client.close(); executor.shutdownNow() }
    }

    @Test fun deadlineInterruptsBlockedReadAndWrite() {
        for (blockWrite in listOf(false, true)) {
            val connection = BlockingConnection(blockWrite)
            val client = LocalAdbClient(pair, connection, LocalAdbClient.Limits(connectMs = 25, authorizationMs = 25))
            assertThrows(SocketTimeoutException::class.java) { client.connect() }
            assertEquals(0L, connection.closed.count)
            assertThrows(IOException::class.java) { client.connect() }
        }
    }

    @Test fun commandHasItsOwnDeadlineAfterSuccessfulConnect() {
        val blocked = BlockingConnection()
        val prefix = ByteArrayInputStream(encodePacket(connected()) + encodePacket(Packet(OKAY, 8, 1)))
        val connection = object : LocalAdbClient.Connection {
            override val input = object : InputStream() {
                override fun read(): Int = if (prefix.available() > 0) prefix.read() else blocked.input.read()
            }
            override val output = ByteArrayOutputStream()
            override fun connect(timeoutMs: Int) = Unit
            override fun readTimeout(timeoutMs: Int) = Unit
            override fun close() { blocked.close() }
        }
        val client = LocalAdbClient(pair, connection, LocalAdbClient.Limits(commandMs = 50))
        client.connect()
        assertThrows(SocketTimeoutException::class.java) { client.shell("id") }
        assertEquals(0L, blocked.closed.count)
    }

    @Test fun repeatedConnectDoesNotRestartAnAuthenticatedSession() {
        val connection = ScriptedConnection(connected())
        LocalAdbClient(pair, connection).use { client ->
            client.connect()
            client.connect()
            assertEquals(listOf(CNXN), connection.sent().map { it.command })
        }
    }

    @Test fun cancellationDuringApprovalDoesNotSendPublicKey() {
        val request = Packet(AUTH, 1, 0, ByteArray(20))
        val connection = ScriptedConnection(request, request, connected())
        val client = LocalAdbClient(pair, connection)
        assertThrows(IOException::class.java) { client.connect { client.close() } }
        assertEquals(listOf(CNXN, AUTH), connection.sent().map { it.command })
        assertTrue(connection.closed)
    }
}
