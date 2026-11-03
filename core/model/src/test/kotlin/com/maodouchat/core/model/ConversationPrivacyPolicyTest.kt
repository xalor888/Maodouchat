package com.maodouchat.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrivacyPolicyTest {

    private val policy = ConversationPrivacyPolicy()

    @Test
    fun `locked context rejects every capability`() {
        val context = ConversationPrivacyContext(isLocked = true)
        for (capability in ConversationCapability.entries) {
            val decision = policy.evaluate(capability, context)
            assertTrue(decision is ConversationPrivacyDecision.Rejected)
            assertEquals(
                ConversationPrivacyRejection.LOCKED_CONVERSATION,
                (decision as ConversationPrivacyDecision.Rejected).reason
            )
        }
    }

    @Test
    fun `secret context rejects background staging capabilities`() {
        val context = ConversationPrivacyContext(isSecret = true)
        assertEquals(
            ConversationPrivacyDecision.Rejected(ConversationPrivacyRejection.SECRET_CONVERSATION),
            policy.evaluate(ConversationCapability.SCHEDULE, context)
        )
        assertEquals(
            ConversationPrivacyDecision.Rejected(ConversationPrivacyRejection.SECRET_CONVERSATION),
            policy.evaluate(ConversationCapability.QUICK_REPLY, context)
        )
    }

    @Test
    fun `secret context allows foreground text`() {
        assertEquals(
            ConversationPrivacyDecision.Allowed,
            policy.evaluate(
                ConversationCapability.SEND_TEXT,
                ConversationPrivacyContext(isSecret = true)
            )
        )
    }

    @Test
    fun `open context allows every capability`() {
        val context = ConversationPrivacyContext()
        for (capability in ConversationCapability.entries) {
            assertEquals(ConversationPrivacyDecision.Allowed, policy.evaluate(capability, context))
        }
    }
}
