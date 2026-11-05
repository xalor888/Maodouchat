package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSurfaceGateModelsTest {

    @Test
    fun `surface gate defaults to no gate`() {
        val state = AppSurfaceGateState()
        assertFalse(state.showFakeChat)
        assertFalse(state.showAppLock)
        assertEquals(true, state.copy(showAppLock = true).showAppLock)
    }
}
