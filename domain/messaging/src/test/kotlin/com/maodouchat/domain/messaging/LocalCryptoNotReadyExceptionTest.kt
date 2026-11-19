package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalCryptoNotReadyExceptionTest {

    @Test
    fun `default reason is signal local crypto not ready`() {
        val error = LocalCryptoNotReadyException()
        assertTrue(error is IllegalStateException)
        assertEquals("signal_local_crypto_not_ready", error.message)
    }

    @Test
    fun `custom reason is kept`() {
        assertEquals("bootstrapping", LocalCryptoNotReadyException("bootstrapping").message)
    }
}
