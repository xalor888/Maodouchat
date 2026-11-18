package com.maodouchat.domain.conversation

import com.maodouchat.core.model.ChatDetailUiState

sealed interface SemanticSearchAdmission {
    data class Accepted(val query: String, val candidateIds: List<String>) : SemanticSearchAdmission
    data class Rejected(val state: ChatDetailUiState) : SemanticSearchAdmission
}
