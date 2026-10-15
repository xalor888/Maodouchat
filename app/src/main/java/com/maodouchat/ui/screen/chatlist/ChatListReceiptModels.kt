package com.maodouchat.ui.screen.chatlist

import com.maodouchat.data.model.MessageStatus

data class Receipt(
    val fromMe: Boolean,
    val status: MessageStatus,
)
