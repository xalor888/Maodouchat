package com.maodouchat.core.crypto

import kotlinx.serialization.Serializable

@Serializable
data class GroupDistributionMetadata(
    val epoch: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)
