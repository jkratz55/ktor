/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.apache

import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import kotlinx.coroutines.*
import org.apache.http.*
import org.apache.http.impl.nio.client.*
import org.apache.http.impl.nio.reactor.*
import java.net.*

private const val MAX_CONNECTIONS_COUNT = 1000
private const val IO_THREAD_COUNT_DEFAULT = 4

internal class ApacheEngine(override val config: ApacheEngineConfig) : HttpClientJvmEngine("ktor-apache") {

    private val engine: CloseableHttpAsyncClient = prepareClient().apply { start() }

    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = createCallContext(data.executionContext)
        val apacheRequest = ApacheRequestProducer(data, config, callContext)
        return withContext(callContext) { engine.sendRequest(apacheRequest, callContext) }
    }

    override fun close() {
        super.close()
        coroutineContext[Job]?.invokeOnCompletion {
            engine.close()
        }
    }

    private fun prepareClient(): CloseableHttpAsyncClient {
        val clientBuilder = HttpAsyncClients.custom()
        with(clientBuilder) {
            setThreadFactory {
                Thread(it, "Ktor-client-apache").apply {
                    isDaemon = true
                    setUncaughtExceptionHandler { _, _ -> }
                }

            }
            disableAuthCaching()
            disableConnectionState()
            disableCookieManagement()
            setDefaultIOReactorConfig(IOReactorConfig.custom().apply {
                setMaxConnPerRoute(MAX_CONNECTIONS_COUNT)
                setMaxConnTotal(MAX_CONNECTIONS_COUNT)
                setIoThreadCount(IO_THREAD_COUNT_DEFAULT)
            }.build())

            setupProxy()
        }

        with(config) {
            clientBuilder.customClient()
        }

        config.sslContext?.let { clientBuilder.setSSLContext(it) }
        return clientBuilder.build()!!
    }

    private fun HttpAsyncClientBuilder.setupProxy() {
        val proxy = config.proxy ?: return

        if (proxy.type() == Proxy.Type.DIRECT) {
            return
        }

        val address = proxy.address()
        check(proxy.type() == Proxy.Type.HTTP && address is InetSocketAddress) {
            "Only http proxy is supported for Apache engine."
        }

        setProxy(HttpHost.create("http://${address.hostName}:${address.port}"))
    }
}
