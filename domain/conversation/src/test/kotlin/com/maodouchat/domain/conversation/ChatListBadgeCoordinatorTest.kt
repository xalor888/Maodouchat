package com.maodouchat.domain.conversation

import com.maodouchat.core.model.Chat
import com.maodouchat.core.model.ExploreBadgeStore
import com.maodouchat.core.model.NotificationCenterItem
import com.maodouchat.core.model.NotificationCenterType
import com.maodouchat.core.model.UnreadBadgeStore
import com.maodouchat.core.session.SessionUiResetEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListBadgeCoordinatorTest {

    private val dispatcher = UnconfinedTestDispatcher()

    private fun item(id: String, type: String = NotificationCenterType.MESSAGE, read: Boolean = false) =
        NotificationCenterItem(id = id, type = type, mergeKey = id, title = "t", read = read)

    private class Harness {
        val notificationItems = MutableStateFlow<List<NotificationCenterItem>>(emptyList())
        val uiResetEvents = MutableSharedFlow<SessionUiResetEvent>()
        val chats = MutableStateFlow<List<Chat>>(emptyList())
        val typingByChat = MutableStateFlow<Map<String, String>>(emptyMap())
        var projectedTyping: Map<String, String>? = null

        fun build(scope: CoroutineScope) = ChatListBadgeCoordinator(
            scope = scope,
            notificationItems = notificationItems,
            initialNotificationUnreadCount = { notificationItems.value.count { !it.read } },
            uiResetEvents = uiResetEvents,
            chats = chats,
            typingByChat = typingByChat,
            updateTypingByChat = { projectedTyping = it },
        )
    }

    @Test
    fun notificationCenterUnreadCountsUnreadItems() = runTest(dispatcher) {
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)
        backgroundScope.launch { coordinator.notificationCenterUnread.collect {} }
        harness.notificationItems.value = listOf(item("a"), item("b", read = true), item("c"))

        assertEquals(2, coordinator.notificationCenterUnread.value)
    }

    @Test
    fun exploreBadgeCountCountsOnlyUnreadPostInteractions() = runTest(dispatcher) {
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)
        backgroundScope.launch { coordinator.exploreBadgeCount.collect {} }
        harness.notificationItems.value = listOf(
            item("a", type = NotificationCenterType.POST_INTERACTION),
            item("b", type = NotificationCenterType.POST_INTERACTION, read = true),
            item("c"),
        )

        assertEquals(1, coordinator.exploreBadgeCount.value)
    }

    @Test
    fun startResetsUnreadBadgeOnUiResetEvent() = runTest(dispatcher) {
        UnreadBadgeStore.totalUnread.value = 7
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)

        coordinator.start()
        harness.uiResetEvents.emit(SessionUiResetEvent.UNREAD_BADGE)

        assertEquals(0, UnreadBadgeStore.totalUnread.value)
    }

    @Test
    fun startPushesUnreadChatTotalExcludingArchived() = runTest(dispatcher) {
        UnreadBadgeStore.totalUnread.value = 0
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)

        coordinator.start()
        harness.chats.value = listOf(
            Chat(id = "c1", unreadCount = 3),
            Chat(id = "c2", unreadCount = 5, archived = true),
            Chat(id = "c3", unreadCount = 2),
        )

        assertEquals(5, UnreadBadgeStore.totalUnread.value)
    }

    @Test
    fun startPushesExploreBadgeCount() = runTest(dispatcher) {
        ExploreBadgeStore.count.value = 0
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)

        coordinator.start()
        harness.notificationItems.value = listOf(
            item("a", type = NotificationCenterType.POST_INTERACTION),
            item("b", type = NotificationCenterType.POST_INTERACTION),
            item("c"),
        )

        assertEquals(2, ExploreBadgeStore.count.value)
    }

    @Test
    fun startProjectsTypingPresence() = runTest(dispatcher) {
        val harness = Harness()
        val coordinator = harness.build(backgroundScope)

        coordinator.start()
        harness.typingByChat.value = mapOf("c1" to "u1")

        assertEquals(mapOf("c1" to "u1"), harness.projectedTyping)
    }
}
