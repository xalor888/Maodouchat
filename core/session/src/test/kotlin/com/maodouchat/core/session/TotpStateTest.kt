package com.maodouchat.core.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
