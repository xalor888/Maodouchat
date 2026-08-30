package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupEncryptionHealthPolicyTest {

    @Test
    fun `healthy when key present and epoch current`() {
        assertEquals(
            GroupEncryptionHealth.HEALTHY,
            GroupEncryptionHealthPolicy.evaluate(hasDistributionKey = true, localEpoch = 5, currentEpoch = 5),
        )
    }

    @Test
    fun `missing key when no distribution`() {
        assertEquals(
            GroupEncryptionHealth.MISSING_KEY,
            GroupEncryptionHealthPolicy.evaluate(hasDistributionKey = false, localEpoch = 5, currentEpoch = 5),
        )
    }

    @Test
    fun `stale epoch when local behind current`() {
        assertEquals(
            GroupEncryptionHealth.STALE_EPOCH,
            GroupEncryptionHealthPolicy.evaluate(hasDistributionKey = true, localEpoch = 3, currentEpoch = 5),
        )
    }

    @Test
    fun `unknown epoch when current is unknown`() {
        assertEquals(
            GroupEncryptionHealth.UNKNOWN_EPOCH,
            GroupEncryptionHealthPolicy.evaluate(hasDistributionKey = true, localEpoch = 5, currentEpoch = null),
        )
    }
}
