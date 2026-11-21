package com.maodouchat.domain.conversation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * NotificationCenterMergePolicy fuzz（清单 931 行第十六块，簇 2）。
 *
 * 源码实测的不变式（Python 迷你模型小域穷举验证）：
 * - 三个判定只对 incoming 侧做 blank 归一化，比较精确匹配（不 trim、大小写敏感）；
 * - 三函数实为对称：incoming 侧归一化看似不对称，但等值关系下双向判定一致；
 * - 全空标识永不等（防脏数据跨行吸附）；MESSAGE/MISSED_CALL 的 item id 命中优先于 extra 键，
 *   FRIEND_REQUEST/AI_TASK 只看 extra 稳定键、不看 item id；未知类型恒 false。
 */
class NotificationCenterMergePolicyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val idPool = listOf(
            "", " ", "\t", "m1", "M1", " m1", "m1 ", "msg_c1_m1", "missed_x", "call_9", "r1", "R1", "task_7"
        )
        private val idAlphabet = ('a'..'z') + ('A'..'Z') + ('0'..'9') + listOf(' ', '_', '-')
        private val itemTypes = listOf("MESSAGE", "MISSED_CALL", "FRIEND_REQUEST", "AI_TASK", "COMMENT", "POST_INTERACTION", "")
        private val extraKeys = listOf("messageId", "callId", "requestId", "taskId", "other")
    }

    private fun randomId(random: Random): String {
        if (random.nextInt(0, 3) == 0) return idPool[random.nextInt(idPool.size)]
        return buildString {
            repeat(random.nextInt(0, 7)) { append(idAlphabet[random.nextInt(idAlphabet.size)]) }
        }
    }

    private fun randomExtra(random: Random): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (key in extraKeys) {
            if (random.nextBoolean()) map[key] = randomId(random)
        }
        return map
    }

    private fun messageOracle(existingId: String, existingMessageId: String?, incomingId: String, incomingMessageId: String?): Boolean {
        val incoming = incomingMessageId?.takeIf(String::isNotBlank)
        if (incoming != null && existingMessageId == incoming) return true
        if (incomingId.isNotBlank() && existingId == incomingId) return true
        return false
    }

    private fun missedCallOracle(existingId: String, existingCallId: String?, incomingId: String, incomingCallId: String?): Boolean {
        if (incomingId.isNotBlank() && existingId == incomingId) return true
        val incoming = incomingCallId?.takeIf(String::isNotBlank) ?: return false
        return existingCallId == incoming
    }

    private fun stableExtraOracle(existingValue: String?, incomingValue: String?): Boolean {
        val incoming = incomingValue?.takeIf(String::isNotBlank) ?: return false
        return existingValue == incoming
    }

    private fun dispatchOracle(itemType: String, existingId: String, existingExtra: Map<String, String>, incomingId: String, incomingExtra: Map<String, String>): Boolean {
        return when (itemType) {
            "MESSAGE" -> messageOracle(existingId, existingExtra["messageId"], incomingId, incomingExtra["messageId"])
            "MISSED_CALL" -> missedCallOracle(existingId, existingExtra["callId"], incomingId, incomingExtra["callId"])
            "FRIEND_REQUEST" -> stableExtraOracle(existingExtra["requestId"], incomingExtra["requestId"])
            "AI_TASK" -> stableExtraOracle(existingExtra["taskId"], incomingExtra["taskId"])
            else -> false
        }
    }

    @Test
    fun `messagePayloadMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val existingId = randomId(random)
            val existingMessageId = if (random.nextBoolean()) null else randomId(random)
            val incomingId = randomId(random)
            val incomingMessageId = if (random.nextBoolean()) null else randomId(random)
            assertEquals(
                messageOracle(existingId, existingMessageId, incomingId, incomingMessageId),
                NotificationCenterMergePolicy.isSameMessagePayload(existingId, existingMessageId, incomingId, incomingMessageId),
                "fuzz 迭代 #$i：($existingId, $existingMessageId) vs ($incomingId, $incomingMessageId)"
            )
        }
    }

    @Test
    fun `missedCallPayloadMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val existingId = randomId(random)
            val existingCallId = if (random.nextBoolean()) null else randomId(random)
            val incomingId = randomId(random)
            val incomingCallId = if (random.nextBoolean()) null else randomId(random)
            assertEquals(
                missedCallOracle(existingId, existingCallId, incomingId, incomingCallId),
                NotificationCenterMergePolicy.isSameMissedCallPayload(existingId, existingCallId, incomingId, incomingCallId),
                "fuzz 迭代 #$i：($existingId, $existingCallId) vs ($incomingId, $incomingCallId)"
            )
        }
    }

    @Test
    fun `stableExtraMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val existing = if (random.nextBoolean()) null else randomId(random)
            val incoming = if (random.nextBoolean()) null else randomId(random)
            assertEquals(
                stableExtraOracle(existing, incoming),
                NotificationCenterMergePolicy.isSameStableExtra(existing, incoming),
                "fuzz 迭代 #$i：($existing, $incoming)"
            )
        }
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", "R1"), "大小写敏感")
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra(" r1", "r1"), "不 trim")
        assertTrue(NotificationCenterMergePolicy.isSameStableExtra("r1", "r1"), "精确相等")
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra(null, "r1"), "existing 为 null 不等")
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", null), "incoming 为 null 不等")
        assertFalse(NotificationCenterMergePolicy.isSameStableExtra("r1", " "), "incoming 空白不等")
    }

    @Test
    fun `dispatchTableMatchesOracle`() {
        val random = Random(0x16_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val itemType = itemTypes[random.nextInt(itemTypes.size)]
            val existingId = randomId(random)
            val incomingId = randomId(random)
            val existingExtra = randomExtra(random)
            val incomingExtra = randomExtra(random)
            assertEquals(
                dispatchOracle(itemType, existingId, existingExtra, incomingId, incomingExtra),
                NotificationCenterMergePolicy.shouldSkipCountIncrement(itemType, existingId, existingExtra, incomingId, incomingExtra),
                "fuzz 迭代 #$i：$itemType ($existingId, $existingExtra) vs ($incomingId, $incomingExtra)"
            )
        }
    }

    @Test
    fun `allThreePredicatesAreSymmetricAndDeterministic`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val aId = randomId(random)
            val bId = randomId(random)
            val aMsg = if (random.nextBoolean()) null else randomId(random)
            val bMsg = if (random.nextBoolean()) null else randomId(random)
            val first = NotificationCenterMergePolicy.isSameMessagePayload(aId, aMsg, bId, bMsg)
            assertEquals(
                NotificationCenterMergePolicy.isSameMessagePayload(bId, bMsg, aId, aMsg), first,
                "fuzz 迭代 #$i：isSameMessagePayload 必须对称"
            )
            assertEquals(
                first,
                NotificationCenterMergePolicy.isSameMessagePayload(aId, aMsg, bId, bMsg),
                "fuzz 迭代 #$i：同一输入两次调用必须相同"
            )
            assertEquals(
                NotificationCenterMergePolicy.isSameMissedCallPayload(bId, bMsg, aId, aMsg),
                NotificationCenterMergePolicy.isSameMissedCallPayload(aId, aMsg, bId, bMsg),
                "fuzz 迭代 #$i：isSameMissedCallPayload 必须对称"
            )
            assertEquals(
                NotificationCenterMergePolicy.isSameStableExtra(bMsg, aMsg),
                NotificationCenterMergePolicy.isSameStableExtra(aMsg, bMsg),
                "fuzz 迭代 #$i：isSameStableExtra 必须对称"
            )
        }
    }

    @Test
    fun `allBlankIdentifiersNeverMatch`() {
        val random = Random(0x16_2026)
        val blanks = listOf("", " ", "\t")
        val blankOrNull = listOf<String?>(null, "", " ", "\t")
        repeat(150) { i ->
            val existingId = blanks[random.nextInt(blanks.size)]
            val incomingId = blanks[random.nextInt(blanks.size)]
            val existingKey = blankOrNull[random.nextInt(blankOrNull.size)]
            val incomingKey = blankOrNull[random.nextInt(blankOrNull.size)]
            assertFalse(
                NotificationCenterMergePolicy.isSameMessagePayload(existingId, existingKey, incomingId, incomingKey),
                "fuzz 迭代 #$i：全空消息标识永不等"
            )
            assertFalse(
                NotificationCenterMergePolicy.isSameMissedCallPayload(existingId, existingKey, incomingId, incomingKey),
                "fuzz 迭代 #$i：全空通话标识永不等"
            )
            assertFalse(
                NotificationCenterMergePolicy.isSameStableExtra(existingKey, incomingKey),
                "fuzz 迭代 #$i：incoming 为空/空白时稳定 extra 永不等"
            )
        }
    }

    @Test
    fun `itemIdWinsForMessageAndCallButNotForRequestOrTask`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val id = randomId(random).takeIf { it.isNotBlank() } ?: "m1"
            val extraA = randomId(random)
            val extraB = randomId(random)
            assertTrue(
                NotificationCenterMergePolicy.shouldSkipCountIncrement(
                    "MESSAGE", id, mapOf("messageId" to extraA), id, mapOf("messageId" to extraB)
                ),
                "fuzz 迭代 #$i：MESSAGE 的 item id 相同即跳过，即使 messageId 不同"
            )
            assertTrue(
                NotificationCenterMergePolicy.shouldSkipCountIncrement(
                    "MISSED_CALL", id, mapOf("callId" to extraA), id, mapOf("callId" to extraB)
                ),
                "fuzz 迭代 #$i：MISSED_CALL 的 item id 相同即跳过，即使 callId 不同"
            )
            assertEquals(
                extraA == extraB && extraB.isNotBlank(),
                NotificationCenterMergePolicy.shouldSkipCountIncrement(
                    "FRIEND_REQUEST", id, mapOf("requestId" to extraA), id, mapOf("requestId" to extraB)
                ),
                "fuzz 迭代 #$i：FRIEND_REQUEST 不看 item id，只看 requestId（incoming 空白恒 false）"
            )
            assertEquals(
                extraA == extraB && extraB.isNotBlank(),
                NotificationCenterMergePolicy.shouldSkipCountIncrement(
                    "AI_TASK", id, mapOf("taskId" to extraA), id, mapOf("taskId" to extraB)
                ),
                "fuzz 迭代 #$i：AI_TASK 不看 item id，只看 taskId（incoming 空白恒 false）"
            )
        }
    }
}
