package com.maodouchat.core.model

data class Receipt(
    val fromMe: Boolean,
    val status: MessageStatus,
)
