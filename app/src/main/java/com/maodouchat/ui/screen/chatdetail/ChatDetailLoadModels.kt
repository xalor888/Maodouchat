package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.core.model.ChatLoadEffect

internal data class ChatLoadPlan(
    val nextState: ChatDetailUiState,
    val effects: List<ChatLoadEffect>,
    val shouldInvalidateSenderKey: Boolean,
    val revisionWarning: String?,
    val fromCache: Boolean,
)
