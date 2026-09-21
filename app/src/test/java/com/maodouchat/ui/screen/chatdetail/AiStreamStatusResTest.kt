package com.maodouchat.ui.screen.chatdetail

import com.maodouchat.R
import com.maodouchat.data.local.entity.AiOperationError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * G161：`ChatDetailIntents.aiStreamStatusRes` 的测试（G161 刚从 aiStreamStatusText 抽出）。
 *
 * 原函数是 `@Composable`，只能靠仪器测试覆盖；抽出后普通 JVM 单测就能逐分支断言。
 * 重点盯两处「多码一支」——那是**有意的**产品决策，拆开会让用户以为区别对待。
 */
class AiStreamStatusResTest {

    @Test
    fun `each single code maps to its own string`() {
        assertEquals(R.string.chat_ai_stream_cancelled, aiStreamStatusRes("CANCELLED"))
        assertEquals(R.string.chat_ai_stream_rate_limited, aiStreamStatusRes(AiOperationError.RATE_LIMITED))
        assertEquals(R.string.chat_ai_stream_quota_exceeded, aiStreamStatusRes(AiOperationError.QUOTA_EXCEEDED))
        assertEquals(R.string.chat_ai_operation_network_failed, aiStreamStatusRes(AiOperationError.NETWORK))
        assertEquals(R.string.chat_ai_operation_server_failed, aiStreamStatusRes(AiOperationError.SERVER))
    }

    @Test
    fun `timeout outcome unknown and unknown share one string`() {
        val shared = aiStreamStatusRes(AiOperationError.TIMEOUT)
        assertEquals(shared, aiStreamStatusRes(AiOperationError.OUTCOME_UNKNOWN))
        assertEquals(shared, aiStreamStatusRes(AiOperationError.UNKNOWN))
        assertEquals(R.string.chat_ai_operation_outcome_unknown, shared)
    }

    @Test
    fun `empty result and invalid response share one string`() {
        val shared = aiStreamStatusRes(AiOperationError.EMPTY_RESULT)
        assertEquals(shared, aiStreamStatusRes(AiOperationError.INVALID_RESPONSE))
        assertEquals(R.string.chat_ai_operation_invalid_result, shared)
    }

    @Test
    fun `unknown codes fall back to the generic failure string`() {
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes(""))
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes("SOMETHING_NEW"))
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes("CONTEXT_MISSING"))
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes(AiOperationError.INTERRUPTED))
        // 大小写敏感：小写不走已知分支
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes("timeout"))
        assertEquals(R.string.chat_ai_operation_failed, aiStreamStatusRes("Timeout"))
    }

    @Test
    fun `the shared groups are not collapsed with each other`() {
        // 两组合并支必须是不同的文案，否则「结果无效」和「可能已处理」会混为一谈
        assertNotEquals(
            aiStreamStatusRes(AiOperationError.TIMEOUT),
            aiStreamStatusRes(AiOperationError.EMPTY_RESULT),
        )
        // 兜底也不能和任何一支相同
        listOf(
            AiOperationError.TIMEOUT,
            AiOperationError.EMPTY_RESULT,
            AiOperationError.NETWORK,
            AiOperationError.SERVER,
        ).forEach { code ->
            assertNotEquals(
                "$code 的文案与兜底相同",
                aiStreamStatusRes(code),
                aiStreamStatusRes("no_such_code"),
            )
        }
    }

    @Test
    fun `cancelled is distinct from every failure branch`() {
        // 「已停止」是用户主动行为，不能和任何失败文案相同
        val cancelled = aiStreamStatusRes("CANCELLED")
        listOf(
            AiOperationError.TIMEOUT,
            AiOperationError.NETWORK,
            AiOperationError.SERVER,
            AiOperationError.EMPTY_RESULT,
            AiOperationError.RATE_LIMITED,
            AiOperationError.QUOTA_EXCEEDED,
        ).forEach { code ->
            assertNotEquals("CANCELLED 与 $code 文案相同", cancelled, aiStreamStatusRes(code))
        }
    }
}
