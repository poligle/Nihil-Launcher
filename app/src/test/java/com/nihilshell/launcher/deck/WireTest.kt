package com.nihilshell.launcher.deck

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.random.Random

class WireTest {
    @Test
    fun bluetoothsUuidIsTheOneTheComputerMakesFromThePin() {
        assertEquals("3d584b8e-5936-80b5-8114-c814fc5d2506", BleLink.uuid("8-eMVPli0fpzjeUBe-b_bmRtjDMwo1bNYXaVTyPSbVc").toString())
    }

    @Test
    fun aHeadIsReadToItsBlankLineAndNoFurther() {
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}next".toByteArray())
        val head = Http.head(input)
        assertEquals(200, head.status)
        assertEquals("application/json", head.type)
        assertTrue(head.reusable)
        assertEquals("{}", String(Http.body(input, head, 1024)))
        assertEquals('n'.code, input.read())
    }

    @Test
    fun aLineThatClosesOrDoesntSayHowLongIsntUsedAgain() {
        val closing = Http.head(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()))
        assertFalse(closing.reusable)
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\nto the end".toByteArray())
        val endless = Http.head(input)
        assertFalse(endless.reusable)
        assertEquals("to the end", String(Http.body(input, endless, 1024)))
        assertFalse(Http.head(ByteArrayInputStream("HTTP/1.1 101 Switching Protocols\r\n\r\n".toByteArray())).reusable)
    }

    @Test
    fun aBodyLongerThanAllowedOrCutShortFails() {
        val big = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789".toByteArray())
        assertTrue(fails { Http.body(big, Http.head(big), 5) } is Http.TooBig)
        val short = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n01234".toByteArray())
        assertTrue(fails { Http.body(short, Http.head(short), 1024) } is IOException)
        assertTrue(fails { Http.head(ByteArrayInputStream("SSH-2.0-OpenSSH\r\n\r\n".toByteArray())) } is IOException)
    }

    @Test
    fun aRequestIsOneHead() {
        val output = ByteArrayOutputStream()
        Http.request(output, "POST", "/v1/vector", "nihil", listOf("Content-Length" to "3", "X-Vector-Name" to "Caf%C3%A9.mp4"))
        assertEquals("POST /v1/vector HTTP/1.1\r\nHost: nihil\r\nContent-Length: 3\r\nX-Vector-Name: Caf%C3%A9.mp4\r\n\r\n", output.toString("UTF-8"))
    }

    @Test
    fun tlsOnTwoStreamsGoesBothWaysAtOnce() {
        val server = server()
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TRUSTING), null) }
        val engine = client.createSSLEngine("nihil", 443).apply { useClientMode = true }
        val pipe = TlsPipe(engine, socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        pipe.idle(10_000)
        pipe.handshake()
        val sent = ByteArray(1024 * 1024).also { Random(7).nextBytes(it) }
        val failed = AtomicReference<Throwable?>()
        val writer = thread {
            try {
                val sizes = Random(3)
                var at = 0
                while (at < sent.size) {
                    val count = minOf(sizes.nextInt(1, 40_000), sent.size - at)
                    pipe.output.write(sent, at, count)
                    at += count
                }
                pipe.output.flush()
            } catch (e: Throwable) {
                failed.set(e)
            }
        }
        val back = ByteArray(sent.size)
        var got = 0
        while (got < back.size) {
            val count = pipe.input.read(back, got, back.size - got)
            if (count < 0) break
            got += count
        }
        writer.join()
        assertEquals(null, failed.get())
        assertArrayEquals(sent, back)
        pipe.close()
        assertTrue(pipe.closed)
        assertTrue(fails { pipe.input.read() } is IOException)
        server.close()
    }

    @Test
    fun aQuietPipeClosesOnItsOwn() {
        val server = server()
        val socket = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val client = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TRUSTING), null) }
        val pipe = TlsPipe(client.createSSLEngine("nihil", 443).apply { useClientMode = true }, socket.getInputStream(), socket.getOutputStream()) { socket.close() }
        pipe.idle(10_000)
        pipe.handshake()
        pipe.idle(1000)
        val started = System.nanoTime()
        assertTrue(fails { pipe.input.read() } is IOException)
        assertTrue((System.nanoTime() - started) / 1_000_000 in 900..6000)
        assertTrue(pipe.closed)
        server.close()
    }

    private fun fails(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (e: Throwable) {
        e
    }

    /** An echo over TLS, with a key keytool makes for the test (none when there's no keytool). */
    private fun server(): SSLServerSocket {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        assumeTrue(keytool.canExecute())
        val store = File.createTempFile("wire", ".p12").apply { delete() }
        store.deleteOnExit()
        val made = ProcessBuilder(
            keytool.path, "-genkeypair", "-alias", "deck", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=nihil",
            "-validity", "2", "-storetype", "PKCS12", "-keystore", store.path, "-storepass", PASSWORD, "-keypass", PASSWORD,
        ).redirectErrorStream(true).start().run {
            inputStream.readBytes()
            waitFor()
        }
        assumeTrue(made == 0)
        val keys = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, PASSWORD.toCharArray()) } }
        val managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, PASSWORD.toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
        val server = context.serverSocketFactory.createServerSocket(0, 4, InetAddress.getLoopbackAddress()) as SSLServerSocket
        thread(isDaemon = true) {
            runCatching {
                while (true) {
                    val connection = server.accept() as SSLSocket
                    thread(isDaemon = true) {
                        runCatching {
                            connection.use {
                                val buffer = ByteArray(16 * 1024)
                                while (true) {
                                    val count = it.inputStream.read(buffer)
                                    if (count < 0) break
                                    it.outputStream.write(buffer, 0, count)
                                }
                            }
                        }
                    }
                }
            }
        }
        return server
    }

    private companion object {
        const val PASSWORD = "nihil-test"

        val TRUSTING = object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
    }
}
