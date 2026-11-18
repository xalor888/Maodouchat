package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import kotlin.test.Test
import kotlin.test.assertEquals

class OutgoingConversationResolverModelsTest {

    @Test
    fun `outgoing conversation models hold request and resolution`() {
        val request = OutgoingConversationRequest(
            ownerUserId = "u1",
            authToken = "t",
            activeConversationId = "c1",
            constructorConversationId = "c1",
            paintedConversation = null,
            activeContactId = "u2",
            createSecretConversation = false,
        )
        assertEquals("u2", request.activeContactId)
        val resolved = ResolvedOutgoingConversation(conversation = Chat(id = "c1"), peerUserId = "u2")
        assertEquals("c1", resolved.conversation.id)
        assertEquals("u2", resolved.peerUserId)
        val errors = OutgoingConversationErrors(
            notLoggedIn = { "login" },
            recipientNotReady = { "not-ready" },
            cannotSendToSelf = { "self" },
        )
        assertEquals("login", errors.notLoggedIn())
    }
}
