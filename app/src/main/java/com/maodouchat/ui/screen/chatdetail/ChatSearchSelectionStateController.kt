package com.maodouchat.ui.screen.chatdetail

/** UI-only search admission, navigation, and semantic-search reset behavior. */
internal class ChatSearchSelectionStateController {
    sealed interface SemanticSearchAdmission {
        data class Accepted(val query: String, val candidateIds: List<String>) : SemanticSearchAdmission
        data class Rejected(val state: ChatDetailUiState) : SemanticSearchAdmission
    }

    fun admitSemanticSearch(
        state: ChatDetailUiState,
        query: String,
        candidateMessageIds: List<String>,
        secretChatError: String,
        aiDisabledError: String,
        blankQueryError: String,
        noContextError: String,
    ): SemanticSearchAdmission {
        if (state.isSecretChat == true) {
            return SemanticSearchAdmission.Rejected(state.copy(semanticSearchError = secretChatError))
        }
        if (!state.aiEnabled) {
            return SemanticSearchAdmission.Rejected(state.copy(semanticSearchError = aiDisabledError))
        }
        val normalized = query.trim().take(300)
        if (normalized.isBlank()) {
            return SemanticSearchAdmission.Rejected(state.copy(semanticSearchError = blankQueryError))
        }
        val safeIds = candidateMessageIds.filter(String::isNotBlank).distinct().take(100)
        if (safeIds.isEmpty()) {
            return SemanticSearchAdmission.Rejected(state.copy(semanticSearchError = noContextError))
        }
        return SemanticSearchAdmission.Accepted(normalized, safeIds)
    }

    fun clearSemanticSearch(state: ChatDetailUiState, showConsentDialog: Boolean): ChatDetailUiState = state.copy(
        semanticSearchResultIds = emptyList(),
        semanticSearchQuery = "",
        isSemanticSearching = false,
        semanticSearchError = null,
        showAiConsentDialog = showConsentDialog,
    )

    fun navigateTo(state: ChatDetailUiState, messageId: String): ChatDetailUiState =
        if (messageId.isBlank()) state else state.copy(navigationTargetMessageId = messageId)

    fun consumeNavigation(state: ChatDetailUiState): ChatDetailUiState =
        state.copy(navigationTargetMessageId = null)
}
