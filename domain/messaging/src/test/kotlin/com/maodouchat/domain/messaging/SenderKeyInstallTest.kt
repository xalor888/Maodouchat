package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.SenderKeyDistOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SenderKeyInstallTest {

    @Test
    fun `only a failed install aborts`() {
        assertTrue(isSenderKeyInstallFailure(SenderKeyDistOutcome.Failed))
        assertFalse(isSenderKeyInstallFailure(SenderKeyDistOutcome.Installed))
        assertFalse(isSenderKeyInstallFailure(SenderKeyDistOutcome.Skipped))
    }

    @Test
    fun `the install failure code is the one the inbox dead-letters on`() {
        assertEquals(
            "messaging_v2_sender_key_install_failed",
            SENDER_KEY_INSTALL_FAILURE_MESSAGE,
        )
    }
}
