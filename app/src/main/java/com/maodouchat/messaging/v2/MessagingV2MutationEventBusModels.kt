package com.maodouchat.messaging.v2

import com.maodouchat.data.model.Message

internal data class MessagingV2AuthoritativeMutation(
    val conversationId: String,
    val messageId: String,
    val kind: MessageMutationKind,
    val message: Message? = null,
)
