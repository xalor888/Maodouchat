package com.maodouchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupSenderKeyReloginPolicyTest {

    @Test
    fun keepStoreLogoutKeepsSenderKeys() {
        assertTrue(GroupSenderKeyReloginPolicy.keepSenderKeysOnLogout(destroyEncryptedDatabase = false))
        assertFalse(GroupSenderKeyReloginPolicy.keepSenderKeysOnLogout(destroyEncryptedDatabase = true))
    }

    @Test
    fun onlyStillWirePeerRowsRequestAfterRelogin() {
        assertTrue(
            GroupSenderKeyReloginPolicy.shouldRequestMissingPeerKey(
                isGroup = true,
                stillWire = true,
                senderId = "peer",
                currentUserId = "me"
            )
        )
        assertFalse(
            GroupSenderKeyReloginPolicy.shouldRequestMissingPeerKey(
                isGroup = true,
                stillWire = false,
                senderId = "peer",
                currentUserId = "me"
            )
        )
        assertFalse(
            GroupSenderKeyReloginPolicy.shouldRequestMissingPeerKey(
                isGroup = true,
                stillWire = true,
                senderId = "me",
                currentUserId = "me"
            )
        )
        assertFalse(
            GroupSenderKeyReloginPolicy.shouldRequestMissingPeerKey(
                isGroup = false,
                stillWire = true,
                senderId = "peer",
                currentUserId = "me"
            )
        )
    }

    @Test
    fun decryptFailureKeepsWireForRetry() {
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.NoSession))
        assertTrue(GroupSenderKeyRequestPolicy.shouldKeepGroupWire(SignalProtocol.DecryptResult.FutureEpoch))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(SignalProtocol.DecryptResult.Failed))
        assertTrue(GroupSenderKeyReloginPolicy.stillWireForRequest(isSenderKeyEnvelope = true, isSenderKeyMessage = false))
        assertFalse(GroupSenderKeyReloginPolicy.stillWireForRequest(isSenderKeyEnvelope = false, isSenderKeyMessage = false))
    }
}
