package com.github.noamm9.utils.network

import com.github.noamm9.NoammAddons.MOD_NAME
import com.github.noamm9.NoammAddons.MOD_VERSION
import com.github.noamm9.NoammAddons.scope
import com.github.noamm9.utils.JsonUtils
import com.github.noamm9.utils.ThreadUtils
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import java.util.concurrent.*

object WebUtils {
    private val sharedRequests = ConcurrentHashMap<String, Deferred<Result<HttpResponse>>>()

    val client = HttpClient(OkHttp) {
        /// fork: OkHttp's default dispatcher threads are not daemons, and the websocket reader runs on one, so a
        /// live socket held the JVM after the client stopped and the shutdown watchdog crashed every exit
        /// (crash reports 2026-10-21 x3 and 2026-10-26, closing the client on ShutdownEvent did not stop it).
        /// fork: OkHttp's fast-fallback races several TCP connects and cancels the losers, which closes a socket
        /// handle while its thread is still polling it; on Windows that is a fatal EXCEPTION_INVALID_HANDLE
        /// (0xc0000008) in ntdll, always on an "OkHttp connect api.hypixel.net" thread (hs_err 2026-10-25, 10-28,
        /// 10-29, 10-30). One connect attempt at a time leaves nothing to cancel.
        engine {
            config {
                fastFallback(false)
                dispatcher(okhttp3.Dispatcher(Executors.newCachedThreadPool { r -> Thread(r, "$MOD_NAME OkHttp").apply { isDaemon = true } }))
            }
        }
        install(UserAgent) { agent = "$MOD_NAME/$MOD_VERSION (+https://noamm.org)" }
        // fork: connectTimeoutMillis only bounds TCP/TLS setup. A server that completes the
        // handshake then stops sending held the calling coroutine - and its entry in
        // sharedRequests - forever, which poisoned that URL for every later caller.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
        install(ContentNegotiation) { json(JsonUtils.json) }
        install(ContentEncoding) {
            gzip(1.0F)
            deflate(0.9F)
        }
        
        install(WebSockets)
    }

    init {
        ThreadUtils.addShutdownHook {
            client.close()
            client.cancel()
        }
    }

    suspend fun get(url: String, block: HttpRequestBuilder.() -> Unit = {}): Result<HttpResponse> {
        while (true) {
            sharedRequests[url]?.let { return it.await() }

            val deferred = scope.async(start = CoroutineStart.LAZY) { runCatching { client.get(url, block) } }
            sharedRequests.putIfAbsent(url, deferred) ?: run {
                deferred.invokeOnCompletion { sharedRequests.remove(url, deferred) }
                deferred.start()
                return deferred.await()
            }
        }
    }

    suspend inline fun <reified T> getAs(url: String) = get(url).mapCatching {
        if (! it.status.isSuccess()) error("HTTP ${it.status.value}-${it.status.description}: ${it.bodyAsText()}")
        it.body<T>()
    }
}