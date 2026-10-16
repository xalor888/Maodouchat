package com.maodouchat.ui.screen.chatdetail

internal sealed interface SemanticSearchAdmission {
    data class Accepted(val query: String, val candidateIds: List<String>) : SemanticSearchAdmission
    data class Rejected(val state: ChatDetailUiState) : SemanticSearchAdmission
}
