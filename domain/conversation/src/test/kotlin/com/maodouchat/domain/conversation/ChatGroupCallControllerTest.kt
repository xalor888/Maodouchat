package com.maodouchat.domain.conversation

import com.maodouchat.core.model.CallOrchestrator
import com.maodouchat.core.model.CallType
import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.User
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGroupCallControllerTest {

    private class Harness(initial: ChatDetailUiState) {
        val uiState = MutableStateFlow(initial)
        var currentUserId: String = "me"
        var callsEnabled: Boolean = true
        var videoEnabled: Boolean = true
        var voiceEnabled: Boolean = true

        val controller = ChatGroupCallController(
            uiState = uiState,
            currentUserId = { currentUserId },
            callsEnabled = { callsEnabled },
            videoCallEnabled = { videoEnabled },
            voiceCallEnabled = { voiceEnabled },
            text = { message -> "[${message.name}]" },
        )
    }

    private fun groupChat(vararg members: User) = ChatDetailUiState(
        chat = Chat(id = "chat-1", participants = members.toList(), isGroup = true),
    )

    private fun user(id: String) = User(id, id)

    private suspend fun noRequest() {
        assertNull(withTimeoutOrNull(300) { CallOrchestrator.groupCallRequests.first() })
    }

    @Test
    fun `no chat is no-op`() = runTest {
        val h = Harness(ChatDetailUiState())
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        assertNull(h.uiState.value.errorMessage)
        assertNull(h.uiState.value.groupEncryptionWarning)
        noRequest()
    }

    @Test
    fun `non-group chat is no-op`() = runTest {
        val h = Harness(ChatDetailUiState(chat = Chat(id = "chat-1", isGroup = false)))
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        assertNull(h.uiState.value.errorMessage)
        noRequest()
    }

    @Test
    fun `calls disabled sets error and emits nothing`() = runTest {
        val h = Harness(groupChat(user("me"), user("u1")))
        h.callsEnabled = false
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        assertEquals("[CALLS_DISABLED]", h.uiState.value.errorMessage)
        noRequest()
    }

    @Test
    fun `video disabled sets video error`() = runTest {
        val h = Harness(groupChat(user("me"), user("u1")))
        h.videoEnabled = false
        h.controller.startGroupCallFromChat(CallType.VIDEO)

        assertEquals("[VIDEO_CALL_DISABLED]", h.uiState.value.errorMessage)
        noRequest()
    }

    @Test
    fun `voice disabled sets voice error`() = runTest {
        val h = Harness(groupChat(user("me"), user("u1")))
        h.voiceEnabled = false
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        assertEquals("[VOICE_CALL_DISABLED]", h.uiState.value.errorMessage)
        noRequest()
    }

    @Test
    fun `only self in group warns empty`() = runTest {
        val h = Harness(groupChat(user("me")))
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        assertEquals("[GROUP_CALL_EMPTY]", h.uiState.value.groupEncryptionWarning)
        noRequest()
    }

    @Test
    fun `happy path invites all except self`() = runTest {
        val h = Harness(groupChat(user("me"), user("u1"), user("u2")))
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        h.controller.startGroupCallFromChat(CallType.AUDIO)

        val req = received.await()
        assertEquals("chat-1", req.chatId)
        assertEquals(listOf("u1", "u2"), req.memberIds)
        assertEquals(CallType.AUDIO, req.callType)
        assertNull(h.uiState.value.errorMessage)
        assertNull(h.uiState.value.groupEncryptionWarning)
    }

    @Test
    fun `selected members filter is respected`() = runTest {
        val h = Harness(groupChat(user("me"), user("u1"), user("u2"), user("u3")))
        val received = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(1_000) { CallOrchestrator.groupCallRequests.first() }
        }
        h.controller.startGroupCallFromChat(CallType.VIDEO, setOf("u2"))

        val req = received.await()
        assertEquals(listOf("u2"), req.memberIds)
        assertEquals(CallType.VIDEO, req.callType)
    }
}
