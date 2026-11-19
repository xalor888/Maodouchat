package com.maodouchat.domain.conversation

data class PrefetchMessage(
    val id: String,
    val senderId: String,
    val eligibleForGroupReadCount: Boolean,
)
