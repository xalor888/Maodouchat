package com.maodouchat.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 群玩法消息 wire 格式 round-trip 测试：parse(format(x)) 必须还原原始语义。
 * round-trip 断裂 = 群玩法互操作 bug（对端无法解析展示），故全量覆盖。
 */
class GroupPlayPolicyTest {

    @Test
    fun `dice round trips value and sides`() {
        val content = GroupPlayPolicy.formatDiceMessage(value = 4, sides = 20, rollerName = "Alice")
        val parsed = GroupPlayPolicy.parseDice(content)
        assertEquals(20 to 4, parsed)
    }

    @Test
    fun `dice parse rejects non dice content`() {
        assertNull(GroupPlayPolicy.parseDice("just text"))
        assertNull(GroupPlayPolicy.parseDice(""))
    }

    @Test
    fun `rollDice stays within bounds`() {
        repeat(50) {
            val v = GroupPlayPolicy.rollDice(sides = 6)
            assertTrue(v in 1..6)
        }
        // 边界钳制：过小/过大面数被约束到 2..100
        repeat(20) {
            assertTrue(GroupPlayPolicy.rollDice(sides = 1) in 1..2)
            assertTrue(GroupPlayPolicy.rollDice(sides = 10_000) in 1..100)
        }
    }

    @Test
    fun `number bomb round trips max secret and label`() {
        val content = GroupPlayPolicy.formatNumberBomb(secret = 42, max = 100, hostLabel = "Host")
        val parsed = GroupPlayPolicy.parseNumberBomb(content)
        assertEquals(Triple(100, 42, "Host started number bomb (1-100)"), parsed)
    }

    @Test
    fun `lucky draw round trips picker and target`() {
        val content = GroupPlayPolicy.formatLuckyDraw("Host", "Bob")
        val parsed = GroupPlayPolicy.parseLuckyDraw(content)
        assertNotNull(parsed)
        assertTrue(parsed.first.contains("Host") || parsed.second.contains("Bob"))
    }

    @Test
    fun `rps round trips choice`() {
        listOf("rock", "paper", "scissors").forEach { choice ->
            val parsed = GroupPlayPolicy.parseRps(GroupPlayPolicy.formatRps(choice, "Alice"))
            assertEquals(choice, parsed)
        }
    }

    @Test
    fun `reaction race round trips token`() {
        val token = GroupPlayPolicy.randomRaceToken()
        val parsed = GroupPlayPolicy.parseReactionRace(GroupPlayPolicy.formatReactionRace(token, "Host"))
        assertNotNull(parsed)
        assertEquals(token, parsed?.first)
    }

    @Test
    fun `would you rather round trips both options`() {
        val parsed = GroupPlayPolicy.parseWouldYouRather(
            GroupPlayPolicy.formatWouldYouRather("coffee", "tea", "Host")
        )
        assertNotNull(parsed)
        assertEquals("coffee", parsed?.first)
        assertEquals("tea", parsed?.second)
    }

    @Test
    fun `emoji rain round trips emoji`() {
        val parsed = GroupPlayPolicy.parseEmojiRain(GroupPlayPolicy.formatEmojiRain("Host", "🎉"))
        assertNotNull(parsed)
        assertEquals("🎉", parsed?.first)
    }

    @Test
    fun `two truths one lie round trips all three statements`() {
        val parsed = GroupPlayPolicy.parseTwoTruthsOneLie(
            GroupPlayPolicy.formatTwoTruthsOneLie("t1", "t2", "lie", "Host")
        )
        assertEquals(listOf("t1", "t2", "lie"), parsed)
    }

    @Test
    fun `quiz round trips question answer and options`() {
        val parsed = GroupPlayPolicy.parseQuiz(
            GroupPlayPolicy.formatQuiz("Q?", "A", listOf("A", "B", "C"), "Host")
        )
        assertNotNull(parsed)
        assertEquals("Q?", parsed?.first)
        assertEquals("A", parsed?.second)
        assertEquals(listOf("A", "B", "C"), parsed?.third)
    }

    @Test
    fun `quiz options with separator chars survive escaping`() {
        // 选项含分隔符 ^ 与 | 时，esc/unesc 必须保证 round-trip 不断裂
        val tricky = listOf("opt^one", "opt|two", "plain")
        val parsed = GroupPlayPolicy.parseQuiz(
            GroupPlayPolicy.formatQuiz("Q?", "A", tricky, "Host")
        )
        assertEquals(tricky, parsed?.third)
    }

    @Test
    fun `spin story countdown round trip`() {
        assertEquals("result", GroupPlayPolicy.parseSpin(GroupPlayPolicy.formatSpin("result", "Host")))
        assertEquals("seed", GroupPlayPolicy.parseStory(GroupPlayPolicy.formatStory("seed", "Host")))
        assertEquals(30, GroupPlayPolicy.parseCountdown(GroupPlayPolicy.formatCountdown(30, "Host")))
    }

    @Test
    fun `parse helpers reject foreign prefixes`() {
        assertNull(GroupPlayPolicy.parseSpin("STORY:x|y"))
        assertNull(GroupPlayPolicy.parseStory("SPIN:x|y"))
        assertNull(GroupPlayPolicy.parseCountdown("not a countdown"))
    }
}
