/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine

import io.ktor.client.request.*
import io.ktor.client.utils.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Abstract implementation of [HttpClientEngine] responsible for lifecycle control of [dispatcher] and
 * [coroutineContext] as well as proper call context management. Should be considered as the best parent class for
 * custom [HttpClientEngine] implementations.
 */
abstract class HttpClientEngineBase(private val engineName: String) : HttpClientEngine {

    override val coroutineContext: CoroutineContext by lazy {
        SilentSupervisor() + this.dispatcher + CoroutineName("$engineName-context")
    }

    /**
     * Flag that identifies that client is closed. For internal usage.
     */
    private val _closed = AtomicBoolean(false)

    /**
     * Flag that identifies that client is closed.
     */
    protected val closed: Boolean
        get() = _closed.value

    /**
     * Execute [data] request processing that assumed to be made within call context. This method should be implemented
     * in engines so that superior code (see [execute]) could control context creation, completion and [data] execution.
     */
    internal suspend fun executeWithCallContext(data: HttpRequestData): HttpResponseData {
        val callContext = createCallContext(data.executionContext, "$engineName-call-context")

        return try {
            withContext(callContext + KtorCallContextElement(callContext[Job] as CompletableJob)) {
                execute(data)
            }
        } catch (cause: Throwable) {
            (callContext[Job] as CompletableJob).completeExceptionally(cause)
            throw cause
        }
    }

    override fun close() {
        if (!_closed.compareAndSet(false, true)) {
            throw ClientEngineClosedException()
        }

        (coroutineContext[Job] as CompletableJob).apply {
            complete()
            invokeOnCompletion {
                dispatcher.close()
            }
        }
    }
}

/**
 * Exception that indicates that client engine is already closed.
 */
class ClientEngineClosedException(override val cause: Throwable? = null) :
    IllegalStateException("Client already closed")

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
private suspend fun createCallContext(parentJob: Job, coroutineName: String): CoroutineContext {
    val callJob = Job(parentJob)
    val callContext = coroutineContext + callJob + CoroutineName(coroutineName)

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
        if (cause == null) {
            return@invokeOnCompletion
        }

        callJob.cancel(CancellationException(cause.message))
    }

    callJob.invokeOnCompletion {
        cleanupHandler.dispose()
    }
}

/**
 * Close [dispatcher] if it's [Closeable].
 */
private fun CoroutineDispatcher.close() = try {
    (this as? Closeable)?.close()
} catch (ignore: Throwable) {
    // Some closeable dispatchers like Dispatchers.IO can't be closed.
}
