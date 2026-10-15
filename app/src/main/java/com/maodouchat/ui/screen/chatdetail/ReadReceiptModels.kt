package com.maodouchat.ui.screen.chatdetail

data class PrefetchMessage(
    val id: String,
    val senderId: String,
    val eligibleForGroupReadCount: Boolean,
)
