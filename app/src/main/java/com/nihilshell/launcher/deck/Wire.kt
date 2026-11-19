package com.nihilshell.launcher.deck

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.Status

/**
 * TLS on a channel's two streams, for Bluetooth: its channel is no socket an SSLSocketFactory
 * could take, so an SSLEngine goes between them. One thread may read while another writes, as
 * the trackpad does. Plain Kotlin, as the rest of this file, so it is tested on the computer.
 */
class TlsPipe(
    private val engine: SSLEngine,
    private val from: InputStream,
    private val to: OutputStream,
    private val shut: () -> Unit,
) {
    private val reading = Any()
    private val writing = Any()
    private var sealedIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize).apply { flip() }
    private var plain: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize).apply { flip() }
    private var sealedOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var ended = false
    private var watch: ScheduledFuture<*>? = null

    @Volatile
    var closed = false
        private set

    @Volatile
    private var moved = System.nanoTime()

    @Volatile
    private var idle = 0L

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            while (true) {
                val count = read(one, 0, 1)
                if (count < 0) return -1
                if (count == 1) return one[0].toInt() and 0xFF
            }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = take(b, off, len)

        override fun available(): Int = synchronized(reading) { plain.remaining() }

        override fun close() = this@TlsPipe.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(writing) {
                if (closed) throw IOException("closed")
                guarded { seal(ByteBuffer.wrap(b, off, len)) }
            }
        }

        override fun flush() = to.flush()

        override fun close() = this@TlsPipe.close()
    }

    fun handshake() = guarded {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                HandshakeStatus.NEED_WRAP -> synchronized(writing) { seal(ByteBuffer.allocate(0)) }
                HandshakeStatus.NEED_TASK -> tasks()
                HandshakeStatus.NOT_HANDSHAKING, HandshakeStatus.FINISHED -> break
                else -> synchronized(reading) { if (!open(true)) throw IOException("closed in the handshake") }
            }
        }
    }

    /** Closes the pipe once nothing has gone either way for millis; 0, never. */
    fun idle(millis: Long) {
        synchronized(this) {
            moved = System.nanoTime()
            idle = millis
            if (millis > 0 && watch == null && !closed) {
                watch = CLOCK.scheduleWithFixedDelay({
                    if (idle > 0 && System.nanoTime() - moved > TimeUnit.MILLISECONDS.toNanos(idle)) close()
                }, WATCH, WATCH, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            watch?.cancel(false)
            watch = null
        }
        runCatching { shut() }
    }

    /** What has come, or -1 once the other side has closed; closed here, an IOException, however the channel ended. */
    private fun take(b: ByteArray, off: Int, len: Int): Int = guarded {
        synchronized(reading) {
            if (len == 0) return@guarded 0
            while (!plain.hasRemaining()) {
                if (closed) throw IOException("closed")
                if (!open(false)) {
                    if (closed) throw IOException("closed")
                    return@guarded -1
                }
            }
            val count = minOf(len, plain.remaining())
            plain.get(b, off, count)
            count
        }
    }

    /** What the engine throws besides an IOException closes the pipe as one would: the trackpad and the requests catch those. */
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        close()
        throw IOException(e)
    }

    /**
     * Opens what has come, reading more while a record isn't whole, until there is something to
     * read (in the handshake, until a record is taken). False once the other side has closed.
     */
    private fun open(handshaking: Boolean): Boolean {
        while (true) {
            if (sealedIn.hasRemaining()) {
                plain.compact()
                val result: SSLEngineResult
                try {
                    result = engine.unwrap(sealedIn, plain)
                } finally {
                    plain.flip()
                }
                when (result.status) {
                    Status.BUFFER_OVERFLOW -> {
                        plain = grown(plain, engine.session.applicationBufferSize)
                        continue
                    }
                    Status.CLOSED -> {
                        ended = true
                        return plain.hasRemaining()
                    }
                    Status.OK -> {
                        answer(result.handshakeStatus)
                        if (plain.hasRemaining()) return true
                        if (handshaking && (result.bytesConsumed() > 0 || engine.handshakeStatus != HandshakeStatus.NEED_UNWRAP)) return true
                        if (result.bytesConsumed() > 0) continue
                    }
                    else -> Unit // BUFFER_UNDERFLOW: the rest of the record is still to come
                }
            }
            if (!fill()) return false
        }
    }

    /** After an unwrap: the checks it handed over run, and what the handshake has to say goes. */
    private fun answer(status: HandshakeStatus) {
        var now = status
        if (now == HandshakeStatus.NEED_TASK) {
            tasks()
            now = engine.handshakeStatus
        }
        if (now == HandshakeStatus.NEED_WRAP) synchronized(writing) { seal(ByteBuffer.allocate(0)) }
    }

    /** Reads what more has come from the channel; false at its end. */
    private fun fill(): Boolean {
        if (ended) return false
        if (sealedIn.position() == 0 && sealedIn.limit() == sealedIn.capacity()) sealedIn = grown(sealedIn, engine.session.packetBufferSize)
        sealedIn.compact()
        var count = -1
        try {
            count = from.read(sealedIn.array(), sealedIn.arrayOffset() + sealedIn.position(), sealedIn.remaining())
            if (count > 0) sealedIn.position(sealedIn.position() + count)
        } finally {
            sealedIn.flip()
        }
        if (count < 0) ended = true else moved = System.nanoTime()
        return count > 0
    }

    /** Seals and sends all of src; with src empty, what the handshake has to say. */
    private fun seal(src: ByteBuffer) {
        while (true) {
            sealedOut.clear()
            val result = engine.wrap(src, sealedOut)
            if (result.status == Status.BUFFER_OVERFLOW) {
                sealedOut = ByteBuffer.allocate(maxOf(sealedOut.capacity() * 2, engine.session.packetBufferSize))
                continue
            }
            sealedOut.flip()
            if (sealedOut.hasRemaining()) {
                to.write(sealedOut.array(), sealedOut.arrayOffset() + sealedOut.position(), sealedOut.remaining())
                moved = System.nanoTime()
            }
            if (result.status == Status.CLOSED) throw IOException("closed")
            if (result.handshakeStatus == HandshakeStatus.NEED_TASK) tasks()
            if (src.hasRemaining()) {
                if (result.bytesConsumed() == 0 && result.bytesProduced() == 0) throw IOException("TLS is waiting for what hasn't come")
                continue
            }
            if (result.bytesProduced() > 0 && engine.handshakeStatus == HandshakeStatus.NEED_WRAP) continue
            return
        }
    }

    private fun tasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    private fun grown(buffer: ByteBuffer, size: Int): ByteBuffer =
        ByteBuffer.allocate(maxOf(size, buffer.capacity() * 2)).apply {
            put(buffer)
            flip()
        }

    private companion object {
        const val WATCH = 1000L

        val CLOCK = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "nihil-tls-watch").apply { isDaemon = true } }
    }
}

/**
 * HTTP/1.1 by hand, for a stream already private: Bluetooth's and the trackpad's upgrade, where
 * HttpsURLConnection can't go. The computer says how long every body is (Content-Length), so a
 * connection can carry the next request once the answer is read.
 */
object Http {
    const val MAX_HEAD = 64
    const val MAX_LINE = 8192

    class Head(val status: Int, val headers: Map<String, String>) {
        val type: String? get() = headers["content-type"]

        val length: Long? get() = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0 }

        /** Whether the connection may carry another request after this answer. */
        val reusable: Boolean
            get() = length != null && status != 101 && headers["connection"]?.lowercase()?.contains("close") != true
    }

    class TooBig : IOException("too big")

    fun request(output: OutputStream, method: String, path: String, host: String, fields: List<Pair<String, String>>) {
        val head = StringBuilder().append(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(host).append("\r\n")
        for ((name, value) in fields) head.append(name).append(": ").append(value).append("\r\n")
        output.write(head.append("\r\n").toString().toByteArray(Charsets.UTF_8))
    }

    fun head(input: InputStream): Head {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) throw IOException("closed")
            if (c == '\n'.code) {
                val text = line.toString().trimEnd('\r')
                if (text.isEmpty()) {
                    if (lines.isEmpty()) continue
                    break
                }
                lines += text
                line.setLength(0)
                if (lines.size > MAX_HEAD) throw IOException("too long")
            } else {
                line.append(c.toChar())
                if (line.length > MAX_LINE) throw IOException("too long")
            }
        }
        val first = lines.first().split(' ')
        val status = first.getOrNull(1)?.toIntOrNull()?.takeIf { first[0].startsWith("HTTP/") } ?: throw IOException("not HTTP")
        val headers = lines.drop(1).mapNotNull { header ->
            header.split(':', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim().lowercase() to it[1].trim() }
        }.toMap()
        return Head(status, headers)
    }

    /** The body, as long as the head says, or to the end without one; no more than max bytes. */
    fun body(input: InputStream, head: Head, max: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        copy(input, head, bytes, max)
        return bytes.toByteArray()
    }

    fun copy(input: InputStream, head: Head, output: OutputStream, max: Long): Long {
        if (head.status == 101 || head.status == 204 || head.status == 304) return 0
        val length = head.length
        if (length != null && length > max) throw TooBig()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (length == null || total < length) {
            val want = if (length == null) buffer.size else minOf(buffer.size.toLong(), length - total).toInt()
            val count = input.read(buffer, 0, want)
            if (count < 0) {
                if (length == null) break
                throw IOException("short")
            }
            total += count
            if (total > max) throw TooBig()
            output.write(buffer, 0, count)
        }
        return total
    }
}
