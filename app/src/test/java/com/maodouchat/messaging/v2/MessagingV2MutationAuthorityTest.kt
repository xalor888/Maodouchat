package com.maodouchat.messaging.v2

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessagingV2MutationAuthorityTest {
    @Test
    fun `message author may mutate own message`() {
        assertTrue(
            allowed(
                action = MessagingV2EventAction.EDIT,
                targetSender = "alice",
                envelopeSender = "alice",
            ),
        )
        assertTrue(
            allowed(
                action = MessagingV2EventAction.REVOKE,
                targetSender = "alice",
                envelopeSender = "alice",
            ),
        )
    }

    @Test
    fun `participant cannot edit revoke or delete another sender message`() {
        listOf(
            MessagingV2EventAction.EDIT,
            MessagingV2EventAction.REVOKE,
            MessagingV2EventAction.DELETE,
        ).forEach { action ->
            assertFalse(allowed(action, targetSender = "alice", envelopeSender = "mallory"))
        }
    }

    @Test
    fun `trusted service principal may only delete`() {
        assertTrue(
            allowed(
                action = MessagingV2EventAction.DELETE,
                targetSender = "alice",
                envelopeSender = "system",
                envelopeDevice = 0,
                kind = "SERVICE",
            ),
        )
        assertFalse(
            allowed(
                action = MessagingV2EventAction.EDIT,
                targetSender = "alice",
                envelopeSender = "system",
                envelopeDevice = 0,
                kind = "SERVICE",
            ),
        )
        assertFalse(
            allowed(
                action = MessagingV2EventAction.DELETE,
                targetSender = "alice",
                envelopeSender = "system",
                envelopeDevice = 1,
                kind = "SERVICE",
            ),
        )
    }

    @Test
    fun `author may delete own message`() {
        assertTrue(
            allowed(
                action = MessagingV2EventAction.DELETE,
                targetSender = "alice",
                envelopeSender = "alice",
            ),
        )
    }

    @Test
    fun `non-mutation actions pass through regardless of sender`() {
        // Authority only gates EDIT/REVOKE/DELETE; receipts and reactions skip the check.
        listOf(
            MessagingV2EventAction.REACTION_SET,
            MessagingV2EventAction.DELIVERY_RECEIPT,
            MessagingV2EventAction.READ_RECEIPT,
        ).forEach { action ->
            assertTrue(allowed(action, targetSender = "alice", envelopeSender = "mallory"))
        }
    }

    @Test
    fun `trusted service principal may not revoke`() {
        assertFalse(
            allowed(
                action = MessagingV2EventAction.REVOKE,
                targetSender = "alice",
                envelopeSender = "system",
                envelopeDevice = 0,
                kind = "SERVICE",
            ),
        )
    }

    @Test
    fun `service delete requires exact system identity`() {
        // Case-sensitive: "System" is not the trusted principal.
        assertFalse(
            allowed(
                action = MessagingV2EventAction.DELETE,
                targetSender = "alice",
                envelopeSender = "System",
                envelopeDevice = 0,
                kind = "SERVICE",
            ),
        )
        assertFalse(
            allowed(
                action = MessagingV2EventAction.DELETE,
                targetSender = "alice",
                envelopeSender = "",
                envelopeDevice = 0,
                kind = "SERVICE",
            ),
        )
    }

    @Test
    fun `blank envelope sender cannot mutate another sender message`() {
        assertFalse(
            allowed(
                action = MessagingV2EventAction.EDIT,
                targetSender = "alice",
                envelopeSender = "",
            ),
        )
    }

    private fun allowed(
        action: String,
        targetSender: String,
        envelopeSender: String,
        envelopeDevice: Int = 1,
        kind: String = "EVENT",
    ): Boolean = MessagingV2MutationAuthority.canApply(
        action = action,
        targetSenderUserId = targetSender,
        envelopeSenderUserId = envelopeSender,
        envelopeSenderDeviceId = envelopeDevice,
        envelopeKind = kind,
    )
}
