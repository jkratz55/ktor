/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.sockets

import io.ktor.network.selector.*
import kotlinx.coroutines.*
import io.ktor.utils.io.*
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.core.*
import io.ktor.utils.io.nio.*
import io.ktor.utils.io.pool.*
import java.nio.*
import java.nio.channels.*

internal fun CoroutineScope.attachForReadingImpl(
    channel: ByteChannel,
    nioChannel: ReadableByteChannel,
    selectable: Selectable,
    selector: SelectorManager,
    pool: ObjectPool<ByteBuffer>,
    idleTimeout: Long? = null
): WriterJob {
    val buffer = pool.borrow()
    return writer(Dispatchers.Unconfined + CoroutineName("cio-from-nio-reader"), channel) {
        try {
            while (true) {
                var rc = 0

                val readLambda: suspend CoroutineScope.() -> Unit = {
                    while (rc == 0) {
                        rc = nioChannel.read(buffer)
                        if (rc == 0) {
                            channel.flush()
                            selectable.interestOp(SelectInterest.READ, true)
                            selector.select(selectable, SelectInterest.READ)
                        }
                    }
                }

                if (idleTimeout == null) {
                    readLambda()
                }
                else {
                    withTimeout(idleTimeout, readLambda)
                }

                if (rc == -1) {
                    channel.close()
                    break
                } else {
                    selectable.interestOp(SelectInterest.READ, false)
                    buffer.flip()
                    channel.writeFully(buffer)
                    buffer.clear()
                }
            }
        } finally {
            pool.recycle(buffer)
            if (nioChannel is SocketChannel) {
                try {
                    nioChannel.socket().shutdownInput()
                } catch (ignore: ClosedChannelException) {
                }
            }
        }
    }
}

@UseExperimental(ExperimentalIoApi::class)
internal fun CoroutineScope.attachForReadingDirectImpl(
    channel: ByteChannel,
    nioChannel: ReadableByteChannel,
    selectable: Selectable,
    selector: SelectorManager,
    idleTimeout: Long? = null
): WriterJob = writer(Dispatchers.Unconfined + CoroutineName("cio-from-nio-reader"), channel) {
    try {
        selectable.interestOp(SelectInterest.READ, false)

        channel.writeSuspendSession {
            while (true) {
                val buffer = request(1)
                if (buffer == null) {
                    if (channel.isClosedForWrite) break
                    channel.flush()
                    tryAwait(1)
                    continue
                }

                var rc = 0

                val readLambda: suspend CoroutineScope.() -> Unit = {
                    while (rc == 0) {
                        rc = nioChannel.read(buffer)
                        if (rc == 0) {
                            channel.flush()
                            selectable.interestOp(SelectInterest.READ, true)
                            selector.select(selectable, SelectInterest.READ)
                        }
                    }
                }

                if (idleTimeout == null) {
                    readLambda()
                }
                else {
                    withTimeout(idleTimeout, readLambda)
                }

                if (rc == -1) {
                    break
                } else {
                    written(rc)
                }
            }
        }

        channel.close()
    } finally {
        if (nioChannel is SocketChannel) {
            try {
                nioChannel.socket().shutdownInput()
            } catch (ignore: ClosedChannelException) {
            }
        }
    }
}
