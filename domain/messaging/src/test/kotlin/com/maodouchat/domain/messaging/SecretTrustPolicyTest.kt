package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretTrustPolicyTest {

    @Test
    fun normalizeIdsTrimsDropsBlankAndDedups() {
        assertEquals(
            setOf("a", "b"),
            SecretTrustPolicy.normalizeIds(listOf(" a ", "", "  ", "b", "a")),
        )
    }

    @Test
    fun normalizeIdTrims() {
        assertEquals("abc", SecretTrustPolicy.normalizeId("  abc  "))
    }

    @Test
    fun blankDeviceNeverTrusted() {
        assertFalse(SecretTrustPolicy.isDeviceTrusted(true, "  ", setOf("x")))
        assertFalse(SecretTrustPolicy.isDeviceTrusted(false, "", setOf("x")))
    }

    @Test
    fun disabledGateTrustsAnyNonBlankDevice() {
        assertTrue(SecretTrustPolicy.isDeviceTrusted(false, "unknown", emptySet()))
    }

    @Test
    fun enabledGateChecksKnownSet() {
        assertTrue(SecretTrustPolicy.isDeviceTrusted(true, "d1", setOf("d1", "d2")))
        assertFalse(SecretTrustPolicy.isDeviceTrusted(true, "d3", setOf("d1", "d2")))
    }

    @Test
    fun blankFingerprintNeverVerified() {
        assertFalse(SecretTrustPolicy.isFingerprintVerified(true, " ", setOf("fp")))
    }

    @Test
    fun disabledGateVerifiesAnyNonBlankFingerprint() {
        assertTrue(SecretTrustPolicy.isFingerprintVerified(false, "fp", emptySet()))
    }

    @Test
    fun enabledGateChecksVerifiedSet() {
        assertTrue(SecretTrustPolicy.isFingerprintVerified(true, "fp", setOf("fp")))
        assertFalse(SecretTrustPolicy.isFingerprintVerified(true, "other", setOf("fp")))
    }

    @Test
    fun blankTargetNeverForwardAllowed() {
        assertFalse(SecretTrustPolicy.isForwardAllowed(true, " ", setOf("c1")))
    }

    @Test
    fun disabledGateAllowsAnyNonBlankTarget() {
        assertTrue(SecretTrustPolicy.isForwardAllowed(false, "c9", emptySet()))
    }

    @Test
    fun emptyWhitelistDeniesAll() {
        assertFalse(SecretTrustPolicy.isForwardAllowed(true, "c1", emptySet()))
    }

    @Test
    fun nonEmptyWhitelistRequiresHit() {
        assertTrue(SecretTrustPolicy.isForwardAllowed(true, "c1", setOf("c1", "c2")))
        assertFalse(SecretTrustPolicy.isForwardAllowed(true, "c3", setOf("c1", "c2")))
    }
}
