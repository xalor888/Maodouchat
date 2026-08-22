package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalSessionPolicyTest {

    @Test
    fun skipsCurrentDeviceSelfFetch() {
        assertFalse(
            SignalSessionPolicy.shouldEstablishSession(
                recipientId = "me",
                deviceId = 2,
                currentUserId = "me",
                localDeviceId = 2,
            )
        )
    }

    @Test
    fun allowsOtherOwnDeviceAndPeers() {
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("me", 3, "me", 2)
        )
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("peer", 2, "me", 2)
        )
        assertTrue(
            SignalSessionPolicy.shouldEstablishSession("peer", 1, null, 1)
        )
    }

    @Test
    fun rejectsBlankRecipientOrInvalidDevice() {
        assertFalse(SignalSessionPolicy.shouldEstablishSession("", 1, "me", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("peer", 0, "me", 1))
        assertFalse(SignalSessionPolicy.shouldEstablishSession("peer", -1, "me", 1))
    }
}
