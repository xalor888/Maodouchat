package com.maodouchat.domain.conversation

import com.maodouchat.core.model.MessageStatus

data class Receipt(
    val fromMe: Boolean,
    val status: MessageStatus,
)
