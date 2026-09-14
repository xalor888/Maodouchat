package com.maodouchat.security

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

    private companion object {
        const val CHAT = "c_secret"
    }
}
