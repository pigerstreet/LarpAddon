package com.github.noamm9.websocket

import com.github.noamm9.NoammAddons
import com.github.noamm9.NoammAddons.logger
import com.github.noamm9.NoammAddons.mc
import com.github.noamm9.event.EventBus
import com.github.noamm9.event.EventBus.register
import com.github.noamm9.event.impl.GameStartEvent
import com.github.noamm9.event.impl.ShutdownEvent
import com.github.noamm9.event.impl.WebSocketEvent
import com.github.noamm9.init.types.ISelfInit
import com.github.noamm9.utils.ChatUtils
import com.github.noamm9.utils.GsonUtils
import com.github.noamm9.utils.ThreadUtils
import com.github.noamm9.utils.network.ApiAuth
import com.github.noamm9.utils.network.WebUtils
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.parameter
import io.ktor.websocket.*
import kotlinx.coroutines.*

object WebSocket: ISelfInit {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("${NoammAddons.MOD_NAME}-WebSocket"))
    @Volatile private var session: WebSocketSession? = null
    @Volatile private var connecting = false
    /// fork: connect() retries every 60s forever, and every failure printed to chat, so an
    /// endpoint that is down put a line on screen every ~75s for the whole session — measured
    /// 2026-09-27 at 01:00:50, 01:02:05, 01:03:20 and again at 16:24:52, 16:26:07, 16:27:22.
    /// The socket is not decorative: it carries the muted-party-chat relay and /ws, so the FIRST
    /// failure is worth saying. The rest say nothing new, so they are held until a reconnect
    /// clears the flag and the next failure is news again. (Upstream now logs every failure and
    /// says nothing in chat; the log line is kept, the first-failure chat line is the fork's.)
    @Volatile private var failureReported = false

    override fun init() {
        ThreadUtils.addShutdownHook {
            scope.cancel()
            session?.cancel()
            session = null
        }

        register<GameStartEvent> {
            scope.launch {
                delay(5000)
                connect()
            }
        }
        /// fork: the socket's OkHttp reader and dispatcher threads are not daemons, so a live socket kept the JVM
        /// up after the client stopped and the shutdown watchdog fired every exit (crash reports at 15:35, 16:57
        /// and 17:56 on 2026-10-21 each show "OkHttp https://ws.noamm.org/..." still reading). Stop the reconnect
        /// loop and close the shared client when the game stops.
        register<ShutdownEvent> {
            scope.cancel()
            runCatching { WebUtils.client.close() }
        }
    }

    fun isConnected() = session?.isActive == true

    fun send(packet: Any) = scope.launch {
        val socket = session?.takeIf(WebSocketSession::isActive) ?: return@launch
        val json = GsonUtils.gson.toJsonTree(packet).asJsonObject
        val type = PacketRegistry.getType(packet)
        if (type != null) json.addProperty("type", type)
        socket.send(Frame.Text(json.toString()))
        ChatUtils.debug("ws", "[WS] sending $json")
    }

    suspend fun connect() {
        if (connecting) return
        connecting = true

        try {
            val token = ApiAuth.token ?: return logger.info("[Websocket] no auth token yet, waiting for auth")
            WebUtils.client.webSocket("wss://ws.noamm.org", {
                parameter("name", mc.user.name)
                parameter("token", token)
            }) {
                session = this
                logger.info("[WebSocket] connected")
                /// fork: reaching here means the handshake succeeded, so whatever was reported
                /// last time is resolved. Say so once, then go quiet until it breaks again.
                if (failureReported) {
                    failureReported = false
                    ChatUtils.modMessage("[WebSocket] reconnected")
                }

                for (frame in incoming) if (frame is Frame.Text) mc.submit {
                    EventBus.post(WebSocketEvent.Payload(frame.readText()))
                }
            }
        }
        catch (e: Throwable) {
            if (e is CancellationException) throw e
            logger.info("[WebSocket] error: ${e.message}")
            /// fork: first failure only — see failureReported. The retry line is included so a
            /// single chat message is not mistaken for a one-off.
            if (! failureReported) {
                failureReported = true
                ChatUtils.modMessage("[WebSocket] error: ${e.message} — retrying every 60s")
            }
        }
        finally {
            session = null
            connecting = false
            scope.launch {
                delay(60_000L)
                connect()
            }
        }
    }
}