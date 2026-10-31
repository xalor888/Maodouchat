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
    fun `event for another chat does not disturb active typer`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-2", "user-b", true)
        assertEquals(listOf("user-a"), observed)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `blank stop leaves active typer and its lease untouched`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "", false)
        assertEquals(listOf("user-a"), observed)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `typing again after stop restarts the full lease`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_900L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(200L)
        runCurrent()

        assertEquals(listOf("user-a", null, "user-a", null), observed)
    }

    @Test
    fun `stopping earliest user keeps latest user visible`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        assertEquals(listOf("user-a", "user-b", "user-b"), observed)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-b", null), observed)
    }

    @Test
    fun `fresh typing after clear starts a new full lease`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.clear()
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null, "user-b", null), observed)
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

    @Test
    fun `stopping the middle user keeps the latest user visible`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", false)

        assertEquals(listOf("user-a", "user-b", "user-c", "user-c"), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `renewing the earlier user extends only its own lease`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(500L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `clear with several users emits a single null and cancels all leases`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.clear()
        assertEquals(listOf("user-a", "user-b", null), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", null), observed)
    }

    @Test
    fun `stop event for another chat leaves the active typer alone`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-2", "user-a", false)
        assertEquals(listOf("user-a"), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `typing again after natural expiry restarts the full lease`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_900L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(100L)
        runCurrent()
        assertEquals(listOf("user-a", null, "user-a", null), observed)
    }


    @Test
    fun `repeated renewals extend from the latest renewal`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_900L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(200L)
        runCurrent()
        assertEquals(listOf("user-a", "user-a", "user-a", null), observed)
    }

    @Test
    fun `custom short timeout expires on its own schedule`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 1_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(999L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `staggered expiry of three users falls back through the chain`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("user-c", observed.last())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals("user-c", observed.last())
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(null, observed.last())
    }

    @Test
    fun `double clear emits null only once`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.clear()
        coordinator.clear()
        assertEquals(listOf("user-a", null), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `stop and retype keeps the other user lease intact`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        advanceTimeBy(500L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-b", "user-a", "user-a", null), observed)
    }

    @Test
    fun `stopping the latest of three falls back to the middle user`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        coordinator.onEvent("chat-1", "chat-1", "user-c", false)

        assertEquals(listOf("user-a", "user-b", "user-c", "user-b"), observed)
    }

    @Test
    fun `whitespace-only user events are ignored in both directions`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "   ", true)
        coordinator.onEvent("chat-1", "chat-1", "   ", false)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(emptyList<String?>(), observed)
    }

    @Test
    fun `expiry of the earliest of three re-emits the latest user`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-c", "user-c"), observed)
    }

    @Test
    fun `clear after natural expiry emits nothing new`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()
        coordinator.clear()

        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `stopping the same user twice emits the fallback only once`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)

        assertEquals(listOf("user-a", "user-b", "user-b"), observed)
    }

    @Test
    fun `blank active chat ignores the event but a later valid chat applies`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("", "", "user-a", true)
        assertEquals(emptyList<String?>(), observed)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `renewing the latest user re-emits and keeps the earlier user as fallback`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-b", "user-b"), observed)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-b", "user-b", null), observed)
    }

    @Test
    fun `stop then retype staggers the two leases so expiry falls back in order`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", false)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-a", "user-b", "user-b"), observed)
        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-a", "user-b", "user-b", null), observed)
    }

    @Test
    fun `renewed user expiry falls back to a newer user that arrived later`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(500L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(2_500L)
        runCurrent()
        assertEquals(listOf("user-a", "user-a", "user-b", "user-b"), observed)
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(listOf("user-a", "user-a", "user-b", "user-b", null), observed)
    }

    @Test
    fun `stop after clear emits nothing new`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.clear()
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `retyping an expired earlier user while another stays active`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-b"), observed)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-b", "user-a", "user-a"), observed)
        advanceTimeBy(2_000L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-b", "user-a", "user-a", null), observed)
    }

    @Test
    fun `renewing the middle user moves it latest and expiries re-emit it`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(1_000L)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        advanceTimeBy(500L)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-c", "user-b", "user-b"), observed)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(listOf("user-a", "user-b", "user-c", "user-b", "user-b", "user-b"), observed)
        advanceTimeBy(500L)
        runCurrent()

        assertEquals(listOf("user-a", "user-b", "user-c", "user-b", "user-b", "user-b", null), observed)
    }

    @Test
    fun `stopping all users one by one walks the fallback chain to null`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", "user-b", true)
        coordinator.onEvent("chat-1", "chat-1", "user-c", true)
        coordinator.onEvent("chat-1", "chat-1", "user-c", false)
        coordinator.onEvent("chat-1", "chat-1", "user-b", false)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)

        assertEquals(listOf("user-a", "user-b", "user-c", "user-b", "user-a", null), observed)
    }

    @Test
    fun `same user retype after clear expires on the new schedule`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.clear()
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_900L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(100L)
        runCurrent()

        assertEquals(listOf("user-a", null, "user-a", null), observed)
    }

    @Test
    fun `blank active chat ignores a non-blank event chat`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)

        coordinator.onEvent("", "chat-1", "user-a", true)
        assertEquals(emptyList<String?>(), observed)

        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(3_000L)
        runCurrent()

        assertEquals(listOf("user-a", null), observed)
    }


    @Test
    fun `padded chat ids never match each other`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)
        coordinator.onEvent("chat-1 ", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1 ", "user-a", true)
        assertEquals(emptyList<String?>(), observed)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        assertEquals(listOf("user-a"), observed)
    }

    @Test
    fun `a padded user id is kept verbatim, not trimmed`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)
        coordinator.onEvent("chat-1", "chat-1", " user-a ", true)
        coordinator.onEvent("chat-1", "chat-1", "user-a", false)
        assertEquals(listOf(" user-a "), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf(" user-a ", null), observed)
    }

    @Test
    fun `a padded stop id does not stop the unpadded typer`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        coordinator.onEvent("chat-1", "chat-1", " user-a ", false)
        assertEquals(listOf("user-a"), observed)
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(listOf("user-a", null), observed)
    }

    @Test
    fun `an event with both chat ids blank is ignored`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)
        coordinator.onEvent("", "", "user-a", true)
        assertEquals(emptyList<String?>(), observed)
    }

    @Test
    fun `a renewal one tick before expiry keeps the user alive`() = runTest {
        val observed = mutableListOf<String?>()
        val coordinator = RemoteTypingCoordinator(this, timeoutMs = 3_000L, observed::add)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_999L)
        coordinator.onEvent("chat-1", "chat-1", "user-a", true)
        advanceTimeBy(2_999L)
        runCurrent()
        assertEquals("user-a", observed.last())
        advanceTimeBy(1L)
        runCurrent()
        assertEquals(listOf("user-a", "user-a", null), observed)
    }
}
