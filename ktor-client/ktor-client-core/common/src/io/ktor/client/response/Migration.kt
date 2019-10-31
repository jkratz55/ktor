/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.response

import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.*
import kotlin.coroutines.*


@Deprecated(
    "Unbound [HttpResponse] is deprecated. Consider using [HttpStatement] instead.",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("HttpStatement", "io.ktor.client.statement.HttpStatement")
)
class HttpResponse : CoroutineScope, HttpMessage {
    override val coroutineContext: CoroutineContext
        get() = error("")
    override val headers: Headers
        get() = error("")
}

@Suppress("DEPRECATION_ERROR")
@Deprecated(
    "Unbound [HttpResponse] is deprecated. Consider using [HttpStatement] instead.",
    level = DeprecationLevel.ERROR
)
suspend fun HttpResponse.readText(charset: Charset? = null): String {
    error("")
}

/**
 * Exactly reads [count] bytes of the [HttpResponse.content].
 */
@Deprecated(
    "Unbound [HttpResponse] is deprecated. Consider using [HttpStatement] instead.",
    level = DeprecationLevel.ERROR
)
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.readBytes(count: Int): ByteArray = TODO()

/**
 * Reads the whole [HttpResponse.content] if Content-Length was specified.
 * Otherwise it just reads one byte.
 */
@Deprecated(
    "Unbound [HttpResponse] is deprecated. Consider using [HttpStatement] instead.",
    level = DeprecationLevel.ERROR
)
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.readBytes(): ByteArray = TODO()

/**
 * Efficiently discards the remaining bytes of [HttpResponse.content].
 */
@Deprecated(
    "Unbound [HttpResponse] is deprecated. Consider using [HttpStatement] instead.",
    level = DeprecationLevel.ERROR
)
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.discardRemaining() {
    TODO()
}
