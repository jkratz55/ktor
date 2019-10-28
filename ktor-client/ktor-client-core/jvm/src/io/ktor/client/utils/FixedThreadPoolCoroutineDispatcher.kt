/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.utils

import kotlinx.coroutines.*
import java.util.concurrent.*

/**
 * Creates [CoroutineDispatcher] based on thread pool of [threadCount] threads.
 */
fun Dispatchers.fixedThreadPoolDispatcher(threadCount: Int): CoroutineDispatcher {
    return Executors.newFixedThreadPool(threadCount) {
        Thread(it).apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
}
