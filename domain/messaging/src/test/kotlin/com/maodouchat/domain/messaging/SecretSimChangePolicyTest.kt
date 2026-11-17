package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecretSimChangePolicyTest {

    @Test
    fun storedBlankBecomesNull() {
        assertNull(SecretSimChangePolicy.normalizeStoredSimId("   "))
        assertNull(SecretSimChangePolicy.normalizeStoredSimId(null))
    }

    @Test
    fun storedValuePassesThrough() {
        assertEquals("sim-1", SecretSimChangePolicy.normalizeStoredSimId("sim-1"))
    }

    @Test
    fun normalizeSimIdTrims() {
        assertEquals("sim-1", SecretSimChangePolicy.normalizeSimId("  sim-1 "))
    }

    @Test
    fun noPreviousMeansNoChange() {
        assertFalse(SecretSimChangePolicy.isSimChanged(null, "sim-1"))
    }

    @Test
    fun sameSimMeansNoChange() {
        assertFalse(SecretSimChangePolicy.isSimChanged("sim-1", "sim-1"))
        assertFalse(SecretSimChangePolicy.isSimChanged("sim-1", "  sim-1  "))
    }

    @Test
    fun differentSimMeansChanged() {
        assertTrue(SecretSimChangePolicy.isSimChanged("sim-1", "sim-2"))
    }
}
