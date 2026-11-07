package com.maodouchat.core.model

data class MessagingV2AuthoritativeMutation(
    val conversationId: String,
    val messageId: String,
    val kind: MessageMutationKind,
    val message: Message? = null,
)
