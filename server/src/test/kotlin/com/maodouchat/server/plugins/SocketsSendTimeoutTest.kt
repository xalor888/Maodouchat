package com.maodouchat.server.plugins

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocketsSendTimeoutTest {
    @Test
    fun `local send timeout becomes io failure without cancelling caller`() = runBlocking {
        val failure = runCatching {
            runWithWsSendTimeout(timeoutMs = 20L) {
                delay(200L)
            }
        }.exceptionOrNull()

        assertIs<IOException>(failure)
        assertTrue(coroutineContext[kotlinx.coroutines.Job]?.isActive == true)
    }

    @Test
    fun `external cancellation is not converted to io failure`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val send = async {
            runWithWsSendTimeout(timeoutMs = 5_000L) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        send.cancel()

        val failure = runCatching { send.await() }.exceptionOrNull()
        assertIs<CancellationException>(failure)
    }
}
