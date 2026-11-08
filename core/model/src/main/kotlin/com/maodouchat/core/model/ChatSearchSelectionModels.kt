package com.maodouchat.core.model

sealed interface SemanticSearchAdmission {
    data class Accepted(val query: String, val candidateIds: List<String>) : SemanticSearchAdmission
    data class Rejected(val state: ChatDetailUiState) : SemanticSearchAdmission
}
