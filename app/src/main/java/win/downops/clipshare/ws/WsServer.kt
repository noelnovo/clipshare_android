package win.downops.clipshare.ws

import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import win.downops.clipshare.logs.Log
import win.downops.clipshare.util.Constants
import java.security.KeyStore
import java.util.Collections

/**
 * Ktor-based WebSocket server used in Android server mode.
 *
 * Accepts inbound connections from other ClipShare clients, receives their
 * clipboard payloads and relays them to every other connected client. The
 * owner [SyncService] is responsible for writing received payloads to the
 * system clipboard.
 */
class WsServer(
    private val port: Int,
    private val bindHost: String,
    private val deviceName: String,
    private val keyStore: KeyStore?,
    private val keyStorePassword: CharArray?,
    private val trustStore: KeyStore? = null,
    private val serverToken: String = "",
    private val onReceived: (from: String, clip: Protocol.Clipboard) -> Unit,
    private val onClientChange: (count: Int) -> Unit,
) {

    private var server: ApplicationEngine? = null
    private val sessions = Collections.synchronizedSet(LinkedHashSet<Session>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var running = false

    fun start() {
        if (running) return
        Log.i("WsServer", "starting server on port $port (tls=${keyStore != null})")

        val env = applicationEngineEnvironment {
            module {
                install(WebSockets) {
                    pingPeriodMillis = Constants.WebSocket.PING_INTERVAL_MS
                    timeoutMillis = Constants.WebSocket.CONNECT_TIMEOUT_MS
                }
                routing {
                    webSocket(Constants.Protocol.WS_PATH) {
                        if (!verifyToken(call.request.queryParameters["token"])) {
                            Log.w("WsServer", "rejected connection: token mismatch")
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "invalid token"))
                            return@webSocket
                        }
                        handleSession(this)
                    }
                }
            }
            if (keyStore != null && keyStorePassword != null) {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = SERVER_KEY_ALIAS,
                    keyStorePassword = { keyStorePassword },
                    privateKeyPassword = { keyStorePassword },
                ) {
                    // A trust store turns on client authentication: with TLS
                    // enabled the server always requires a client certificate
                    // signed by its own CA (mutual TLS).
                    trustStore = this@WsServer.trustStore
                    this.port = this@WsServer.port
                    this.host = bindHost
                }
            } else {
                connector {
                    this.port = this@WsServer.port
                    this.host = bindHost
                }
            }
        }

        // Netty is required: the CIO engine does not support HTTPS connectors.
        // Retry briefly if a previous instance is still releasing the port
        // (rapid stop/start from the quick-settings tile).
        var last: Exception? = null
        for (attempt in 0 until 3) {
            var engine: ApplicationEngine? = null
            try {
                engine = embeddedServer(Netty, env)
                engine.start(wait = false)
                server = engine
                running = true
                Log.i("WsServer", "server started on port $port")
                return
            } catch (e: Exception) {
                last = e
                runCatching { engine?.stop(0, 0) }
                Log.w("WsServer", "server bind attempt ${attempt + 1} failed: ${e.message}")
                if (attempt < 2) {
                    try {
                        Thread.sleep(300)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
        running = false
        throw last ?: IllegalStateException("server failed to start on port $port")
    }

    fun stop() {
        running = false
        synchronized(sessions) {
            sessions.toList().forEach { session ->
                kotlin.runCatching {
                    runBlocking {
                        session.session.close(CloseReason(CloseReason.Codes.NORMAL, "server stopping"))
                    }
                }
            }
            sessions.clear()
        }
        server?.stop(500, 1000)
        server = null
        scope.cancel()
        onClientChange(0)
        Log.i("WsServer", "stopped")
    }

    /** Send text to every connected client except [skipFrom]. */
    fun broadcast(text: String, from: String, skipFrom: String? = null): Boolean {
        val msg = Protocol.clipboard(text, from)
        return sendToAll(msg, skipFrom)
    }

    /** Send an image to every connected client except [skipFrom]. */
    fun broadcastImage(bytes: ByteArray, mime: String, from: String, skipFrom: String? = null): Boolean {
        val msg = Protocol.clipboardImage(bytes, mime, from)
        return sendToAll(msg, skipFrom)
    }

    private fun sendToAll(msg: String, skipFrom: String?): Boolean {
        val targets = synchronized(sessions) {
            sessions.filter { skipFrom == null || it.name != skipFrom }
        }
        if (targets.isEmpty()) return false
        targets.forEach { session ->
            // Use the suspending send (applies backpressure/queues) instead of a
            // non-blocking trySend, which silently drops frames when the channel
            // is momentarily full or the session is closing.
            scope.launch {
                try {
                    session.session.send(Frame.Text(msg))
                } catch (e: Exception) {
                    Log.w("WsServer", "failed to send to ${session.name}: ${e.message}")
                }
            }
        }
        return true
    }

    private suspend fun handleSession(ws: DefaultWebSocketServerSession) {
        var name = ""
        val session = Session(ws, name)
        synchronized(sessions) { sessions.add(session) }
        onClientChange(sessions.size)

        try {
            val hello = Protocol.hello(deviceName, Constants.Protocol.PLATFORM_ANDROID, Constants.App.VERSION)
            ws.send(Frame.Text(hello))

            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                ProtocolDispatcher.dispatch(
                    message = ProtocolParser.parse(text),
                    onHello = { newName ->
                        name = newName
                        session.name = newName
                        onClientChange(sessions.size)
                        Log.i("WsServer", "client identified as $newName")
                    },
                    onClipboard = { clip ->
                        Log.i("WsServer", "clipboard from $name")
                        onReceived(name, clip)
                    },
                    onError = {},
                    onUnknown = { Log.d("WsServer", "ignored message type") },
                    sendPong = { scope.launch { ws.send(Frame.Text(Protocol.pong())) } },
                )
            }
        } catch (e: Exception) {
            Log.w("WsServer", "session error: ${e.message}")
        } finally {
            synchronized(sessions) { sessions.remove(session) }
            onClientChange(sessions.size)
        }
    }

    private data class Session(
        val session: DefaultWebSocketServerSession,
        @Volatile var name: String,
    )

    private fun verifyToken(provided: String?): Boolean {
        if (serverToken.isBlank()) return true
        return provided == serverToken
    }

    companion object {
        private const val SERVER_KEY_ALIAS = "server"
    }
}
