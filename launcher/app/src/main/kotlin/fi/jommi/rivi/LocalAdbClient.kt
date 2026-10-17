package fi.jommi.rivi

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A single authenticated connection to this TV's existing local debugging service.
 * No endpoint, forwarding, file transfer, pairing, or debugging-enablement API is exposed.
 * Call off the main thread; close() may be called from another thread to cancel I/O.
 * Callers must supply only their own validated commands and verify the resulting state:
 * the legacy shell protocol returns combined output, not an exit status.
 *
 * Wire formats checked against AOSP system/core, android-10.0.0_r1:
 * adb/protocol.txt, adb/client/auth.cpp, adb/adb.cpp, libcrypto_utils/android_pubkey.c.
 */
internal class LocalAdbClient(
    private val keyPair: KeyPair,
    private val connection: Connection = LoopbackConnection(),
    private val limits: Limits = Limits(),
) : Closeable {
    internal data class Limits(
        val connectMs: Int = 2_000,
        val authorizationMs: Int = 45_000,
        val commandMs: Int = 15_000,
        val outputBytes: Int = 1_048_576,
    ) {
        init {
            require(connectMs > 0 && authorizationMs > 0 && commandMs > 0)
            require(outputBytes in 1..1_048_576)
        }
    }

    /** Stream seam for protocol/failure tests; production always uses [LoopbackConnection]. */
    internal interface Connection : Closeable {
        val input: InputStream
        val output: OutputStream
        fun connect(timeoutMs: Int)
        fun readTimeout(timeoutMs: Int)
    }

    private class LoopbackConnection : Connection {
        private val socket = Socket()
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun connect(timeoutMs: Int) {
            // A literal address prevents DNS lookup or connection to a different device.
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            socket.connect(InetSocketAddress(loopback, 5555), timeoutMs)
            socket.tcpNoDelay = true
        }
        override fun readTimeout(timeoutMs: Int) { socket.soTimeout = timeoutMs }
        override fun close() { socket.close() }
    }

    private val closed = AtomicBoolean(false)
    private val deadlineExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "prettTV-local-recovery-timeout").apply { isDaemon = true }
    }
    private var connected = false
    private var nextStreamId = 1
    private var outgoingLimit = MAX_PAYLOAD
    private val completedStreams = LinkedHashMap<Int, Int>()

    init {
        val publicKey = keyPair.public as? RSAPublicKey
            ?: throw IllegalArgumentException("Local debugging needs an RSA key")
        val privateKey = keyPair.private as? RSAPrivateKey
            ?: throw IllegalArgumentException("Local debugging needs an RSA private key")
        require(publicKey.modulus.bitLength() == 2048 && publicKey.publicExponent == EXPONENT) {
            "Local debugging needs a 2048-bit RSA key with exponent 65537"
        }
        require(publicKey.modulus == privateKey.modulus) { "Local debugging key pair does not match" }
    }

    /** May invoke [onAuthorizing] once, on the calling worker thread, before Android asks approval. */
    @Synchronized
    fun connect(onAuthorizing: () -> Unit = {}) {
        ensureOpen()
        if (connected) return
        bounded(limits.connectMs.toLong() + limits.authorizationMs) {
            connection.connect(limits.connectMs)
            connection.readTimeout(limits.authorizationMs)
            send(Packet(CNXN, VERSION, MAX_PAYLOAD, "host::prettTV\u0000".toByteArray(Charsets.UTF_8)))
            var authReplies = 0
            while (true) {
                val packet = receive()
                when (packet.command) {
                    AUTH -> {
                        protocol(packet.arg0 == 1 && packet.arg1 == 0 && packet.payload.size == 20,
                            "Invalid local debugging authorization request")
                        when (authReplies++) {
                            0 -> send(Packet(AUTH, 2, 0, signToken(keyPair, packet.payload)))
                            1 -> {
                                onAuthorizing()
                                ensureOpen()
                                send(Packet(AUTH, 3, 0, publicKeyPayload(keyPair.public as RSAPublicKey)))
                            }
                            else -> throw IOException("Local debugging authorization was not accepted")
                        }
                    }
                    CNXN -> {
                        protocol(packet.arg0 in VERSION..VERSION_WITHOUT_CHECKSUM && packet.arg1 >= MAX_PAYLOAD,
                            "Unsupported local debugging protocol")
                        protocol(packet.payload.toString(Charsets.UTF_8).startsWith("device::"),
                            "Local debugging is not in normal TV mode")
                        outgoingLimit = minOf(packet.arg1, MAX_PAYLOAD)
                        connected = true
                        return@bounded
                    }
                    else -> throw IOException("Unexpected local debugging handshake")
                }
            }
        }
    }

    /** Sequential, bounded legacy shell command. Does not interpret output as an exit code. */
    @Synchronized
    fun shell(command: String): String {
        ensureOpen()
        check(connected) { "Local debugging is not connected" }
        require(command.isNotBlank() && command.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Invalid recovery command"
        }
        val destination = "shell:$command\u0000".toByteArray(Charsets.UTF_8)
        require(destination.size <= outgoingLimit) { "Recovery command is too long" }
        return bounded(limits.commandMs.toLong()) {
            connection.readTimeout(limits.commandMs)
            val localId = nextStreamId++
            if (nextStreamId <= 0) nextStreamId = 1
            completedStreams.remove(localId)
            var ignoredPackets = 0
            fun nextPacket(): Packet {
                while (true) {
                    val packet = receive()
                    if (packet.arg1 == localId) return packet
                    val completedRemote = completedStreams[packet.arg1]
                    val isLatePacket = completedRemote != null &&
                        (packet.arg0 == completedRemote || (packet.command == CLSE && packet.arg0 == 0)) &&
                        (packet.command == WRTE ||
                            ((packet.command == CLSE || packet.command == OKAY) && packet.payload.isEmpty()))
                    if (!isLatePacket) return packet
                    // AOSP explicitly permits in-flight packets addressed to already closed streams.
                    // Ignore only a stream pair we completed, without accepting its data as this reply.
                    protocol(++ignoredPackets <= MAX_LATE_PACKETS, "Too many late recovery shell packets")
                }
            }
            send(Packet(OPEN, localId, 0, destination))
            val first = nextPacket()
            if (first.command == CLSE && first.arg0 == 0 && first.arg1 == localId && first.payload.isEmpty()) {
                throw IOException("Local debugging refused the recovery shell")
            }
            protocol(first.command == OKAY && first.arg0 != 0 && first.arg1 == localId && first.payload.isEmpty(),
                "Invalid recovery shell response: expected OKAY for stream $localId; ${headerDescription(first)}")
            val remoteId = first.arg0
            val output = ByteArrayOutputStream()
            while (true) {
                val packet = nextPacket()
                protocol(packet.arg1 == localId && (packet.arg0 == remoteId ||
                    (packet.command == CLSE && packet.arg0 == 0)),
                    "Mismatched recovery shell stream: expected $remoteId/$localId; ${headerDescription(packet)}")
                when (packet.command) {
                    WRTE -> {
                        protocol(packet.payload.size <= limits.outputBytes - output.size(),
                            "Recovery shell output exceeded the limit")
                        output.write(packet.payload)
                        send(Packet(OKAY, localId, remoteId))
                    }
                    CLSE -> {
                        protocol(packet.payload.isEmpty(), "Invalid recovery shell close")
                        completedStreams[localId] = remoteId
                        if (completedStreams.size > MAX_COMPLETED_STREAMS) {
                            completedStreams.remove(completedStreams.keys.first())
                        }
                        return@bounded output.toString(Charsets.UTF_8.name())
                    }
                    else -> throw IOException("Unexpected recovery shell packet")
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable")
        }
    }

    /** No lock: cancelling must interrupt a blocked connect, read, or write. */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { connection.close() } catch (_: IOException) { /* Already disconnected. */ }
            deadlineExecutor.shutdownNow()
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IOException("Local debugging connection is closed")
    }

    private fun <T> bounded(timeoutMs: Long, block: () -> T): T {
        ensureOpen()
        val expired = AtomicBoolean(false)
        val completed = AtomicBoolean(false)
        val timeout = try {
            deadlineExecutor.schedule({
                if (completed.compareAndSet(false, true)) {
                    expired.set(true)
                    close()
                }
            }, timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: RejectedExecutionException) {
            // close() can win immediately after ensureOpen(), before scheduling the deadline.
            throw IOException("Local debugging connection is closed", error)
        }
        try {
            val result = block()
            if (!completed.compareAndSet(false, true) || expired.get()) {
                throw SocketTimeoutException("Local debugging timed out")
            }
            ensureOpen()
            return result
        } catch (error: Exception) {
            completed.set(true)
            close()
            if (expired.get()) throw SocketTimeoutException("Local debugging timed out").apply { initCause(error) }
            throw error
        } finally {
            timeout.cancel(false)
        }
    }

    private fun send(packet: Packet) {
        ensureOpen()
        connection.output.write(encodePacket(packet))
        connection.output.flush()
    }

    private fun receive(): Packet {
        ensureOpen()
        return readPacket(connection.input)
    }

    internal data class Packet(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray = byteArrayOf())

    companion object {
        internal const val CNXN = 0x4e584e43
        internal const val AUTH = 0x48545541
        internal const val OPEN = 0x4e45504f
        internal const val OKAY = 0x59414b4f
        internal const val CLSE = 0x45534c43
        internal const val WRTE = 0x45545257
        internal const val VERSION = 0x01000000
        private const val VERSION_WITHOUT_CHECKSUM = 0x01000001
        internal const val MAX_PAYLOAD = 4096
        private const val MAX_COMPLETED_STREAMS = 64
        private const val MAX_LATE_PACKETS = 64
        private val EXPONENT = BigInteger.valueOf(65537)
        // RSA_sign(NID_sha1, token) signs this digest envelope; it must not hash the token again.
        private val SHA1_DIGEST_INFO = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05,
            0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSAKeyGenParameterSpec(2048, EXPONENT))
        }.generateKeyPair()

        internal fun signToken(pair: KeyPair, token: ByteArray): ByteArray {
            require(token.size == 20) { "Invalid local debugging token" }
            return Signature.getInstance("NONEwithRSA").run {
                initSign(pair.private)
                update(SHA1_DIGEST_INFO)
                update(token)
                sign()
            }
        }

        internal fun publicKeyPayload(key: RSAPublicKey): ByteArray {
            require(key.modulus.bitLength() == 2048 && key.publicExponent == EXPONENT)
            val wordModulus = BigInteger.ONE.shiftLeft(32)
            val n0inv = wordModulus.subtract(key.modulus.mod(wordModulus).modInverse(wordModulus))
            val rr = BigInteger.ONE.shiftLeft(4096).mod(key.modulus)
            val encoded = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(64).putInt(n0inv.toInt())
                .put(littleEndian256(key.modulus)).put(littleEndian256(rr))
                .putInt(key.publicExponent.toInt()).array()
            return (Base64.getEncoder().encodeToString(encoded) + " prettTV@localhost\u0000")
                .toByteArray(Charsets.US_ASCII)
        }

        private fun littleEndian256(value: BigInteger): ByteArray {
            val bigEndian = value.toByteArray()
            return ByteArray(256) { i -> if (i < bigEndian.size) bigEndian[bigEndian.lastIndex - i] else 0 }
        }

        internal fun encodePacket(packet: Packet): ByteArray {
            require(packet.payload.size <= MAX_PAYLOAD)
            return ByteBuffer.allocate(24 + packet.payload.size).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(packet.command).putInt(packet.arg0).putInt(packet.arg1)
                .putInt(packet.payload.size).putInt(checksum(packet.payload)).putInt(packet.command.inv())
                .put(packet.payload).array()
        }

        internal fun readPacket(input: InputStream): Packet {
            val header = ByteBuffer.wrap(readExactly(input, 24)).order(ByteOrder.LITTLE_ENDIAN)
            val command = header.int
            val arg0 = header.int
            val arg1 = header.int
            val length = header.int
            val checksum = header.int
            val magic = header.int
            protocol(command == CNXN || command == AUTH || command == OPEN || command == OKAY ||
                command == CLSE || command == WRTE, "Unknown local debugging packet")
            protocol(magic == command.inv(), "Invalid local debugging packet header")
            protocol(length in 0..MAX_PAYLOAD, "Invalid local debugging packet size")
            val payload = readExactly(input, length)
            // We negotiate v1, so checksum checking stays enabled even for newer adbd versions.
            protocol(checksum == checksum(payload), "Corrupt local debugging packet")
            return Packet(command, arg0, arg1, payload)
        }

        private fun checksum(bytes: ByteArray): Int = bytes.fold(0) { sum, byte -> sum + (byte.toInt() and 0xff) }

        /** Header only: command output can contain private TV/account information. */
        private fun headerDescription(packet: Packet): String {
            val name = when (packet.command) {
                CNXN -> "CNXN"; AUTH -> "AUTH"; OPEN -> "OPEN"
                OKAY -> "OKAY"; CLSE -> "CLSE"; WRTE -> "WRTE"
                else -> "unknown"
            }
            return "received $name(${packet.arg0},${packet.arg1},${packet.payload.size} bytes)"
        }

        private fun readExactly(input: InputStream, count: Int): ByteArray {
            val bytes = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(bytes, offset, count - offset)
                if (read < 0) throw EOFException("Local debugging disconnected")
                if (read == 0) throw IOException("Local debugging returned an empty read")
                offset += read
            }
            return bytes
        }

        private fun protocol(valid: Boolean, message: String) { if (!valid) throw IOException(message) }
    }
}
