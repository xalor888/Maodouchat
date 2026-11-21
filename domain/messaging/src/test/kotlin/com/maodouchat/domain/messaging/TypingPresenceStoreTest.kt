package com.maodouchat.domain.messaging

import com.maodouchat.core.realtime.DefaultRealtimeEventDispatcher
import com.maodouchat.core.realtime.RealtimeDomainEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TypingPresenceStoreTest {

    private var me = "me-owner"

    @BeforeTest
    fun setUp() {
        me = "me-owner"
        TypingPresenceStore.clear()
    }

    @AfterTest
    fun tearDown() {
        TypingPresenceStore.clear()
    }

    private fun TestScope.startStore(timeoutMs: Long = 3_000L): DefaultRealtimeEventDispatcher {
        val dispatcher = DefaultRealtimeEventDispatcher()
        TypingPresenceStore.start(dispatcher, currentUserId = { me }, scope = backgroundScope, timeoutMs = timeoutMs)
        // 先泵一次，让订阅协程真正挂上 typingEvents 再发事件：SharedFlow replay=0，订阅前的首个事件会丢。
        runCurrent()
        return dispatcher
    }

    private fun typing(userId: String, chatId: String = "chat-1", isTyping: Boolean = true) =
        RealtimeDomainEvent.Typing(userId = userId, chatId = chatId, isTyping = isTyping)

    @Test
    fun typingEventSetsTypingByChat() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()

        assertEquals(mapOf("chat-1" to "user-a"), TypingPresenceStore.typingByChat.value)
        assertEquals("user-a", TypingPresenceStore.isTyping("chat-1"))
    }

    @Test
    fun stopEventClearsTheChatEntry() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()
        dispatcher.dispatch(typing("user-a", isTyping = false))
        runCurrent()

        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
        assertNull(TypingPresenceStore.isTyping("chat-1"))
    }

    @Test
    fun ownUserTypingEventIsIgnored() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("me-owner"))
        runCurrent()

        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }

    @Test
    fun blankAndMeUserIdsAreIgnored() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing(""))
        dispatcher.dispatch(typing("me"))
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())

        me = ""
        dispatcher.dispatch(typing("user-a"))
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }

    @Test
    fun userSwitchClearsPreviousState() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()
        assertEquals(mapOf("chat-1" to "user-a"), TypingPresenceStore.typingByChat.value)

        me = "me-other"
        dispatcher.dispatch(typing("user-b", chatId = "chat-2"))
        runCurrent()
        assertEquals(mapOf("chat-2" to "user-b"), TypingPresenceStore.typingByChat.value)

        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }

    @Test
    fun entryExpiresAfterTimeout() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()

        advanceTimeBy(3_000L)
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }

    @Test
    fun renewalExtendsTheLease() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()

        advanceTimeBy(2_000L)
        dispatcher.dispatch(typing("user-a"))
        runCurrent()
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals("user-a", TypingPresenceStore.isTyping("chat-1"))

        advanceTimeBy(1_900L)
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }

    @Test
    fun clearEmptiesMapAndCancelsPendingExpiry() = runTest {
        val dispatcher = startStore()
        dispatcher.dispatch(typing("user-a"))
        runCurrent()

        TypingPresenceStore.clear()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())

        advanceTimeBy(5_000L)
        runCurrent()
        assertTrue(TypingPresenceStore.typingByChat.value.isEmpty())
    }
}
