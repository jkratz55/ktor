/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.jetty

import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.util.thread.*
import java.util.*

internal class JettyHttp2Engine(
    override val config: JettyEngineConfig
) : HttpClientJvmEngine("ktor-jetty") {

    private val clientCache =
        Collections.synchronizedMap(object : LinkedHashMap<HttpTimeoutAttributes, HTTP2Client>(10, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<HttpTimeoutAttributes, HTTP2Client>): Boolean {
                val remove = size > 10
                if (remove) {
                    // TODO: do we need to wait this client to complete all existing operations?
                    eldest.value.stop()
                }
                return remove
            }
        })

    private fun getJettyClient(data: HttpRequestData): HTTP2Client {
        val httpTimeoutAttributes = data.attributes.getOrNull(HttpTimeoutAttributes.key)
        synchronized(clientCache) {
            var res = clientCache[httpTimeoutAttributes]
            res?.let { return it }

            res = HTTP2Client().apply {
                addBean(config.sslContextFactory)
                check(config.proxy == null) { "Proxy unsupported in Jetty engine." }

                executor = QueuedThreadPool().apply {
                    name = "ktor-jetty-client-qtp"
                }

                httpTimeoutAttributes?.connectTimeout?.let { connectTimeout = if (it == 0L) Long.MAX_VALUE else it }
                httpTimeoutAttributes?.socketTimeout?.let { idleTimeout = it }

                start()
            }

            clientCache[httpTimeoutAttributes] = res
            return res
        }
    }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = createCallContext(data.executionContext)
        return try {
            val jettyClient = getJettyClient(data)
            withContext(callContext) {
                data.executeRequest(jettyClient, config, callContext)
            }
        } catch (cause: Throwable) {
            (callContext[Job] as? CompletableJob)?.completeExceptionally(cause)
            throw cause
        }
    }

    override fun close() {
        super.close()
        coroutineContext[Job]?.invokeOnCompletion {
            clientCache.forEach { (_, client) -> client.stop() }
        }
    }
}
