package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalDeviceIdRecoveryPolicyModelsTest {

    @Test
    fun `pending approval exception names the device`() {
        assertEquals("signal_device_pending_approval:9",
            DevicePendingApprovalException(9).message)
    }

    @Test
    fun `status unavailable exception keeps its cause`() {
        val cause = IllegalStateException("missing")
        assertEquals(cause, DeviceStatusUnavailableException(cause).cause)
    }

    @Test
    fun `identity recovery exception marks the failed migration`() {
        assertTrue(IdentityRecoveryFailedException(null).message
            ?.startsWith("signal_identity_recovery_failed") == true)
    }
}
