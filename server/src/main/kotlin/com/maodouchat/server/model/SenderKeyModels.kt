package com.maodouchat.server.model

import kotlinx.serialization.Serializable

// 密钥分发 DTO：sender key 分发目标与状态。


@Serializable
data class SenderKeyDistributionTargetResponse(
    val userId: String,
    val deviceId: Int,
    val status: String,
    val error: String? = null,
    val updatedAt: Long
)


@Serializable
data class SenderKeyDistributionStatusResponse(
    val chatId: String,
    val epoch: Long,
    val total: Int,
    val sent: Int,
    val failed: Int,
    val pending: Int,
    val targets: List<SenderKeyDistributionTargetResponse>
)
