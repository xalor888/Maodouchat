package com.maodouchat.domain.messaging

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SenderKeyMissingEpochTest {

    @Test
    fun `non sender key ciphertext never asks for a repair`() = runTest {
        var providerCalls = 0
        assertNull(
            resolveSenderKeyMissingEpoch("TEXT", null) { providerCalls++; 9L },
        )
        assertEquals(0, providerCalls)
    }

    @Test
    fun `envelope revision wins and the provider is not consulted`() = runTest {
        var providerCalls = 0
        assertEquals(
            9L,
            resolveSenderKeyMissingEpoch("SENDER_KEY", 9L) { providerCalls++; 7L },
        )
        assertEquals(0, providerCalls)
    }

    @Test
    fun `a missing envelope revision falls back to the provided revision`() = runTest {
        assertEquals(
            7L,
            resolveSenderKeyMissingEpoch("SENDER_KEY", null) { 7L },
        )
    }

    @Test
    fun `no revision anywhere means no repair`() = runTest {
        var providerCalls = 0
        assertNull(
            resolveSenderKeyMissingEpoch("SENDER_KEY", null) { providerCalls++; null },
        )
        assertEquals(1, providerCalls)
    }

    @Test
    fun `a non-positive epoch never asks for a repair`() = runTest {
        assertNull(resolveSenderKeyMissingEpoch("SENDER_KEY", null) { 0L })
        assertNull(resolveSenderKeyMissingEpoch("SENDER_KEY", 0L) { 5L })
        assertNull(resolveSenderKeyMissingEpoch("SENDER_KEY", -3L) { 5L })
    }
}
