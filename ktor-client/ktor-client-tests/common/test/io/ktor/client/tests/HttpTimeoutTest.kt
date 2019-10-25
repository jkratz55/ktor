/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.mock.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.util.date.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlin.reflect.*
import kotlin.test.*

/**
 * Util function that checks that the specified [exception] is the [expectedCause] itself or caused by it.
 */
private fun assertContainsCause(expectedCause: KClass<out Throwable>, exception: Throwable?) {
    var prevCause: Throwable? = null
    var currCause = exception
    while (currCause != null) {
        if (currCause::class == expectedCause) {
            return
        }

        prevCause = currCause
        currCause = currCause.cause
    }

    fail("Exception expected to have $expectedCause cause, but it doesn't (root cause is $prevCause).")
}

private fun TestClientBuilder<*>.testWithTimeout(timeout: Long, block: suspend (client: HttpClient) -> Unit) {
    test = { client ->
        try {
            withTimeout(timeout) {
                block(client)
            }
        }
        catch(e: Throwable) {
            throw IllegalStateException(e)
        }
    }
}

class HttpTimeoutTest : ClientLoader() {
    @Test
    fun getTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        testWithTimeout(5_000) { client ->
            val response = client.get<String>("$TEST_SERVER/timeout/with-delay?delay=10")
            assertEquals("Text", response)
        }
    }

    @Test
    fun getRequestTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 10 }
        }

        testWithTimeout(5_000) { client ->
            val exception = assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-delay?delay=500")
            }

            assertContainsCause(HttpTimeoutCancellationException::class, exception)
        }
    }

    @Test
    fun getWithSeparateReceive() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 200 }
        }

        testWithTimeout(5_000) { client ->
            val call = client.call("$TEST_SERVER/timeout/with-delay?delay=10") { method = HttpMethod.Get }
            val res: String = call.receive()

            assertEquals("Text", res)
        }
    }

    @Test
    fun getRequestTimeoutWithSeparateReceive() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 200 }
        }

        testWithTimeout(5_000) { client ->
            val call = client.call("$TEST_SERVER/timeout/with-stream?delay=100") { method = HttpMethod.Get }
            val exception = assertFails { call.receive<String>() }

            assertContainsCause(HttpTimeoutCancellationException::class, exception)
        }
    }

    @Test
    fun getStreamTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        testWithTimeout(5_000) { client ->
            val response = client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=10")

            assertEquals("Text", String(response))
        }
    }

    @Test
    fun getStreamRequestTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        testWithTimeout(5_000) { client ->
            val exception = assertFails {
                client.get<ByteArray>("$TEST_SERVER/timeout/with-stream?delay=200")
            }

            assertContainsCause(HttpTimeoutCancellationException::class, exception)
        }
    }

    @Test
    fun redirectTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 500 }
        }

        testWithTimeout(5_000) { client ->
            val response = client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=10&count=2")
            assertEquals("Text", response)
        }
    }

    @Test
    fun redirectRequestTimeoutOnFirstStepTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 10 }
        }

        testWithTimeout(5_000) { client ->
            val exception = assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=500&count=5")
            }

            assertEquals(HttpTimeoutCancellationException::class, exception::class)
        }
    }

    @Test
    fun redirectRequestTimeoutOnSecondStepTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 200 }
        }

        testWithTimeout(5_000) { client ->
            val exception = assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-redirect?delay=250&count=5")
            }

            assertContainsCause(HttpTimeoutCancellationException::class, exception)
        }
    }

    @Test
    fun connectionTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 1000 }
        }

        testWithTimeout(5_000) { client ->
            client.get<String>("http://www.google.com")
            val start = GMTDate().timestamp
            assertFails {
                client.get<String>("http://www.google.com:81")
            }
            val end = GMTDate().timestamp
            val time = end - start
            println("Connect timeout took $time ms")
            assertTrue("Time is $time, expected ~1000.") { time > 1000 }
            assertTrue("Time is $time, expected ~1000.") { time < 1100 }
        }
    }

    @Test
    fun socketTimeoutTest() = clientTests {
        config {
            install(HttpTimeout) { requestTimeout = 1000 }
        }

        testWithTimeout(5_000) { client ->
            client.get<String>("http://www.google.com")
            val start = GMTDate().timestamp
            assertFails {
                client.get<String>("$TEST_SERVER/timeout/with-stream?delay=5000")
            }
            val end = GMTDate().timestamp
            val time = end - start
            println("Socket timeout took $time ms")
            assertTrue("Time is $time, expected ~1000.") { time > 1000 }
            assertTrue("Time is $time, expected ~1000.") { time < 1100 }
        }
    }
}
