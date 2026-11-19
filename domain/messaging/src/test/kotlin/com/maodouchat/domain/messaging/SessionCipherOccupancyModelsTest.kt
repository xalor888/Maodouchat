package com.maodouchat.domain.messaging

import kotlin.test.assertEquals
import kotlin.test.Test

class SessionCipherOccupancyModelsTest {

    @Test
    fun `lease keeps its generation`() {
        assertEquals(42L, Lease(42L).generation)
    }
}
