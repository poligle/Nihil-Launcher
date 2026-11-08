package com.nihilshell.launcher.deck

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
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
import javax.net.ssl.X509TrustManager

/**
 * The phone's side of docs/deck.md (Nihil-Shell): HTTPS to the computer,
 * trusting only the certificate whose public key is the pin from its QR,
 * checked before anything is sent. Blocking calls: run them off the main
 * thread.
 */
class DeckClient(private val computer: Computer, private val token: String?) {

    class Reply(val status: Int, val type: String?, val bytes: ByteArray) {
        val json: JSONObject?
            get() = if (type?.startsWith("application/json") == true) runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull() else null

        val error: String? get() = json?.optString("error")?.takeIf { it.isNotEmpty() }
    }

    /**
     * The address that answered last, then the others from the QR, then
     * whatever mDNS finds as _nihil-deck._tcp: the pin decides which is it.
     * Null when no address answers; [Computer.last] is where one did.
     */
    fun call(method: String, path: String, body: JSONObject? = null, context: Context? = null): Reply? {
        val tried = LinkedHashSet<String>()
        for (host in listOfNotNull(computer.last) + computer.hosts) {
            if (!tried.add(host)) continue
            once(host, computer.port, computer.pin, method, path, body, token)?.let {
                computer.last = host
                return it
            }
        }
        if (context == null) return null
        for (host in discover(context)) {
            if (!tried.add(host)) continue
            once(host, computer.port, computer.pin, method, path, body, token)?.let {
                computer.last = host
                return it
            }
        }
        return null
    }

    /** What the computer's QR holds (nihil-deck://pair?…). */
    class Invitation(val name: String, val hosts: List<String>, val port: Int, val code: String, val pin: String) {
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
                if (hosts.isEmpty() || port !in 1..65535 || !CODE.matches(code) || !PIN.matches(pin)) return null
                Invitation(uri.getQueryParameter("name").orEmpty().ifEmpty { hosts.first() }, hosts, port, code, pin)
            }.getOrNull()
        }
    }

    sealed class Pairing {
        class Paired(val computer: Computer, val token: String) : Pairing()

        /** wrong, closed, expired, wait (docs/deck.md, Errors), or unreachable. */
        class Failed(val reason: String) : Pairing()
    }

    companion object {
        const val SERVICE = "_nihil-deck._tcp"

        /** Pairs with the computer of [invitation]: the first address that answers to hello is the one. */
        fun pair(invitation: Invitation, device: String): Pairing {
            for (host in invitation.hosts) {
                val hello = once(host, invitation.port, invitation.pin, "GET", "/v1/hello", null, null) ?: continue
                if (hello.status != 200) continue
                val body = JSONObject().put("code", invitation.code).put("device", device)
                val reply = once(host, invitation.port, invitation.pin, "POST", "/v1/pair", body, null)
                    ?: return Pairing.Failed("unreachable")
                val json = reply.json
                if (reply.status != 200 || json == null) return Pairing.Failed(reply.error ?: "unreachable")
                val name = json.optString("name").ifEmpty { invitation.name }
                val computer = Computer(name, invitation.pin, invitation.port, invitation.hosts, host, json.optString("device"))
                return Pairing.Paired(computer, json.getString("token"))
            }
            return Pairing.Failed("unreachable")
        }

        /** One request to one address, or null when it can't be reached or isn't the computer of [pin]. */
        fun once(host: String, port: Int, pin: String, method: String, path: String, body: JSONObject?, token: String?): Reply? =
            runCatching {
                val connection = URL("https", host, port, path).openConnection() as HttpsURLConnection
                try {
                    connection.sslSocketFactory = tls(pin).socketFactory
                    connection.hostnameVerifier = HostnameVerifier { _, _ -> true } // the pin decides
                    connection.connectTimeout = 2500
                    connection.readTimeout = 9000
                    connection.useCaches = false
                    connection.requestMethod = method
                    if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
                    if (body != null) {
                        connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json")
                        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    }
                    val status = connection.responseCode
                    val stream = if (status >= 400) connection.errorStream else connection.inputStream
                    val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                    Reply(status, connection.contentType, bytes)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

        private fun tls(pin: String): SSLContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf(Pinned(pin)), null) }

        /** The computers mDNS finds on this network in [timeout] ms, as addresses. */
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

    /** Trusts one public key: the pin's (SHA-256 of its SubjectPublicKeyInfo, base64url). */
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
