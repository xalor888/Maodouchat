package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCostVisibilityPolicyTest {

    @Test
    fun `http 429 classifies as rate limited and maps to rate limit code`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(429, null, null, null)
        assertTrue(signal.isRateLimited)
        assertFalse(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_RATE_LIMIT, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `http 402 classifies as quota and maps to quota code`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(402, null, null, null)
        assertTrue(signal.isQuota)
        assertEquals(AiCostVisibilityPolicy.ERROR_QUOTA, AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `non limited failure maps to null code`() {
        val signal = AiCostVisibilityPolicy.classifyHttpFailure(500, null, null, null)
        assertFalse(signal.isRateLimited)
        assertNull(AiCostVisibilityPolicy.mapToErrorCode(signal))
    }

    @Test
    fun `default waits are quota 300 and rate limit 30`() {
        assertEquals(300L, AiCostVisibilityPolicy.waitSecondsFor(AiCostVisibilityPolicy.ERROR_QUOTA))
        assertEquals(30L, AiCostVisibilityPolicy.waitSecondsFor(AiCostVisibilityPolicy.ERROR_RATE_LIMIT))
        assertEquals(45L, AiCostVisibilityPolicy.waitSecondsFor(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT, serverRetryAfterSeconds = 45L))
    }

    @Test
    fun `error code suffix round trips through encode and base`() {
        assertEquals("RATE_LIMITED:45", AiCostVisibilityPolicy.encodeErrorCode(
            AiCostVisibilityPolicy.ERROR_RATE_LIMIT, 45L))
        assertEquals("RATE_LIMITED", AiCostVisibilityPolicy.baseErrorCode("RATE_LIMITED:45"))
    }

    @Test
    fun `streaming and cancelled hints mark possible billing`() {
        assertEquals(BillingHint.IN_FLIGHT_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(null, isStreaming = true))
        assertEquals(BillingHint.CANCELLED_PARTIAL_MAY_BILL,
            AiCostVisibilityPolicy.billingHintFor(AiCostVisibilityPolicy.ERROR_CANCELLED))
    }

    @Test
    fun `display signal bundles code wait hint and retry warning`() {
        val signal = AiCostVisibilityPolicy.displaySignalFor(AiCostVisibilityPolicy.ERROR_QUOTA)
        assertEquals("QUOTA_EXCEEDED", signal.errorCode)
        assertEquals(300L, signal.waitSeconds)
        assertEquals(BillingHint.QUOTA_EXCEEDED, signal.hint)
        assertTrue(signal.warnRetryBills)
    }
}
