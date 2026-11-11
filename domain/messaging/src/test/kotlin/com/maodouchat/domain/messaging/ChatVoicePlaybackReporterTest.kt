package com.maodouchat.domain.messaging

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ChatDetailUiState
import com.maodouchat.core.model.Message
import com.maodouchat.core.model.MessageType
import com.maodouchat.core.model.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

class ChatVoicePlaybackReporterTest {

    private val me = User(id = "owner-1", name = "Me")
    private val peer = User(id = "u2", name = "U2")

    private fun message(id: String, senderId: String) = Message(
        id = id,
        chatId = "c1",
        senderId = senderId,
        content = "voice",
        type = MessageType.VOICE,
    )

    private fun groupChat() = Chat(
        id = "c1",
        participants = listOf(me, peer),
        isGroup = true,
        memberRevision = 42L,
    )

    private class Harness(
        var ownerId: String = "owner-1",
        var activeChatId: String = "c1",
        var chatId: String = "c1",
        var sessionActive: Boolean = true,
        var chat: Chat? = null,
        messages: List<Message> = emptyList(),
        var failEnqueue: Boolean = false,
    ) {
        val scopeJob = Job()
        val scope = CoroutineScope(UnconfinedTestDispatcher() + scopeJob)
        var state = ChatDetailUiState(chat = chat, messages = messages)
        val enqueued = mutableListOf<Triple<String, String, Long?>>()
        val warnings = mutableListOf<Pair<String, Throwable?>>()

        fun reporter(playingIds: Flow<String> = flowOf()) =
            ChatVoicePlaybackReporter(
                scope = scope,
                currentUserId = { ownerId },
                currentState = { state },
                activeChatId = { activeChatId },
                chatId = { chatId },
                playingMessageIds = playingIds,
                sessionMayContinue = { sessionActive },
                enqueuePlayReceipt = { conversationId, messageId, groupRevision ->
                    if (failEnqueue) throw RuntimeException("boom")
                    enqueued += Triple(conversationId, messageId, groupRevision)
                },
                logWarning = { message, error -> warnings += message to error },
            )

        suspend fun settle() {
            scopeJob.children.forEach { it.join() }
        }
    }

    @Test
    fun `peer voice message enqueues play receipt with group revision`() = runTest {
        val harness = Harness(chat = groupChat(), messages = listOf(message("m1", "u2")))
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertEquals(listOf(Triple<String, String, Long?>("c1", "m1", 42L)), harness.enqueued)
    }

    @Test
    fun `own voice message is not reported`() = runTest {
        val harness = Harness(chat = groupChat(), messages = listOf(message("m1", "owner-1")))
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun `unknown message id is not reported`() = runTest {
        val harness = Harness(chat = groupChat(), messages = listOf(message("m1", "u2")))
        harness.reporter().markVoiceMessagePlayed("mX")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun `blank owner id is not reported`() = runTest {
        val harness = Harness(
            ownerId = "",
            chat = groupChat(),
            messages = listOf(message("m1", "u2")),
        )
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun `placeholder me owner id is not reported`() = runTest {
        val harness = Harness(
            ownerId = "me",
            chat = groupChat(),
            messages = listOf(message("m1", "u2")),
        )
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun `inactive session is not reported`() = runTest {
        val harness = Harness(
            sessionActive = false,
            chat = groupChat(),
            messages = listOf(message("m1", "u2")),
        )
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
    }

    @Test
    fun `blank active chat id falls back to constructor chat id`() = runTest {
        val harness = Harness(
            activeChatId = "",
            chatId = "c9",
            chat = groupChat(),
            messages = listOf(message("m1", "u2")),
        )
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertEquals(listOf(Triple<String, String, Long?>("c9", "m1", 42L)), harness.enqueued)
    }

    @Test
    fun `enqueue failure is logged instead of crashing`() = runTest {
        val harness = Harness(
            chat = groupChat(),
            messages = listOf(message("m1", "u2")),
            failEnqueue = true,
        )
        harness.reporter().markVoiceMessagePlayed("m1")
        harness.settle()
        assertTrue(harness.enqueued.isEmpty())
        assertEquals(1, harness.warnings.size)
        assertTrue(harness.warnings.single().first.startsWith("v2 play receipt enqueue failed: "))
    }

    @Test
    fun `observe dedups repeated playback of the same message`() = runTest {
        val harness = Harness(
            chat = groupChat(),
            messages = listOf(message("m1", "u2"), message("m2", "u2")),
        )
        harness.reporter(playingIds = flowOf("m1", "m1", "m2", "m1")).observeVoicePlayback()
        harness.settle()
        assertEquals(
            listOf(Triple<String, String, Long?>("c1", "m1", 42L), Triple<String, String, Long?>("c1", "m2", 42L)),
            harness.enqueued,
        )
    }
}
