package com.maodouchat.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecryptHistoryPolicyTest {

    @Test
    fun `success drops the wire row, failures keep it`() {
        assertFalse(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.Success("ok")))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldKeepWire(DecryptResult.NotForThisDevice))
    }

    @Test
    fun `only session-repairable results defer repair`() {
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.NoSession))
        assertTrue(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.UntrustedIdentity))
        assertFalse(DecryptHistoryPolicy.shouldDeferSessionRepair(DecryptResult.Failed))
    }

    @Test
    fun `repair attempts de-duplicate per sender`() {
        assertTrue(DecryptHistoryPolicy.shouldAttemptSessionRepair("alice", emptySet()))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("alice", setOf("alice")))
        assertFalse(DecryptHistoryPolicy.shouldAttemptSessionRepair("  ", emptySet()))
    }

    @Test
    fun `new device without restored identity cannot decrypt history`() {
        assertTrue(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(false))
        assertFalse(DecryptHistoryPolicy.newDeviceHistoryCannotDecrypt(true))
    }
}
