/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.statement

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.reflect.*

/**
 * Prepared statement for http client request.
 * This statement doesn't perform any network requests until [execute] method call.
 *
 * [HttpStatement] is safe to execute multiple times.
 */
class HttpStatement(
    private val builder: HttpRequestBuilder,
    private val client: HttpClient
) {
    /**
     * Executes this statement and call the [block] with the streaming [response].
     *
     * The [response] argument holds a network connection until the [block] isn't completed. You can read the body
     * on-demand or at once with [receive<T>()] method.
     *
     * After [block] finishes, [response] will be completed body will be discarded or released depends on the engine configuration.
     */
    suspend fun <T> execute(block: suspend (response: HttpResponse) -> T): T {
        val builder = HttpRequestBuilder().takeFrom(builder)
        @Suppress("DEPRECATION_ERROR")
        val call = client.execute(builder)

        try {
            return block(call.response)
        } finally {
            val job = call.coroutineContext[Job]!! as CompletableJob

            job.apply {
                complete()
                try {
                    call.response.content.cancel()
                } catch (_: Throwable) {}
                join()
            }
        }
    }

    /**
     * Executes this statement and download the response.
     * After the method finishes, the client downloads the response body in memory and release the connection.
     *
     * To receive exact type you consider using [receive<T>()] method.
     */
    suspend fun execute(): HttpResponse = execute {
        val savedCall = it.call.save()
        savedCall.response
    }

    /**
     * Executes this statement and run [HttpClient.responsePipeline] with the response and expected type [T].
     *
     * Note that T can't be a streamed type, because the connection is released at the end of the [receive].
     */
    @UseExperimental(ExperimentalStdlibApi::class)
    suspend inline fun <reified T> receive(): T = when (typeOf<T>()) {
        typeOf<HttpStatement>() -> this as T
        typeOf<HttpResponse>() -> execute() as T
        typeOf<ByteReadChannel>() -> throw IllegalArgumentException(
            "[ByteReadChannel] is streaming content. Consider using method with [block] parameter or fetch response in memory with [receive<ByteReadPacket>()]."
        )
        typeOf<WebSocketSession>() -> throw IllegalArgumentException(
            "[WebSocketSession] is streaming content. Consider using method with [block] parameter."
        )
        typeOf<Input>() -> {
            TODO()
        }
//        typeOf<InputStream>() -> {
//            TODO()
//        }
        else -> execute<T> { it.receive<T>() }
    }

    /**
     * Executes this statement and run the [block] with a [HttpClient.responsePipeline] execution result.
     *
     * Note that T can be a streamed type such as [ByteReadChannel].
     */
    suspend inline fun <reified T, R> receive(crossinline block: suspend (response: T) -> R) = execute {
        val response = it.receive<T>()
        return@execute block(response)
    }
}

@Deprecated(
    "[HttpStatement] isn't closeable.",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("this.execute<T>(block)")
)
@Suppress("KDocMissingDocumentation")
fun <T> HttpStatement.use(block: suspend (response: HttpResponse) -> T) {
}


@Deprecated("", level = DeprecationLevel.ERROR, replaceWith = ReplaceWith("this.execute()"))
@Suppress("KDocMissingDocumentation")
val HttpStatement.response: HttpResponse
    get() = error("")

/**
 * Read the [HttpResponse.content] as a String. You can pass an optional [charset]
 * to specify a charset in the case no one is specified as part of the Content-Type response.
 * If no charset specified either as parameter or as part of the response,
 * [HttpResponseConfig.defaultCharset] will be used.
 *
 * Note that [charset] parameter will be ignored if the response already has a charset.
 *      So it just acts as a fallback, honoring the server preference.
 */
suspend fun HttpResponse.readText(charset: Charset? = null): String {
    val originCharset = charset() ?: charset ?: Charsets.UTF_8
    val decoder = originCharset.newDecoder()
    val input = receive<Input>()

    return decoder.decode(input)
}
