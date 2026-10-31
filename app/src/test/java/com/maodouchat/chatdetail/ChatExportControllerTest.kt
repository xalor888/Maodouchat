package com.maodouchat.chatdetail

import android.content.Context
import com.maodouchat.R
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.session.CurrentSession
import com.maodouchat.session.Snapshot
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatExportController
import com.maodouchat.util.ChatExport
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatExportControllerTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var messageRepo: LocalMessageStore
    private lateinit var tokenManager: TokenManager
    private lateinit var uiState: MutableStateFlow<ChatDetailUiState>
    private lateinit var mockContext: Context
    private lateinit var controller: ChatExportController

    @Before
    fun setUp() {
        messageRepo = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        uiState = MutableStateFlow(ChatDetailUiState())
        mockContext = mockk(relaxed = true)

        every { tokenManager.getUserId() } returns "user_me"

        CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        every { tokenManager.getToken() } returns "valid_token"

        controller = ChatExportController(
            messageRepo = messageRepo,
            uiState = uiState,
            textProvider = { resId, _ -> "text_$resId" },
            context = mockContext,
            scope = testScope,
        )
    }

    @Test
    fun exportChatAsJson_includesFormatVersionAndMessages() {
        val chat = Chat(
            id = "c1",
            isGroup = false,
        )
        val messages = listOf(
            Message(
                id = "m1",
                chatId = "c1",
                senderId = "user_me",
                content = "Export json test",
                type = MessageType.TEXT,
            )
        )
        uiState.value = ChatDetailUiState(
            chat = chat,
            messages = messages,
            isSecretChat = false,
        )

        val json = controller.exportChatAsJson()
        assertNotNull(json)
        assertTrue(json.contains("\"formatVersion\":${ChatExport.FORMAT_VERSION}"))
        assertTrue(json.contains("\"chatId\":\"c1\""))
        assertTrue(json.contains("\"messageCount\":1"))
        assertTrue(json.contains("Export json test"))
    }

    @Test
    fun exportChatAsJson_blocksSecretChat() {
        val secretChat = Chat(
            id = "c_secret",
            isGroup = false,
            chatType = "SECRET",
        )
        uiState.value = ChatDetailUiState(
            chat = secretChat,
            messages = listOf(
                Message(
                    id = "m_sec",
                    chatId = "c_secret",
                    senderId = "user_me",
                    content = "Secret content",
                    type = MessageType.TEXT,
                )
            ),
            isSecretChat = true,
        )

        val json = controller.exportChatAsJson()
        assertEquals("{}", json)
    }

    @Test
    fun exportChatHistory_blocksSecretChat() = testScope.runTest {
        val secretChat = Chat(
            id = "c_secret",
            isGroup = false,
            chatType = "SECRET",
        )
        uiState.value = ChatDetailUiState(
            chat = secretChat,
            isSecretChat = true,
        )

        controller.exportChatHistory()
        advanceUntilIdle()

        assertTrue(uiState.value.infoMessage.orEmpty().contains("text_"))
    }

    @Test
    fun exportChatHistory_reportsDisabledAndTouchesNoRepoWhenFlagHardOff() = testScope.runTest {
        controller.exportChatHistory()
        advanceUntilIdle()

        assertEquals("text_${R.string.chat_export_disabled}", uiState.value.infoMessage)
        coVerify(exactly = 0) { messageRepo.getRecentMessages(any(), any()) }
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenChatMissing() {
        uiState.value = ChatDetailUiState(chat = null, messages = emptyList())

        assertEquals("{}", controller.exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_blocksWhenTokenBlank() {
        CurrentSession.override = { Snapshot(null, "user_me") }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun exportChatAsJson_marksPartialWhenOlderMessagesRemain() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
            hasMoreOlderMessages = true,
        )

        assertTrue(controller.exportChatAsJson().contains("\"partial\":true"))
    }

    @Test
    fun exportChatAsJson_includesGroupNameForGroups() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "g1", isGroup = true, groupName = "Fam"),
            messages = emptyList(),
        )
        val json = controller.exportChatAsJson()

        assertTrue(json.contains("\"isGroup\":true"))
        assertTrue(json.contains("\"groupName\":\"Fam\""))
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdBlank() {
        CurrentSession.override = { Snapshot("valid_token", "") }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdMissing() {
        CurrentSession.override = { Snapshot("valid_token", null) }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun exportChatAsJson_blocksSecretChatByModelFlag() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c_secret", isGroup = false, chatType = "SECRET"),
            messages = emptyList(),
            isSecretChat = false,
        )

        assertEquals("{}", controller.exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_marksNotPartialByDefault() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"partial\":false"))
    }

    @Test
    fun clearExportInfo_clearsExportInfoMessage() {
        uiState.value = ChatDetailUiState(exportInfoMessage = "done")

        controller.clearExportInfo()

        assertNull(uiState.value.exportInfoMessage)
    }

    @Test
    fun exportChatAsJson_includesPerMessageFields() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(
                    id = "m1",
                    chatId = "c1",
                    senderId = "user_me",
                    content = "hello",
                    type = MessageType.TEXT,
                    timestamp = 12345L,
                    status = com.maodouchat.data.model.MessageStatus.READ,
                ),
                Message(
                    id = "m2",
                    chatId = "c1",
                    senderId = "other",
                    content = "pic",
                    type = MessageType.IMAGE,
                    timestamp = 12346L,
                ),
            ),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"sender\":\"user_me\""))
        assertTrue(json.contains("\"type\":\"IMAGE\""))
        assertTrue(json.contains("\"status\":\"READ\""))
        assertTrue(json.contains("\"timestamp\":12345"))
        assertTrue(json.contains("\"id\":\"m2\""))
    }

    @Test
    fun exportChatAsJson_returnsEmptyWhenOwnerIsFallbackMe() {
        CurrentSession.override = { Snapshot("valid_token", "me") }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun exportChatAsJson_countsAllLoadedMessages() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "a", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
                Message(id = "b", chatId = "c1", senderId = "user_me", content = "two", type = MessageType.TEXT),
                Message(id = "c", chatId = "c1", senderId = "user_me", content = "three", type = MessageType.TEXT),
            ),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"messageCount\":3"))
        assertTrue(json.contains("one") && json.contains("two") && json.contains("three"))
    }

    @Test
    fun exportChatAsJson_includesExportTimestamp() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"exportedAt\":"))
    }

    @Test
    fun exportChatAsJson_marksDirectChatNotGroup() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"isGroup\":false"))
    }

    @Test
    fun `exportChatAsJson reports zero count for empty messages`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"messageCount\":0"))
        assertTrue(json.contains("\"partial\":false"))
    }

    @Test
    fun `exportChatAsJson includes blank group name verbatim`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "g1", isGroup = true, groupName = ""),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"groupName\":\"\""))
    }

    @Test
    fun `exportChatAsJson preserves message order`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
                Message(id = "m2", chatId = "c1", senderId = "user_me", content = "two", type = MessageType.TEXT),
                Message(id = "m3", chatId = "c1", senderId = "user_me", content = "three", type = MessageType.TEXT),
            ),
        )

        val json = controller.exportChatAsJson()
        val first = json.indexOf("\"id\":\"m1\"")
        val second = json.indexOf("\"id\":\"m2\"")
        val third = json.indexOf("\"id\":\"m3\"")
        assertTrue(first >= 0 && first < second && second < third)
    }

    @Test
    fun `exportChatAsJson includes plain text content verbatim`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "order 12345 confirmed", type = MessageType.TEXT),
            ),
        )

        assertTrue(controller.exportChatAsJson().contains("order 12345 confirmed"))
    }

    @Test
    fun `clearExportInfo leaves other info message untouched`() {
        uiState.value = ChatDetailUiState(exportInfoMessage = "done", infoMessage = "kept")

        controller.clearExportInfo()

        assertNull(uiState.value.exportInfoMessage)
        assertEquals("kept", uiState.value.infoMessage)
    }

    @Test
    fun exportChatAsJson_blocksSecretChatByStateFlagAlone() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c_flag", isGroup = false),
            messages = emptyList(),
            isSecretChat = true,
        )

        assertEquals("{}", controller.exportChatAsJson())
    }

    @Test
    fun exportChatAsJson_reportsEmptyWhenUserIdWhitespace() {
        CurrentSession.override = { Snapshot("valid_token", "   ") }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun `exportChatAsJson includes spaces group name verbatim`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "g1", isGroup = true, groupName = "   "),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"groupName\":\"   \""))
    }

    @Test
    fun `exportChatAsJson includes empty content verbatim`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "", type = MessageType.TEXT),
            ),
        )

        assertTrue(controller.exportChatAsJson().contains("\"content\":\"\""))
    }

    @Test
    fun `exportChatAsJson includes zero timestamp`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "hi", type = MessageType.TEXT, timestamp = 0L),
            ),
        )

        assertTrue(controller.exportChatAsJson().contains("\"timestamp\":0"))
    }

    @Test
    fun `exportChatAsJson counts messages from other chats without filtering`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
                Message(id = "m2", chatId = "c-other", senderId = "user_me", content = "two", type = MessageType.TEXT),
            ),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"messageCount\":2"))
        assertTrue(json.contains("one") && json.contains("two"))
    }

    @Test
    fun `exportChatAsJson reports empty when token is whitespace`() {
        CurrentSession.override = { Snapshot("   ", "user_me") }
        try {
            uiState.value = ChatDetailUiState(
                chat = Chat(id = "c1", isGroup = false),
                messages = emptyList(),
            )

            assertEquals("{}", controller.exportChatAsJson())
        } finally {
            CurrentSession.override = { Snapshot(tokenManager.getToken(), tokenManager.getUserId()) }
        }
    }

    @Test
    fun `exportChatAsJson marks partial with loaded messages`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
                Message(id = "m2", chatId = "c1", senderId = "user_me", content = "two", type = MessageType.TEXT),
            ),
            hasMoreOlderMessages = true,
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"partial\":true"))
        assertTrue(json.contains("\"messageCount\":2"))
    }

    @Test
    fun `exportChatAsJson keeps list order when timestamps run backwards`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "first", type = MessageType.TEXT, timestamp = 300L),
                Message(id = "m2", chatId = "c1", senderId = "user_me", content = "second", type = MessageType.TEXT, timestamp = 100L),
            ),
        )

        val json = controller.exportChatAsJson()
        val first = json.indexOf("\"id\":\"m1\"")
        val second = json.indexOf("\"id\":\"m2\"")
        assertTrue(first >= 0 && first < second)
    }

    @Test
    fun `exportChatAsJson twice returns full json both times`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
            ),
        )

        val first = controller.exportChatAsJson()
        val second = controller.exportChatAsJson()
        assertTrue(first.contains("\"chatId\":\"c1\"") && first.contains("\"messageCount\":1"))
        assertTrue(second.contains("\"chatId\":\"c1\"") && second.contains("\"messageCount\":1"))
    }

    @Test
    fun `exportChatAsJson reports null group name for direct chat`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = emptyList(),
        )

        assertTrue(controller.exportChatAsJson().contains("\"groupName\":null"))
    }

    @Test
    fun `exportChatAsJson escapes quotes backslash and newline in content`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "say \"hi\" \\ path\nline", type = MessageType.TEXT),
            ),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("say \\\"hi\\\" \\\\ path\\nline"))
    }

    @Test
    fun `exportChatAsJson uses default sent status when message omits status`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT),
            ),
        )

        assertTrue(controller.exportChatAsJson().contains("\"status\":\"SENT\""))
    }

    @Test
    fun `exportChatAsJson keeps negative timestamp verbatim`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "c1", isGroup = false),
            messages = listOf(
                Message(id = "m1", chatId = "c1", senderId = "user_me", content = "one", type = MessageType.TEXT, timestamp = -1L),
            ),
        )

        assertTrue(controller.exportChatAsJson().contains("\"timestamp\":-1"))
    }

    @Test
    fun `exportChatAsJson reports zero count for empty group chat`() {
        uiState.value = ChatDetailUiState(
            chat = Chat(id = "g1", isGroup = true, groupName = "G"),
            messages = emptyList(),
        )

        val json = controller.exportChatAsJson()
        assertTrue(json.contains("\"messageCount\":0"))
        assertTrue(json.contains("\"isGroup\":true"))
        assertTrue(json.contains("\"partial\":false"))
    }

}
