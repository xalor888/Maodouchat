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
}
