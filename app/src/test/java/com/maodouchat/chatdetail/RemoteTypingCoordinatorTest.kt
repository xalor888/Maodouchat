package com.maodouchat.chatdetail

import com.maodouchat.ui.screen.chatdetail.RemoteTypingCoordinator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTypingCoordinatorTest {
    @Test
    fun `latest group typer falls back to an earlier active user`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", false)

        assertEquals(listOf("user-a", "user-b", "user-a"), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `renewing a typing lease prevents the old timeout from clearing it`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(1_900L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `wrong chat blank user and clear are handled safely`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-2", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "", true)
        assertEquals(emptyList<String?>(), observed)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.clear()
        assertEquals(listOf("user-a", null), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `renewing an already typing user emits again but keeps a single lease`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()

        // 第二次续租只替换租约，超时后只清一次
        assertEquals(listOf("user-a", "user-a", null), observed)
    }

    @Test
    fun `stopping an unknown user emits nothing`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-ghost", false)

        assertEquals(emptyList<String?>(), observed)
    }

    @Test
    fun `expiry of the earliest user falls back to the still-active newer one`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals("user-b", observed.last())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `clear on an idle coordinator emits nothing`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.clear()

        assertEquals(emptyList<String?>(), observed)
    }

    @Test
    fun `stop after natural expiry is a no-op`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)

        assertEquals(listOf("user-a", null), observed)
    }
}
