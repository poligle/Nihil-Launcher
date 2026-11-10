package com.nihilshell.launcher.deck

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The deck's trackpad on the wire (docs/deck.md in Nihil-Shell, The trackpad): a stream to
 * the computer while the trackpad shows, opened again a moment after it drops. The fingers
 * go as frames, each with its time; a keepalive every second, which the computer answers,
 * so each side knows the other is still there.
 */
class TrackpadLink(private val store: DeckStore, private val onState: (State) -> Unit) {

    enum class State { CONNECTING, READY, OLDER, ACCESS, REFUSED, UNREACHABLE, FORGOTTEN }

    /** The trackpad's size in units, [resolution] of them a millimetre. */
    data class Size(val width: Int, val height: Int, val resolution: Int)

    /** One connection, and when the computer was last heard on it. */
    private class Line(val stream: DeckClient.Stream) {
        @Volatile var heard = SystemClock.uptimeMillis()
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = LinkedBlockingQueue<ByteArray>(512)
    private val session = AtomicInteger()
    private var size: Size? = null
    private var thread: Thread? = null
    @Volatile private var line: Line? = null

    /** Streams with this [size] until [stop]; with another size, from the start again. */
    fun start(size: Size) {
        if (thread != null && this.size == size) return
        stop()
        this.size = size
        val id = session.incrementAndGet()
        thread = Thread({ run(id, size) }, "nihil-trackpad").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        session.incrementAndGet()
        thread?.interrupt()
        thread = null
        line?.stream?.close()
        line = null
        queue.clear()
    }

    /** A frame: [count] fingers touching at [time] (ms), their ids and where, in units. */
    fun frame(time: Long, count: Int, ids: IntArray, xs: IntArray, ys: IntArray) {
        if (line == null) return
        val frame = ByteBuffer.allocate(6 + 5 * count)
        frame.put(TOUCH).putInt(time.toInt()).put(count.toByte())
        for (i in 0 until count) frame.put(ids[i].toByte()).putShort(xs[i].toShort()).putShort(ys[i].toShort())
        queue.offer(frame.array())
    }

    private fun active(id: Int) = session.get() == id

    private fun run(id: Int, size: Size) {
        var wait = 500L
        while (active(id)) {
            val computer = store.computer ?: return post(id, State.FORGOTTEN)
            post(id, State.CONNECTING)
            val (opened, answer) = DeckClient(computer, store.token())
                .upgrade("/v1/trackpad?w=${size.width}&h=${size.height}&r=${size.resolution}", PROTOCOL)
            store.save(computer)
            if (opened == null) {
                val state = when {
                    answer == null -> State.UNREACHABLE
                    answer.status == 401 -> State.FORGOTTEN
                    answer.status == 404 -> State.OLDER
                    answer.status == 503 && answer.error == "access" -> State.ACCESS
                    else -> State.REFUSED
                }
                post(id, state)
                if (state == State.FORGOTTEN || state == State.OLDER || !pause(wait)) return
                wait = (wait * 2).coerceAtMost(8000L)
                continue
            }
            wait = 500L
            val current = Line(opened)
            if (!active(id)) {
                opened.close()
                return
            }
            queue.clear()
            line = current
            post(id, State.READY)
            val listener = Thread({ listen(current) }, "nihil-trackpad-answers").apply {
                isDaemon = true
                start()
            }
            try {
                write(id, current)
            } catch (e: IOException) {
                // Dropped: again in a moment.
            } catch (e: InterruptedException) {
                return
            } finally {
                if (line === current) line = null
                opened.close()
                listener.interrupt()
            }
            if (!pause(300)) return
        }
    }

    /** The frames as they come, and a keepalive every second, until the stream breaks or stops. */
    private fun write(id: Int, current: Line) {
        val batch = ByteArrayOutputStream()
        var next = SystemClock.uptimeMillis() + KEEPALIVE_EVERY
        while (active(id)) {
            val first = queue.poll((next - SystemClock.uptimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            batch.reset()
            if (first != null) {
                batch.write(first)
                while (true) batch.write(queue.poll() ?: break)
            }
            val now = SystemClock.uptimeMillis()
            if (now >= next) {
                batch.write(ByteBuffer.allocate(5).put(KEEPALIVE).putInt(now.toInt()).array())
                next = now + KEEPALIVE_EVERY
            }
            if (batch.size() > 0) {
                current.stream.output.write(batch.toByteArray())
                current.stream.output.flush()
            }
            if (now - current.heard > QUIET) throw IOException("the computer went quiet")
        }
    }

    /** The computer's answers to the keepalives: each one, news that it is there. */
    private fun listen(current: Line) {
        runCatching {
            current.stream.socket.soTimeout = 0
            while (current.stream.input.read() >= 0) current.heard = SystemClock.uptimeMillis()
        }
        current.heard = 0L
    }

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    private fun post(id: Int, state: State) {
        main.post { if (active(id)) onState(state) }
    }

    companion object {
        const val PROTOCOL = "nihil-trackpad"
        private const val TOUCH: Byte = 1
        private const val KEEPALIVE: Byte = 2
        private const val KEEPALIVE_EVERY = 1000L
        private const val QUIET = 4000L
    }
}
