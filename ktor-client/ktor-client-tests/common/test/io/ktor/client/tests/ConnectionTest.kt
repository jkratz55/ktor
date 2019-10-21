/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.tests

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.client.statement.*
import io.ktor.client.tests.utils.*
import io.ktor.http.*
import io.ktor.utils.io.core.*
import kotlin.test.*

class ConnectionTest : ClientLoader() {
    private val testContent = buildString {
        append("x".repeat(100))
    }

    @Test
    fun testContentLengthWithEmptyBody() = clientTests {
        test { client ->
            repeat(10) {
                val response = client.request<HttpStatement> {
                    method = HttpMethod.Head
                    url.takeFrom("$TEST_SERVER/content/emptyHead")
                }.execute()

                response.also {
                    assertTrue(it.status.isSuccess())
                    assertTrue(it.readBytes().isEmpty())
                }
            }
        }
    }

    @Test
    fun testCloseResponseWithConnectionPipeline() = clientTests {
        suspend fun HttpClient.testCall(): HttpStatement = request {
            url.takeFrom("$TEST_SERVER/content/xxx")
        }

        test { client ->
            client.testCall().execute()
            assertEquals(testContent, client.testCall().receive())
        }
    }
}
