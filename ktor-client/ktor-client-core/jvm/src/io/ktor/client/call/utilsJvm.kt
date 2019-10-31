/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.call

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.net.*

/**
 * Constructs a [HttpClientCall] from this [HttpClient],
 * an [url] and an optional [block] configuring a [HttpRequestBuilder].
 */
@Deprecated(
    "",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("this.request<HttpStatement>(url, block)", "io.ktor.client.statement.HttpStatement")
)
suspend fun HttpClient.call(url: URL, block: HttpRequestBuilder.() -> Unit = {}): HttpClientCall = TODO()
