/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.jetty

import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import kotlinx.coroutines.*
import org.eclipse.jetty.http2.client.*
import org.eclipse.jetty.util.thread.*
import kotlin.coroutines.*

internal class JettyHttp2Engine(
    override val config: JettyEngineConfig
) : AbstractHttpClientEngine(
    "ktor-jetty",
    dispatcherInitializer = { Dispatchers.fixedThreadPoolDispatcher(config.threadsCount) }
) {
    private val jettyClient = HTTP2Client().apply {
        addBean(config.sslContextFactory)
        check(config.proxy == null) { "Proxy unsupported in Jetty engine." }

        executor = QueuedThreadPool().apply {
            name = "ktor-jetty-client-qtp"
        }

        start()
    }

    override suspend fun executeWithinCallContext(
        data: HttpRequestData,
        callContext: CoroutineContext
    ): HttpResponseData {
        return try {
            data.executeRequest(jettyClient, config, callContext)
        } catch (cause: Throwable) {
            (callContext[Job] as? CompletableJob)?.completeExceptionally(cause)
            throw cause
        }
    }

    override fun close() {
        closeAndExecuteOnCompletion {
            jettyClient.stop()
        }
    }
}
