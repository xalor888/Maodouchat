package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatSearchSelectionStateController
import com.maodouchat.ui.screen.chatdetail.SemanticSearchAdmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchSelectionStateControllerTest {
    private val controller = ChatSearchSelectionStateController()

    @Test
    fun `admission normalizes query and deduplicates capped candidate ids`() {
        val result = controller.admitSemanticSearch(
            state = ChatDetailUiState(aiEnabled = true),
            query = "  nearest cafe  ",
            candidateMessageIds = listOf("a", "a", "b"),
            secretChatError = "secret",
            aiDisabledError = "disabled",
            blankQueryError = "blank",
            noContextError = "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        result as SemanticSearchAdmission.Accepted
        assertEquals("nearest cafe", result.query)
        assertEquals(listOf("a", "b"), result.candidateIds)
    }

    @Test
    fun `secret chat rejects semantic search without touching results`() {
        val state = ChatDetailUiState(
            isSecretChat = true,
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        result as SemanticSearchAdmission.Rejected
        assertEquals("secret", result.state.semanticSearchError)
        assertEquals(listOf("old"), result.state.semanticSearchResultIds)
    }

    @Test
    fun `clear keeps consent only when caller says it remains pending`() {
        val cleared = controller.clearSemanticSearch(
            ChatDetailUiState(
                semanticSearchResultIds = listOf("one"),
                semanticSearchQuery = "query",
                isSemanticSearching = true,
                semanticSearchError = "error",
                showAiConsentDialog = true,
            ),
            showConsentDialog = false,
        )

        assertTrue(cleared.semanticSearchResultIds.isEmpty())
        assertFalse(cleared.isSemanticSearching)
        assertEquals(null, cleared.semanticSearchError)
        assertFalse(cleared.showAiConsentDialog)
    }
}
