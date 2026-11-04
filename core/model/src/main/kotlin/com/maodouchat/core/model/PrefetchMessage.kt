package com.maodouchat.core.model

data class PrefetchMessage(
    val id: String,
    val senderId: String,
    val eligibleForGroupReadCount: Boolean,
)
