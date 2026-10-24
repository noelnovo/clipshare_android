package win.downops.clipshare.ws

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import win.downops.clipshare.util.Constants
import win.downops.clipshare.ws.Protocol
import win.downops.clipshare.ws.ProtocolParser
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WsServerTest {

    private val errors = ConcurrentLinkedQueue<String>()

    private fun findFreePort(): Int = ServerSocket(0).use { it.localPort }

    private fun awaitListening(port: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket("127.0.0.1", port).close()
                return
            } catch (_: Exception) {
                Thread.sleep(50)
            }
        }
        throw AssertionError("server did not start listening on $port")
    }

    private fun connect(port: Int, token: String? = null, onMessage: (String) -> Unit): WebSocket {
        var url = "ws://127.0.0.1:$port${Constants.Protocol.WS_PATH}"
        if (!token.isNullOrBlank()) url += "?token=$token"
        return OkHttpClient().newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = onMessage(text)

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    errors.add("ws failure: ${t.message}")
                }
            },
        )
    }

    private fun <T> LinkedBlockingQueue<T>.await(timeoutMs: Long = 15_000): T =
        poll(timeoutMs, TimeUnit.MILLISECONDS)
            ?: throw AssertionError("timed out waiting for queue value")

    @Test
    fun sendsHelloAndRelaysClipboard() {
        val port = findFreePort()
        val received = LinkedBlockingQueue<Pair<String, Protocol.Clipboard>>()
        val clientCounts = LinkedBlockingQueue<Int>()
        val server = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            onReceived = { from, clip -> received.add(from to clip) },
            onClientChange = { clientCounts.add(it) },
        )
        server.start()
        try {
            awaitListening(port)
            val messages = LinkedBlockingQueue<String>()
            val client = connect(port) { messages.add(it) }

            val hello = ProtocolParser.parseHello(messages.await())
            assertEquals("android-server", hello)

            client.send(Protocol.hello("desktop-pc", "desktop", "1.0.0"))
            client.send(Protocol.clipboard("shared text", "desktop-pc"))

            val (from, clip) = received.await()
            assertEquals("desktop-pc", from)
            assertEquals("shared text", clip.text)
            assertNull(clip.image)

            client.close(1000, "bye")
        } finally {
            server.stop()
        }
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun releasesThePortForANewServerAfterStop() {
        val port = findFreePort()
        val first = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            onReceived = { _, _ -> },
            onClientChange = {},
        )
        first.start()
        awaitListening(port)
        first.stop()

        val second = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            onReceived = { _, _ -> },
            onClientChange = {},
        )
        try {
            second.start()
            awaitListening(port)
        } finally {
            second.stop()
        }
    }

    @Test
    fun answersPingWithPong() {
        val port = findFreePort()
        val server = WsServer(port, "127.0.0.1", "android-server", null, null, onReceived = { _, _ -> }, onClientChange = {})
        server.start()
        try {
            awaitListening(port)
            val messages = LinkedBlockingQueue<String>()
            val client = connect(port) { messages.add(it) }
            ProtocolParser.parseHello(messages.await())

            client.send(Protocol.ping())

            assertEquals(Protocol.pong(), messages.await())
            client.close(1000, "bye")
        } finally {
            server.stop()
        }
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun broadcastReachesClientsExceptSkipped() {
        val port = findFreePort()
        val counts = LinkedBlockingQueue<Int>()
        val server = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            onReceived = { _, _ -> },
            onClientChange = { counts.add(it) },
        )
        server.start()
        try {
            awaitListening(port)
            val msgsA = LinkedBlockingQueue<String>()
            val msgsB = LinkedBlockingQueue<String>()
            val a = connect(port) { msgsA.add(it) }
            val b = connect(port) { msgsB.add(it) }
            assertNotNull(ProtocolParser.parseHello(msgsA.await()))
            assertNotNull(ProtocolParser.parseHello(msgsB.await()))

            a.send(Protocol.hello("client-a", "android", "1"))
            a.send(Protocol.ping())
            assertNotNull(msgsA.await())
            b.send(Protocol.hello("client-b", "android", "1"))
            b.send(Protocol.ping())
            assertNotNull(msgsB.await())

            assertTrue(counts.contains(2))

            assertTrue(server.broadcast("hello all", "server"))
            assertEquals("hello all", ProtocolParser.parseClipboard(msgsA.await())?.text)
            assertEquals("hello all", ProtocolParser.parseClipboard(msgsB.await())?.text)

            assertTrue(server.broadcast("only b", "server", skipFrom = "client-a"))
            assertEquals("only b", ProtocolParser.parseClipboard(msgsB.await())?.text)
            assertNull("client-a should not receive skipped broadcast", msgsA.poll(300, TimeUnit.MILLISECONDS))

            a.close(1000, "bye")
            b.close(1000, "bye")
        } finally {
            server.stop()
        }
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun broadcastImageRelaysBytes() {
        val port = findFreePort()
        val server = WsServer(port, "127.0.0.1", "android-server", null, null, onReceived = { _, _ -> }, onClientChange = {})
        server.start()
        try {
            awaitListening(port)
            val messages = LinkedBlockingQueue<String>()
            val client = connect(port) { messages.add(it) }
            ProtocolParser.parseHello(messages.await())
            client.send(Protocol.hello("client-a", "android", "1"))

            val bytes = byteArrayOf(0x0, 0x1, 0x2, 0x7f, -1, 0x42)
            assertTrue(server.broadcastImage(bytes, Constants.Mime.IMAGE_PNG, "server"))

            val clip = ProtocolParser.parseClipboard(messages.await())
            assertEquals(Constants.Mime.IMAGE_PNG, clip?.mime)
            assertArrayEquals(bytes, clip?.image)

            client.close(1000, "bye")
        } finally {
            server.stop()
        }
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun rejectsConnectionWithWrongToken() {
        val port = findFreePort()
        val server = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            serverToken = "expected-token",
            onReceived = { _, _ -> },
            onClientChange = {},
        )
        server.start()
        try {
            awaitListening(port)
            val messages = LinkedBlockingQueue<String>()
            val client = connect(port, token = "wrong-token") { messages.add(it) }

            // The server should close the socket; no hello should arrive.
            assertNull("client with wrong token should not receive hello", messages.poll(500, TimeUnit.MILLISECONDS))
            client.close(1000, "bye")
        } finally {
            server.stop()
        }
    }

    @Test
    fun allowsConnectionWithCorrectToken() {
        val port = findFreePort()
        val server = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            serverToken = "expected-token",
            onReceived = { _, _ -> },
            onClientChange = {},
        )
        server.start()
        try {
            awaitListening(port)
            val messages = LinkedBlockingQueue<String>()
            val client = connect(port, token = "expected-token") { messages.add(it) }

            assertEquals("android-server", ProtocolParser.parseHello(messages.await()))
            client.close(1000, "bye")
        } finally {
            server.stop()
        }
    }

    @Test
    fun repeatedStartAndStopDoesNotCrash() {
        val port = findFreePort()
        val server = WsServer(
            port = port,
            bindHost = "127.0.0.1",
            deviceName = "android-server",
            keyStore = null,
            keyStorePassword = null,
            onReceived = { _, _ -> },
            onClientChange = { _ -> },
        )
        repeat(10) {
            server.start()
            Thread.sleep(50)
            server.stop()
        }
    }

    @Test
    fun broadcastReturnsFalseWithNoClients() {
        val port = findFreePort()
        val server = WsServer(port, "127.0.0.1", "android-server", null, null, onReceived = { _, _ -> }, onClientChange = {})
        server.start()
        try {
            awaitListening(port)
            assertTrue(!server.broadcast("nobody here", "server"))
        } finally {
            server.stop()
        }
    }
}