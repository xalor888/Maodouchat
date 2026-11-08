package com.maodouchat.core.model

data class ChatLoadPlan(
    val nextState: ChatDetailUiState,
    val effects: List<ChatLoadEffect>,
    val shouldInvalidateSenderKey: Boolean,
    val revisionWarning: String?,
    val fromCache: Boolean,
)
