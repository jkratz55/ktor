/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.android

import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.cio.*
import io.ktor.util.date.*
import kotlinx.coroutines.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import java.io.*
import java.lang.Integer.*
import java.net.*
import javax.net.ssl.*
import kotlin.coroutines.*

/**
 * Android client engine
 */
class AndroidClientEngine(override val config: AndroidEngineConfig) : AbstractHttpClientEngine(
    "ktor-android",
    dispatcherInitializer = { Dispatchers.fixedThreadPoolDispatcher(config.threadsCount) }
) {
    override suspend fun executeWithinCallContext(
        data: HttpRequestData,
        callContext: CoroutineContext
    ): HttpResponseData {
        val requestTime: GMTDate = GMTDate()

        val url: String = URLBuilder().takeFrom(data.url).buildString()
        val outgoingContent: OutgoingContent = data.body
        val contentLength: Long? =
            data.headers[HttpHeaders.ContentLength]?.toLong() ?: outgoingContent.contentLength

        val connection: HttpURLConnection = getProxyAwareConnection(url).apply {
            // Take timeout parameters from engine config.
            connectTimeout = config.connectTimeout
            readTimeout = config.socketTimeout

            // Take timeout parameters from timeout feature and override previous values.
            if (data.attributes.contains(HttpTimeoutAttributes.key)) {
                val timeoutAttributes = data.attributes[HttpTimeoutAttributes.key]
                timeoutAttributes.connectTimeout?.let { connectTimeout = it.toInt() }
                timeoutAttributes.socketTimeout?.let { readTimeout = it.toInt() }
                // Android performs blocking connect call, so we need to add an upper bound on the call time.
                timeoutAttributes.requestTimeout?.let {
                    if (it != 0L) {
                        connectTimeout = if (connectTimeout == 0) {
                            it.toInt()
                        } else {
                            if (connectTimeout < it) connectTimeout else it.toInt()
                        }
                    }
                }
            }

            if (this is HttpsURLConnection) {
                config.sslManager(this)
            }

            requestMethod = data.method.value
            useCaches = false
            instanceFollowRedirects = false

            mergeHeaders(data.headers, outgoingContent) { key: String, value: String ->
                addRequestProperty(key, value)
            }

            config.requestConfig(this)

            if (outgoingContent !is OutgoingContent.NoContent) {
                if (data.method in listOf(HttpMethod.Get, HttpMethod.Head)) throw RequestInvalidException(
                    "Request of type ${data.method} couldn't send a body with the [Android] engine."
                )

                if (contentLength == null && getRequestProperty(HttpHeaders.TransferEncoding) == null) {
                    addRequestProperty(HttpHeaders.TransferEncoding, "chunked")
                }

                contentLength?.let { setFixedLengthStreamingMode(it.toInt()) } ?: setChunkedStreamingMode(0)
                doOutput = true

                outgoingContent.writeTo(outputStream, callContext)
            }
        }

        try {
            connection.connect()
        } catch (cause: Throwable) {
            // Allow to throw request timeout cancellation exception instead of connect timeout exception.
            yield()
            if (cause is SocketTimeoutException) {
                throw HttpTimeoutCancellationException("Connect timeout has been expired")
            }

            throw cause
        }

        val statusCode = HttpStatusCode(connection.responseCode, connection.responseMessage)
        val content: ByteReadChannel = connection.content(callContext)
        val headerFields: MutableMap<String?, MutableList<String>> = connection.headerFields
        val version: HttpProtocolVersion = HttpProtocolVersion.HTTP_1_1

        val responseHeaders = HeadersBuilder().apply {
            headerFields.forEach { (key: String?, values: MutableList<String>) ->
                if (key != null) appendAll(key, values)
            }
        }.build()

        return HttpResponseData(statusCode, requestTime, responseHeaders, version, content, callContext)
    }

    private fun getProxyAwareConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val connection: URLConnection = config.proxy?.let { url.openConnection(it) } ?: url.openConnection()
        return connection as HttpURLConnection
    }
}

internal suspend fun OutgoingContent.writeTo(
    stream: OutputStream, callContext: CoroutineContext
): Unit = stream.use { blockingOutput ->
    when (this) {
        is OutgoingContent.ByteArrayContent -> blockingOutput.write(bytes())
        is OutgoingContent.ReadChannelContent -> run {
            readFrom().copyTo(blockingOutput)
        }
        is OutgoingContent.WriteChannelContent -> {
            val channel = GlobalScope.writer(callContext) {
                writeTo(channel)
            }.channel

            channel.copyTo(blockingOutput)
        }
        else -> throw UnsupportedContentTypeException(this)
    }
}

internal fun HttpURLConnection.content(callScope: CoroutineContext): ByteReadChannel = try {
    inputStream?.buffered()
} catch (_: IOException) {
    errorStream?.buffered()
}?.toByteReadChannel(context = callScope, pool = KtorDefaultPool) ?: ByteReadChannel.Empty

@Suppress("KDocMissingDocumentation")
internal class RequestInvalidException(override val message: String) : IllegalStateException()
