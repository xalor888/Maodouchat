package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MultiRecipientCoverageModelsTest {

    @Test
    fun `coverage target keeps user and device`() {
        val target = Target("u1", 3)
        assertEquals("u1", target.userId)
        assertEquals(3, target.deviceId)
        assertEquals(Target("u1", 3), target.copy(deviceId = 3))
    }
}
