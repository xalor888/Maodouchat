package com.maodouchat.domain.messaging

import com.maodouchat.core.model.AiSummaryScope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AiSummaryCacheKey fuzz（清单 931 行第十六块，簇 1）。
 *
 * 源码实测的不变式（Python 迷你模型 3000 次往返零失配）：
 * - 风格归一化：trim + lowercase 后命中 6 个合法词即原词，否则 RECENT 回 "brief"、其余 scope 回 "detailed"；
 * - 键格式恒为 "manual:chat:scope:style:first:last:count"（无冒号 id 时恰 7 段），scope 往返恒等；
 * - 坏键永不抛：非 manual 前缀恒 UNREAD，未知 scope 名与段缺失恒 RECENT；
 * - 已知 quirk：build 不做风格归一化，chatId 含 ":" 会使 scope 解析错位到 RECENT，只钉住行为不改生产。
 */
class AiSummaryCacheKeyFuzzTest {

    private companion object {
        private const val RANDOM_ITERATIONS = 200
        private val legalStyles = listOf("brief", "detailed", "decisions", "tasks", "timeline", "risks")
        private val styleWords = listOf(
            "brief", "detailed", "decisions", "tasks", "timeline", "risks",
            "BRIEF", "Detailed", "TASKS", "bogus", "summary", "", " ", "brief ", " DETAILED"
        )
        private val idAlphabet = ('a'..'z') + ('0'..'9')
    }

    private fun randomId(random: Random): String = buildString {
        repeat(random.nextInt(0, 9)) { append(idAlphabet[random.nextInt(idAlphabet.size)]) }
    }

    private fun randomStyle(random: Random): String {
        val word = styleWords[random.nextInt(styleWords.size)]
        val cased = word.map { if (random.nextBoolean()) it.uppercaseChar() else it.lowercaseChar() }.joinToString("")
        val pad = listOf("", " ", "  ", "\t", "\n")[random.nextInt(5)]
        return pad + cased + pad
    }

    private fun expectedStyle(style: String, scope: AiSummaryScope): String {
        val normalized = style.trim().lowercase()
        return if (normalized in legalStyles) normalized
        else if (scope == AiSummaryScope.RECENT) "brief" else "detailed"
    }

    @Test
    fun `randomStyleNormalizesToLegalSet`() {
        val random = Random(0x16_2026)
        repeat(RANDOM_ITERATIONS) { i ->
            val scope = AiSummaryScope.entries[random.nextInt(AiSummaryScope.entries.size)]
            val style = randomStyle(random)
            val actual = normalizeAiSummaryStyle(style, scope)
            assertEquals(expectedStyle(style, scope), actual, "fuzz 迭代 #$i：风格 $style@$scope")
            assertTrue(actual in legalStyles, "fuzz 迭代 #$i：归一化结果必须在合法词集内，实际 $actual")
        }
    }

    @Test
    fun `scopeRoundTripsThroughKey`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val scope = AiSummaryScope.entries[random.nextInt(AiSummaryScope.entries.size)]
            val chatId = randomId(random)
            val first = randomId(random)
            val last = randomId(random)
            val count = random.nextInt(0, 10_000)
            val key = buildAiSummaryCacheKey(chatId, scope, "brief", first, last, count)
            assertEquals("manual:$chatId:${scope.name}:brief:$first:$last:$count", key, "fuzz 迭代 #$i：键格式")
            assertEquals(7, key.split(':').size, "fuzz 迭代 #$i：无冒号 id 时键恰 7 段")
            assertEquals(scope, summaryScopeFromCacheKey(key), "fuzz 迭代 #$i：scope 往返 $scope")
        }
    }

    @Test
    fun `badKeysNeverThrowAndFallBack`() {
        val cases = listOf(
            "" to AiSummaryScope.UNREAD,
            "c1:m1:m2:5" to AiSummaryScope.UNREAD,
            "Manual:c1:RECENT:brief:m1:m2:5" to AiSummaryScope.UNREAD,
            "manual:c1" to AiSummaryScope.RECENT,
            "manual:" to AiSummaryScope.RECENT,
            "manual" to AiSummaryScope.RECENT,
            "manual:c1:BOGUS:brief:m1:m2:5" to AiSummaryScope.RECENT,
            "manual:c1:recent:brief:m1:m2:5" to AiSummaryScope.RECENT,
            "manual:c1::brief:m1:m2:5" to AiSummaryScope.RECENT,
        )
        for ((key, expected) in cases) {
            assertEquals(expected, summaryScopeFromCacheKey(key), "坏键 $key")
        }
    }

    @Test
    fun `colonInChatIdMisalignsScopeParse`() {
        for (scope in AiSummaryScope.entries) {
            val key = buildAiSummaryCacheKey("c:1", scope, "brief", "m1", "m2", 5)
            // 第 2 段变成 "1"，查表不到恒 RECENT；first/last 含冒号不影响第 2 段，scope 仍往返。
            assertEquals(AiSummaryScope.RECENT, summaryScopeFromCacheKey(key), "chatId 含冒号 @$scope")
            if (scope != AiSummaryScope.RECENT) {
                assertNotEquals(scope, summaryScopeFromCacheKey(key), "chatId 含冒号时往返不再恒等 @$scope")
            }
            val shifted = buildAiSummaryCacheKey("c1", scope, "brief", "m:1", "m2", 5)
            assertEquals(scope, summaryScopeFromCacheKey(shifted), "firstMessageId 含冒号不影响 scope @$scope")
        }
        val rawStyle = buildAiSummaryCacheKey("c1", AiSummaryScope.RECENT, "WeIrD", "m1", "m2", 5)
        assertTrue(":WeIrD:" in rawStyle, "build 不归一化风格，键里原样透传")
    }

    @Test
    fun `determinismAndExtremeCounts`() {
        val random = Random(0x16_2026)
        repeat(150) { i ->
            val scope = AiSummaryScope.entries[random.nextInt(AiSummaryScope.entries.size)]
            val count = listOf(-1, 0, Int.MAX_VALUE)[random.nextInt(3)]
            val first = buildAiSummaryCacheKey("c1", scope, "brief", "m1", "m2", count)
            val second = buildAiSummaryCacheKey("c1", scope, "brief", "m1", "m2", count)
            assertEquals(first, second, "fuzz 迭代 #$i：同一输入两次建键必须相同")
            assertTrue(first.endsWith(":$count"), "fuzz 迭代 #$i：极值 count 仍按模板拼装")
            assertEquals(scope, summaryScopeFromCacheKey(first), "fuzz 迭代 #$i：极值 count 不影响 scope 解析")
            assertFalse(first.isEmpty(), "fuzz 迭代 #$i：键不得为空")
        }
    }
}
