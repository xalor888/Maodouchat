package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.Test

class TotpStateTest {

    @Test
    fun setupReady_defaultsAreEmptyAndNotConfirming() {
        val state = TotpState.SetupReady(secret = "JBSW")
        assertEquals("JBSW", state.secret)
        assertNull(state.otpauthUrl)
        assertTrue(state.recoveryCodes.isEmpty())
        assertEquals(false, state.isConfirming)
        assertNull(state.error)
    }

    @Test
    fun enabled_defaultsAreIdleAndErrorFree() {
        val state = TotpState.Enabled()
        assertTrue(state.recoveryCodes.isEmpty())
        assertEquals(false, state.isWorking)
        assertNull(state.error)
    }

    @Test
    fun error_carriesPreviousState() {
        val error = TotpState.Error(message = "bad code", previousState = TotpState.Disabled)
        assertEquals(TotpState.Disabled, error.previousState)
    }
}
