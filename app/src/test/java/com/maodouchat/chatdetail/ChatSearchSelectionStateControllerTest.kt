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

    @Test
    fun `ai disabled rejects semantic search with dedicated error`() {
        val state = ChatDetailUiState(
            aiEnabled = false,
            semanticSearchResultIds = listOf("old"),
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        result as SemanticSearchAdmission.Rejected
        assertEquals("disabled", result.state.semanticSearchError)
        assertEquals(listOf("old"), result.state.semanticSearchResultIds)
    }

    @Test
    fun `blank query rejected without touching results`() {
        val state = ChatDetailUiState(aiEnabled = true)

        val result = controller.admitSemanticSearch(
            state, "   ", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("blank", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `blank-only candidates rejected as no context`() {
        val state = ChatDetailUiState(aiEnabled = true)

        val result = controller.admitSemanticSearch(
            state, "query", listOf(" ", ""), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("empty", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `query truncated to 300 chars`() {
        val longQuery = "q".repeat(400)

        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            longQuery,
            listOf("a"),
            "secret",
            "disabled",
            "blank",
            "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals(300, (result as SemanticSearchAdmission.Accepted).query.length)
    }

    @Test
    fun `navigation target set, blank ignored, consumed`() {
        val blank = controller.navigateTo(ChatDetailUiState(), "")
        assertEquals(null, blank.navigationTargetMessageId)

        val targeted = controller.navigateTo(blank, "message")
        assertEquals("message", targeted.navigationTargetMessageId)

        val consumed = controller.consumeNavigation(targeted)
        assertEquals(null, consumed.navigationTargetMessageId)
    }
    @Test
    fun `secret chat precedence over disabled ai`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(isSecretChat = true, aiEnabled = false),
            "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("secret", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `candidate ids capped at one hundred`() {
        val ids = (0 until 150).map { "id$it" }
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true), "query", ids, "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        result as SemanticSearchAdmission.Accepted
        assertEquals(100, result.candidateIds.size)
        assertEquals("id99", result.candidateIds.last())
    }

    @Test
    fun `clear keeps consent when caller says it remains pending`() {
        val cleared = controller.clearSemanticSearch(
            ChatDetailUiState(
                semanticSearchResultIds = listOf("one"),
                semanticSearchQuery = "query",
                isSemanticSearching = true,
                semanticSearchError = "error",
                showAiConsentDialog = false,
            ),
            showConsentDialog = true,
        )

        assertTrue(cleared.semanticSearchResultIds.isEmpty())
        assertFalse(cleared.isSemanticSearching)
        assertEquals(null, cleared.semanticSearchError)
        assertTrue(cleared.showAiConsentDialog)
    }

    @Test
    fun `blank query beats empty candidates in admission order`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true), "   ", emptyList(), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("blank", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `navigation overwrite keeps other state and consume only clears target`() {
        val state = ChatDetailUiState(
            navigationTargetMessageId = "old",
            semanticSearchQuery = "query",
            semanticSearchResultIds = listOf("result"),
        )

        val targeted = controller.navigateTo(state, "new")
        assertEquals("new", targeted.navigationTargetMessageId)
        assertEquals("query", targeted.semanticSearchQuery)

        val consumed = controller.consumeNavigation(targeted)
        assertEquals(null, consumed.navigationTargetMessageId)
        assertEquals("query", consumed.semanticSearchQuery)
        assertEquals(listOf("result"), consumed.semanticSearchResultIds)
    }
}
