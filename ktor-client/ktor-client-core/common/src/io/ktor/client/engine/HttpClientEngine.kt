/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Base interface use to define engines for [HttpClient].
 */
interface HttpClientEngine : CoroutineScope, Closeable {
    /**
     * [CoroutineDispatcher] specified for io operations.
     */
    val dispatcher: CoroutineDispatcher

    /**
     * Engine configuration
     */
    val config: HttpClientEngineConfig

    /**
     * Creates a new [HttpClientCall] specific for this engine, using a request [data].
     */
    @InternalAPI
    suspend fun execute(data: HttpRequestData): HttpResponseData

    /**
     * Install engine into [HttpClient].
     */
    @InternalAPI
    fun install(client: HttpClient) {
        client.sendPipeline.intercept(HttpSendPipeline.Engine) { content ->
            val requestData = HttpRequestBuilder().apply {
                takeFrom(context)
                body = content
            }.build()

            validateHeaders(requestData)

            val responseData = executeWithinCallContext(requestData)
            val call = HttpClientCall(client, requestData, responseData)

            responseData.callContext[Job]!!.invokeOnCompletion { cause ->
                @Suppress("UNCHECKED_CAST")
                val childContext = requestData.executionContext as CompletableJob
                if (cause == null) childContext.complete() else childContext.completeExceptionally(cause)
            }

            proceedWith(call)
        }
    }

    /**
     * Create call context and use it as a coroutine context to [execute] request.
     */
    private suspend fun executeWithinCallContext(requestData: HttpRequestData): HttpResponseData {
        val callContext = createCallContext(requestData.executionContext)

        return async(callContext + KtorCallContextElement(callContext[Job] as CompletableJob)) {
            execute(requestData)
        }.await()
    }
}


/**
 * Factory of [HttpClientEngine] with a specific [T] of [HttpClientEngineConfig].
 */
interface HttpClientEngineFactory<out T : HttpClientEngineConfig> {
    /**
     * Creates a new [HttpClientEngine] optionally specifying a [block] configuring [T].
     */
    fun create(block: T.() -> Unit = {}): HttpClientEngine
}

/**
 * Creates a new [HttpClientEngineFactory] based on this one
 * with further configurations from the [nested] block.
 */
fun <T : HttpClientEngineConfig> HttpClientEngineFactory<T>.config(nested: T.() -> Unit): HttpClientEngineFactory<T> {
    val parent = this

    return object : HttpClientEngineFactory<T> {
        override fun create(block: T.() -> Unit): HttpClientEngine = parent.create {
            nested()
            block()
        }
    }
}

/**
 * Returns current call context if exists, otherwise null.
 */
@InternalAPI
suspend fun callContext(): CoroutineContext? = coroutineContext[KtorCallContextElement]?.let {
    coroutineContext + it.callJob
}

/**
 * Coroutine context element containing call job.
 */
private class KtorCallContextElement(val callJob: CompletableJob) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*>
        get() = KtorCallContextElement

    companion object : CoroutineContext.Key<KtorCallContextElement>
}

/**
 * Create call context with the specified [parentJob] to be used during call execution in the engine. Call context
 * inherits [coroutineContext], but overrides job and coroutine name so that call job's parent is [parentJob] and
 * call coroutine's name is $engineName-call-context.
 */
private suspend fun createCallContext(parentJob: Job): CoroutineContext {
    val callJob = Job(parentJob)
    val callContext = coroutineContext + callJob + CoroutineName("call-context")

    attachToUserJob(callJob)

    return callContext
}

/**
 * Attach [callJob] to user job using the following logic: when user job completes with exception, [callJob] completes
 * with exception too.
 */
@UseExperimental(InternalCoroutinesApi::class)
private suspend inline fun attachToUserJob(callJob: Job) {
    val userJob = coroutineContext[Job]!!

    val cleanupHandler = userJob.invokeOnCompletion(onCancelling = true) { cause ->
        cause ?: return@invokeOnCompletion
        callJob.cancel(CancellationException(cause.message))
    }

    callJob.invokeOnCompletion {
        cleanupHandler.dispose()
    }
}

/**
 * Validates request headers and fails if there are unsafe headers supplied
 */
private fun validateHeaders(request: HttpRequestData) {
    val requestHeaders = request.headers
    for (header in HttpHeaders.UnsafeHeadersList) {
        if (header in requestHeaders) {
            throw UnsafeHeaderException(header)
        }
    }
}
