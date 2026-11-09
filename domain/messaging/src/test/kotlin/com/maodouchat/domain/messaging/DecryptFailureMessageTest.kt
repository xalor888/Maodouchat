package com.maodouchat.domain.messaging

import com.maodouchat.core.crypto.DecryptResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DecryptFailureMessageTest {

    @Test
    fun `each failure maps to its own diagnostic code`() {
        assertEquals("messaging_v2_no_session", decryptFailureMessage(DecryptResult.NoSession))
        assertEquals(
            "messaging_v2_untrusted_identity",
            decryptFailureMessage(DecryptResult.UntrustedIdentity),
        )
        assertEquals(
            "messaging_v2_future_group_revision",
            decryptFailureMessage(DecryptResult.FutureEpoch),
        )
        assertEquals(
            "messaging_v2_wrong_device",
            decryptFailureMessage(DecryptResult.NotForThisDevice),
        )
        assertEquals(
            "messaging_v2_unsupported_ciphertext",
            decryptFailureMessage(DecryptResult.UnsupportedEnvelope),
        )
        assertEquals("messaging_v2_decrypt_failed", decryptFailureMessage(DecryptResult.Failed))
    }

    @Test
    fun `the six codes are all distinct`() {
        val codes = listOf(
            DecryptResult.NoSession,
            DecryptResult.UntrustedIdentity,
            DecryptResult.FutureEpoch,
            DecryptResult.NotForThisDevice,
            DecryptResult.UnsupportedEnvelope,
            DecryptResult.Failed,
        ).map(::decryptFailureMessage)
        assertEquals(codes.distinct().size, codes.size)
    }

    @Test
    fun `success and duplicate have no failure message`() {
        assertFailsWith<IllegalStateException> {
            decryptFailureMessage(DecryptResult.Success("plain"))
        }
        assertFailsWith<IllegalStateException> {
            decryptFailureMessage(DecryptResult.Duplicate)
        }
    }
}
