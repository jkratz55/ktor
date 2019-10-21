/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.response

import io.ktor.http.*
import io.ktor.utils.io.charsets.*
import kotlinx.coroutines.*
import kotlin.coroutines.*


@Deprecated(
    "",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("HttpStatement", "io.ktor.client.statement.HttpStatement")
)
class HttpResponse : CoroutineScope, HttpMessage {
    override val coroutineContext: CoroutineContext
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
    override val headers: Headers
        get() = TODO("not implemented") //To change initializer of created properties use File | Settings | File Templates.
}

@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.readText(charset: Charset? = null): String {
    TODO()
}

/**
 * Exactly reads [count] bytes of the [HttpResponse.content].
 */
@Deprecated("")
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.readBytes(count: Int): ByteArray = TODO()

/**
 * Reads the whole [HttpResponse.content] if Content-Length was specified.
 * Otherwise it just reads one byte.
 */
@Deprecated("")
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.readBytes(): ByteArray = TODO()

/**
 * Efficiently discards the remaining bytes of [HttpResponse.content].
 */
@Deprecated("")
@Suppress("DEPRECATION_ERROR")
suspend fun HttpResponse.discardRemaining() {
    TODO()
}
