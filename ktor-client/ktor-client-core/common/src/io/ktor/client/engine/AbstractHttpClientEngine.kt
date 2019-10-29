/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine

import io.ktor.client.request.*
import io.ktor.util.*
import io.ktor.utils.io.core.*
import kotlinx.atomicfu.*
import kotlinx.coroutines.*
import kotlin.coroutines.*

/**
 * Abstract implementation of [HttpClientEngine] responsible for lifecycle control of [clientContext], [dispatcher] and
 * [coroutineContext] as well as proper call context management. Should be considered as the best parent class for
 * custom [HttpClientEngine] implementations.
 */
abstract class AbstractHttpClientEngine(
    private val engineName: String,
    clientContextInitializer: () -> CoroutineContext = { SilentSupervisor() },
    dispatcherInitializer: () -> CoroutineDispatcher = { Dispatchers.Unconfined }
) : HttpClientEngine {

    override val clientContext: CoroutineContext by lazy(clientContextInitializer)

    override val dispatcher: CoroutineDispatcher by lazy(dispatcherInitializer)

    override val coroutineContext: CoroutineContext by lazy {
        this.clientContext + this.dispatcher + CoroutineName("$engineName-context")
    }

    /**
     * Flag that identifies that client is closed.
     */
    private val closed = atomic(false)

    /**
     * Execute [data] request processing that assumed to be made within call context. This method should be implemented
     * in engines so that superior code (see [execute]) could control context creation, completion and [data] execution.
     */
    protected abstract suspend fun executeWithinCallContext(
        data: HttpRequestData,
        callContext: CoroutineContext
    ): HttpResponseData

    @InternalCoroutinesApi
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = createCallContext(data.executionContext)
        val callJob = callContext[Job] as CompletableJob

        return try {
            withContext(callContext) {
                executeWithinCallContext(data, callContext)
            }
        }
        catch (cause: Throwable) {
            callJob.completeExceptionally(cause)
            throw cause
        }
    }

    override fun close() {
        checkClientEngineIsNotClosedAndClose()
        closeWithoutCheck()
    }

    /**
     * Call [close] method and adds completion handler that will be invoked when the coroutine context completes.
     * This method assumed to be called in subclasses in case of [close] override.
     */
    protected fun closeAndExecuteOnCompletion(block: () -> Unit = {}) {
        checkClientEngineIsNotClosedAndClose()
        closeWithoutCheck()

        coroutineContext[Job]?.invokeOnCompletion {
            block()
        }
    }

    /**
     * Check that this client engine is not closed yet, otherwise throw [ClientClosedException].
     */
    protected fun checkClientEngineIsNotClosed() {
        if (closed.value) {
            throw ClientClosedException()
        }
    }

    /**
     * Check that this client engine is not closed yet and closes it, otherwise throw [ClientClosedException].
     */
    private fun checkClientEngineIsNotClosedAndClose() {
        if (!closed.compareAndSet(false, true)) {
            throw ClientClosedException()
        }
    }

    /**
     * Create call context with the specified [parentJob] to be used during call execution in the engine. Call context
     * inherits [coroutineContext], but overrides job and coroutine name so that call job's parent is [parentJob] and
     * call coroutine's name is $engineName-call-context.
     */
    @InternalCoroutinesApi
    private suspend fun createCallContext(parentJob: Job): CoroutineContext {
        val callJob = Job(parentJob)
        val callContext = coroutineContext + callJob + CoroutineName("$engineName-call-context")

        bindCallJobWithUserJob(callJob)

        return callContext
    }

    /**
     * Close [dispatcher] if it's [Closeable].
     */
    private fun closeDispatcher() {
        val dispatcher = dispatcher
        if (dispatcher is Closeable) {
            try {
                dispatcher.close()
            }
            catch(ignore: Throwable) {
                // Some closeable dispatchers like Dispatchers.IO can't be closed.
            }
        }
    }

    /**
     * Close this client engine without checking if it's already closed.
     */
    private fun closeWithoutCheck() {
        val job = clientContext[Job] as CompletableJob

        job.complete()
        job.invokeOnCompletion { closeDispatcher() }
    }

    /**
     * TODO: This logic inherited from HttpClientJvmEngine, we need to check if it's actually needed.
     * Bind [callJob] with user job using the following logic: when job completes with exception, [callJob] completes
     * with exception too.
     */
    @InternalCoroutinesApi
    private suspend fun bindCallJobWithUserJob(callJob: Job) {
        currentContext()[Job]?.let { userJob ->
            val onUserCancelCleanupHandle = userJob.invokeOnCompletion(onCancelling = true) { cause ->
                if (cause != null) {
                    callJob.cancel(CancellationException(cause.message))
                }
            }

            callJob.invokeOnCompletion { onUserCancelCleanupHandle.dispose() }
        }
    }
}

/**
 * Exception that indicates that client engine is already closed.
 */
class ClientClosedException(override val cause: Throwable? = null) : IllegalStateException("Client already closed")

/**
 * Util function that returns current user coroutine context.
 */
private suspend inline fun currentContext() = coroutineContext
