package com.maodouchat.domain.messaging

import kotlinx.serialization.Serializable

@Serializable
data class AiSemanticSearchCandidate(
    val messageId: String,
    val sender: String = "",
    val text: String,
    val timestamp: Long
)
