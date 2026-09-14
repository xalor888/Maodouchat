package com.maodouchat.security

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * G9：密聊活动心跳的单测。
 *
 * 这段逻辑原先埋在 `ChatDetailRoute.kt` 的 `LaunchedEffect` 里，**没有任何测试**——
 * 「过期就销毁解密缓存」「未过期不能误销毁」「按节奏写 activity」三条只能靠肉眼读。
 * 抽成 [SecretChatActivityHeartbeat.run] 之后依赖全部可注入，这三条才第一次可验证。
 */
class SecretChatActivityHeartbeatTest {

    private class StopHeartbeat : RuntimeException("stop heartbeat loop")

    /**
     * 跑 [SecretChatActivityHeartbeat.run]，把它那个无限循环用「第 N 次 sleep 抛哨兵」结束。
     */
    private suspend fun runCycles(
        cycles: Int,
        lastActivityAt: Long?,
        expired: Boolean,
        readFails: Boolean = false,
    ): Harness {
        val h = Harness()
        try {
            SecretChatActivityHeartbeat.run(
                chatId = CHAT,
                readLastActivityAt = {
                    if (readFails) error("db locked")
                    h.events += "read"
                    lastActivityAt
                },
                isExpired = { _, _ -> h.events += "isExpired"; expired },
                destroy = { h.events += "destroy" },
                touch = { h.events += "touch" },
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
                "sleep:${SecretChatActivityHeartbeat.HEARTBEAT_INTERVAL_MS}",
                it,
                "心跳间隔必须保持 60s（原实现值）：${h.events}",
            )
        }
    }

    /**
     * G10 回归：心跳被取消后**不得再产生任何副作用**。
     *
     * 这是取消语义的核心：`touchActivity` 会延长密聊 TTL。如果取消后还能写一次活动时间，
     * 「离开会话/销毁会话」就可能被一次泄漏的心跳续命——TTL 语义被悄悄破坏。
     *
     * 当前实现用 `runCatching { }` 包住首次读取，会把取消异常一起吞掉，随后 `while(true)`
     * 仍会调用一次（非协作的）`touch`。这个用例就是钉住它。
     */
    @Test
    fun `cancellation during the initial read performs no write at all`() = runTest {
        val h = Harness()
        val readStarted = CompletableDeferred<Unit>()

        val job = launch {
            SecretChatActivityHeartbeat.run(
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
            SecretChatActivityHeartbeat.run(
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
        advanceTimeBy(3 * SecretChatActivityHeartbeat.HEARTBEAT_INTERVAL_MS)
        runCurrent()

        assertEquals(1, h.events.count { it == "touch" }, "取消后不得再写活动：${h.events}")
    }

    private companion object {
        const val CHAT = "c_secret"
    }
}
