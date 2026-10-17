package com.maodouchat.server.repository

import com.maodouchat.server.messaging.v2.SendMessageV2Result
import com.maodouchat.server.model.MessageResponse

data class Published(
    val message: MessageResponse,
    val mailbox: SendMessageV2Result,
) : PublishResult()
data object Rejected : PublishResult()
