package com.maodouchat.chatlist

import com.maodouchat.network.ChatDto
import com.maodouchat.network.ChatSettingsResponse
import com.maodouchat.network.TokenManager
import com.maodouchat.ui.screen.chatlist.ChatListPorts
import com.maodouchat.util.RuntimeFlags
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking

/**
 * ChatList DI：端口袋可注入假实现，不触碰 MaodouchatApp / ApiService / DAO。
 */
class ChatListPortsTest {

    @Test
    fun sessionAndRemoteSeamsUseInjectedLambdas() = runBlocking {
        var generation = 7L
        var remoteCalls = 0
        var draftDeleted = false
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val ports = ChatListPorts(
            tokenManager = tokenManager,
            chatRepository = mockk(relaxed = true),
            messageStore = mockk(relaxed = true),
            missedCallRepository = mockk(relaxed = true),
            notificationCenter = mockk(relaxed = true),
            scheduleCoordinator = mockk(relaxed = true),
            conversationLocalStateCoordinator = mockk(relaxed = true),
            realtimeEventDispatcher = mockk(relaxed = true),
            sessionGeneration = { generation },
            activeChatId = { "chat-active" },
            isPurgeInProgress = { false },
            chatReadEvents = MutableSharedFlow(),
            chatMessageSentEvents = MutableSharedFlow(),
            withRoomTransaction = { block -> block() },
            fetchRemoteChats = {
                remoteCalls += 1
                Result.success(emptyList())
            },
            fetchActiveAnnouncements = { Result.success("[]") },
            ackAnnouncementRemote = { _, _ -> Result.success(Unit) },
            fetchPushVerifyKeyRaw = { Result.success("{}") },
            applyPushVerifyKey = {},
            updateChatSettingsRemote = { _, _, _ ->
                Result.success(mockk<ChatSettingsResponse>(relaxed = true))
            },
            deleteChatRemote = { _, _ -> Result.success(Unit) },
            createChatRemote = { _, _, _, _, _ -> Result.success(mockk<ChatDto>(relaxed = true)) },
            touchSecretChat = {},
            cancelMessageNotification = {},
            cancelMissedCallNotification = {},
            markChatMessagesRead = {},
            secretChatFeatureEnabled = { true },
            secretChatDisabledMessage = { "disabled" },
            secretChatStartFailedMessage = { "failed" },
            applyRealtimeVisibility = { _, _, _, _, _ -> },
            requestBacklogSync = {},
            loadDismissedArchiveIds = { emptyList() },
            addArchiveDismissal = { _, _, _ -> },
            refreshArchiveSuggestions = { emptyList() },
            enqueueReadReceipt = { _, _, _ -> },
            observeDraftsForOwner = { emptyFlow() },
            deleteDraftForChat = { _, _ -> draftDeleted = true },
            listLockedChatIds = { emptySet() },
            searchChatIdsByMessageContent = { emptyList() },
            listSecretChatIds = { emptySet() },
            trustChangedRemoteIds = { _, _ -> emptySet() },
            isFlagEnabled = { it == RuntimeFlags.CHAT_PIN },
        )

        assertSame(tokenManager, ports.tokenManager)
        assertEquals(7L, ports.sessionGeneration())
        generation = 9L
        assertEquals(9L, ports.sessionGeneration())
        assertEquals("chat-active", ports.activeChatId())
        assertFalse(ports.isPurgeInProgress())
        assertTrue(ports.fetchRemoteChats("tok").isSuccess)
        assertEquals(1, remoteCalls)
        ports.deleteDraftForChat("u1", "c1")
        assertTrue(draftDeleted)
        assertTrue(ports.isFlagEnabled(RuntimeFlags.CHAT_PIN))
        assertFalse(ports.isFlagEnabled(RuntimeFlags.CHAT_MUTE))
        assertTrue(ports.secretChatFeatureEnabled())
    }
}
