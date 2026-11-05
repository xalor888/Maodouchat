package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SignalInitializationModelsTest {

    @Test
    fun `signal initialization defaults to not ready`() {
        val state = SignalInitializationState()
        assertNull(state.accountId)
        assertFalse(state.localCryptoReady)
        assertFalse(state.publicationReady)
        assertEquals("u1", state.copy(accountId = "u1").accountId)
    }
}
