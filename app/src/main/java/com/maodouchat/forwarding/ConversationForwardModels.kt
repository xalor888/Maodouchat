package com.maodouchat.forwarding

import com.maodouchat.core.model.MessageType

data class ForwardBatchResult(
    val forwardedCount: Int,
    val failedCount: Int,
    val firstError: Throwable? = null,
    val attachmentFailed: Boolean = false,
)

class ConversationForwardSessionException(message: String) : IllegalStateException(message)
class ConversationForwardUnsupportedException(val messageType: MessageType) :
    IllegalArgumentException("forward_type_unsupported:$messageType")
