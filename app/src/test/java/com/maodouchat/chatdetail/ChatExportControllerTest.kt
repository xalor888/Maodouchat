package com.maodouchat.chatdetail

import android.content.Context
import com.maodouchat.data.model.Chat
import com.maodouchat.data.model.Message
import com.maodouchat.data.model.MessageType
import com.maodouchat.data.repository.LocalMessageStore
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.screen.chatdetail.ChatDetailUiState
import com.maodouchat.ui.screen.chatdetail.ChatExportController
import com.maodouchat.util.ChatExport
import com.maodouchat.util.JsonFormat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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

        com.maodouchat.security.BackgroundSessionGate.sessionOverride = { tokenManager.getToken() to tokenManager.getUserId() }
        every { tokenManager.getToken() } returns "valid_token"

        controller = ChatExportController(
            messageRepo = messageRepo,
            tokenManager = tokenManager,
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
}
