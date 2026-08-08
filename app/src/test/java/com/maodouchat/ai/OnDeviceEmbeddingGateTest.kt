package com.maodouchat.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDeviceEmbeddingGateTest {
    @Test
    fun `implementation stays blocked until eval gate opens`() {
        assertFalse(OnDeviceEmbeddingGate.isImplementationAllowed)
        val blocked = OnDeviceEmbeddingGate.assessApkBudget(
            currentApkBytes = 19L * 1024 * 1024,
            embeddingDeltaBytes = 1L * 1024 * 1024
        )
        assertFalse(blocked.allowed)
        assertTrue(blocked.reasons.contains("implementation_flag_false"))
    }

    @Test
    fun `oversized delta is rejected`() {
        val assessment = OnDeviceEmbeddingGate.assessApkBudget(
            currentApkBytes = 19L * 1024 * 1024,
            embeddingDeltaBytes = 5L * 1024 * 1024
        )
        assertFalse(assessment.allowed)
        assertTrue(assessment.reasons.any { it.startsWith("embedding_delta") })
    }

    @Test
    fun `isolation contracts are mandatory`() {
        assertTrue(OnDeviceEmbeddingGate.requiresOwnerScopedKeys())
        assertTrue(OnDeviceEmbeddingGate.mustPurgeOnAccountChange())
    }
}
