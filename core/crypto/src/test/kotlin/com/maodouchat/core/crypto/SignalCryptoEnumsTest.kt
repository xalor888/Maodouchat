package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalCryptoEnumsTest {

    @Test
    fun `exchange failure entries keep declaration order`() {
        assertEquals(
            listOf(
                SignalExchangeFailure.SIGNED_PREKEY_MISSING,
                SignalExchangeFailure.HTTP,
                SignalExchangeFailure.EMPTY_RESPONSE,
                SignalExchangeFailure.TIMEOUT,
                SignalExchangeFailure.NETWORK,
                SignalExchangeFailure.INVALID_RESPONSE,
                SignalExchangeFailure.UNEXPECTED,
            ),
            SignalExchangeFailure.entries.toList(),
        )
    }

    @Test
    fun `sender key dist outcome round-trips every entry`() {
        SenderKeyDistOutcome.entries.forEach {
            assertEquals(it, SenderKeyDistOutcome.valueOf(it.name))
        }
    }

    @Test
    fun `initialization action flags rebuild correctly`() {
        assertTrue(SignalInitializationAction.FULL_INITIALIZATION.rebuildsLocalStore)
        assertEquals(false, SignalInitializationAction.REUSE.rebuildsLocalStore)
        assertEquals(false, SignalInitializationAction.UPLOAD_ONLY.rebuildsLocalStore)
    }
}
