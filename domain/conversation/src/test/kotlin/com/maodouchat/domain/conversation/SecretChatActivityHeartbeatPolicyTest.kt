package com.maodouchat.domain.conversation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

// 密聊活动心跳单测：由 app 侧 SecretChatActivityHeartbeatTest 平移，
// 覆盖对象改为 SecretChatActivityHeartbeatPolicy.run；行为与原来一致。
class SecretChatActivityHeartbeatPolicyTest {

    private class StopHeartbeat : RuntimeException("stop heartbeat loop")

    /**
     * 跑 [SecretChatActivityHeartbeatPolicy.run]，把它那个无限循环用「第 N 次 sleep 抛哨兵」结束。
     */
    private suspend fun runCycles(
        cycles: Int,
        lastActivityAt: Long?,
        expired: Boolean,
        readFails: Boolean = false,
        intervalMs: Long = SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS,
    ): Harness {
        val h = Harness()
        try {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = {
                    if (readFails) error("db locked")
                    h.events += "read"
                    lastActivityAt
                },
                isExpired = { _, _ -> h.events += "isExpired"; expired },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
                intervalMs = intervalMs,
                sleep = { ms ->
                    h.events += "sleep:$ms"
                    if (h.events.count { it.startsWith("sleep") } >= cycles) throw StopHeartbeat()
                },
            )
            fail("心跳循环不应当自然结束（原实现是 while(true)，由 LaunchedEffect 取消结束）")
        } catch (_: StopHeartbeat) {
            // 预期：由测试结束循环
        }
        return h
    }

    private class Harness {
        val events = mutableListOf<String>()
    }

    @Test
    fun `an expired session destroys the local cache before the heartbeat starts`() = runTest {
        val h = runCycles(cycles = 1, lastActivityAt = 10L, expired = true)
        assertTrue("destroy" in h.events, "已过期的会话必须销毁本地解密缓存：${h.events}")
        // 销毁发生在第一次写活动之前（进入即校验）。
        assertTrue(
            h.events.indexOf("destroy") < h.events.indexOf("touch"),
            "必须先做 TTL 校验再开始心跳：${h.events}",
        )
    }

    @Test
    fun `a live session is not destroyed`() = runTest {
        val h = runCycles(cycles = 1, lastActivityAt = 10L, expired = false)
        assertTrue("destroy" !in h.events, "未过期绝不能销毁：${h.events}")
        assertTrue("isExpired" in h.events, "未过期也要真的判过一次：${h.events}")
    }

    @Test
    fun `a missing record is not treated as expired`() = runTest {
        val h = runCycles(cycles = 1, lastActivityAt = null, expired = true)
        assertTrue("destroy" !in h.events, "没有记录时不应销毁：${h.events}")
        assertTrue("isExpired" !in h.events, "没有记录时无需判过期：${h.events}")
    }

    @Test
    fun `a failed read does not stop the heartbeat`() = runTest {
        val h = runCycles(cycles = 1, lastActivityAt = null, expired = false, readFails = true)
        assertTrue("touch" in h.events, "读失败必须被吞掉、心跳照常：${h.events}")
        assertTrue("destroy" !in h.events, "读失败不得触发销毁：${h.events}")
    }

    @Test
    fun `the heartbeat touches activity once per interval`() = runTest {
        val h = runCycles(cycles = 3, lastActivityAt = 10L, expired = false)
        val touches = h.events.count { it == "touch" }
        val sleeps = h.events.filter { it.startsWith("sleep") }
        assertEquals(3, touches, "每轮心跳写一次活动：${h.events}")
        assertEquals(3, sleeps.size, "每写一次活动就睡一轮：${h.events}")
        sleeps.forEach {
            assertEquals(
                "sleep:${SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS}",
                it,
                "心跳间隔必须保持 60s（原实现值）：${h.events}",
            )
        }
    }

    // G10 回归：心跳被取消后不得再产生任何副作用——touchActivity 会延长密聊 TTL，
    // 取消后还能写一次活动，就等于让泄漏的心跳给已离开的会话续命。
    @Test
    fun `cancellation during the initial read performs no write at all`() = runTest {
        val h = Harness()
        val readStarted = CompletableDeferred<Unit>()

        val job = launch {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = {
                    readStarted.complete(Unit)
                    // 读取挂起在这里，模拟真实的数据库 IO 尚未返回。
                    awaitCancellation()
                },
                isExpired = { _, _ -> h.events += "isExpired"; false },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
                // 用默认的 delay：取消会在这里被观察到，循环随之结束（不会空转）。
            )
        }

        readStarted.await()
        job.cancelAndJoin()

        assertEquals(
            emptyList(),
            h.events,
            "取消之后不得再有 destroy/touch —— touchActivity 会延长密聊 TTL：${h.events}",
        )
    }

    @Test
    fun `cancellation while waiting performs no further write`() = runTest {
        val h = Harness()
        val job = launch {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { h.events += "read"; null },
                isExpired = { _, _ -> false },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
            )
        }

        runCurrent()
        assertEquals(1, h.events.count { it == "touch" }, "应当先写一次活动：${h.events}")

        job.cancelAndJoin()
        advanceTimeBy(3 * SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS)
        runCurrent()

        assertEquals(1, h.events.count { it == "touch" }, "取消后不得再写活动：${h.events}")
    }

    // 取消发生在「读取已返回、但还没走到销毁/写活动」之间：守读取之后那一句 ensureActive()。
    @Test
    fun `cancellation right after the read prevents the destroy and the heartbeat`() = runTest {
        val h = Harness()

        val job = launch {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { id ->
                    h.events += "read"
                    // 读取期间被取消（不抛异常，只把当前任务置为已取消）。
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.cancel()
                    LAST_ACTIVITY_AT
                },
                isExpired = { _, _ -> h.events += "isExpired"; true },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
            )
        }
        job.join()

        assertEquals(
            listOf("read"),
            h.events,
            "读取之后已被取消，就不该再判过期、销毁或写活动：${h.events}",
        )
    }

    @Test
    fun `destroy, touch and the expiry check all see the injected chat id`() = runTest {
        val destroyArgs = mutableListOf<String>()
        val touchArgs = mutableListOf<String>()
        val expiryArgs = mutableListOf<Pair<String, Long>>()
        try {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { LAST_ACTIVITY_AT },
                isExpired = { chatId, at -> expiryArgs += chatId to at; true },
                destroy = { chatId -> destroyArgs += chatId },
                touch = { chatId -> touchArgs += chatId },
                sleep = { throw StopHeartbeat() },
            )
            fail("心跳循环不应当自然结束")
        } catch (_: StopHeartbeat) {
        }
        assertEquals(listOf(CHAT), destroyArgs, "销毁必须带上注入的 chatId")
        assertEquals(listOf(CHAT to LAST_ACTIVITY_AT), expiryArgs, "判过期必须拿到 chatId 与记录时间戳")
        assertEquals(listOf(CHAT), touchArgs, "写活动必须带上注入的 chatId")
    }

    @Test
    fun `the expiry check runs exactly once across multiple cycles`() = runTest {
        val h = Harness()
        var expiryChecks = 0
        try {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { 10L },
                isExpired = { _, _ -> expiryChecks++; h.events += "isExpired"; false },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
                sleep = { ms ->
                    h.events += "sleep:$ms"
                    if (h.events.count { it.startsWith("sleep") } >= 3) throw StopHeartbeat()
                },
            )
            fail("心跳循环不应当自然结束")
        } catch (_: StopHeartbeat) {
        }
        assertEquals(1, expiryChecks, "判过期只在进入时做一次，循环里不再重复：${h.events}")
    }

    @Test
    fun `touch and sleep strictly alternate per cycle`() = runTest {
        val h = runCycles(cycles = 2, lastActivityAt = 10L, expired = false)
        val heartbeat = h.events.dropWhile { it != "touch" }
        assertEquals(
            listOf(
                "touch",
                "sleep:${SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS}",
                "touch",
                "sleep:${SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS}",
            ),
            heartbeat,
            "每轮必须是 写活动→睡一轮 的交替：${h.events}",
        )
    }

    @Test
    fun `a custom interval is honored by the sleep`() = runTest {
        val h = runCycles(cycles = 2, lastActivityAt = 10L, expired = false, intervalMs = 1_000L)
        assertEquals(
            listOf("sleep:1000", "sleep:1000"),
            h.events.filter { it.startsWith("sleep") },
            "注入的 intervalMs 必须生效：${h.events}",
        )
    }

    @Test
    fun `cancellation right after destroy skips the first touch`() = runTest {
        val h = Harness()
        lateinit var job: Job
        job = launch {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { h.events += "read"; 10L },
                isExpired = { _, _ -> h.events += "isExpired"; true },
                destroy = { h.events += "destroy"; job.cancel() },
                touch = { h.events += "touch" },
            )
        }
        job.join()
        assertEquals(
            listOf("read", "isExpired", "destroy"),
            h.events,
            "销毁之后被取消，就不该再写活动：${h.events}",
        )
    }

    // destroy 在进入校验的 try 块内：它抛普通异常应被吞掉，心跳照常开始。
    @Test
    fun `a destroy failure is swallowed and the heartbeat continues`() = runTest {
        val h = Harness()
        try {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { h.events += "read"; 10L },
                isExpired = { _, _ -> h.events += "isExpired"; true },
                destroy = { h.events += "destroy"; error("cache already gone") },
                touch = { h.events += "touch" },
                sleep = { ms ->
                    h.events += "sleep:$ms"
                    if (h.events.count { it.startsWith("sleep") } >= 1) throw StopHeartbeat()
                },
            )
            fail("心跳循环不应当自然结束")
        } catch (_: StopHeartbeat) {
        }
        assertEquals(
            listOf("read", "isExpired", "destroy", "touch", "sleep:${SecretChatActivityHeartbeatPolicy.HEARTBEAT_INTERVAL_MS}"),
            h.events,
            "销毁失败被吞掉后，心跳必须照常写活动：${h.events}",
        )
    }

    // touch 在循环体内、不在 try 块内：它抛异常应向上传播，心跳随之结束。
    @Test
    fun `a failing touch aborts the heartbeat`() = runTest {
        val h = Harness()
        assertFailsWith<IllegalStateException> {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { null },
                isExpired = { _, _ -> false },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch"; error("db locked") },
            )
        }
        assertEquals(listOf("touch"), h.events, "touch 失败后不得再 sleep 或重试：${h.events}")
    }

    // sleep 同样在 try 块之外：它抛普通异常应向上传播，而不是被当成读失败吞掉。
    @Test
    fun `a failing sleep aborts the heartbeat`() = runTest {
        val h = Harness()
        assertFailsWith<IllegalStateException> {
            SecretChatActivityHeartbeatPolicy.run(
                chatId = CHAT,
                readLastActivityAt = { null },
                isExpired = { _, _ -> false },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
                sleep = { error("timer broken") },
            )
        }
        assertEquals(listOf("touch"), h.events, "sleep 失败前应当恰好写过一次活动：${h.events}")
    }

    private companion object {
        const val CHAT = "c_secret"
        const val LAST_ACTIVITY_AT = 10L
    }
}
