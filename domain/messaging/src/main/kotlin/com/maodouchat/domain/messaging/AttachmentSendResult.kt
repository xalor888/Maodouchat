package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Message

sealed interface AttachmentSendResult {
    data object ExistingTransfer : AttachmentSendResult
    data class Queued(val message: Message) : AttachmentSendResult
}
