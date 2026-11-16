package com.maodouchat.domain.messaging

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppLockTimeoutPolicyTest {

    @Test
    fun `all ten whitelisted timeouts are kept`() {
        for (minutes in listOf(1L, 2L, 5L, 10L, 15L, 30L, 60L, 120L, 240L, 360L)) {
            assertEquals(minutes, normalizeAppLockTimeout(minutes))
        }
    }

    @Test
    fun `other values fall back to five minutes`() {
        assertEquals(5L, normalizeAppLockTimeout(0L))
        assertEquals(5L, normalizeAppLockTimeout(3L))
        assertEquals(5L, normalizeAppLockTimeout(7L))
        assertEquals(5L, normalizeAppLockTimeout(999L))
        assertEquals(5L, normalizeAppLockTimeout(-1L))
    }
}
