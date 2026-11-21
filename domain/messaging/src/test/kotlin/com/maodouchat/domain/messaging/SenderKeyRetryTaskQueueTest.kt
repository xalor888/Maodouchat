package com.maodouchat.domain.messaging

import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SenderKeyRetryTaskQueueTest {

    private val now = 1_000_000L

    private class FakeStore : SenderKeyRetryTaskStore {
        val tasks = mutableMapOf<Pair<String, String>, SenderKeyRetryTask>()
        override suspend fun get(ownerUserId: String, chatId: String): SenderKeyRetryTask? =
            tasks[ownerUserId to chatId]

        override suspend fun upsert(task: SenderKeyRetryTask) {
            tasks[task.ownerUserId to task.chatId] = task
        }
    }

    private fun queue(
        store: FakeStore,
        sessionActive: (String) -> Boolean = { true },
        refreshed: MutableList<String>? = null
    ) = SenderKeyRetryTaskQueue(
        store = store,
        sessionActive = sessionActive,
        refreshSchedule = { refreshed?.add(it) },
        nowMs = { now },
        jitterFactor = { 0.5 }
    )

    private fun task(
        attempts: Int = 0,
        epoch: Long = 1L,
        nextAttemptAt: Long = 0L,
        lastError: String? = null
    ) = SenderKeyRetryTask(
        ownerUserId = "u1",
        chatId = "c1",
        epoch = epoch,
        reason = "coverage_required",
        attempts = attempts,
        nextAttemptAt = nextAttemptAt,
        lastError = lastError,
        updatedAt = now
    )

    @Test
    fun `fresh epoch resets nextAttemptAt to now plus delay`() = runTest {
        val store = FakeStore()
        queue(store).enqueueLocked("u1", "c1", 1L, "coverage_required", 30_000L)
        val saved = store.get("u1", "c1")!!
        assertEquals(now + 30_000L, saved.nextAttemptAt)
        assertEquals(0, saved.attempts)
        assertEquals(1L, saved.epoch)
    }

    @Test
    fun `same epoch keeps later nextAttemptAt instead of pulling backoff back`() = runTest {
        val store = FakeStore()
        store.upsert(task(attempts = 2, nextAttemptAt = now + 100_000L))
        queue(store).enqueueLocked("u1", "c1", 1L, "coverage_required", 30_000L)
        val saved = store.get("u1", "c1")!!
        assertEquals(now + 100_000L, saved.nextAttemptAt)
        assertEquals(2, saved.attempts)
    }

    @Test
    fun `attempts preserved on same epoch and reset on new epoch`() = runTest {
        val store = FakeStore()
        store.upsert(task(attempts = 3, nextAttemptAt = now + 60_000L))
        queue(store).enqueueLocked("u1", "c1", 1L, "again", 30_000L)
        assertEquals(3, store.get("u1", "c1")!!.attempts)
        queue(store).enqueueLocked("u1", "c1", 2L, "rotated", 30_000L)
        val saved = store.get("u1", "c1")!!
        assertEquals(0, saved.attempts)
        assertEquals(now + 30_000L, saved.nextAttemptAt)
    }

    @Test
    fun `reaching max attempts abandons with MAX_VALUE and refreshes schedule`() = runTest {
        val store = FakeStore()
        val refreshed = mutableListOf<String>()
        store.upsert(task(attempts = 4))
        queue(store, refreshed = refreshed)
            .markFailure(store.get("u1", "c1")!!, IllegalStateException("protocol error"))
        val saved = store.get("u1", "c1")!!
        assertEquals(5, saved.attempts)
        assertEquals(Long.MAX_VALUE, saved.nextAttemptAt)
        assertEquals(listOf("u1"), refreshed)
    }

    @Test
    fun `network and protocol errors use different backoff tiers`() = runTest {
        val store = FakeStore()
        queue(store).markFailure(task(), IOException("boom"))
        assertEquals(now + 10_000L, store.get("u1", "c1")!!.nextAttemptAt)
        store.tasks.clear()
        queue(store).markFailure(task(), IllegalStateException("protocol error"))
        assertEquals(now + 30_000L, store.get("u1", "c1")!!.nextAttemptAt)
    }

    @Test
    fun `network error allows more attempts before abandoning`() = runTest {
        val store = FakeStore()
        queue(store).markFailure(task(attempts = 6), IOException("boom"))
        val saved = store.get("u1", "c1")!!
        assertEquals(7, saved.attempts)
        assertTrue(saved.nextAttemptAt != Long.MAX_VALUE)
        store.tasks.clear()
        queue(store).markFailure(task(attempts = 6), IllegalStateException("protocol error"))
        assertEquals(Long.MAX_VALUE, store.get("u1", "c1")!!.nextAttemptAt)
    }

    @Test
    fun `markFailure is a no-op when session is inactive`() = runTest {
        val store = FakeStore()
        val refreshed = mutableListOf<String>()
        store.upsert(task(attempts = 1))
        queue(store, sessionActive = { false }, refreshed = refreshed)
            .markFailure(store.get("u1", "c1")!!, IOException("boom"))
        assertEquals(1, store.get("u1", "c1")!!.attempts)
        assertTrue(refreshed.isEmpty())
    }

    @Test
    fun `reason truncated to 80 chars and lastError truncated to 200 chars`() = runTest {
        val store = FakeStore()
        queue(store).enqueueLocked("u1", "c1", 1L, "r".repeat(100), 30_000L)
        assertEquals(80, store.get("u1", "c1")!!.reason.length)
        queue(store).markFailure(
            store.get("u1", "c1")!!,
            IllegalStateException("e".repeat(300))
        )
        val saved = store.get("u1", "c1")!!
        assertEquals(200, saved.lastError!!.length)
    }
}
