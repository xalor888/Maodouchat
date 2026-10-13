package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Chat

data class OutgoingConversationRequest(
    val ownerUserId: String,
    val authToken: String,
    val activeConversationId: String,
    val constructorConversationId: String,
    val paintedConversation: Chat?,
    val activeContactId: String,
    val createSecretConversation: Boolean,
)

data class ResolvedOutgoingConversation(
    val conversation: Chat,
    val peerUserId: String?,
)

data class OutgoingConversationErrors(
    val notLoggedIn: () -> String,
    val recipientNotReady: () -> String,
    val cannotSendToSelf: () -> String,
)
