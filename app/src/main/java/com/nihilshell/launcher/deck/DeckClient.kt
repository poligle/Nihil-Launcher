package com.nihilshell.launcher.deck

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class DeckClient(private val computer: Computer, private val token: String?) {
    class TooBig : IOException("too big")

    /** An answer; air, whether it came over Bluetooth. */
    class Reply(val status: Int, val type: String?, val bytes: ByteArray, val air: Boolean = false) {
        val json: JSONObject?
            get() = if (type?.startsWith("application/json") == true) runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() else null

        val error: String? get() = json?.optString("error")?.takeIf { it.isNotEmpty() }
    }

    fun call(method: String, path: String, body: JSONObject? = null, context: Context? = null): Reply? =
        each(context, { host -> once(host, computer.port, computer.pin, method, path, body, token) }) {
            air(computer.pin, computer.psm, token, method, path, body, CALL_IDLE)
        }

    fun listen(path: String, timeout: Int, context: Context? = null): Reply? =
        each(context, { host -> exchange(host, computer.port, computer.pin, token, "GET", path, timeout) }) {
            air(computer.pin, computer.psm, token, "GET", path, null, timeout.toLong())
        }

    fun download(path: String, into: File, max: Long): Reply? = each(null, { host ->
        exchange(host, computer.port, computer.pin, token, "GET", path, 30000) { connection, status ->
            if (status != 200) return@exchange read(connection, status)
            connection.inputStream.use { input -> into.outputStream().use { output -> copy(input, output, max) } }
            Reply(status, connection.contentType, ByteArray(0))
        }
    }) {
        air(computer.pin, computer.psm, fields(token, null, -1), "GET", path, 30000) { head, input ->
            if (head.status != 200) return@air Reply(head.status, head.type, Http.body(input, head, MAX_REPLY), true)
            into.outputStream().use { output -> Http.copy(input, head, output, max) }
            Reply(head.status, head.type, ByteArray(0), true)
        }
    }

    fun upload(path: String, type: String, name: String, file: File, context: Context? = null): Reply? = each(context, { host ->
        exchange(host, computer.port, computer.pin, token, "POST", path, 60000, send = { connection ->
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(file.length())
            connection.setRequestProperty("Content-Type", type)
            connection.setRequestProperty("X-Vector-Name", Uri.encode(name))
            connection.outputStream.use { output -> file.inputStream().use { it.copyTo(output, CHUNK) } }
        })
    }) {
        val fields = fields(token, type, file.length(), "X-Vector-Name" to Uri.encode(name))
        air(computer.pin, computer.psm, fields, "POST", path, 60000, send = { output -> file.inputStream().use { it.copyTo(output, CHUNK) } })
    }

    /**
     * Each of the computer's addresses until one answers, then the ones mDNS finds (with a
     * context); then Bluetooth, if the computer is on it. Bluetooth goes first for a while after
     * it answered where the network didn't, so each request doesn't wait for the network to fail.
     */
    private fun <T : Any> each(context: Context?, attempt: (String) -> T?, air: () -> T?): T? {
        val first = BleLink.lately()
        if (first) air()?.let { return it }
        val tried = LinkedHashSet<String>()
        for (host in listOfNotNull(computer.last) + computer.hosts) {
            if (!tried.add(host)) continue
            attempt(host)?.let {
                computer.last = host
                return it
            }
        }
        if (context != null) {
            for (host in discover(context)) {
                if (!tried.add(host)) continue
                attempt(host)?.let {
                    computer.last = host
                    return it
                }
            }
        }
        if (first) return null
        return air()?.also { BleLink.answered() }
    }

    /** A connection upgraded to another protocol, over the network or Bluetooth. */
    class Stream(val input: InputStream, val output: OutputStream, private val untime: () -> Unit, private val end: () -> Unit) {
        /** Reads wait as long as they take, from now on. */
        fun untimed() {
            runCatching { untime() }
        }

        fun close() {
            runCatching { end() }
        }
    }

    fun upgrade(path: String, protocol: String): Pair<Stream?, Reply?> {
        val first = BleLink.lately()
        if (first) upgradeAir(path, protocol)?.let { return it }
        for (host in (listOfNotNull(computer.last) + computer.hosts).distinct()) {
            val socket = runCatching {
                (tls(computer.pin).socketFactory.createSocket() as SSLSocket).apply {
                    connect(InetSocketAddress(host, computer.port), 2500)
                    soTimeout = 9000
                    tcpNoDelay = true
                    startHandshake()
                }
            }.getOrNull() ?: continue
            val answer = runCatching { ask(socket, host, path, protocol) }.getOrNull()
            if (answer == null) {
                runCatching { socket.close() }
                continue
            }
            computer.last = host
            if (answer.status == 101) return Stream(socket.inputStream, socket.outputStream, { socket.soTimeout = 0 }, socket::close) to null
            runCatching { socket.close() }
            return null to answer
        }
        if (first) return null to null
        return upgradeAir(path, protocol)?.also { BleLink.answered() } ?: (null to null)
    }

    private fun ask(socket: SSLSocket, host: String, path: String, protocol: String): Reply {
        val address = if (host.contains(':')) "[$host]" else host
        Http.request(socket.outputStream, "GET", path, "$address:${computer.port}", fields(token, null, -1, *upgrading(protocol)))
        socket.outputStream.flush()
        val head = Http.head(socket.inputStream)
        if (head.status == 101) return Reply(101, null, ByteArray(0))
        return Reply(head.status, head.type, Http.body(socket.inputStream, head, 65536))
    }

    private fun upgradeAir(path: String, protocol: String): Pair<Stream?, Reply?>? {
        val psm = computer.psm ?: return null
        val (pipe, head) = asked(computer.pin, psm, CALL_IDLE) { output ->
            Http.request(output, "GET", path, BleLink.HOST, fields(token, null, -1, *upgrading(protocol)))
        } ?: return null
        if (head.status == 101) return Stream(pipe.input, pipe.output, { pipe.idle(0) }, pipe::close) to null
        val answer = runCatching { Reply(head.status, head.type, Http.body(pipe.input, head, 65536), true) }.getOrNull()
        pipe.close()
        return answer?.let { null to it }
    }

    class Invitation(val name: String, val hosts: List<String>, val port: Int, val code: String, val pin: String, val psm: Int? = null) {
        companion object {
            private val CODE = Regex("[A-HJ-NP-Z2-9]{8}")
            private val PIN = Regex("[A-Za-z0-9_-]{43}")

            fun parse(text: String): Invitation? = runCatching {
                val uri = Uri.parse(text.trim())
                if (uri.scheme != "nihil-deck" || uri.host != "pair" || uri.getQueryParameter("v") != "1") return null
                val hosts = uri.getQueryParameter("hosts").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val port = uri.getQueryParameter("port")?.toIntOrNull() ?: return null
                val code = uri.getQueryParameter("code").orEmpty()
                val pin = uri.getQueryParameter("pin").orEmpty()
                if (port !in 1..65535 || !CODE.matches(code) || !PIN.matches(pin)) return null
                // Over Bluetooth only when the UUID it announces is the one this pin makes.
                val psm = uri.getQueryParameter("psm")?.toIntOrNull()?.takeIf { it in 0x80..0xFF && uri.getQueryParameter("ble") == BleLink.uuid(pin).toString() }
                if (hosts.isEmpty() && psm == null) return null
                val name = uri.getQueryParameter("name").orEmpty().ifEmpty { hosts.firstOrNull() ?: "NIHIL" }
                Invitation(name, hosts, port, code, pin, psm)
            }.getOrNull()
        }
    }

    sealed class Pairing {
        class Paired(val computer: Computer, val token: String) : Pairing()

        class Failed(val reason: String) : Pairing()
    }

    companion object {
        const val SERVICE = "_nihil-deck._tcp"

        fun pair(invitation: Invitation, device: String): Pairing {
            val body = JSONObject().put("code", invitation.code).put("device", device)
            for (host in invitation.hosts) {
                val hello = once(host, invitation.port, invitation.pin, "GET", "/v1/hello", null, null) ?: continue
                if (hello.status != 200) continue
                val reply = once(host, invitation.port, invitation.pin, "POST", "/v1/pair", body, null)
                    ?: return Pairing.Failed("unreachable")
                return paired(invitation, reply, host)
            }
            val psm = invitation.psm ?: return Pairing.Failed("unreachable")
            val hello = air(invitation.pin, psm, null, "GET", "/v1/hello", null, CALL_IDLE)
            if (hello?.status != 200) return Pairing.Failed("unreachable")
            val reply = air(invitation.pin, psm, null, "POST", "/v1/pair", body, PAIR_IDLE) ?: return Pairing.Failed("unreachable")
            return paired(invitation, reply, null)
        }

        private fun paired(invitation: Invitation, reply: Reply, host: String?): Pairing {
            val json = reply.json
            if (reply.status != 200 || json == null) return Pairing.Failed(reply.error ?: "unreachable")
            val name = json.optString("name").ifEmpty { invitation.name }
            val psm = if (json.has("ble")) BleLink.psm(json.optJSONObject("ble"), invitation.pin) else invitation.psm
            val computer = Computer(name, invitation.pin, invitation.port, invitation.hosts, host, json.optString("device"), psm)
            return Pairing.Paired(computer, json.getString("token"))
        }

        private const val CHUNK = 64 * 1024
        private const val CALL_IDLE = 9000L
        private const val PAIR_IDLE = 12_000L           // the computer waits 8 s for someone to allow it
        private const val MAX_REPLY = 32L * 1024 * 1024

        private fun upgrading(protocol: String) = arrayOf("Connection" to "Upgrade", "Upgrade" to protocol)

        private fun fields(token: String?, type: String?, length: Long, vararg more: Pair<String, String>): List<Pair<String, String>> {
            val fields = ArrayList<Pair<String, String>>()
            if (token != null) fields += "Authorization" to "Bearer $token"
            if (type != null) fields += "Content-Type" to type
            if (length >= 0) fields += "Content-Length" to length.toString()
            fields += more
            return fields
        }

        /** A request with JSON, or none, over Bluetooth. */
        private fun air(pin: String, psm: Int?, token: String?, method: String, path: String, body: JSONObject?, idle: Long): Reply? {
            val bytes = body?.toString()?.toByteArray(Charsets.UTF_8)
            val length = bytes?.size?.toLong() ?: if (method == "POST") 0L else -1L
            val fields = fields(token, if (bytes != null) "application/json" else null, length)
            return air(pin, psm, fields, method, path, idle, send = { output -> if (bytes != null) output.write(bytes) })
        }

        /** A request over Bluetooth: send writes its body, as long as fields say; receive reads the answer. */
        private fun air(
            pin: String,
            psm: Int?,
            fields: List<Pair<String, String>>,
            method: String,
            path: String,
            idle: Long,
            send: (OutputStream) -> Unit = {},
            receive: (Http.Head, InputStream) -> Reply = { head, input -> Reply(head.status, head.type, Http.body(input, head, MAX_REPLY), true) },
        ): Reply? {
            if (psm == null) return null
            val (pipe, head) = asked(pin, psm, idle) { output ->
                Http.request(output, method, path, BleLink.HOST, fields)
                send(output)
            } ?: return null
            return try {
                receive(head, pipe.input).also { if (head.reusable) BleLink.keep(pin, pipe) else pipe.close() }
            } catch (e: Exception) {
                pipe.close()
                null
            }
        }

        /**
         * Writes a request on a line to the computer and reads its answer's head. A kept line the
         * computer had let go fails before any answer: then once more, on a new one.
         */
        private fun asked(pin: String, psm: Int, idle: Long, write: (OutputStream) -> Unit): Pair<TlsPipe, Http.Head>? {
            repeat(2) {
                val line = BleLink.line(pin, psm) { engine(pin) } ?: return null
                try {
                    line.pipe.idle(idle)
                    val output = BufferedOutputStream(line.pipe.output, CHUNK)
                    write(output)
                    output.flush()
                    return line.pipe to Http.head(line.pipe.input)
                } catch (e: Exception) {
                    line.pipe.close()
                    if (!line.kept) return null
                }
            }
            return null
        }

        private fun engine(pin: String): SSLEngine = tls(pin).createSSLEngine(BleLink.HOST, 443).apply { useClientMode = true }

        fun once(host: String, port: Int, pin: String, method: String, path: String, body: JSONObject?, token: String?): Reply? =
            exchange(host, port, pin, token, method, path, 9000, send = { connection ->
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
            })

        private fun exchange(
            host: String,
            port: Int,
            pin: String,
            token: String?,
            method: String,
            path: String,
            timeout: Int,
            send: (HttpsURLConnection) -> Unit = {},
            receive: (HttpsURLConnection, Int) -> Reply = { connection, status -> read(connection, status) },
        ): Reply? = runCatching {
            val connection = URL("https", host, port, path).openConnection() as HttpsURLConnection
            try {
                connection.sslSocketFactory = tls(pin).socketFactory
                connection.hostnameVerifier = HostnameVerifier { _, _ -> true } // the pin decides
                connection.connectTimeout = 2500
                connection.readTimeout = timeout
                connection.useCaches = false
                connection.requestMethod = method
                if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                send(connection)
                receive(connection, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

        private fun read(connection: HttpsURLConnection, status: Int): Reply {
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            return Reply(status, connection.contentType, bytes)
        }

        fun copy(input: InputStream, output: OutputStream, max: Long): Long {
            val buffer = ByteArray(CHUNK)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return total
                total += count
                if (total > max) throw TooBig()
                output.write(buffer, 0, count)
            }
        }

        private fun tls(pin: String): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(Pinned(pin)), null) }

        fun discover(context: Context, timeout: Long = 3000): List<String> {
            val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
            val found = Collections.synchronizedList(ArrayList<String>())
            val done = CountDownLatch(1)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            info.host?.hostAddress?.let { found += it }
                        }
                    })
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = done.countDown()

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onServiceLost(info: NsdServiceInfo) = Unit
            }
            runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener) }.onFailure { return emptyList() }
            done.await(timeout, TimeUnit.MILLISECONDS)
            runCatching { nsd.stopServiceDiscovery(listener) }
            return found.toList()
        }
    }

    private class Pinned(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val hash = MessageDigest.getInstance("SHA-256").digest(chain.firstOrNull()?.publicKey?.encoded ?: throw CertificateException("none"))
            val seen = android.util.Base64.encodeToString(hash, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            if (!MessageDigest.isEqual(seen.toByteArray(), pin.toByteArray())) throw CertificateException("not this computer")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("no clients")

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }
}
