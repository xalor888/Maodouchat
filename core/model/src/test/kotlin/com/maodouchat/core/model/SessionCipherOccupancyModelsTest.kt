package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCipherOccupancyModelsTest {

    @Test
    fun `lease keeps its generation`() {
        assertEquals(42L, Lease(42L).generation)
    }
}
