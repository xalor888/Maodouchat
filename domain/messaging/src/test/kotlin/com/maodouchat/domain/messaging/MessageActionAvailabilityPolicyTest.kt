package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageActionAvailabilityPolicyTest {

    @Test
    fun `own text within window can be edited`() {
        assertTrue(MessageActionAvailabilityPolicy.canEdit(true, true, MessageType.TEXT))
        assertTrue(MessageActionAvailabilityPolicy.canEdit(true, true, MessageType.MARKDOWN))
    }

    @Test
    fun `edit requires own message inside window and editable type`() {
        assertFalse(MessageActionAvailabilityPolicy.canEdit(false, true, MessageType.TEXT))
        assertFalse(MessageActionAvailabilityPolicy.canEdit(true, false, MessageType.TEXT))
        assertFalse(MessageActionAvailabilityPolicy.canEdit(true, true, MessageType.IMAGE))
        assertFalse(MessageActionAvailabilityPolicy.canEdit(true, true, MessageType.VOICE))
    }

    @Test
    fun `own message within window can be revoked unless already revoked`() {
        assertTrue(MessageActionAvailabilityPolicy.canRevoke(true, true, MessageType.TEXT))
        assertTrue(MessageActionAvailabilityPolicy.canRevoke(true, true, MessageType.IMAGE))
        assertFalse(MessageActionAvailabilityPolicy.canRevoke(true, true, MessageType.REVOKED))
    }

    @Test
    fun `revoke requires own message inside window`() {
        assertFalse(MessageActionAvailabilityPolicy.canRevoke(false, true, MessageType.TEXT))
        assertFalse(MessageActionAvailabilityPolicy.canRevoke(true, false, MessageType.TEXT))
    }

    @Test
    fun `others non-system messages can be reported`() {
        assertTrue(MessageActionAvailabilityPolicy.canReport(false, MessageType.TEXT))
        assertTrue(MessageActionAvailabilityPolicy.canReport(false, MessageType.IMAGE))
        assertFalse(MessageActionAvailabilityPolicy.canReport(true, MessageType.TEXT))
    }

    @Test
    fun `key distribution system and revoked messages cannot be reported`() {
        assertFalse(MessageActionAvailabilityPolicy.canReport(false, MessageType.SK_DIST))
        assertFalse(MessageActionAvailabilityPolicy.canReport(false, MessageType.SYSTEM))
        assertFalse(MessageActionAvailabilityPolicy.canReport(false, MessageType.REVOKED))
    }
}
