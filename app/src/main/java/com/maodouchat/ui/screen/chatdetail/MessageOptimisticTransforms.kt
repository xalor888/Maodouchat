package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageMeta
import com.maodouchat.data.model.MessageType

internal fun Message.toRevokedPlaceholder(placeholder: String): Message = copy(
    content = placeholder,
    type = MessageType.REVOKED,
    meta = MessageMeta()
)

internal fun Message.toOptimisticEdit(newContent: String): Message {
    val previousRevision = maxOf(timestamp, editedAt ?: timestamp)
    val nextRevision = if (previousRevision == Long.MAX_VALUE) Long.MAX_VALUE else previousRevision + 1L
    return copy(content = newContent, editedAt = nextRevision)
}
