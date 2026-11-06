package com.maodouchat.ui.screen.chatlist

import com.maodouchat.core.model.MessageStatus

data class Receipt(
    val fromMe: Boolean,
    val status: MessageStatus,
)
