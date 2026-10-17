package com.maodouchat.crypto

import kotlinx.serialization.Serializable

@Serializable
internal data class GroupDistributionMetadata(
    val epoch: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0
)
