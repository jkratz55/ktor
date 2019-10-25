/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.features

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.util.*
import kotlinx.coroutines.*

/**
 * Client HTTP timeout feature.
 * [requestTimeout] - request timeout in milliseconds.
 */
class HttpTimeout(private val requestTimeout: Long?, private val connectTimeout: Long?, private val socketTimeout: Long?) {
    /**
     * [HttpTimeout] configuration that is used during installation
     */
    class Configuration {
        /**
         * Request timeout in milliseconds.
         *
         * Default value is 10000 (10 seconds).
         */
        var requestTimeout: Long? = null

        var connectTimeout: Long? = null

        var socketTimeout: Long? = null

        internal fun build(): HttpTimeout = HttpTimeout(requestTimeout, connectTimeout, socketTimeout)
    }

    /**
     * Companion object for feature installation.
     */
    companion object Feature : HttpClientFeature<Configuration, HttpTimeout> {

        override val key: AttributeKey<HttpTimeout> = AttributeKey("Timeout")

        override fun prepare(block: Configuration.() -> Unit): HttpTimeout = Configuration().apply(block).build()

        override fun install(feature: HttpTimeout, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Before) {
                if (!context.attributes.contains(HttpTimeoutAttributes.key)) {
                    context.attributes.put(HttpTimeoutAttributes.key, HttpTimeoutAttributes())
                }

                context.attributes[HttpTimeoutAttributes.key].apply {
                    connectTimeout = connectTimeout ?: feature.connectTimeout
                    socketTimeout = socketTimeout ?: feature.socketTimeout
                    requestTimeout = requestTimeout ?: feature.requestTimeout

                    val requestTimeout = requestTimeout ?: feature.requestTimeout
                    if (requestTimeout != null && requestTimeout != 0L) {
                        val executionContext = context.executionContext!!
                        val killer = launch {
                            delay(requestTimeout)
                            executionContext.cancel(
                                HttpTimeoutCancellationException("Request timeout has been expired")
                            )
                        }

                        context.executionContext!!.invokeOnCompletion {
                            killer.cancel()
                        }
                    }
                }

            }
        }
    }
}

/**
 * This exception is thrown by [HttpTimeout] to indicate timeout.
 */
class HttpTimeoutCancellationException(message: String? = null) : CancellationException(message)

data class HttpTimeoutAttributes(
    var requestTimeout: Long? = null,
    var connectTimeout: Long? = null,
    var socketTimeout: Long? = null
) {
    companion object {
        val key = AttributeKey<HttpTimeoutAttributes>("TimeoutAttributes")
    }
}
