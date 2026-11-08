package com.maodouchat.domain.messaging

import com.maodouchat.core.model.MessageMutationKind
import com.maodouchat.core.model.MessagingV2EventAction
import com.maodouchat.domain.messaging.MessageEventMissingTargetPolicy.MissingTargetDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageEventMissingTargetPolicyTest {

    @Test
    fun `revoke without target tombstones as revoke`() {
        assertEquals(
            MissingTargetDecision.Tombstone(MessageMutationKind.REVOKE),
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.REVOKE),
        )
    }

    @Test
    fun `delete without target tombstones as delete`() {
        assertEquals(
            MissingTargetDecision.Tombstone(MessageMutationKind.DELETE),
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.DELETE),
        )
    }

    @Test
    fun `receipts without target are no ops`() {
        assertEquals(
            MissingTargetDecision.NoOp,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.DELIVERY_RECEIPT),
        )
        assertEquals(
            MissingTargetDecision.NoOp,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.READ_RECEIPT),
        )
        assertEquals(
            MissingTargetDecision.NoOp,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.PLAY_RECEIPT),
        )
    }

    @Test
    fun `edit and reactions without target are errors`() {
        assertEquals(
            MissingTargetDecision.Error,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.EDIT),
        )
        assertEquals(
            MissingTargetDecision.Error,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.REACTION_SET),
        )
        assertEquals(
            MissingTargetDecision.Error,
            MessageEventMissingTargetPolicy.decide(MessagingV2EventAction.REACTION_SNAPSHOT),
        )
    }

    @Test
    fun `unknown action without target is an error`() {
        assertEquals(
            MissingTargetDecision.Error,
            MessageEventMissingTargetPolicy.decide("SOMETHING_ELSE"),
        )
    }
}
