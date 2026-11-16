package com.maodouchat.domain.messaging

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertNotNull

class ChatPinUnlockPolicyTest {

    @Test
    fun `valid pin is 4 to 8 digits`() {
        assertTrue(ChatPinUnlockPolicy.isValidPin("1234"))
        assertTrue(ChatPinUnlockPolicy.isValidPin("12345678"))
    }

    @Test
    fun `pin shorter than 4 digits is invalid`() {
        assertFalse(ChatPinUnlockPolicy.isValidPin("123"))
        assertFalse(ChatPinUnlockPolicy.isValidPin(""))
    }

    @Test
    fun `pin longer than 8 digits is invalid`() {
        assertFalse(ChatPinUnlockPolicy.isValidPin("123456789"))
    }

    @Test
    fun `pin with non-digit is invalid`() {
        assertFalse(ChatPinUnlockPolicy.isValidPin("12a4"))
        assertFalse(ChatPinUnlockPolicy.isValidPin("123 "))
    }

    @Test
    fun `resolve prefers active chat id`() {
        assertEquals(
            "active-1",
            ChatPinUnlockPolicy.resolveLockChatId("active-1", "chat-1"),
        )
    }

    @Test
    fun `resolve falls back to chat id when active is blank`() {
        assertEquals(
            "chat-1",
            ChatPinUnlockPolicy.resolveLockChatId("", "chat-1"),
        )
        assertEquals(
            "chat-1",
            ChatPinUnlockPolicy.resolveLockChatId("   ", "chat-1"),
        )
    }

    @Test
    fun `resolve returns blank when both ids are blank`() {
        assertEquals("", ChatPinUnlockPolicy.resolveLockChatId("", ""))
    }

    @Test
    fun `blank chat id succeeds without verify or callback`() = runTest {
        var verifyCalls = 0
        var unlockedCalls = 0
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "",
            pin = "1234",
            verify = { _, _ -> verifyCalls++; true },
            onUnlocked = { unlockedCalls++ },
        )
        assertTrue(ok)
        assertEquals(0, verifyCalls)
        assertEquals(0, unlockedCalls)
    }

    @Test
    fun `blank-only chat id succeeds without verify or callback`() = runTest {
        var verifyCalls = 0
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "   ",
            pin = "1234",
            verify = { _, _ -> verifyCalls++; true },
            onUnlocked = {},
        )
        assertTrue(ok)
        assertEquals(0, verifyCalls)
    }

    @Test
    fun `verify success runs onUnlocked once`() = runTest {
        var unlockedCalls = 0
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "chat-1",
            pin = "1234",
            verify = { _, _ -> true },
            onUnlocked = { unlockedCalls++ },
        )
        assertTrue(ok)
        assertEquals(1, unlockedCalls)
    }

    @Test
    fun `verify failure skips onUnlocked`() = runTest {
        var unlockedCalls = 0
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "chat-1",
            pin = "0000",
            verify = { _, _ -> false },
            onUnlocked = { unlockedCalls++ },
        )
        assertFalse(ok)
        assertEquals(0, unlockedCalls)
    }

    @Test
    fun `verify receives chat id and pin`() = runTest {
        var seenId = ""
        var seenPin = ""
        ChatPinUnlockPolicy.unlock(
            chatId = "chat-1",
            pin = "1234",
            verify = { id, p -> seenId = id; seenPin = p; true },
            onUnlocked = {},
        )
        assertEquals("chat-1", seenId)
        assertEquals("1234", seenPin)
    }

    @Test
    fun `verify exception becomes failure`() = runTest {
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "chat-1",
            pin = "1234",
            verify = { _, _ -> throw IllegalStateException("room down") },
            onUnlocked = { throw AssertionError("must not run") },
        )
        assertFalse(ok)
    }

    @Test
    fun `onUnlocked may call suspend functions`() = runTest {
        // MediaCenter 的 onUnlocked 要调挂起的 resolveChatName；回调必须是挂起类型。
        var ran = false
        val ok = ChatPinUnlockPolicy.unlock(
            chatId = "chat-1",
            pin = "1234",
            verify = { _, _ -> true },
            onUnlocked = {
                kotlinx.coroutines.delay(1)
                ran = true
            },
        )
        assertTrue(ok)
        assertTrue(ran)
    }

    @Test
    fun `cancellation exception propagates`() = runTest {
        var caught: CancellationException? = null
        try {
            ChatPinUnlockPolicy.unlock(
                chatId = "chat-1",
                pin = "1234",
                verify = { _, _ -> throw CancellationException("cancelled") },
                onUnlocked = {},
            )
        } catch (e: CancellationException) {
            caught = e
        }
        assertNotNull(caught, "取消异常应原样抛出而不是判失败")
    }
}
