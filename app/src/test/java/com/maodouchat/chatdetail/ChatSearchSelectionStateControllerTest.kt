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
    @Test
    fun `disabled ai precedence over blank query`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = false),
            "   ", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("disabled", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `candidate ids blank-filtered and deduplicated in order`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            "query", listOf("b", " ", "a", "b", ""), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals(listOf("b", "a"), (result as SemanticSearchAdmission.Accepted).candidateIds)
    }

    @Test
    fun `tab and newline only query rejected as blank`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            "\t\n ", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("blank", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `clear keeps unrelated navigation target`() {
        val cleared = controller.clearSemanticSearch(
            ChatDetailUiState(
                navigationTargetMessageId = "nav",
                semanticSearchResultIds = listOf("one"),
                isSemanticSearching = true,
                semanticSearchError = "error",
            ),
            showConsentDialog = false,
        )

        assertEquals("nav", cleared.navigationTargetMessageId)
        assertTrue(cleared.semanticSearchResultIds.isEmpty())
        assertFalse(cleared.isSemanticSearching)
        assertEquals(null, cleared.semanticSearchError)
    }

    @Test
    fun `blank navigation does not overwrite existing target`() {
        val targeted = controller.navigateTo(
            ChatDetailUiState(navigationTargetMessageId = "old"), ""
        )

        assertEquals("old", targeted.navigationTargetMessageId)
    }

    @Test
    fun `secret precedence over blank query and empty candidates`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(isSecretChat = true, aiEnabled = true),
            "   ", emptyList(), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("secret", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `long query is trimmed before the 300 char cap`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            "  " + "q".repeat(400) + "  ",
            listOf("a"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals("q".repeat(300), (result as SemanticSearchAdmission.Accepted).query)
    }

    @Test
    fun `blank rejection preserves existing results and query`() {
        val state = ChatDetailUiState(
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
            semanticSearchQuery = "oldq",
        )

        val result = controller.admitSemanticSearch(
            state, "   ", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("blank", rejected.semanticSearchError)
        assertEquals(listOf("old"), rejected.semanticSearchResultIds)
        assertEquals("oldq", rejected.semanticSearchQuery)
    }

    @Test
    fun `whitespace navigation id leaves target untouched`() {
        val targeted = controller.navigateTo(
            ChatDetailUiState(navigationTargetMessageId = "old"), "   "
        )

        assertEquals("old", targeted.navigationTargetMessageId)
    }

    @Test
    fun `duplicate ids collapse before the one hundred cap`() {
        val ids = (0 until 150).map { "id${it % 60}" }
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true), "query", ids, "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        val accepted = (result as SemanticSearchAdmission.Accepted)
        assertEquals(60, accepted.candidateIds.size)
        assertEquals("id0", accepted.candidateIds.first())
        assertEquals("id59", accepted.candidateIds.last())
    }

    @Test
    fun `query of exactly three hundred chars passes unchanged`() {
        val query = "q".repeat(300)

        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            query,
            listOf("a"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals(query, (result as SemanticSearchAdmission.Accepted).query)
    }

    @Test
    fun `secret precedence over disabled blank and empty candidates together`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(isSecretChat = true, aiEnabled = false),
            "   ", emptyList(), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        assertEquals("secret", (result as SemanticSearchAdmission.Rejected).state.semanticSearchError)
    }

    @Test
    fun `exactly one hundred distinct candidates all survive the cap`() {
        val ids = (0 until 100).map { "id$it" }
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true), "query", ids, "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        val accepted = (result as SemanticSearchAdmission.Accepted)
        assertEquals(100, accepted.candidateIds.size)
        assertEquals("id0", accepted.candidateIds.first())
        assertEquals("id99", accepted.candidateIds.last())
    }

    @Test
    fun `clear resets the search query to empty`() {
        val cleared = controller.clearSemanticSearch(
            ChatDetailUiState(
                semanticSearchResultIds = listOf("one"),
                semanticSearchQuery = "query",
                isSemanticSearching = true,
                semanticSearchError = "error",
            ),
            showConsentDialog = false,
        )

        assertEquals("", cleared.semanticSearchQuery)
        assertTrue(cleared.semanticSearchResultIds.isEmpty())
        assertFalse(cleared.isSemanticSearching)
        assertEquals(null, cleared.semanticSearchError)
    }

    @Test
    fun `consume navigation twice stays null and keeps other state`() {
        val state = ChatDetailUiState(
            semanticSearchQuery = "query",
            semanticSearchResultIds = listOf("result"),
        )

        val targeted = controller.navigateTo(state, "message")
        val consumed = controller.consumeNavigation(targeted)
        val consumedAgain = controller.consumeNavigation(consumed)

        assertEquals(null, consumedAgain.navigationTargetMessageId)
        assertEquals("query", consumedAgain.semanticSearchQuery)
        assertEquals(listOf("result"), consumedAgain.semanticSearchResultIds)
    }

    @Test
    fun `internal double spaces in query are preserved`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            "a  b", listOf("x"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals("a  b", (result as SemanticSearchAdmission.Accepted).query)
    }

    @Test
    fun `secret rejection preserves query and searching flag`() {
        val state = ChatDetailUiState(
            isSecretChat = true,
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
            semanticSearchQuery = "oldq",
            isSemanticSearching = true,
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("secret", rejected.semanticSearchError)
        assertEquals(listOf("old"), rejected.semanticSearchResultIds)
        assertEquals("oldq", rejected.semanticSearchQuery)
        assertTrue(rejected.isSemanticSearching)
    }

    @Test
    fun `disabled rejection preserves results and query`() {
        val state = ChatDetailUiState(
            aiEnabled = false,
            semanticSearchResultIds = listOf("old"),
            semanticSearchQuery = "oldq",
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("disabled", rejected.semanticSearchError)
        assertEquals(listOf("old"), rejected.semanticSearchResultIds)
        assertEquals("oldq", rejected.semanticSearchQuery)
    }

    @Test
    fun `empty candidates rejection preserves existing results`() {
        val state = ChatDetailUiState(
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf(" ", ""), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("empty", rejected.semanticSearchError)
        assertEquals(listOf("old"), rejected.semanticSearchResultIds)
    }

    @Test
    fun `navigation preserves existing search error`() {
        val state = ChatDetailUiState(semanticSearchError = "e")

        val targeted = controller.navigateTo(state, "m")

        assertEquals("m", targeted.navigationTargetMessageId)
        assertEquals("e", targeted.semanticSearchError)
    }

    @Test
    fun `tab and newline padded query trims to inner text`() {
        val result = controller.admitSemanticSearch(
            ChatDetailUiState(aiEnabled = true),
            "\t\n hello world \n\t", listOf("x"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals("hello world", (result as SemanticSearchAdmission.Accepted).query)
    }

    @Test
    fun `blank rejection preserves searching flag`() {
        val state = ChatDetailUiState(
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
            isSemanticSearching = true,
        )

        val result = controller.admitSemanticSearch(
            state, "   ", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("blank", rejected.semanticSearchError)
        assertTrue(rejected.isSemanticSearching)
    }

    @Test
    fun `empty candidates rejection preserves existing query`() {
        val state = ChatDetailUiState(
            aiEnabled = true,
            semanticSearchResultIds = listOf("old"),
            semanticSearchQuery = "oldq",
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf(" ", ""), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("empty", rejected.semanticSearchError)
        assertEquals("oldq", rejected.semanticSearchQuery)
    }

    @Test
    fun `secret rejection preserves navigation target and consent flag`() {
        val state = ChatDetailUiState(
            isSecretChat = true,
            aiEnabled = true,
            navigationTargetMessageId = "nav",
            showAiConsentDialog = true,
        )

        val result = controller.admitSemanticSearch(
            state, "query", listOf("message"), "secret", "disabled", "blank", "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Rejected)
        val rejected = (result as SemanticSearchAdmission.Rejected).state
        assertEquals("secret", rejected.semanticSearchError)
        assertEquals("nav", rejected.navigationTargetMessageId)
        assertTrue(rejected.showAiConsentDialog)
    }

    @Test
    fun `consume navigation preserves search error`() {
        val state = ChatDetailUiState(
            navigationTargetMessageId = "m",
            semanticSearchError = "e",
        )

        val consumed = controller.consumeNavigation(state)

        assertEquals(null, consumed.navigationTargetMessageId)
        assertEquals("e", consumed.semanticSearchError)
    }

    @Test
    fun `clear with consent false clears searching flag and consent`() {
        val state = ChatDetailUiState(
            semanticSearchResultIds = listOf("old"),
            semanticSearchQuery = "oldq",
            isSemanticSearching = true,
            semanticSearchError = "e",
            showAiConsentDialog = true,
        )
        val cleared = controller.clearSemanticSearch(state, showConsentDialog = false)

        assertEquals(emptyList<String>(), cleared.semanticSearchResultIds)
        assertEquals("", cleared.semanticSearchQuery)
        assertFalse(cleared.isSemanticSearching)
        assertEquals(null, cleared.semanticSearchError)
        assertFalse(cleared.showAiConsentDialog)
    }

    @Test
    fun `navigate to padded message id keeps padding`() {
        val navigated = controller.navigateTo(ChatDetailUiState(), "  m  ")

        assertEquals("  m  ", navigated.navigationTargetMessageId)
    }

    @Test
    fun `admission trims ends but preserves internal tab in query`() {
        val result = controller.admitSemanticSearch(
            state = ChatDetailUiState(aiEnabled = true),
            query = "\t hello\tworld \n",
            candidateMessageIds = listOf("a"),
            secretChatError = "secret",
            aiDisabledError = "disabled",
            blankQueryError = "blank",
            noContextError = "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        assertEquals("hello\tworld", (result as SemanticSearchAdmission.Accepted).query)
    }

    @Test
    fun `admission query of three hundred one chars after trim truncates to three hundred`() {
        val result = controller.admitSemanticSearch(
            state = ChatDetailUiState(aiEnabled = true),
            query = " " + "a".repeat(301) + " ",
            candidateMessageIds = listOf("a"),
            secretChatError = "secret",
            aiDisabledError = "disabled",
            blankQueryError = "blank",
            noContextError = "empty",
        )

        assertTrue(result is SemanticSearchAdmission.Accepted)
        val accepted = result as SemanticSearchAdmission.Accepted
        assertEquals(300, accepted.query.length)
        assertTrue(accepted.query.all { it == 'a' })
    }

    @Test
    fun `clear preserves ai enabled and secret chat flags`() {
        val state = ChatDetailUiState(aiEnabled = true, isSecretChat = true, semanticSearchResultIds = listOf("old"))
        val cleared = controller.clearSemanticSearch(state, showConsentDialog = false)

        assertTrue(cleared.aiEnabled)
        assertTrue(cleared.isSecretChat == true)
        assertEquals(emptyList<String>(), cleared.semanticSearchResultIds)
    }

}
