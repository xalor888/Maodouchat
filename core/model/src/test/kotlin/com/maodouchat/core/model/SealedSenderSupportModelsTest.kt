package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SealedSenderSupportModelsTest {

    @Test
    fun `certificate keeps fields and copy semantics`() {
        val cert = Certificate("v1.abc", 1700000000000L, 1, "u1")
        assertEquals("u1", cert.userId)
        assertEquals(2, cert.copy(deviceId = 2).deviceId)
    }

    @Test
    fun `cache key equality is by user and device`() {
        assertEquals(CacheKey("u1", 1), CacheKey("u1", 1))
    }
}
