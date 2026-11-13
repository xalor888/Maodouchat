package com.maodouchat.domain.messaging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// 从 app 侧 AiStreamStatusResTest 随迁：断言由 R int 改为文案种类，分支语义逐字不变。
class AiStreamStatusTextTest {

    @Test
    fun `each single code maps to its own kind`() {
        assertEquals(AiStreamStatusText.CANCELLED, resolveAiStreamStatusText("CANCELLED"))
        assertEquals(AiStreamStatusText.RATE_LIMITED, resolveAiStreamStatusText("RATE_LIMITED"))
        assertEquals(AiStreamStatusText.QUOTA_EXCEEDED, resolveAiStreamStatusText("QUOTA_EXCEEDED"))
        assertEquals(AiStreamStatusText.NETWORK, resolveAiStreamStatusText("NETWORK"))
        assertEquals(AiStreamStatusText.SERVER, resolveAiStreamStatusText("SERVER"))
    }

    @Test
    fun `timeout outcome unknown and unknown share one kind`() {
        val shared = resolveAiStreamStatusText("TIMEOUT")
        assertEquals(shared, resolveAiStreamStatusText("OUTCOME_UNKNOWN"))
        assertEquals(shared, resolveAiStreamStatusText("UNKNOWN"))
        assertEquals(AiStreamStatusText.OUTCOME_UNKNOWN, shared)
    }

    @Test
    fun `empty result and invalid response share one kind`() {
        val shared = resolveAiStreamStatusText("EMPTY_RESULT")
        assertEquals(shared, resolveAiStreamStatusText("INVALID_RESPONSE"))
        assertEquals(AiStreamStatusText.INVALID_RESULT, shared)
    }

    @Test
    fun `unknown codes fall back to the generic failure kind`() {
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText(""))
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText("SOMETHING_NEW"))
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText("CONTEXT_MISSING"))
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText("INTERRUPTED"))
        // 大小写敏感：小写不走已知分支
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText("timeout"))
        assertEquals(AiStreamStatusText.FAILED, resolveAiStreamStatusText("Timeout"))
    }

    @Test
    fun `the shared groups are not collapsed with each other`() {
        // 两组合并支必须是不同的文案，否则“结果无效”和“可能已处理”会混为一谈
        assertNotEquals(
            resolveAiStreamStatusText("TIMEOUT"),
            resolveAiStreamStatusText("EMPTY_RESULT"),
        )
        // 兜底也不能和任何一支相同
        listOf("TIMEOUT", "EMPTY_RESULT", "NETWORK", "SERVER").forEach { code ->
            assertNotEquals(
                resolveAiStreamStatusText(code),
                resolveAiStreamStatusText("no_such_code"),
                "$code 的文案与兜底相同",
            )
        }
    }

    @Test
    fun `cancelled is distinct from every failure branch`() {
        // “已停止”是用户主动行为，不能和任何失败文案相同
        val cancelled = resolveAiStreamStatusText("CANCELLED")
        listOf("TIMEOUT", "NETWORK", "SERVER", "EMPTY_RESULT", "RATE_LIMITED", "QUOTA_EXCEEDED").forEach { code ->
            assertNotEquals(cancelled, resolveAiStreamStatusText(code), "CANCELLED 与 $code 文案相同")
        }
    }
}
