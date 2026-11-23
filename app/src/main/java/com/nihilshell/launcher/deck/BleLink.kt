package com.nihilshell.launcher.deck

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLEngine

/**
 * The computer over Bluetooth LE, for when the phone reaches none of its addresses: found by the
 * UUID its pin makes, and an LE channel (L2CAP) to it on the PSM it said, with nothing to pair in
 * the system's settings. On the channel go the same TLS, the pin checked, and the same HTTP as
 * over the network (Wire.kt); a channel left after an answer is kept a few seconds for the next
 * request, as a browser keeps a connection.
 */
object BleLink {
    val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    const val HOST = "nihil"

    private const val LOOK = 5000L          // to find the computer, which announces itself every second or so
    private const val CONNECT = 6000L       // for a channel to open
    private const val HANDSHAKE = 9000L
    private const val AGAIN = 20_000L       // after the computer wasn't found or didn't open, before trying again
    private const val LATELY = 30_000L      // Bluetooth first, for this long after it answered where the network didn't
    private const val KEEP = 6000L          // a channel kept for the next request: the computer lets one go at 10 s
    private const val SPARE = 2

    private class Seen(val uuid: UUID, val device: BluetoothDevice, val at: Long)

    private class Spare(val pin: String, val pipe: TlsPipe, val at: Long)

    class Line(val pipe: TlsPipe, val kept: Boolean)

    @Volatile
    private var app: Context? = null

    @Volatile
    private var seen: Seen? = null

    @Volatile
    private var missed = 0L

    @Volatile
    private var answered = 0L

    private val looking = Any()
    private val spares = ArrayDeque<Spare>()
    private val clock = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "nihil-ble").apply { isDaemon = true } }

    fun attach(context: Context) {
        if (app == null) app = context.applicationContext
    }

    /**
     * The UUID the computer announces itself by: the first 16 bytes of SHA-256 over "nihil-deck ble",
     * a zero byte and the pin, with the version (8) and the variant set as RFC 9562 has them.
     */
    fun uuid(pin: String): UUID {
        val hash = MessageDigest.getInstance("SHA-256").digest("nihil-deck ble\u0000$pin".toByteArray(Charsets.UTF_8))
        hash[6] = ((hash[6].toInt() and 0x0F) or 0x80).toByte()
        hash[8] = ((hash[8].toInt() and 0x3F) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(hash, 0, 16)
        return UUID(bytes.long, bytes.long)
    }

    /** The PSM in what the computer says of its Bluetooth ({"uuid", "psm"}), if it is this pin's. */
    fun psm(ble: JSONObject?, pin: String): Int? {
        if (ble == null) return null
        return ble.optInt("psm", 0).takeIf { it in 0x80..0xFF && ble.optString("uuid") == uuid(pin).toString() }
    }

    /**
     * Whether this phone has the channel and the asking for it: an L2CAP one came in Android 10, and
     * BLUETOOTH_SCAN and BLUETOOTH_CONNECT in Android 12, where a scan asks for no location. Below
     * 12 the deck goes over the network only, as it did before Bluetooth.
     */
    val available: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun allowed(context: Context): Boolean =
        available && PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Whether Bluetooth answered lately where the network didn't: then it goes first. */
    fun lately(): Boolean = SystemClock.elapsedRealtime() - answered < LATELY

    fun answered() {
        answered = SystemClock.elapsedRealtime()
    }

    /** A line to the computer: a channel kept from the last answer, or a new one with TLS on it. */
    fun line(pin: String, psm: Int, engine: () -> SSLEngine): Line? {
        kept(pin)?.let { return Line(it, true) }
        return fresh(pin, psm, engine)?.let { Line(it, false) }
    }

    /** Keeps a channel whose answer was read whole for the next request, a few seconds. */
    fun keep(pin: String, pipe: TlsPipe) {
        if (pipe.closed) return
        pipe.idle(KEEP)
        synchronized(spares) {
            spares.addLast(Spare(pin, pipe, SystemClock.elapsedRealtime()))
            while (spares.size > SPARE) spares.pollFirst()?.pipe?.close()
        }
    }

    private fun kept(pin: String): TlsPipe? {
        synchronized(spares) {
            val now = SystemClock.elapsedRealtime()
            while (true) {
                val spare = spares.pollLast() ?: return null
                if (spare.pin == pin && !spare.pipe.closed && now - spare.at < KEEP - 1000) return spare.pipe
                spare.pipe.close()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fresh(pin: String, psm: Int, engine: () -> SSLEngine): TlsPipe? {
        val context = app ?: return null
        if (!allowed(context) || SystemClock.elapsedRealtime() - missed < AGAIN) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val uuid = uuid(pin)
        val known = seen?.takeIf { it.uuid == uuid }?.device
        val socket = known?.let { channel(it, psm) } ?: find(adapter, uuid)?.let { channel(it, psm) }
        if (socket == null) {
            missed = SystemClock.elapsedRealtime()
            return null
        }
        val pipe = TlsPipe(engine(), socket.inputStream, socket.outputStream) { socket.close() }
        return try {
            pipe.idle(HANDSHAKE)
            pipe.handshake()
            pipe
        } catch (e: Exception) {
            pipe.close()
            missed = SystemClock.elapsedRealtime()
            null
        }
    }

    /** Looks for the computer's advertisement, one look at a time: Android allows an app only a few a minute. */
    @SuppressLint("MissingPermission")
    private fun find(adapter: BluetoothAdapter, uuid: UUID): BluetoothDevice? {
        synchronized(looking) {
            seen?.takeIf { it.uuid == uuid && SystemClock.elapsedRealtime() - it.at < LOOK }?.let { return it.device }
            val scanner = adapter.bluetoothLeScanner ?: return null
            val found = AtomicReference<BluetoothDevice?>()
            val done = CountDownLatch(1)
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    found.compareAndSet(null, result.device)
                    done.countDown()
                }

                override fun onScanFailed(errorCode: Int) = done.countDown()
            }
            val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid)).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(listOf(filter), settings, callback)
            } catch (e: RuntimeException) {
                return null
            }
            done.await(LOOK, TimeUnit.MILLISECONDS)
            runCatching { scanner.stopScan(callback) }
            val device = found.get() ?: return null
            seen = Seen(uuid, device, SystemClock.elapsedRealtime())
            return device
        }
    }

    /** An LE channel to the device; insecure, as Android calls one with no bonding: TLS is the check. */
    @SuppressLint("MissingPermission")
    private fun channel(device: BluetoothDevice, psm: Int): BluetoothSocket? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val socket = try {
            device.createInsecureL2capChannel(psm)
        } catch (e: Exception) {
            return null
        }
        val stop = clock.schedule({ runCatching { socket.close() } }, CONNECT, TimeUnit.MILLISECONDS)
        return try {
            socket.connect()
            socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            null
        } finally {
            stop.cancel(false)
        }
    }
}
